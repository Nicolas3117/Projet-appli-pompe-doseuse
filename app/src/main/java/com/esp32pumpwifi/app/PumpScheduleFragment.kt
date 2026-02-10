package com.esp32pumpwifi.app

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.gson.Gson
import com.google.android.material.textfield.TextInputLayout
import kotlin.math.roundToInt

class PumpScheduleFragment : Fragment() {

    private val tabsViewModel: ScheduleTabsViewModel by activityViewModels()

    private val schedules = mutableListOf<PumpSchedule>()
    private lateinit var adapter: PumpScheduleAdapter

    private var pumpNumber: Int = 1
    private val gson = Gson()

    private var isReadOnly: Boolean = false
    private var addButton: Button? = null

    // ✅ Optionnel : module verrouillé (anti mélange multi-modules)
    private var lockedEspId: Long? = null

    companion object {
        const val MAX_PUMP_DURATION_SEC = 600
        private const val MAX_SCHEDULES_PER_PUMP = 12

        private const val ARG_PUMP_NUMBER = "pumpNumber"
        private const val ARG_ESP_ID = "espId" // Long

        fun newInstance(pumpNumber: Int): PumpScheduleFragment =
            PumpScheduleFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_PUMP_NUMBER, pumpNumber)
                }
            }

        // ✅ Variante compatible future (si ScheduleActivity/Adapter passe l’ID module)
        fun newInstance(pumpNumber: Int, espId: Long): PumpScheduleFragment =
            PumpScheduleFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_PUMP_NUMBER, pumpNumber)
                    putLong(ARG_ESP_ID, espId)
                }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pumpNumber = arguments?.getInt(ARG_PUMP_NUMBER) ?: 1
        lockedEspId = arguments?.takeIf { it.containsKey(ARG_ESP_ID) }?.getLong(ARG_ESP_ID)
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
                // ✅ important : l'ordre affiché doit rester cohérent
                sortSchedulesByTime()
                enforceMaxSchedulesCap()
                saveSchedules()
                syncToProgramStore()
                notifyActiveTotalChanged()
                adapter.notifyDataSetChanged()
            }
        )

        view.findViewById<ListView>(R.id.lv_schedules).adapter = adapter


        addButton = view.findViewById<Button>(R.id.btn_add_schedule).also {
            it.setOnClickListener { showAddScheduleDialog() }
        }

        // Ne pas sync si on n'a rien chargé (évite de "vider" sur un téléphone neuf)
        val loaded = loadSchedules()
        if (loaded) {
            syncToProgramStore()
        }

        // Publie toujours le total courant (0 si vide)
        notifyActiveTotalChanged()

        applyReadOnlyState()
        return view
    }

    // ---------------------------------------------------------------------
    // ✅ Résolution espId : priorité au verrouillage, sinon getActive() (comportement actuel)
    // ---------------------------------------------------------------------
    private fun resolveEspIdOrNull(): Long? {
        return lockedEspId ?: Esp32Manager.getActive(requireContext())?.id
    }

    // ---------------------------------------------------------------------
    // ✅ Tri stable par heure (utile pour #1..#12)
    // ---------------------------------------------------------------------
    private fun sortSchedulesByTime() {
        schedules.sortWith(compareBy<PumpSchedule> {
            val p = parseTimeOrNull(it.time)
            if (p == null) Int.MAX_VALUE else (p.first * 60 + p.second)
        }.thenBy { it.time })
    }

    // ✅ Cap dur (évite JSON corrompu > 12)
    private fun enforceMaxSchedulesCap() {
        if (schedules.size <= MAX_SCHEDULES_PER_PUMP) return
        // On garde les 12 premières après tri
        while (schedules.size > MAX_SCHEDULES_PER_PUMP) {
            schedules.removeAt(schedules.size - 1)
        }
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
        val espId = resolveEspIdOrNull() ?: return "Pompe $pump"
        val prefs = requireContext().getSharedPreferences("prefs", Context.MODE_PRIVATE)

        return prefs.getString(
            "esp_${espId}_pump${pump}_name",
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

    private fun showLimitReachedPopup() {
        AlertDialog.Builder(requireContext())
            .setTitle("Limite atteinte")
            .setMessage(
                "12 programmations maximum par pompe.\n" +
                        "Supprimez-en pour continuer."
            )
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
        val etAntiInterference = dialogView.findViewById<EditText>(R.id.et_anti_interference)
        val timeLayout = dialogView.findViewById<TextInputLayout>(R.id.layout_time)
        val quantityLayout = dialogView.findViewById<TextInputLayout>(R.id.layout_quantity)
        val antiInterferenceLayout = dialogView.findViewById<TextInputLayout>(R.id.layout_anti_interference)
        QuantityInputUtils.applyInputFilter(etQuantity)

        val espId = resolveEspIdOrNull()
        val antiPrefDefault = if (espId != null) {
            requireContext().getSharedPreferences("prefs", Context.MODE_PRIVATE)
                .getInt("esp_${espId}_pump${pumpNumber}_anti_overlap_minutes", 0)
                .coerceAtLeast(0)
        } else 0
        etAntiInterference.setText(antiPrefDefault.toString())
        etAntiInterference.isEnabled = false
        etAntiInterference.isFocusable = false
        etAntiInterference.isFocusableInTouchMode = false
        antiInterferenceLayout.helperText = "Valeur définie dans la calibration de la pompe"

        var addBtn: android.widget.Button? = null

        val validateManualInput: () -> Unit = manual@{
            timeLayout.error = null
            quantityLayout.error = null
            antiInterferenceLayout.error = null

            if (schedules.size >= MAX_SCHEDULES_PER_PUMP) {
                timeLayout.error = "Aucune place disponible (max 12)."
                addBtn?.isEnabled = false
                return@manual
            }

            val antiMin = antiPrefDefault

            val parsed = ScheduleOverlapUtils.parseTimeOrNull(etTime.text?.toString()?.trim().orEmpty())
            val qtyTenth = QuantityInputUtils.parseQuantityTenth(etQuantity.text?.toString().orEmpty())

            if (parsed == null) {
                timeLayout.error = "Format invalide"
                addBtn?.isEnabled = false
                return@manual
            }
            if (qtyTenth == null) {
                quantityLayout.error = "Format invalide"
                addBtn?.isEnabled = false
                return@manual
            }
            if (espId == null) {
                timeLayout.error = "Module introuvable"
                addBtn?.isEnabled = false
                return@manual
            }

            val prefs = requireContext().getSharedPreferences("prefs", Context.MODE_PRIVATE)
            val flow = prefs.getFloat("esp_${espId}_pump${pumpNumber}_flow", 0f)
            val startMs = (parsed.first * 3600L + parsed.second * 60L) * 1000L
            val volumeMl = QuantityInputUtils.quantityMl(qtyTenth).toDouble()
            val durationMs = DoseValidationUtils.computeDurationMs(volumeMl, flow)

            Log.i(
                "MANUAL_VALIDATE",
                "input pump=$pumpNumber startMs=$startMs qtyTenth=$qtyTenth volumeMl=$volumeMl flow=$flow antiMin=$antiMin"
            )

            if (durationMs == null) {
                quantityLayout.error = "Débit non calibré : impossible de calculer la durée."
                addBtn?.isEnabled = false
                Log.w("MANUAL_VALIDATE", "invalid reason=flow_missing pump=$pumpNumber flow=$flow")
                return@manual
            }

            val candidate = DoseInterval(pump = pumpNumber, startMs = startMs, endMs = startMs + durationMs)

            val allSchedules = (1..4).associateWith { pump ->
                val json = requireContext().getSharedPreferences("schedules", Context.MODE_PRIVATE)
                    .getString("esp_${espId}_pump$pump", null)
                if (json.isNullOrBlank()) emptyList() else runCatching { PumpScheduleJson.fromJson(json).toList() }.getOrDefault(emptyList())
            }
            val flowByPump = (1..4).associateWith { pump ->
                prefs.getFloat("esp_${espId}_pump${pump}_flow", 0f)
            }
            val existing = DoseValidationUtils.buildIntervalsFromSchedules(allSchedules, flowByPump)
            val validation = DoseValidationUtils.validateNewInterval(candidate, existing, antiMin)

            if (!validation.isValid) {
                when (validation.reason) {
                    DoseValidationReason.OVERLAP_SAME_PUMP -> {
                        val next = validation.nextAllowedStartMs?.let { ScheduleAddMergeUtils.toTimeString(it) } ?: "--:--"
                        timeLayout.error = "Une distribution est déjà en cours à ce moment. Prochaine heure possible : $next"
                    }
                    DoseValidationReason.ANTI_INTERFERENCE_GAP -> {
                        val next = validation.nextAllowedStartMs?.let { ScheduleAddMergeUtils.toTimeString(it) } ?: "--:--"
                        val blockedPumpLabel = validation.conflictPumpNum?.let { getPumpName(it) } ?: "une autre pompe"
                        timeLayout.error = "Respectez au moins $antiMin min après la fin de la distribution de la pompe $blockedPumpLabel. Prochaine heure possible : $next."
                    }
                    DoseValidationReason.OVERFLOW_MIDNIGHT -> {
                        val endText = ScheduleAddMergeUtils.toTimeString(validation.overflowEndMs ?: 0L)
                        quantityLayout.error = "Cette dose finirait après minuit (fin estimée : $endText). Avancez l’heure ou réduisez le volume."
                    }
                    else -> timeLayout.error = "Format invalide"
                }
                addBtn?.isEnabled = false
                Log.w(
                    "MANUAL_VALIDATE",
                    "invalid reason=${validation.reason} candidate=[${candidate.startMs},${candidate.endMs}) antiMin=$antiMin blockedByPump=${validation.conflictPumpNum} nextAllowed=${validation.nextAllowedStartMs} durationMs=$durationMs"
                )
                return@manual
            }

            addBtn?.isEnabled = true
            Log.i(
                "MANUAL_VALIDATE",
                "valid pump=$pumpNumber interval=[${candidate.startMs},${candidate.endMs}) antiMin=$antiMin durationMs=$durationMs"
            )
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Ajouter une programmation")
            .setView(dialogView)
            .setPositiveButton("Enregistrer", null)
            .setNegativeButton("Annuler", null)
            .create()

        dialog.setOnShowListener {
            addBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            validateManualInput.let { it() }
            addBtn?.setOnClickListener {
                validateManualInput.let { it() }
                if (addBtn?.isEnabled != true) return@setOnClickListener

                val time = etTime.text.toString().trim()
                val qtyTenth = QuantityInputUtils.parseQuantityTenth(etQuantity.text.toString()) ?: return@setOnClickListener
                addSchedule(time, qtyTenth)
                dialog.dismiss()
            }
        }

        etTime.addTextChangedListener { validateManualInput.let { it() } }
        etQuantity.addTextChangedListener { validateManualInput.let { it() } }

        dialog.show()
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

        // ✅ tri avant affichage (numérotation stable)
        sortSchedulesByTime()
        enforceMaxSchedulesCap()

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
        val espId = resolveEspIdOrNull() ?: return ConflictResult()
        val prefs = requireContext().getSharedPreferences("prefs", Context.MODE_PRIVATE)

        val startMs = ScheduleOverlapUtils.timeToStartMs(time)
            ?: return ConflictResult(blockingMessage = "Format invalide", isPopup = false)

        val flow = prefs.getFloat("esp_${espId}_pump${pumpNumber}_flow", 0f)
        if (flow <= 0f) {
            return ConflictResult(blockingMessage = "Pompe non calibrée", isPopup = false)
        }

        // min/max durée centralisés
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
            espId = espId,
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
        val espId = resolveEspIdOrNull() ?: return

        // ✅ normalise avant persist
        sortSchedulesByTime()
        enforceMaxSchedulesCap()

        requireContext()
            .getSharedPreferences("schedules", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "esp_${espId}_pump$pumpNumber",
                PumpScheduleJson.toJson(schedules, gson)
            )
            .apply()
    }

    // Retourne true si on a réellement chargé des données depuis prefs
    private fun loadSchedules(): Boolean {
        val espId = resolveEspIdOrNull() ?: return false

        val key = "esp_${espId}_pump$pumpNumber"
        val json = requireContext()
            .getSharedPreferences("schedules", Context.MODE_PRIVATE)
            .getString(key, null) ?: return false

        val loaded: MutableList<PumpSchedule> = PumpScheduleJson.fromJson(json)

        schedules.clear()
        schedules.addAll(loaded)

        // ✅ tri + cap dur (évite injection > 12)
        sortSchedulesByTime()
        enforceMaxSchedulesCap()

        if (this::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
        }

        return true
    }

    // ---------------------------------------------------------------------
    // 🔁 SYNC → ProgramStore
    // ---------------------------------------------------------------------
    private fun syncToProgramStore() {
        val espId = resolveEspIdOrNull() ?: return
        val prefs = requireContext().getSharedPreferences("prefs", Context.MODE_PRIVATE)

        while (ProgramStore.count(requireContext(), espId, pumpNumber) > 0) {
            ProgramStore.removeLine(requireContext(), espId, pumpNumber, 0)
        }

        val flow = prefs.getFloat("esp_${espId}_pump${pumpNumber}_flow", 0f)
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

            android.util.Log.d(
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

            ProgramStore.addLine(requireContext(), espId, pumpNumber, line)
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

    /**
     * Remplace la liste de programmations par une nouvelle liste (ex: retour /read_ms).
     * On persiste + reconstruit ProgramStore + met à jour les totaux.
     */
    fun replaceSchedules(newSchedules: List<PumpSchedule>) {
        schedules.clear()
        schedules.addAll(newSchedules)

        // ✅ tri + cap dur (évite injection > 12)
        sortSchedulesByTime()
        enforceMaxSchedulesCap()

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

    fun getPumpNumber(): Int = pumpNumber
}
