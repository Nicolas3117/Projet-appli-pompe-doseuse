package com.esp32pumpwifi.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar

class TankFragment : Fragment() {

    private var pumpNumber: Int = 1

    companion object {
        private const val ARG_PUMP_NUMBER = "pump_number"

        fun newInstance(pumpNumber: Int): TankFragment =
            TankFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_PUMP_NUMBER, pumpNumber)
                }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pumpNumber = arguments?.getInt(ARG_PUMP_NUMBER) ?: 1
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_tank, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences("prefs", Context.MODE_PRIVATE)

        val activeModule = Esp32Manager.getActive(requireContext())
        if (activeModule == null) {
            Toast.makeText(requireContext(), "Aucun ESP32 sélectionné", Toast.LENGTH_LONG).show()
            requireActivity().finish()
            return
        }

        val espId = activeModule.id

        val tankLevel = view.findViewById<TextView>(R.id.tv_tank_level)
        val tankCapacity = view.findViewById<EditText>(R.id.edit_tank_capacity)
        val tankAlert = view.findViewById<EditText>(R.id.edit_tank_alert)
        val tankReset = view.findViewById<View>(R.id.btn_tank_reset)
        val tankAlertSave = view.findViewById<View>(R.id.btn_save_tank_alert)

        loadTankUI(prefs, espId, pumpNumber, tankLevel, tankCapacity, tankAlert)

        tankReset.setOnClickListener {
            confirmTankReset(
                prefs = prefs,
                espId = espId,
                pumpNum = pumpNumber,
                level = tankLevel,
                capacity = tankCapacity,
                alert = tankAlert
            )
        }

        tankAlertSave.setOnClickListener {
            val value = tankAlert.text.toString().toIntOrNull()
            if (value != null) {
                prefs.edit()
                    .putInt("esp_${espId}_pump${pumpNumber}_low_threshold", value)
                    .apply()
                showThresholdSaved()
            }
        }
    }

    private fun showThresholdSaved() {
        val root = requireView().findViewById<View>(R.id.root_refill)
        Snackbar.make(
            root,
            getString(R.string.threshold_saved),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun confirmTankReset(
        prefs: SharedPreferences,
        espId: Long,
        pumpNum: Int,
        level: TextView,
        capacity: EditText,
        alert: EditText
    ) {
        val pumpName = getPumpDisplayName(prefs, espId, pumpNum)
        AlertDialog.Builder(requireContext())
            .setTitle("Confirmation")
            .setMessage(
                "Confirmer que le réservoir de ${pumpName} a été entièrement rechargé ?"
            )
            .setPositiveButton("Oui") { _, _ ->

                val capacityValue = capacity.text.toString().toIntOrNull()
                if (capacityValue == null || capacityValue <= 0) {
                    Toast.makeText(requireContext(), "Volume invalide", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                prefs.edit()
                    .putInt("esp_${espId}_pump${pumpNum}_tank_capacity", capacityValue)
                    .putFloat(
                        "esp_${espId}_pump${pumpNum}_tank_remaining",
                        capacityValue.toFloat()
                    )
                    .putBoolean("esp_${espId}_pump${pumpNum}_low_alert_sent", false)
                    .putBoolean("esp_${espId}_pump${pumpNum}_empty_alert_sent", false)
                    .apply()

                loadTankUI(
                    prefs,
                    espId,
                    pumpNum,
                    level,
                    capacity,
                    alert
                )

                Toast.makeText(requireContext(), "Réservoir rechargé", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun getPumpDisplayName(
        prefs: SharedPreferences,
        espId: Long,
        pumpNum: Int
    ): String {
        val fallback = "Pompe $pumpNum"
        val name = prefs.getString("esp_${espId}_pump${pumpNum}_name", fallback)
        return if (name.isNullOrBlank()) fallback else name
    }

    private fun loadTankUI(
        prefs: SharedPreferences,
        espId: Long,
        pumpNum: Int,
        levelTv: TextView,
        capacityEt: EditText,
        alertEt: EditText
    ) {
        val capacity =
            prefs.getInt("esp_${espId}_pump${pumpNum}_tank_capacity", 0)

        val remaining =
            prefs.getFloat(
                "esp_${espId}_pump${pumpNum}_tank_remaining",
                capacity.toFloat()
            )

        val threshold =
            prefs.getInt(
                "esp_${espId}_pump${pumpNum}_low_threshold",
                20
            )

        if (capacity > 0) {
            val percent = ((remaining / capacity) * 100).toInt()
            levelTv.text = "Restant : $percent %"
        } else {
            levelTv.text = "Restant : -- %"
        }

        capacityEt.setText(if (capacity > 0) capacity.toString() else "")
        alertEt.setText(threshold.toString())

        var skipNextFocusSave = false
        alertEt.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                if (skipNextFocusSave) {
                    skipNextFocusSave = false
                    return@setOnFocusChangeListener
                }
                alertEt.text.toString().toIntOrNull()?.let {
                    prefs.edit()
                        .putInt(
                            "esp_${espId}_pump${pumpNum}_low_threshold",
                            it
                        )
                        .apply()
                    showThresholdSaved()
                }
            }
        }

        alertEt.setOnEditorActionListener { _, actionId, event ->
            val isDone = actionId == EditorInfo.IME_ACTION_DONE
            val isEnter = event != null &&
                event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (isDone || isEnter) {
                alertEt.text.toString().toIntOrNull()?.let {
                    skipNextFocusSave = true
                    prefs.edit()
                        .putInt(
                            "esp_${espId}_pump${pumpNum}_low_threshold",
                            it
                        )
                        .apply()
                    showThresholdSaved()
                }
                return@setOnEditorActionListener true
            }
            false
        }
    }
}
