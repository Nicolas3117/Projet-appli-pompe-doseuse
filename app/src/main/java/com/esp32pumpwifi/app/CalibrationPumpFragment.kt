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
import androidx.fragment.app.Fragment

class CalibrationPumpFragment : Fragment() {

    private var moduleId: Long = 0L
    private var pumpNum: Int = 1

    companion object {
        private const val ARG_MODULE_ID = "module_id"
        private const val ARG_PUMP_NUM = "pump_num"

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

        val prefs = requireContext().getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val pumpNameKey = "esp_${moduleId}_pump${pumpNum}_name"
        val pumpFlowKey = "esp_${moduleId}_pump${pumpNum}_flow"

        val currentName = prefs.getString(pumpNameKey, "")
        if (!currentName.isNullOrBlank()) {
            editPumpName.setText(currentName)
        }

        if (prefs.contains(pumpFlowKey)) {
            val flow = prefs.getFloat(pumpFlowKey, 0f)
            tvResult.text = "Débit : $flow"
        }

        btnSavePumpName.setOnClickListener {
            val name = editPumpName.text.toString().trim()
            if (name.isNotEmpty()) {
                prefs.edit().putString(pumpNameKey, name).apply()
                Toast.makeText(requireContext(), "Nom sauvegardé", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
