package com.esp32pumpwifi.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar

class RefillTanksActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_refill_tanks)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.tanks_title)

        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)

        val activeModule = Esp32Manager.getAll(this).firstOrNull { it.isActive }

        if (activeModule == null) {
            Toast.makeText(this, "Aucun ESP32 sélectionné", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val espId = activeModule.id

        val pumpTitles = arrayOf(
            findViewById<TextView>(R.id.tv_refill_title1),
            findViewById(R.id.tv_refill_title2),
            findViewById(R.id.tv_refill_title3),
            findViewById(R.id.tv_refill_title4)
        )

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

        val tankAlertSaveButtons = arrayOf(
            findViewById<View>(R.id.btn_save_tank_alert1),
            findViewById(R.id.btn_save_tank_alert2),
            findViewById(R.id.btn_save_tank_alert3),
            findViewById(R.id.btn_save_tank_alert4)
        )

        for (i in 0..3) {
            val pumpNum = i + 1
            val name = getPumpDisplayName(prefs, espId, pumpNum)
            pumpTitles[i].text = name

            loadTankUI(
                prefs,
                espId,
                pumpNum,
                tankLevels[i],
                tankCapacities[i],
                tankAlerts[i]
            )

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

            tankAlertSaveButtons[i].setOnClickListener {
                val value = tankAlerts[i].text.toString().toIntOrNull()
                if (value != null) {
                    prefs.edit()
                        .putInt("esp_${espId}_pump${pumpNum}_low_threshold", value)
                        .apply()
                    showThresholdSaved()
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun showThresholdSaved() {
        Snackbar.make(
            findViewById(R.id.root_refill),
            getString(R.string.threshold_saved),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun confirmTankReset(
        prefs: SharedPreferences,
        espId: Long,
        pumpNum: Int,
        index: Int,
        levels: Array<TextView>,
        capacities: Array<EditText>,
        alerts: Array<EditText>
    ) {
        val pumpName = getPumpDisplayName(prefs, espId, pumpNum)
        AlertDialog.Builder(this)
            .setTitle("Confirmation")
            .setMessage(
                "Confirmer que le réservoir de ${pumpName} a été entièrement rechargé ?"
            )
            .setPositiveButton("Oui") { _, _ ->

                val capacity = capacities[index].text.toString().toIntOrNull()
                if (capacity == null || capacity <= 0) {
                    Toast.makeText(this, "Volume invalide", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // ✅ Option 1 (source unique):
                // 1) On enregistre la capacité (si l'utilisateur l'a modifiée)
                prefs.edit()
                    .putInt("esp_${espId}_pump${pumpNum}_tank_capacity", capacity)
                    .apply()

                // 2) Reset officiel (remaining + flags + last_processed_time)
                TankManager.resetTank(
                    context = this,
                    espId = espId,
                    pumpNum = pumpNum
                )

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
