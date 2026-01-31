package com.esp32pumpwifi.app

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import java.util.Locale

class CalibrationPumpFragment : Fragment() {

    private var moduleId: Long = 0L
    private var pumpNum: Int = 1

    companion object {
        private const val ARG_MODULE_ID = "module_id"
        private const val ARG_PUMP_NUM = "pump_num"
        private const val MAX_CALIBRATION_DURATION_SEC = 250
        private const val FLOW_DISPLAY_DECIMALS = 3

        fun newInstance(moduleId: Long, pumpNum: Int): CalibrationPumpFragment =
            CalibrationPumpFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_MODULE_ID, moduleId)
                    putInt(ARG_PUMP_NUM, pumpNum)
                }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            moduleId = it.getLong(ARG_MODULE_ID)
            pumpNum = it.getInt(ARG_PUMP_NUM)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_calibration_pump, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val editPumpName = view.findViewById<EditText>(R.id.edit_pump_name)
        val btnSavePumpName = view.findViewById<ImageButton>(R.id.btn_save_pump_name)
        val tvResult = view.findViewById<TextView>(R.id.tv_result)
        val editDuration = view.findViewById<EditText>(R.id.edit_duration)
        val editVolume = view.findViewById<EditText>(R.id.edit_volume)
        val editVolumeBis = view.findViewById<EditText>(R.id.edit_volume_bis)
        val btnStart = view.findViewById<View>(R.id.btn_start)
        val btnCalc = view.findViewById<View>(R.id.btn_calc)

        val activeModule = Esp32Manager.getActive(requireContext())
        if (activeModule == null || activeModule.id != moduleId) {
            Toast.makeText(requireContext(), "Aucun module actif", Toast.LENGTH_SHORT).show()
            return
        }
        val esp32Ip = activeModule.ip

        val prefs = requireContext().getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val pumpNameKey = "esp_${moduleId}_pump${pumpNum}_name"
        val pumpFlowKey = "esp_${moduleId}_pump${pumpNum}_flow"

        val currentName = prefs.getString(pumpNameKey, "")
        val displayName = if (!currentName.isNullOrBlank()) {
            currentName
        } else {
            "Pompe $pumpNum"
        }
        editPumpName.setText(displayName)

        if (prefs.contains(pumpFlowKey)) {
            val flow = prefs.getFloat(pumpFlowKey, 0f)
            tvResult.text = "Débit : ${formatFlow(flow)} mL/s"
        }

        btnSavePumpName.setOnClickListener {
            val name = editPumpName.text.toString().trim()
            if (name.isNotEmpty()) {
                prefs.edit().putString(pumpNameKey, name).apply()
                Toast.makeText(requireContext(), "Nom sauvegardé", Toast.LENGTH_SHORT).show()
            }
        }

        btnStart.setOnClickListener {
            val duration = editDuration.text.toString().toIntOrNull()
            if (duration == null || duration <= 0) {
                Toast.makeText(requireContext(), "Durée invalide", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (duration > MAX_CALIBRATION_DURATION_SEC) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Impossible")
                    .setMessage(
                        "Durée trop longue : maximum ${MAX_CALIBRATION_DURATION_SEC}s\n" +
                            "Réduis la durée pour l’étalonnage."
                    )
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnClickListener
            }

            NetworkHelper.sendManualCommand(requireContext(), esp32Ip, pumpNum, duration)
        }

        btnCalc.setOnClickListener {
            val duration = parseFloatFr(editDuration.text.toString())
            val volume1 = parseFloatFr(editVolume.text.toString())
            val volume2Text = editVolumeBis.text.toString()
            val volume2 = parseFloatFr(volume2Text)

            if (duration == null || duration <= 0f) {
                Toast.makeText(requireContext(), "Durée invalide", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (volume1 == null || volume1 <= 0f) {
                Toast.makeText(requireContext(), "Volume 1 invalide", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (volume2Text.isNotBlank() && (volume2 == null || volume2 <= 0f)) {
                Toast.makeText(requireContext(), "Volume 2 invalide", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val flow1 = volume1 / duration
            val flowFinal = if (volume2 != null && volume2 > 0f) {
                val flow2 = volume2 / duration
                (flow1 + flow2) / 2f
            } else {
                flow1
            }

            tvResult.text = "Débit : ${formatFlow(flowFinal)} mL/s"

            prefs.edit()
                .putFloat("esp_${moduleId}_pump${pumpNum}_flow", flowFinal)
                .apply()
        }
    }

    private fun formatFlow(flow: Float): String {
        return String.format(
            Locale.getDefault(),
            "%.${FLOW_DISPLAY_DECIMALS}f",
            flow
        )
    }

    private fun parseFloatFr(text: String): Float? {
        val t = text.trim()
        if (t.isEmpty()) return null
        return t.replace(',', '.').toFloatOrNull()
    }
}
