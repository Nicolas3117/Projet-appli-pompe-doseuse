package com.esp32pumpwifi.app

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.gson.Gson
import kotlin.math.roundToInt

class PumpScheduleFragment : Fragment() {

    private val tabsViewModel: ScheduleTabsViewModel by activityViewModels()
    private val schedules = mutableListOf<PumpSchedule>()
    private lateinit var adapter: PumpScheduleAdapter
    private var pumpNumber: Int = 1
    private val gson = Gson()
    private var isReadOnly: Boolean = false
    private var addButton: Button? = null

    companion object {
        const val MAX_PUMP_DURATION_SEC = 600
        private const val MAX_SCHEDULES_PER_PUMP = 12

        fun newInstance(pumpNumber: Int): PumpScheduleFragment =
            PumpScheduleFragment().apply {
                arguments = Bundle().apply {
                    putInt("pumpNumber", pumpNumber)
                }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pumpNumber = arguments?.getInt("pumpNumber") ?: 1
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        val view = inflater.inflate(R.layout.fragment_pump_schedule, container, false)

        adapter = PumpScheduleAdapter(
            context = requireContext(),
            schedules = schedules,
            onScheduleChanged = {
                saveSchedules()
                syncToProgramStore()
                notifyActiveTotalChanged()
            }
        )

        view.findViewById<ListView>(R.id.lv_schedules).adapter = adapter

        addButton = view.findViewById<Button>(R.id.btn_add_schedule)
        addButton?.setOnClickListener {
            showAddScheduleDialog()
        }

        // ✅ IMPORTANT FIX :
        // Ne pas appeler syncToProgramStore() si on n'a rien chargé,
        // sinon on peut "vider" le ProgramStore sur un téléphone neuf.
        val loaded = loadSchedules()
        if (loaded) {
            syncToProgramStore()
        }

        // ✅ Publie toujours le total courant (0 si vide)
        notifyActiveTotalChanged()

        applyReadOnlyState()
        return view
    }

    // ---------------------------------------------------------------------
    // ✅ Parse + validation HH:MM (00-23 / 00-59)
    // ---------------------------------------------------------------------
    private fun parseTimeOrNull(time: String): Pair<Int, Int>? {
        return ScheduleOverlapUtils.parseTimeOrNull(time)
    }

    // ---------------------------------------------------------------------
    // 🔤 NOM PERSONNALISÉ DE LA POMPE
    // ---------------------------------------------------------------------
    private fun getPumpName(pump: Int): String {
        val active = Esp32Manager.getActive(requireContext()) ?: return "Pompe $pump"
        val prefs = requireContext().getSharedPreferences("prefs", Context.MODE_PRIVATE)

        return prefs.getString(
            "esp_${active.id}_pump${pump}_name",
            "Pompe $pump"
        ) ?: "Pompe $pump"
    }

    // ---------------------------------------------------------------------
    // ✅ POPUP bloquante
    // ---------------------------------------------------------------------
    private fun showBlockingPopup(message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Impossible")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    // ---------------------------------------------------------------------
    // ➕ AJOUT PROGRAMMATION
    // ---------------------------------------------------------------------
    private fun showAddScheduleDialog() {
        if (isReadOnly) return

        val dialogView = layoutInflater.inflate(R.layout.dialog_add_schedule, null)

        val etTime = dialogView.findViewById<EditText>(R.id.et_time)
        val etQuantity = dialogView.findViewById<EditText>(R.id.et_quantity)
        QuantityInputUtils.applyInputFilter(etQuantity)

        AlertDialog.Builder(requireContext())
            .setTitle("Ajouter une programmation")
            .setView(dialogView)
            .setPositiveButton("Enregistrer") { _, _ ->

                if (schedules.size >= MAX_SCHEDULES_PER_PUMP) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Limite atteinte")
                        .setMessage("Attention : 12 programmations maximum par pompe.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@setPositiveButton
                }

                val time = etTime.text.toString().trim()
                val qtyTenth = QuantityInputUtils.parseQuantityTenth(etQuantity.text.toString())

                if (parseTimeOrNull(time) == null || qtyTenth == null) {
                    Toast.makeText(requireContext(), "Format invalide", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val check = detectConflicts(time, qtyTenth)

                if (check.blockingMessage != null) {
                    if (check.isPopup) {
                        showBlockingPopup(check.blockingMessage)
                    } else {
                        Toast.makeText(requireContext(), check.blockingMessage, Toast.LENGTH_LONG)
                            .show()
                    }
                    return@setPositiveButton
                }

                if (check.warningMessage != null) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Chevauchement détecté")
                        .setMessage(check.warningMessage)
                        .setPositiveButton("Oui") { _, _ ->
                            addSchedule(time, qtyTenth)
                        }
                        .setNegativeButton("Non", null)
                        .show()
                    return@setPositiveButton
                }

                addSchedule(time, qtyTenth)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun addSchedule(time: String, qtyTenth: Int) {
        schedules.add(
            PumpSchedule(
                pumpNumber = pumpNumber,
                time = time,
                quantityTenth = qtyTenth,
                enabled = true
            )
        )

        if (this::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
        }
        saveSchedules()
        syncToProgramStore()
        notifyActiveTotalChanged()
    }

    // ---------------------------------------------------------------------
    // 🔍 DÉTECTION DES CONFLITS (réutilise ScheduleOverlapUtils)
    // ---------------------------------------------------------------------
    private fun detectConflicts(
        time: String,
        quantityTenth: Int
    ): ConflictResult {

        val active = Esp32Manager.getActive(requireContext()) ?: return ConflictResult()
        val prefs = requireContext().getSharedPreferences("prefs", Context.MODE_PRIVATE)

        val startMs = ScheduleOverlapUtils.timeToStartMs(time)
            ?: return ConflictResult(blockingMessage = "Format invalide", isPopup = false)

        val flow = prefs.getFloat("esp_${active.id}_pump${pumpNumber}_flow", 0f)
        if (flow <= 0f) {
            return ConflictResult(blockingMessage = "Pompe non calibrée", isPopup = false)
        }

        // ✅ min/max durée centralisés (même logique que partout)
        val durationMs = ScheduleOverlapUtils.durationMsFromQuantity(quantityTenth, flow)
        if (durationMs == null) {
            val minMl = flow * (ManualDoseActivity.MIN_PUMP_DURATION_MS / 1000f)
            val msg =
                "Quantité invalide.\n" +
                        "Minimum ≈ ${"%.2f".format(minMl)} mL (${ManualDoseActivity.MIN_PUMP_DURATION_MS} ms)\n" +
                        "Maximum = ${MAX_PUMP_DURATION_SEC}s\n" +
                        "Débit actuel : ${"%.1f".format(flow)} mL/s"
            return ConflictResult(blockingMessage = msg, isPopup = true)
        }

        val endMs = startMs + durationMs.toLong()
        if (endMs >= 86_400_000L) {
            return ConflictResult(blockingMessage = "La distribution dépasse minuit", isPopup = false)
        }

        val overlapResult = ScheduleOverlapUtils.findOverlaps(
            context = requireContext(),
            espId = active.id,
            pumpNumber = pumpNumber,
            candidateWindow = ScheduleOverlapUtils.ScheduleWindow(startMs, endMs),
            samePumpSchedules = schedules
        )

        if (overlapResult.samePumpConflict) {
            return ConflictResult(
                blockingMessage = "Distribution simultanée détectée sur ${getPumpName(pumpNumber)}",
                isPopup = false
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

    data class ConflictResult(
        val blockingMessage: String? = null,
        val warningMessage: String? = null,
        val isPopup: Boolean = false
    )

    // ---------------------------------------------------------------------
    // 💾 SAUVEGARDE / CHARGEMENT
    // ---------------------------------------------------------------------
    private fun saveSchedules() {
        val active = Esp32Manager.getActive(requireContext()) ?: return

        requireContext()
            .getSharedPreferences("schedules", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "esp_${active.id}_pump$pumpNumber",
                PumpScheduleJson.toJson(schedules, gson)
            )
            .apply()
    }

    // ✅ Retourne true si on a réellement chargé des données depuis prefs
    private fun loadSchedules(): Boolean {
        val active = Esp32Manager.getActive(requireContext()) ?: return false

        val json =
            requireContext()
                .getSharedPreferences("schedules", Context.MODE_PRIVATE)
                .getString("esp_${active.id}_pump$pumpNumber", null)
                ?: return false

        val loaded: MutableList<PumpSchedule> = PumpScheduleJson.fromJson(json)

        schedules.clear()
        schedules.addAll(loaded)

        if (this::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
        }

        return true
    }

    // ---------------------------------------------------------------------
    // 🔁 SYNC → ProgramStore
    // ---------------------------------------------------------------------
    private fun syncToProgramStore() {
        val active = Esp32Manager.getActive(requireContext()) ?: return
        val prefs = requireContext().getSharedPreferences("prefs", Context.MODE_PRIVATE)

        while (ProgramStore.count(requireContext(), active.id, pumpNumber) > 0) {
            ProgramStore.removeLine(requireContext(), active.id, pumpNumber, 0)
        }

        val flow = prefs.getFloat("esp_${active.id}_pump${pumpNumber}_flow", 0f)
        if (flow <= 0f) return

        val minMl = flow * (ManualDoseActivity.MIN_PUMP_DURATION_MS / 1000f)
        var ignoredCount = 0

        for (s in schedules) {
            if (!s.enabled) continue
            if (s.quantityMl < minMl) {
                ignoredCount++
                continue
            }

            val parsed = parseTimeOrNull(s.time)
            if (parsed == null) {
                ignoredCount++
                continue
            }
            val (hh, mm) = parsed

            val durationMs = (s.quantityMl / flow * 1000f).roundToInt()
            if (durationMs < ManualDoseActivity.MIN_PUMP_DURATION_MS) {
                ignoredCount++
                continue
            }
            if (durationMs > ManualDoseActivity.MAX_PUMP_DURATION_MS) {
                ignoredCount++
                continue
            }

            Log.d(
                "PROGRAM_SAVE",
                "Saving schedule line: pump=$pumpNumber time=$hh:$mm " +
                        "volumeMl=${QuantityInputUtils.formatQuantityMl(s.quantityTenth)} flow=$flow durationMs=$durationMs"
            )

            val line = ProgramLine(
                enabled = true,
                pump = pumpNumber,
                hour = hh,
                minute = mm,
                qtyMs = durationMs
            )

            ProgramStore.addLine(requireContext(), active.id, pumpNumber, line)
        }

        if (ignoredCount > 0) {
            Toast.makeText(
                requireContext(),
                "⚠️ $ignoredCount programmation(s) ignorée(s) (quantité trop faible ou trop longue)",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun getSchedules(): List<PumpSchedule> = schedules

    // ✅ IMPORTANT FIX :
    // Quand ScheduleActivity fait adapter.updateSchedules(...) (après /read_ms),
    // on doit aussi persister + reconstruire ProgramStore pour éviter que le brouillon reste vide.
    fun replaceSchedules(newSchedules: List<PumpSchedule>) {
        schedules.clear()
        schedules.addAll(newSchedules)

        if (this::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
        }

        if (!isAdded) return

        saveSchedules()
        syncToProgramStore()
        notifyActiveTotalChanged()
    }

    private fun notifyActiveTotalChanged() {
        val totalTenth = schedules.filter { it.enabled }.sumOf { it.quantityTenth }
        tabsViewModel.setActiveTotal(pumpNumber, totalTenth)
    }

    fun setReadOnly(readOnly: Boolean) {
        isReadOnly = readOnly
        applyReadOnlyState()
    }

    private fun applyReadOnlyState() {
        val enabled = !isReadOnly
        addButton?.isEnabled = enabled
        addButton?.alpha = if (enabled) 1f else 0.5f
        if (this::adapter.isInitialized) {
            adapter.setReadOnly(isReadOnly)
        }
    }
}
