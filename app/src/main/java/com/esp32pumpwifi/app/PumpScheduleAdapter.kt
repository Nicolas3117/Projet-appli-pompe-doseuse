package com.esp32pumpwifi.app

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog

class PumpScheduleAdapter(
    private val context: Context,
    private val schedules: MutableList<PumpSchedule>,
    private val onScheduleChanged: () -> Unit,
    private var readOnly: Boolean = false
) : BaseAdapter() {

    companion object {
        const val MAX_PUMP_DURATION_SEC = 600
    }

    override fun getCount(): Int = schedules.size
    override fun getItem(position: Int): Any = schedules[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {

        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_schedule, parent, false)

        // 🔹 LISTE TRIÉE POUR L’AFFICHAGE UNIQUEMENT
        val displaySchedules = schedules.withIndex()
            .sortedBy { safeTimeToMinutes(it.value.time) }

        if (position !in displaySchedules.indices) return view

        val indexedSchedule = displaySchedules[position]
        val schedule = indexedSchedule.value

        // 🔹 index réel dans la liste source (non triée)
        val sourceIndex = indexedSchedule.index

        val tvPump = view.findViewById<TextView>(R.id.tv_pump)
        val tvTime = view.findViewById<TextView>(R.id.tv_time)
        val tvQty = view.findViewById<TextView>(R.id.tv_quantity)
        val swEnabled = view.findViewById<Switch>(R.id.sw_enabled)
        val btnEdit = view.findViewById<Button>(R.id.btn_edit)
        val btnDelete = view.findViewById<Button>(R.id.btn_delete)

        // --- Affichage ---
        tvPump.text = "Pompe ${schedule.pumpNumber}"
        tvTime.text = schedule.time
        tvQty.text = "${QuantityInputUtils.formatQuantityMl(schedule.quantityTenth)} mL"

        // --- Switch ON/OFF ---
        swEnabled.setOnCheckedChangeListener(null)
        swEnabled.isChecked = schedule.enabled
        swEnabled.isEnabled = !readOnly
        if (!readOnly) {
            swEnabled.setOnCheckedChangeListener { _, checked ->
                schedule.enabled = checked
                onScheduleChanged()
            }
        }

        btnEdit.isEnabled = !readOnly
        btnDelete.isEnabled = !readOnly
        val actionAlpha = if (readOnly) 0.5f else 1f
        swEnabled.alpha = actionAlpha
        btnEdit.alpha = actionAlpha
        btnDelete.alpha = actionAlpha

        // -----------------------------------------------------------------
        // ✏ MODIFIER (AVEC CONTRÔLE CONFLITS)
        // -----------------------------------------------------------------
        btnEdit.setOnClickListener {
            if (readOnly) return@setOnClickListener

            val dialogView = LayoutInflater.from(context)
                .inflate(R.layout.dialog_add_schedule, null)

            val etTime = dialogView.findViewById<EditText>(R.id.et_time)
            val etQty = dialogView.findViewById<EditText>(R.id.et_quantity)

            etTime.setText(schedule.time)
            etQty.setText(QuantityInputUtils.formatQuantityMl(schedule.quantityTenth))
            QuantityInputUtils.applyInputFilter(etQty)

            AlertDialog.Builder(context)
                .setTitle("Modifier la programmation")
                .setView(dialogView)
                .setPositiveButton("Enregistrer") { _, _ ->

                    val newTime = etTime.text.toString().trim()
                    val newQtyTenth =
                        QuantityInputUtils.parseQuantityTenth(etQty.text.toString())

                    // ✅ format + bornes HH/MM
                    if (ScheduleOverlapUtils.parseTimeOrNull(newTime) == null || newQtyTenth == null) {
                        Toast.makeText(context, "Format invalide", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    val conflict = detectConflict(
                        pumpNumber = schedule.pumpNumber,
                        newTime = newTime,
                        newQtyTenth = newQtyTenth,
                        editedIndex = sourceIndex
                    )

                    if (conflict.blockingMessage != null) {
                        // ✅ popup seulement pour "Quantité trop faible" ou "Durée trop longue"
                        if (conflict.blockingMessage.startsWith("Quantité trop faible") ||
                            conflict.blockingMessage.startsWith("Durée trop longue")
                        ) {
                            AlertDialog.Builder(context)
                                .setTitle("Impossible")
                                .setMessage(conflict.blockingMessage)
                                .setPositiveButton("OK", null)
                                .show()
                        } else {
                            Toast.makeText(context, conflict.blockingMessage, Toast.LENGTH_LONG)
                                .show()
                        }
                        return@setPositiveButton
                    }

                    if (conflict.warningMessage != null) {
                        AlertDialog.Builder(context)
                            .setTitle("Chevauchement détecté")
                            .setMessage(conflict.warningMessage)
                            .setPositiveButton("Oui") { _, _ ->
                                schedule.time = newTime
                                schedule.quantityTenth = newQtyTenth
                                notifyDataSetChanged()
                                onScheduleChanged()
                            }
                            .setNegativeButton("Non", null)
                            .show()
                        return@setPositiveButton
                    }

                    schedule.time = newTime
                    schedule.quantityTenth = newQtyTenth
                    notifyDataSetChanged()
                    onScheduleChanged()
                }
                .setNegativeButton("Annuler", null)
                .show()
        }

        // -----------------------------------------------------------------
        // 🗑 SUPPRIMER (CORRECT)
        // -----------------------------------------------------------------
        btnDelete.setOnClickListener {
            if (readOnly) return@setOnClickListener
            schedules.removeAt(sourceIndex)
            notifyDataSetChanged()
            onScheduleChanged()
        }

        return view
    }

    // ---------------------------------------------------------------------
    // 🔍 DÉTECTION CONFLITS + MINUIT
    // ---------------------------------------------------------------------
    private fun detectConflict(
        pumpNumber: Int,
        newTime: String,
        newQtyTenth: Int,
        editedIndex: Int
    ): ConflictResult {

        val active = Esp32Manager.getActive(context) ?: return ConflictResult()
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

        // ✅ sécurité HH/MM (au cas où)
        val startMs = ScheduleOverlapUtils.timeToStartMs(newTime)
            ?: return ConflictResult(blockingMessage = "Format invalide")

        val flowKey = "esp_${active.id}_pump${pumpNumber}_flow"
        val flow = prefs.getFloat(flowKey, 0f)
        if (flow <= 0f) return ConflictResult(blockingMessage = "Pompe non calibrée")

        // ✅ Durée centralisée (min/max) => même logique que Fragment/Helper
        val durationMs = ScheduleOverlapUtils.durationMsFromQuantity(newQtyTenth, flow)
        if (durationMs == null) {
            val minMl = flow * (ManualDoseActivity.MIN_PUMP_DURATION_MS / 1000f)
            return ConflictResult(
                blockingMessage =
                    "Quantité trop faible : minimum ${"%.2f".format(minMl)} mL (${ManualDoseActivity.MIN_PUMP_DURATION_MS} ms)\n" +
                            "Débit actuel : ${"%.1f".format(flow)} mL/s"
            )
        }

        val endMs: Long = startMs + durationMs.toLong()

        if (endMs >= 86_400_000L) {
            return ConflictResult(blockingMessage = "La distribution dépasse minuit (00:00)")
        }

        val overlapResult = ScheduleOverlapUtils.findOverlaps(
            context = context,
            espId = active.id,
            pumpNumber = pumpNumber,
            candidateWindow = ScheduleOverlapUtils.ScheduleWindow(startMs, endMs),
            ignoreSamePumpPredicate = { index, _ -> index == editedIndex }
        )

        if (overlapResult.samePumpConflict) {
            return ConflictResult(
                blockingMessage =
                    "Distribution simultanée détectée sur ${getPumpName(pumpNumber)}"
            )
        }

        if (overlapResult.overlappingPumpNames.isNotEmpty()) {
            return ConflictResult(
                warningMessage =
                    "La distribution chevauche les pompes suivantes :\n" +
                            overlapResult.overlappingPumpNames.joinToString(
                                separator = "\n• ",
                                prefix = "• "
                            ) +
                            "\n\nVoulez-vous continuer ?"
            )
        }

        return ConflictResult()
    }

    private fun getPumpName(pump: Int): String {
        val active = Esp32Manager.getActive(context) ?: return "Pompe $pump"
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        return prefs.getString(
            "esp_${active.id}_pump${pump}_name",
            "Pompe $pump"
        ) ?: "Pompe $pump"
    }

    data class ConflictResult(
        val blockingMessage: String? = null,
        val warningMessage: String? = null
    )

    fun setReadOnly(readOnly: Boolean) {
        this.readOnly = readOnly
        notifyDataSetChanged()
    }

    // ---------------------------------------------------------------------
    // ✅ Tri affichage : fallback safe (ne casse pas si timeToMinutes n’existe pas)
    // ---------------------------------------------------------------------
    private fun safeTimeToMinutes(time: String): Int {
        // Si ton projet a déjà une fonction timeToMinutes(time), remplace ce call
        // par ton implémentation existante, ou supprime cette fonction.
        val parsed = ScheduleOverlapUtils.parseTimeOrNull(time) ?: return Int.MAX_VALUE
        return parsed.first * 60 + parsed.second
    }
}
