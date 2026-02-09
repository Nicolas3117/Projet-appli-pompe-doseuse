package com.esp32pumpwifi.app

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

class ScheduleHelperActivity : AppCompatActivity() {

    private var startMinutes: Int? = null
    private var endMinutes: Int? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule_helper)

        title = getString(R.string.schedule_helper_title)

        pumpNumber = intent.getIntExtra(EXTRA_PUMP_NUMBER, 1)
        moduleId = intent.getStringExtra(EXTRA_MODULE_ID)

        val pumpLabel = getString(R.string.pump_label, pumpNumber)
        findViewById<TextView>(R.id.tv_schedule_helper_pump).text = pumpLabel

        bindViews()
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

    private fun setupListeners() {
        startInput.setOnClickListener {
            showTimePicker(startMinutes) { minutes ->
                startMinutes = minutes
                startInput.setText(formatMinutes(minutes))
                updateUi()
            }
        }

        endInput.setOnClickListener {
            showTimePicker(endMinutes) { minutes ->
                endMinutes = minutes
                endInput.setText(formatMinutes(minutes))
                updateUi()
            }
        }

        doseInput.addTextChangedListener { updateUi() }
        volumeInput.addTextChangedListener { updateUi() }
        antiOverlapInput.addTextChangedListener { updateUi() }

        findViewById<Button>(R.id.button_cancel).setOnClickListener {
            finish()
        }

        addButton.setOnClickListener {
            val validation = validateInputs()
            if (!validation.isValid) {
                updateUi()
                return@setOnClickListener
            }

            val resultIntent = Intent().apply {
                putExtra(EXTRA_PUMP_NUMBER, pumpNumber)
                putExtra(EXTRA_MODULE_ID, moduleId)
                putExtra(EXTRA_START_MINUTES, validation.startMinutes)
                putExtra(EXTRA_END_MINUTES, validation.endMinutes)
                putExtra(EXTRA_ANTI_CHEV_MINUTES, validation.antiOverlapMinutes)
                putExtra(EXTRA_VOLUME_PER_DOSE, validation.volumePerDose)
                putStringArrayListExtra(EXTRA_SCHEDULE_TIMES, ArrayList(validation.formattedTimes))
                putIntegerArrayListExtra(
                    EXTRA_SCHEDULE_MINUTES,
                    ArrayList(validation.proposedMinutes)
                )
            }

            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun updateUi() {
        val validation = validateInputs()
        addButton.isEnabled = validation.isValid
        updateProposals(validation)
    }

    private fun updateProposals(validation: ValidationResult) {
        proposalsContainer.removeAllViews()
        if (!validation.isValid) {
            return
        }

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
                    params.topMargin = resources.getDimensionPixelSize(R.dimen.schedule_helper_proposal_spacing)
                    layoutParams = params
                }
            }
            proposalsContainer.addView(item)
        }
    }

    private fun validateInputs(): ValidationResult {
        startLayout.error = null
        endLayout.error = null
        doseLayout.error = null
        volumeLayout.error = null
        antiOverlapLayout.error = null

        val start = startMinutes
        val end = endMinutes

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

        val doseCount = doseInput.text?.toString()?.trim()?.toIntOrNull()
        if (doseCount == null || doseCount < 1) {
            doseLayout.error = getString(R.string.schedule_helper_error_dose_count)
            isValid = false
        }

        val volumeTotal = volumeInput.text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()
        if (volumeTotal == null || volumeTotal <= 0) {
            volumeLayout.error = getString(R.string.schedule_helper_error_volume_total)
            isValid = false
        }

        val antiOverlap = antiOverlapInput.text?.toString()?.trim()?.toIntOrNull()
        if (antiOverlap == null || antiOverlap < 0) {
            antiOverlapLayout.error = getString(R.string.schedule_helper_error_anti_overlap)
            isValid = false
        }

        if (!isValid || start == null || end == null || doseCount == null || volumeTotal == null || antiOverlap == null) {
            return ValidationResult.invalid()
        }

        val proposedMinutes = buildScheduleMinutes(start, end, doseCount)
        val formattedTimes = proposedMinutes.map { formatMinutes(it) }
        val volumePerDose = volumeTotal / doseCount.toDouble()

        return ValidationResult(
            isValid = true,
            startMinutes = start,
            endMinutes = end,
            antiOverlapMinutes = antiOverlap,
            doseCount = doseCount,
            volumePerDose = volumePerDose,
            proposedMinutes = proposedMinutes,
            formattedTimes = formattedTimes
        )
    }

    private fun buildScheduleMinutes(start: Int, end: Int, doseCount: Int): List<Int> {
        if (doseCount <= 1) {
            return listOf(start)
        }
        val duration = end - start
        val step = duration.toDouble() / (doseCount - 1)
        return (0 until doseCount).map { index ->
            (start + index * step).roundToInt()
        }
    }

    private fun showTimePicker(initialMinutes: Int?, onSelected: (Int) -> Unit) {
        val calendar = Calendar.getInstance()
        val initialHour = initialMinutes?.div(60) ?: calendar.get(Calendar.HOUR_OF_DAY)
        val initialMinute = initialMinutes?.rem(60) ?: calendar.get(Calendar.MINUTE)

        TimePickerDialog(
            this,
            { _, hourOfDay, minute ->
                onSelected(hourOfDay * 60 + minute)
            },
            initialHour,
            initialMinute,
            true
        ).show()
    }

    private fun formatMinutes(minutes: Int): String {
        val hours = minutes / 60
        val remaining = minutes % 60
        return String.format(Locale.getDefault(), "%02d:%02d", hours, remaining)
    }

    private data class ValidationResult(
        val isValid: Boolean,
        val startMinutes: Int,
        val endMinutes: Int,
        val antiOverlapMinutes: Int,
        val doseCount: Int,
        val volumePerDose: Double,
        val proposedMinutes: List<Int>,
        val formattedTimes: List<String>
    ) {
        companion object {
            fun invalid(): ValidationResult {
                return ValidationResult(
                    isValid = false,
                    startMinutes = 0,
                    endMinutes = 0,
                    antiOverlapMinutes = 0,
                    doseCount = 0,
                    volumePerDose = 0.0,
                    proposedMinutes = emptyList(),
                    formattedTimes = emptyList()
                )
            }
        }
    }

    companion object {
        const val EXTRA_PUMP_NUMBER = "pumpNumber"
        const val EXTRA_MODULE_ID = "moduleId"
        const val EXTRA_START_MINUTES = "startMinutes"
        const val EXTRA_END_MINUTES = "endMinutes"
        const val EXTRA_ANTI_CHEV_MINUTES = "antiChevMinutes"
        const val EXTRA_VOLUME_PER_DOSE = "volumePerDose"
        const val EXTRA_SCHEDULE_TIMES = "scheduleTimes"
        const val EXTRA_SCHEDULE_MINUTES = "scheduleMinutes"
    }
}
