package com.esp32pumpwifi.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class CalibrationActivity : AppCompatActivity() {

    companion object {
        // ✅ Limite spécifique calibration
        const val MAX_CALIBRATION_DURATION_SEC = 250
        const val FLOW_DISPLAY_DECIMALS = 3
    }

    private fun formatFlow(flow: Float): String {
        return String.format(
            Locale.getDefault(),
            "%.${FLOW_DISPLAY_DECIMALS}f",
            flow
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)

        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)

        // ----------------------------------------------------------
        // ✅ ESP32 ACTIF
        // ----------------------------------------------------------
        val activeModule = Esp32Manager.getAll(this).firstOrNull { it.isActive }

        if (activeModule == null) {
            Toast.makeText(this, "Aucun ESP32 sélectionné", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val espId = activeModule.id
        val esp32Ip = activeModule.ip

        Log.i("CALIBRATION", "ESP32 utilisé → IP = $esp32Ip")

        // ----------------------------------------------------------
        // 🔧 UI – ÉTALONNAGE
        // ----------------------------------------------------------
        val pumpDurations = arrayOf(
            findViewById<EditText>(R.id.edit_duration1),
            findViewById(R.id.edit_duration2),
            findViewById(R.id.edit_duration3),
            findViewById(R.id.edit_duration4)
        )

        val pumpVolumes = arrayOf(
            findViewById<EditText>(R.id.edit_volume1),
            findViewById(R.id.edit_volume2),
            findViewById(R.id.edit_volume3),
            findViewById(R.id.edit_volume4)
        )

        val pumpResults = arrayOf(
            findViewById<TextView>(R.id.tv_result1),
            findViewById(R.id.tv_result2),
            findViewById(R.id.tv_result3),
            findViewById(R.id.tv_result4)
        )

        val startButtons = arrayOf(
            findViewById<Button>(R.id.btn_start1),
            findViewById(R.id.btn_start2),
            findViewById(R.id.btn_start3),
            findViewById(R.id.btn_start4)
        )

        val calcButtons = arrayOf(
            findViewById<Button>(R.id.btn_calc1),
            findViewById(R.id.btn_calc2),
            findViewById(R.id.btn_calc3),
            findViewById(R.id.btn_calc4)
        )

        val pumpTitles = arrayOf(
            findViewById<TextView>(R.id.tv_title1),
            findViewById(R.id.tv_title2),
            findViewById(R.id.tv_title3),
            findViewById(R.id.tv_title4)
        )

        val pumpNames = arrayOf(
            findViewById<EditText>(R.id.edit_name1),
            findViewById(R.id.edit_name2),
            findViewById(R.id.edit_name3),
            findViewById(R.id.edit_name4)
        )

        // ----------------------------------------------------------
        // 🔋 UI – RÉSERVOIRS
        // ----------------------------------------------------------
        val tankLevels = arrayOf(
            findViewById<TextView>(R.id.tv_tank_level1),
            findViewById(R.id.tv_tank_level2),
            findViewById(R.id.tv_tank_level3),
            findViewById(R.id.tv_tank_level4)
        )

        val tankCapacities = arrayOf(
            findViewById<EditText>(R.id.edit_tank_capacity1),
            findViewById(R.id.edit_tank_capacity2),
            findViewById(R.id.edit_tank_capacity3),
            findViewById(R.id.edit_tank_capacity4)
        )

        val tankAlerts = arrayOf(
            findViewById<EditText>(R.id.edit_tank_alert1),
            findViewById(R.id.edit_tank_alert2),
            findViewById(R.id.edit_tank_alert3),
            findViewById(R.id.edit_tank_alert4)
        )

        val tankResetButtons = arrayOf(
            findViewById<Button>(R.id.btn_tank_reset1),
            findViewById(R.id.btn_tank_reset2),
            findViewById(R.id.btn_tank_reset3),
            findViewById(R.id.btn_tank_reset4)
        )

        // ----------------------------------------------------------
        // 📌 CHARGEMENT INITIAL
        // ----------------------------------------------------------
        for (i in 0..3) {
            val pumpNum = i + 1

            val name = prefs.getString(
                "esp_${espId}_pump${pumpNum}_name",
                "Pompe $pumpNum"
            ) ?: "Pompe $pumpNum"

            pumpNames[i].setText(name)
            pumpTitles[i].text = name

            val flowKey = "esp_${espId}_pump${pumpNum}_flow"
            val flow = prefs.getFloat(flowKey, -1f)

            if (flow > 0f) {
                pumpResults[i].text = "Débit : ${formatFlow(flow)} mL/s"
            }

            loadTankUI(
                prefs,
                espId,
                pumpNum,
                tankLevels[i],
                tankCapacities[i],
                tankAlerts[i]
            )
        }

        // ----------------------------------------------------------
        // ▶️ COMMANDES + CALCUL
        // ----------------------------------------------------------
        for (i in 0..3) {
            val pumpNum = i + 1

            startButtons[i].setOnClickListener {
                val duration = pumpDurations[i].text.toString().toIntOrNull()
                if (duration == null || duration <= 0) {
                    Toast.makeText(this, "Durée invalide", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // ✅ Blocage > 250s (limite calibration) + message clair
                if (duration > MAX_CALIBRATION_DURATION_SEC) {
                    AlertDialog.Builder(this)
                        .setTitle("Impossible")
                        .setMessage(
                            "Durée trop longue : maximum ${MAX_CALIBRATION_DURATION_SEC}s\n" +
                                    "Réduis la durée pour l’étalonnage."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                    return@setOnClickListener
                }

                NetworkHelper.sendManualCommand(this, esp32Ip, pumpNum, duration)
            }

            calcButtons[i].setOnClickListener {
                val duration = pumpDurations[i].text.toString().toFloatOrNull()
                val volume = pumpVolumes[i].text.toString().toFloatOrNull()

                if (duration == null || volume == null || duration <= 0 || volume <= 0) {
                    Toast.makeText(this, "Valeurs invalides", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val flow = volume / duration
                val flowKey = "esp_${espId}_pump${pumpNum}_flow"
                pumpResults[i].text = "Débit : ${formatFlow(flow)} mL/s"

                prefs.edit()
                    .putFloat(flowKey, flow)
                    .apply()

                // ✅ INFO : les programmations doivent être renvoyées pour appliquer le nouveau débit
                AlertDialog.Builder(this)
                    .setTitle("Info")
                    .setMessage(
                        "Calibration terminée.\n" +
                                "Veuillez renvoyer la programmation pour appliquer les nouveaux débits."
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }

            tankResetButtons[i].setOnClickListener {
                confirmTankReset(
                    prefs,
                    espId,
                    pumpNum,
                    i,
                    tankLevels,
                    tankCapacities,
                    tankAlerts
                )
            }
        }

        // ----------------------------------------------------------
        // 💾 SAUVEGARDE NOMS
        // ----------------------------------------------------------
        findViewById<Button>(R.id.btn_save_names).setOnClickListener {
            val edit = prefs.edit()
            for (i in 0..3) {
                val pumpNum = i + 1
                val name = pumpNames[i].text.toString().trim()
                if (name.isNotEmpty()) {
                    edit.putString("esp_${espId}_pump${pumpNum}_name", name)
                    pumpTitles[i].text = name
                }
            }
            edit.apply()
            Toast.makeText(this, "Noms sauvegardés", Toast.LENGTH_SHORT).show()
        }
    }

    // ----------------------------------------------------------
    // 🔔 CONFIRMATION RÉSERVOIR RECHARGÉ
    // ----------------------------------------------------------
    private fun confirmTankReset(
        prefs: SharedPreferences,
        espId: Long,
        pumpNum: Int,
        index: Int,
        levels: Array<TextView>,
        capacities: Array<EditText>,
        alerts: Array<EditText>
    ) {
        AlertDialog.Builder(this)
            .setTitle("Confirmation")
            .setMessage(
                "Confirmer que le réservoir de la pompe $pumpNum a été entièrement rechargé ?"
            )
            .setPositiveButton("Oui") { _, _ ->

                val capacity = capacities[index].text.toString().toIntOrNull()
                if (capacity == null || capacity <= 0) {
                    Toast.makeText(this, "Volume invalide", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                prefs.edit()
                    .putInt("esp_${espId}_pump${pumpNum}_tank_capacity", capacity)
                    .putFloat("esp_${espId}_pump${pumpNum}_tank_remaining", capacity.toFloat())
                    // ✅ RÉARMEMENT CORRECT DES ALERTES
                    .putBoolean("esp_${espId}_pump${pumpNum}_low_alert_sent", false)
                    .putBoolean("esp_${espId}_pump${pumpNum}_empty_alert_sent", false)
                    .apply()

                loadTankUI(
                    prefs,
                    espId,
                    pumpNum,
                    levels[index],
                    capacities[index],
                    alerts[index]
                )

                Toast.makeText(this, "Réservoir rechargé", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ----------------------------------------------------------
    // 🔋 UI RÉSERVOIR
    // ----------------------------------------------------------
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

        // ✅ CLÉ OFFICIELLE UTILISÉE PARTOUT
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

        alertEt.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                alertEt.text.toString().toIntOrNull()?.let {
                    prefs.edit()
                        .putInt(
                            "esp_${espId}_pump${pumpNum}_low_threshold",
                            it
                        )
                        .apply()
                }
            }
        }
    }
}
