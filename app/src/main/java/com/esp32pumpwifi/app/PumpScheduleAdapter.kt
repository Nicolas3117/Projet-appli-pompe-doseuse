package com.esp32pumpwifi.app

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import kotlin.math.roundToInt

class PumpScheduleAdapter(
    private val context: Context,
    private val schedules: MutableList<PumpSchedule>,
    private val onScheduleChanged: () -> Unit
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
            .sortedBy { timeToMinutes(it.value.time) }

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
        swEnabled.setOnCheckedChangeListener { _, checked ->
            schedule.enabled = checked
            onScheduleChanged()
        }

        // -----------------------------------------------------------------
        // ✅ Parse + validation HH:MM (00-23 / 00-59)
        // -----------------------------------------------------------------
        fun parseTimeOrNull(time: String): Pair<Int, Int>? {
            val t = time.trim()
            if (!t.matches(Regex("""\d{2}:\d{2}"""))) return null
            val parts = t.split(":")
            if (parts.size != 2) return null
            val hh = parts[0].toIntOrNull() ?: return null
            val mm = parts[1].toIntOrNull() ?: return null
            if (hh !in 0..23) return null
            if (mm !in 0..59) return null
            return hh to mm
        }

        // -----------------------------------------------------------------
        // ✏ MODIFIER (AVEC CONTRÔLE CONFLITS)
        // -----------------------------------------------------------------
        btnEdit.setOnClickListener {

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
                    if (parseTimeOrNull(newTime) == null || newQtyTenth == null) {
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
        val t = newTime.trim()
        if (!t.matches(Regex("""\d{2}:\d{2}"""))) {
            return ConflictResult(blockingMessage = "Format invalide")
        }
        val parts = t.split(":")
        if (parts.size != 2) return ConflictResult(blockingMessage = "Format invalide")
        val h = parts[0].toIntOrNull() ?: return ConflictResult(blockingMessage = "Format invalide")
        val m = parts[1].toIntOrNull() ?: return ConflictResult(blockingMessage = "Format invalide")
        if (h !in 0..23 || m !in 0..59) {
            return ConflictResult(blockingMessage = "Format invalide")
        }

        val startMs = (h * 3600L + m * 60L) * 1000L

        val flowKey = "esp_${active.id}_pump${pumpNumber}_flow"
        val flow = prefs.getFloat(flowKey, 0f)
        if (flow <= 0f) return ConflictResult(blockingMessage = "Pompe non calibrée")

        // ✅ Minimum réel : 50 ms (comme le manuel et l’ESP32)
        val minMl = flow * (ManualDoseActivity.MIN_PUMP_DURATION_MS / 1000f)
        val newQtyMl = QuantityInputUtils.quantityMl(newQtyTenth)
        if (newQtyMl < minMl) {
            return ConflictResult(
                blockingMessage =
                    "Quantité trop faible : minimum ${"%.2f".format(minMl)} mL (${ManualDoseActivity.MIN_PUMP_DURATION_MS} ms)\n" +
                            "Débit actuel : ${"%.1f".format(flow)} mL/s"
            )
        }

        val durationMs = (newQtyMl / flow * 1000f).roundToInt()
        if (durationMs < ManualDoseActivity.MIN_PUMP_DURATION_MS) {
            return ConflictResult(
                blockingMessage =
                    "Quantité trop faible : minimum ${"%.2f".format(minMl)} mL (${ManualDoseActivity.MIN_PUMP_DURATION_MS} ms)\n" +
                            "Débit actuel : ${"%.1f".format(flow)} mL/s"
            )
        }

        // ✅ limite firmware 600s en édition aussi (sinon surprise ESP32)
        if (durationMs > ManualDoseActivity.MAX_PUMP_DURATION_MS) {
            return ConflictResult(
                blockingMessage =
                    "Durée trop longue : maximum ${MAX_PUMP_DURATION_SEC}s\n" +
                            "Réduis la quantité ou recalibre le débit."
            )
        }

        val endMs = startMs + durationMs

        if (endMs >= 86_400_000L) {
            return ConflictResult(blockingMessage = "La distribution dépasse minuit (00:00)")
        }

        val overlappingPumps = mutableSetOf<String>()

        for (p in 1..4) {

            val json = context
                .getSharedPreferences("schedules", Context.MODE_PRIVATE)
                .getString("esp_${active.id}_pump$p", null)
                ?: continue

            val list: MutableList<PumpSchedule> = PumpScheduleJson.fromJson(json)

            for ((index, s) in list.withIndex()) {

                if (p == pumpNumber && index == editedIndex) continue
                if (!s.enabled) continue

                // ✅ ignore si heure invalide (legacy/corruption)
                val st = s.time.trim()
                if (!st.matches(Regex("""\d{2}:\d{2}"""))) continue
                val sp = st.split(":")
                if (sp.size != 2) continue
                val hh = sp[0].toIntOrNull() ?: continue
                val mm = sp[1].toIntOrNull() ?: continue
                if (hh !in 0..23 || mm !in 0..59) continue

                val sStartMs = (hh * 3600L + mm * 60L) * 1000L

                val flowOther =
                    prefs.getFloat("esp_${active.id}_pump${p}_flow", flow)

                val minOtherMl =
                    flowOther * (ManualDoseActivity.MIN_PUMP_DURATION_MS / 1000f)
                if (s.quantityMl < minOtherMl) continue

                val sDurationMs = (s.quantityMl / flowOther * 1000f).roundToInt()
                if (sDurationMs < ManualDoseActivity.MIN_PUMP_DURATION_MS) continue
                if (sDurationMs > ManualDoseActivity.MAX_PUMP_DURATION_MS) continue

                val sEndMs = sStartMs + sDurationMs

                if (startMs < sEndMs && endMs > sStartMs) {
                    // ✅ même pompe = bloquant (message cohérent)
                    if (p == pumpNumber) {
                        return ConflictResult(
                            blockingMessage =
                                "Distribution simultanée détectée sur ${getPumpName(pumpNumber)}"
                        )
                    }
                    overlappingPumps.add(getPumpName(p))
                }
            }
        }

        if (overlappingPumps.isNotEmpty()) {
            return ConflictResult(
                warningMessage =
                    "La distribution chevauche les pompes suivantes :\n" +
                            overlappingPumps.joinToString(
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
}
