package com.esp32pumpwifi.app

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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

    // ✅ places restantes avant d’atteindre 12 (en tenant compte des prefs existantes)
    private var remainingSlots: Int = MAX_SCHEDULES_PER_PUMP

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

        val pumpLabel = getString(R.string.pump_label, pumpNumber)
        findViewById<TextView>(R.id.tv_schedule_helper_pump).text = pumpLabel

        bindViews()
        computeRemainingSlots()
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
        antiOverlapInput.addTextChangedListener { updateUi() }

        findViewById<Button>(R.id.button_cancel).setOnClickListener { finish() }

        addButton.setOnClickListener {
            val validation = validateInputs(applyAutoClamp = true)
            if (!validation.isValid) {
                updateUi()
                return@setOnClickListener
            }

            val resultIntent = Intent().apply {
                putExtra(EXTRA_PUMP_NUMBER, pumpNumber)
                putExtra(EXTRA_MODULE_ID, moduleId)
                putExtra(EXTRA_START_MS, validation.startMs)
                putExtra(EXTRA_END_MS, validation.endMs)
                putExtra(EXTRA_ANTI_CHEV_MINUTES, validation.antiOverlapMinutes)
                putExtra(EXTRA_VOLUME_PER_DOSE, validation.volumePerDose)
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
                validation.volumePerDose
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

        // ✅ Anti-chevauchement : vide => 0 (non bloquant)
        val antiText = antiOverlapInput.text?.toString()?.trim().orEmpty()
        val antiOverlap = if (antiText.isEmpty()) 0 else antiText.toIntOrNull()
        if (antiOverlap == null || antiOverlap < 0) {
            antiOverlapLayout.error = getString(R.string.schedule_helper_error_anti_overlap)
            isValid = false
        }

        if (!isValid || start == null || end == null || doseCountRaw == null || volumeTotal == null || antiOverlap == null) {
            return ValidationResult.invalid()
        }

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
            val actualSpacingMinutes = (windowMs.toDouble() / (doseCount - 1).toDouble()) / MINUTES_IN_MS
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

        val proposedTimesMs = buildScheduleTimesMs(start, end, doseCount, antiOverlap)
        if (proposedTimesMs.size != doseCount) {
            antiOverlapLayout.error = "Intervalle insuffisant pour générer les doses demandées."
            Log.w(
                TAG_ANTI_INTERFERENCE,
                "invalid_generation doseCount=$doseCount antiMin=$antiOverlap startMs=$start endMs=$end generated=${proposedTimesMs.size}"
            )
            return ValidationResult.invalid()
        }
        val formattedTimes = proposedTimesMs.map { formatTimeMs(it) }
        val volumePerDose = volumeTotal / doseCount.toDouble()

        // ✅ BONUS : bloquer si une dose > 600 secondes (si flow dispo)
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val flow = prefs.getFloat("esp_${expectedEspId}_pump${pumpNumber}_flow", 0f) // mL/s
        if (flow > 0f) {
            val durationSec = (volumePerDose / flow.toDouble())
            if (durationSec > MAX_DOSE_DURATION_SEC) {
                val maxVolume = flow.toDouble() * MAX_DOSE_DURATION_SEC.toDouble()
                AlertDialog.Builder(this)
                    .setTitle("Durée trop longue")
                    .setMessage(
                        "Une distribution ne peut pas dépasser 600 secondes.\n" +
                                "Avec le débit actuel (${String.format(Locale.getDefault(), "%.1f", flow)} mL/s), " +
                                "la dose maximale est ${String.format(Locale.getDefault(), "%.1f", maxVolume)} mL."
                    )
                    .setPositiveButton("OK", null)
                    .show()
                return ValidationResult.invalid()
            }
        }

        return ValidationResult(
            isValid = true,
            startMs = start,
            endMs = end,
            antiOverlapMinutes = antiOverlap,
            doseCount = doseCount,
            volumePerDose = volumePerDose,
            proposedTimesMs = proposedTimesMs,
            formattedTimes = formattedTimes
        )
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
        val stepMs = durationMs / (doseCount - 1).toLong()
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
        val volumePerDose: Double,
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
                    volumePerDose = 0.0,
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

        private const val MAX_SCHEDULES_PER_PUMP = 12
        private const val MAX_DOSE_DURATION_SEC = 600

        private const val MS_PER_MINUTE = 60_000L
        private const val MINUTES_IN_MS = 60_000.0
        private const val TAG_ANTI_INTERFERENCE = "ANTI_INTERFERENCE"
        private const val MS_PER_HOUR = 3_600_000L

        private fun toMs(hour: Int, minute: Int): Long {
            return (hour * 60L + minute) * MS_PER_MINUTE
        }
    }
}
