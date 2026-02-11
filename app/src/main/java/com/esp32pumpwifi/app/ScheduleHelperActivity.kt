package com.esp32pumpwifi.app

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

class ScheduleHelperActivity : AppCompatActivity() {

    private var startMs: Long? = null
    private var endMs: Long? = null

    private lateinit var startLayout: TextInputLayout
    private lateinit var endLayout: TextInputLayout
    private lateinit var doseLayout: TextInputLayout
    private lateinit var volumeLayout: TextInputLayout
    private lateinit var antiOverlapLayout: TextInputLayout

    private lateinit var startInput: TextInputEditText
    private lateinit var endInput: TextInputEditText
    private lateinit var doseInput: TextInputEditText
    private lateinit var volumeInput: TextInputEditText
    private lateinit var antiOverlapInput: TextInputEditText

    private lateinit var proposalsContainer: LinearLayout
    private lateinit var addButton: Button

    private var pumpNumber: Int = 1
    private var moduleId: String? = null
    private var expectedEspId: Long = -1L
    private var currentPumpName: String = ""

    // ✅ places restantes avant d’atteindre 12 (en tenant compte des prefs existantes)
    private var remainingSlots: Int = MAX_SCHEDULES_PER_PUMP
    private var calibrationAntiMin: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule_helper)

        title = getString(R.string.schedule_helper_title)

        pumpNumber = intent.getIntExtra(EXTRA_PUMP_NUMBER, 1)
        moduleId = intent.getStringExtra(EXTRA_MODULE_ID)

        val parsedExpectedEspId = moduleId?.toLongOrNull()
        if (parsedExpectedEspId == null) {
            Toast.makeText(
                this,
                "Le module sélectionné n’est plus disponible.\nVeuillez fermer cet écran et réessayer.",
                Toast.LENGTH_LONG
            ).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        expectedEspId = parsedExpectedEspId

        val active = Esp32Manager.getActive(this)
        if (active != null && active.id != expectedEspId) {
            Toast.makeText(
                this,
                "Module différent détecté. Fermez et rouvrez l’écran.",
                Toast.LENGTH_LONG
            ).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        currentPumpName = getPumpDisplayName(pumpNumber)
        findViewById<TextView>(R.id.tv_schedule_helper_pump).text = currentPumpName
        supportActionBar?.subtitle = currentPumpName

        bindViews()
        computeRemainingSlots()
        preloadAntiInterferenceDefault()
        setupListeners()
        updateUi()
    }

    private fun bindViews() {
        startLayout = findViewById(R.id.layout_start_time)
        endLayout = findViewById(R.id.layout_end_time)
        doseLayout = findViewById(R.id.layout_dose_count)
        volumeLayout = findViewById(R.id.layout_volume_total)
        antiOverlapLayout = findViewById(R.id.layout_anti_overlap)

        startInput = findViewById(R.id.input_start_time)
        endInput = findViewById(R.id.input_end_time)
        doseInput = findViewById(R.id.input_dose_count)
        volumeInput = findViewById(R.id.input_volume_total)
        antiOverlapInput = findViewById(R.id.input_anti_overlap)

        proposalsContainer = findViewById(R.id.layout_proposals)
        addButton = findViewById(R.id.button_add_doses)
    }

    private fun preloadAntiInterferenceDefault() {
        calibrationAntiMin = getAntiInterferenceMinutes(this, expectedEspId).coerceAtLeast(0)
        antiOverlapInput.setText(calibrationAntiMin.toString())
        antiOverlapInput.isEnabled = false
        antiOverlapLayout.isEnabled = false
        antiOverlapLayout.helperText = getString(R.string.anti_interference_calibration_read_only)
        Log.i(
            TAG_ANTI_INTERFERENCE,
            "ANTI_INTERFERENCE source=calibration value=$calibrationAntiMin pump=$pumpNumber moduleId=$expectedEspId"
        )
    }

    private fun computeRemainingSlots() {
        val schedulesPrefs = getSharedPreferences("schedules", Context.MODE_PRIVATE)
        val json = schedulesPrefs.getString("esp_${expectedEspId}_pump$pumpNumber", null)

        val existingCount = json?.let {
            try {
                PumpScheduleJson.fromJson(it).size
            } catch (_: Exception) {
                0
            }
        } ?: 0

        remainingSlots = (MAX_SCHEDULES_PER_PUMP - existingCount).coerceAtLeast(0)

        // ✅ si déjà plein : on bloque proprement
        if (remainingSlots == 0) {
            doseLayout.error = "Aucune place disponible (max 12)."
        }
    }

    private fun setupListeners() {
        startInput.setOnClickListener {
            showTimePicker(startMs) { selectedMs ->
                startMs = selectedMs
                startInput.setText(formatTimeMs(selectedMs))
                updateUi()
            }
        }

        endInput.setOnClickListener {
            showTimePicker(endMs) { selectedMs ->
                endMs = selectedMs
                endInput.setText(formatTimeMs(selectedMs))
                updateUi()
            }
        }

        doseInput.addTextChangedListener { updateUi() }
        volumeInput.addTextChangedListener { updateUi() }
        findViewById<Button>(R.id.button_cancel).setOnClickListener { finish() }

        addButton.setOnClickListener {
            val validation = validateInputs(applyAutoClamp = true)
            if (!validation.isValid) {
                updateUi()
                return@setOnClickListener
            }

            Log.i(
                TAG_TIME_BUG,
                "helper_submit pump=$pumpNumber moduleId=$moduleId startMs=${validation.startMs} endMs=${validation.endMs} antiMin=${validation.antiOverlapMinutes} doseCount=${validation.doseCount}"
            )
            Log.i(
                TAG_TIME_BUG,
                "helper_submit proposedTimesMs=${validation.proposedTimesMs} formattedTimes=${validation.formattedTimes}"
            )

            val resultIntent = Intent().apply {
                putExtra(EXTRA_PUMP_NUMBER, pumpNumber)
                putExtra(EXTRA_MODULE_ID, moduleId)
                putExtra(EXTRA_START_MS, validation.startMs)
                putExtra(EXTRA_END_MS, validation.endMs)
                putExtra(EXTRA_ANTI_CHEV_MINUTES, validation.antiOverlapMinutes)
                putExtra(EXTRA_VOLUME_PER_DOSE, validation.volumePerDoseMlList.firstOrNull() ?: 0.0)
                putIntegerArrayListExtra(
                    EXTRA_VOLUME_PER_DOSE_TENTH_LIST,
                    ArrayList(validation.volumePerDoseTenthList)
                )
                putStringArrayListExtra(EXTRA_SCHEDULE_TIMES, ArrayList(validation.formattedTimes))
                putExtra(EXTRA_SCHEDULE_MS, validation.proposedTimesMs.toLongArray())
            }

            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun updateUi() {
        val validation = validateInputs(applyAutoClamp = false)
        addButton.isEnabled = validation.isValid
        updateProposals(validation)
    }

    private fun updateProposals(validation: ValidationResult) {
        proposalsContainer.removeAllViews()
        if (!validation.isValid) return

        validation.formattedTimes.forEachIndexed { index, time ->
            val text = getString(
                R.string.schedule_helper_proposal_item,
                time,
                validation.volumePerDoseMlList.getOrElse(index) { 0.0 }
            )
            val item = TextView(this).apply {
                setText(text)
                textSize = 16f
                if (index > 0) {
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params.topMargin =
                        resources.getDimensionPixelSize(R.dimen.schedule_helper_proposal_spacing)
                    layoutParams = params
                }
            }
            proposalsContainer.addView(item)
        }
    }

    private fun validateInputs(applyAutoClamp: Boolean): ValidationResult {
        startLayout.error = null
        endLayout.error = null
        doseLayout.error = null
        volumeLayout.error = null
        antiOverlapLayout.error = null

        val start = startMs
        val end = endMs
        var isValid = true

        if (start == null) {
            startLayout.error = getString(R.string.schedule_helper_error_start_time)
            isValid = false
        }

        if (end == null) {
            endLayout.error = getString(R.string.schedule_helper_error_end_time)
            isValid = false
        }

        if (start != null && end != null && end <= start) {
            endLayout.error = getString(R.string.schedule_helper_error_end_after_start)
            isValid = false
        }

        val doseCountRaw = doseInput.text?.toString()?.trim()?.toIntOrNull()
        if (doseCountRaw == null || doseCountRaw < 1) {
            doseLayout.error = getString(R.string.schedule_helper_error_dose_count)
            isValid = false
        }

        // ✅ limiter la saisie max à 12
        if (doseCountRaw != null && doseCountRaw > MAX_SCHEDULES_PER_PUMP) {
            if (applyAutoClamp) {
                doseInput.setText(MAX_SCHEDULES_PER_PUMP.toString())
            } else {
                doseLayout.error = "Maximum $MAX_SCHEDULES_PER_PUMP distributions."
                isValid = false
            }
        }

        val volumeTotal = volumeInput.text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()
        if (volumeTotal == null || volumeTotal <= 0) {
            volumeLayout.error = getString(R.string.schedule_helper_error_volume_total)
            isValid = false
        }

        val antiOverlap = calibrationAntiMin

        if (!isValid || start == null || end == null || doseCountRaw == null || volumeTotal == null) {
            return ValidationResult.invalid()
        }

        Log.i(
            TAG_ANTI_INTERFERENCE,
            "ANTI_INTERFERENCE reason=helper_anti_selected moduleId=$expectedEspId pump=$pumpNumber blockedByPump=-1 antiMin=$antiOverlap nextAllowed=-1 existingCount=0"
        )

        // ✅ Limite 12 en tenant compte de l’existant
        if (remainingSlots <= 0) {
            doseLayout.error = "Aucune place disponible (max 12)."
            return ValidationResult.invalid()
        }

        var doseCount = doseCountRaw.coerceAtMost(MAX_SCHEDULES_PER_PUMP)

        // ✅ clamp vs remainingSlots : en live => erreur rouge, au clic => clamp + toast
        if (doseCount > remainingSlots) {
            if (applyAutoClamp) {
                Toast.makeText(
                    this,
                    "Il ne reste que $remainingSlots place(s) disponible(s) (max 12).",
                    Toast.LENGTH_LONG
                ).show()
                doseCount = remainingSlots
                doseInput.setText(doseCount.toString())
            } else {
                doseLayout.error = "Il ne reste que $remainingSlots place(s) disponible(s) (max 12)."
                return ValidationResult.invalid()
            }
        }

        val windowMs = end - start
        if (antiOverlap > 0 && doseCount > 1) {
            val maxDosesPossible = ScheduleAntiInterferenceUtils.computeMaxDoses(windowMs, antiOverlap)
            val requiredMinAnti = ScheduleAntiInterferenceUtils.minAntiMinutesRequired(windowMs, doseCount)

            // ✅ Aligné avec le pattern généré (buildScheduleTimesMs utilise step = windowMs / doseCount)
            val actualSpacingMinutes = (windowMs.toDouble() / doseCount.toDouble()) / MINUTES_IN_MS
            val actualSpacingText = String.format(Locale.getDefault(), "%.1f", actualSpacingMinutes)

            if (doseCount > maxDosesPossible) {
                antiOverlapLayout.error =
                    "Avec $antiOverlap min d’anti-interférence, vous pouvez au maximum $maxDosesPossible doses sur cette plage. " +
                            "Minimum requis : $requiredMinAnti min (actuel: $actualSpacingText min)."
                Log.w(
                    TAG_ANTI_INTERFERENCE,
                    "invalid doseCount=$doseCount antiMin=$antiOverlap windowMs=$windowMs maxDoses=$maxDosesPossible requiredMinAnti=$requiredMinAnti actualSpacingMin=$actualSpacingMinutes reason=too_many_doses"
                )
                return ValidationResult.invalid()
            }

            Log.i(
                TAG_ANTI_INTERFERENCE,
                "valid antiMin=$antiOverlap doseCount=$doseCount windowMs=$windowMs maxDoses=$maxDosesPossible requiredMinAnti=$requiredMinAnti actualSpacingMin=$actualSpacingMinutes"
            )
        } else {
            Log.i(
                TAG_ANTI_INTERFERENCE,
                "no_constraint antiMin=$antiOverlap doseCount=$doseCount startMs=$start endMs=$end"
            )
        }

        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val flowCurrentPump = prefs.getFloat("esp_${expectedEspId}_pump${pumpNumber}_flow", 0f)

        val totalTenth = (volumeTotal * 10.0).roundToInt()
        val volumePerDoseTenthList = splitTotalVolumeTenth(totalTenth, doseCount)
        val volumePerDoseMlList = volumePerDoseTenthList.map { it / 10.0 }

        val splitBase = if (doseCount > 0) totalTenth / doseCount else 0
        val splitRemainder = if (doseCount > 0) totalTenth % doseCount else 0
        val splitSum = volumePerDoseTenthList.sum()
        Log.i(
            TAG_HELPER_VOLUME_SPLIT,
            "totalTenth=$totalTenth, doseCount=$doseCount, base=$splitBase, remainder=$splitRemainder, list=$volumePerDoseTenthList, sum=$splitSum"
        )

        val durationMsPerDose = volumePerDoseMlList.map { doseMl ->
            DoseValidationUtils.computeDurationMs(doseMl, flowCurrentPump)
        }
        if (durationMsPerDose.any { it == null }) {
            volumeLayout.error = "Débit non calibré : impossible de calculer la durée."
            Log.w(
                TAG_ANTI_INTERFERENCE,
                "helper_invalid reason=flow_missing pump=$pumpNumber flow=$flowCurrentPump antiMin=$antiOverlap"
            )
            return ValidationResult.invalid()
        }

        val durationMsPerDoseSafe: List<Long> = durationMsPerDose.filterNotNull()

        // ✅ FIX BUILD: List<Long> -> IntArray (pas de toIntArray() sur Collection<Long>)
        val durationsMsIntArray: IntArray = durationMsPerDoseSafe.map { it.toInt() }.toIntArray()

        val baseTimesMs = buildScheduleTimesMs(start, end, doseCount, antiOverlap)
        if (baseTimesMs.size != doseCount) {
            antiOverlapLayout.error = "Intervalle insuffisant pour générer les doses demandées."
            Log.w(
                TAG_ANTI_INTERFERENCE,
                "invalid_generation doseCount=$doseCount antiMin=$antiOverlap startMs=$start endMs=$end generated=${baseTimesMs.size}"
            )
            return ValidationResult.invalid()
        }

        val offsetsMs = LongArray(doseCount) { index -> baseTimesMs[index] - baseTimesMs[0] }
        Log.i(
            "HELPER_WINDOW",
            "startMs=$start endMs=$end doseCount=$doseCount antiMin=$antiOverlap durationByDoseMs=$durationMsPerDoseSafe offsetsMs=${offsetsMs.joinToString(",")}"
        )

        val allSchedules = loadSchedulesByPump()
        val flowByPump = (1..4).associateWith { pump ->
            prefs.getFloat("esp_${expectedEspId}_pump${pump}_flow", 0f)
        }
        Log.i(
            "HELPER_FLOW",
            "moduleId=$expectedEspId pump=$pumpNumber flowCurrentPump=$flowCurrentPump flowByPump=$flowByPump"
        )
        val existingGlobalIntervals = DoseValidationUtils.buildIntervalsFromSchedules(allSchedules, flowByPump)
        val aroundCurrentPump = existingGlobalIntervals
            .filter { it.pump == pumpNumber }
            .take(3)
            .joinToString { "[${it.startMs},${it.endMs})" }
        Log.i(
            "HELPER_EXISTING",
            "intervalsCount=${existingGlobalIntervals.size} moduleId=$expectedEspId pump=$pumpNumber aroundPump=$aroundCurrentPump pumps=${existingGlobalIntervals.map { it.pump }.distinct().sorted()}"
        )

        val start0 = findGlidedStart0(
            windowStartMs = start,
            windowEndMs = end,
            offsetsMs = offsetsMs,
            durationsMs = durationsMsIntArray,
            antiMin = antiOverlap,
            existingGlobal = existingGlobalIntervals,
            pumpNumber = pumpNumber
        ) ?: run {
            antiOverlapLayout.error = "Intervalle insuffisant pour générer les doses demandées."
            return ValidationResult.invalid()
        }

        val proposedTimesMs = offsetsMs.map { start0 + it }
        val acceptedIntervals = mutableListOf<DoseInterval>()
        var globalErrorMessage: String? = null

        for ((index, startCandidate) in proposedTimesMs.withIndex()) {
            val durationMs = durationMsPerDoseSafe.getOrElse(index) { return ValidationResult.invalid() }
            val candidate = DoseInterval(
                pump = pumpNumber,
                startMs = startCandidate,
                endMs = startCandidate + durationMs
            )

            val result = DoseValidationUtils.validateNewInterval(
                newInterval = candidate,
                existing = existingGlobalIntervals + acceptedIntervals,
                antiMin = antiOverlap
            )

            if (!result.isValid) {
                val conflictStart = result.conflictStartMs?.let { formatTimeMs(it) }
                val conflictEnd = result.conflictEndMs?.let { formatTimeMs(it.coerceAtMost(86_399_999L)) }
                globalErrorMessage = when (result.reason) {
                    DoseValidationReason.OVERLAP_SAME_PUMP ->
                        "Une distribution est déjà en cours à ce moment (${result.conflictPumpNum?.let { getPumpDisplayName(it) } ?: "une autre pompe"} de $conflictStart à $conflictEnd)."

                    DoseValidationReason.ANTI_INTERFERENCE_GAP -> {
                        val blockedPumpName =
                            result.conflictPumpNum?.let { getPumpDisplayName(it) } ?: "une autre pompe"
                        antiInterferenceGapErrorMessage(antiOverlap, blockedPumpName, result.nextAllowedStartMs)
                    }

                    DoseValidationReason.OVERFLOW_MIDNIGHT -> {
                        val endText = result.overflowEndMs?.let {
                            formatTimeMs(((it % 86_400_000L) + 86_400_000L) % 86_400_000L)
                        } ?: "--:--"
                        "Cette dose finirait après minuit (fin estimée : $endText). Avancez l’heure ou réduisez le volume."
                    }

                    else -> "Intervalle invalide."
                }
                Log.w(
                    TAG_ANTI_INTERFERENCE,
                    "ANTI_INTERFERENCE reason=${result.reason} moduleId=$expectedEspId pump=$pumpNumber blockedByPump=${result.conflictPumpNum} antiMin=$antiOverlap nextAllowed=${result.nextAllowedStartMs} existingCount=${existingGlobalIntervals.size}"
                )
                break
            }

            acceptedIntervals.add(candidate)
        }

        if (globalErrorMessage != null) {
            antiOverlapLayout.error = globalErrorMessage
            return ValidationResult.invalid()
        }

        val formattedTimes = proposedTimesMs.map { formatTimeMs(it) }

        val maxDurationMs = durationMsPerDoseSafe.maxOrNull() ?: 0L
        if (maxDurationMs > MAX_DOSE_DURATION_SEC * 1000L) {
            val maxVolume = flowCurrentPump.toDouble() * MAX_DOSE_DURATION_SEC.toDouble()
            volumeLayout.error =
                "Une distribution ne peut pas dépasser 600 secondes. " +
                        "Avec le débit actuel (${String.format(Locale.getDefault(), "%.1f", flowCurrentPump)} mL/s), " +
                        "la dose maximale est ${String.format(Locale.getDefault(), "%.1f", maxVolume)} mL."
            return ValidationResult.invalid()
        }

        Log.i(
            TAG_ANTI_INTERFERENCE,
            "helper_valid window=[$start,$end] antiMin=$antiOverlap doseCount=$doseCount flow=$flowCurrentPump durationMsPerDose=$durationMsPerDoseSafe intervals=${acceptedIntervals.joinToString { "P${it.pump}[${it.startMs},${it.endMs})" }}"
        )

        return ValidationResult(
            isValid = true,
            startMs = start,
            endMs = end,
            antiOverlapMinutes = antiOverlap,
            doseCount = doseCount,
            volumePerDoseMlList = volumePerDoseMlList,
            volumePerDoseTenthList = volumePerDoseTenthList,
            proposedTimesMs = proposedTimesMs,
            formattedTimes = formattedTimes
        )
    }

    private fun getPumpDisplayName(pump: Int): String {
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val savedName = prefs.getString("esp_${expectedEspId}_pump${pump}_name", null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return savedName ?: getString(R.string.pump_label, pump)
    }

    private fun loadSchedulesByPump(): Map<Int, List<PumpSchedule>> {
        val schedulesPrefs = getSharedPreferences("schedules", Context.MODE_PRIVATE)
        return (1..4).associateWith { pump ->
            val json = schedulesPrefs.getString("esp_${expectedEspId}_pump$pump", null)
            if (json.isNullOrBlank()) emptyList()
            else runCatching { PumpScheduleJson.fromJson(json).toList() }.getOrDefault(emptyList())
        }
    }

    private fun buildScheduleTimesMs(
        startMs: Long,
        endMs: Long,
        doseCount: Int,
        antiOverlapMinutes: Int
    ): List<Long> {
        if (doseCount <= 0) return emptyList()
        if (doseCount == 1) return listOf(startMs)

        val durationMs = endMs - startMs
        val stepMs = durationMs / doseCount.toLong()
        if (stepMs <= 0L) return emptyList()
        if (antiOverlapMinutes > 0) {
            val minStepMsRequired = antiOverlapMinutes.toLong() * MS_PER_MINUTE
            if (stepMs < minStepMsRequired) {
                Log.w(
                    TAG_ANTI_INTERFERENCE,
                    "build_invalid startMs=$startMs endMs=$endMs doseCount=$doseCount antiMin=$antiOverlapMinutes stepMs=$stepMs minStepMsRequired=$minStepMsRequired"
                )
                return emptyList()
            }
        }

        return (0 until doseCount).map { index ->
            startMs + index * stepMs
        }
    }

    private fun findGlidedStart0(
        windowStartMs: Long,
        windowEndMs: Long,
        offsetsMs: LongArray,
        durationsMs: IntArray,
        antiMin: Int,
        existingGlobal: List<DoseInterval>,
        pumpNumber: Int
    ): Long? {
        val dayEndStrict = 86_400_000L - 1L
        val ws = (windowStartMs / MS_PER_MINUTE) * MS_PER_MINUTE
        val we = (windowEndMs / MS_PER_MINUTE) * MS_PER_MINUTE
        val doseCount = offsetsMs.size
        Log.i("HELPER_GLIDE", "start anti=$antiMin window=[$ws,$we] count=$doseCount pump=$pumpNumber")

        fun isValidStart(start0: Long): Boolean {
            if (start0 < ws || doseCount == 0 || durationsMs.size != doseCount) return false

            val accepted = mutableListOf<DoseInterval>()
            for (i in 0 until doseCount) {
                val start = start0 + offsetsMs[i]
                val end = start + durationsMs[i].toLong()

                // ✅ IMPORTANT: la dose doit tenir dans la fenêtre ET finir strictement avant minuit
                if (start < ws) return false
                if (start > windowEndMs) return false
                if (end > windowEndMs) return false
                if (end > dayEndStrict) return false

                val newInterval = DoseInterval(pump = pumpNumber, startMs = start, endMs = end)
                val res = DoseValidationUtils.validateNewInterval(newInterval, existingGlobal + accepted, antiMin)
                if (i == 0 && start0 == ws) {
                    Log.i(
                        "VALIDATE_CANDIDATE",
                        "candidateStart=$start reason=${res.reason} conflictStart=${res.conflictStartMs} conflictEnd=${res.conflictEndMs} nextAllowed=${res.nextAllowedStartMs}"
                    )
                }
                if (!res.isValid) return false
                accepted.add(newInterval)
            }
            return true
        }

        var start0: Long? = null

        // Forward minute-by-minute
        var candidate = ws
        while (candidate <= we && start0 == null) {
            if (isValidStart(candidate)) start0 = candidate
            candidate += MS_PER_MINUTE
        }

        // Backward minute-by-minute
        candidate = we
        while (candidate >= ws && start0 == null) {
            if (isValidStart(candidate)) start0 = candidate
            candidate -= MS_PER_MINUTE
        }

        Log.i("HELPER_GLIDE", start0?.let { "result start0=$it" } ?: "result NO_SPACE")
        return start0
    }

    private fun splitTotalVolumeTenth(totalTenth: Int, doseCount: Int): List<Int> {
        return VolumeSplitUtils.splitTotalVolumeTenth(totalTenth, doseCount)
    }

    private fun showTimePicker(initialMs: Long?, onSelected: (Long) -> Unit) {
        val calendar = Calendar.getInstance()
        val initialHour =
            initialMs?.let { (it / MS_PER_HOUR).toInt() } ?: calendar.get(Calendar.HOUR_OF_DAY)
        val initialMinute =
            initialMs?.let { ((it % MS_PER_HOUR) / MS_PER_MINUTE).toInt() }
                ?: calendar.get(Calendar.MINUTE)

        TimePickerDialog(
            this,
            { _, hourOfDay, minute ->
                onSelected(toMs(hourOfDay, minute))
            },
            initialHour,
            initialMinute,
            true
        ).show()
    }

    private fun formatTimeMs(timeMs: Long): String {
        val hours = (timeMs / MS_PER_HOUR).toInt()
        val minutes = ((timeMs % MS_PER_HOUR) / MS_PER_MINUTE).toInt()
        return String.format(Locale.getDefault(), "%02d:%02d", hours, minutes)
    }

    private data class ValidationResult(
        val isValid: Boolean,
        val startMs: Long,
        val endMs: Long,
        val antiOverlapMinutes: Int,
        val doseCount: Int,
        val volumePerDoseMlList: List<Double>,
        val volumePerDoseTenthList: List<Int>,
        val proposedTimesMs: List<Long>,
        val formattedTimes: List<String>
    ) {
        companion object {
            fun invalid(): ValidationResult {
                return ValidationResult(
                    isValid = false,
                    startMs = 0L,
                    endMs = 0L,
                    antiOverlapMinutes = 0,
                    doseCount = 0,
                    volumePerDoseMlList = emptyList(),
                    volumePerDoseTenthList = emptyList(),
                    proposedTimesMs = emptyList(),
                    formattedTimes = emptyList()
                )
            }
        }
    }

    companion object {
        const val EXTRA_PUMP_NUMBER = "pumpNumber"
        const val EXTRA_MODULE_ID = "moduleId"
        const val EXTRA_START_MS = "startMs"
        const val EXTRA_END_MS = "endMs"
        const val EXTRA_ANTI_CHEV_MINUTES = "antiChevMinutes"
        const val EXTRA_VOLUME_PER_DOSE = "volumePerDose"
        const val EXTRA_SCHEDULE_TIMES = "scheduleTimes"
        const val EXTRA_SCHEDULE_MS = "scheduleMs"
        const val EXTRA_VOLUME_PER_DOSE_TENTH_LIST = "volumePerDoseTenthList"

        private const val MAX_SCHEDULES_PER_PUMP = 12
        private const val MAX_DOSE_DURATION_SEC = 600

        private const val MS_PER_MINUTE = 60_000L
        private const val MINUTES_IN_MS = 60_000.0
        private const val TAG_ANTI_INTERFERENCE = "ANTI_INTERFERENCE"
        private const val TAG_TIME_BUG = "TIME_BUG"
        private const val TAG_HELPER_VOLUME_SPLIT = "HELPER_VOLUME_SPLIT"
        private const val MS_PER_HOUR = 3_600_000L

        private fun toMs(hour: Int, minute: Int): Long {
            return (hour * 60L + minute) * MS_PER_MINUTE
        }
    }
}
