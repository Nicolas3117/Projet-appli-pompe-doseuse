package com.esp32pumpwifi.app

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlin.math.roundToInt

class ManualDoseActivity : AppCompatActivity() {

    private var pumpNumber: Int = 1

    companion object {
        const val MAX_PUMP_DURATION_SEC = 600
        const val MIN_PUMP_DURATION_MS = 50
        const val MAX_PUMP_DURATION_MS = 600_000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manual_dose)

        pumpNumber = intent.getIntExtra("pump_number", 1)

        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)

        val activeModule = Esp32Manager.getActive(this)
        if (activeModule == null) {
            Toast.makeText(this, "Aucun ESP32 sélectionné", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val espIp = activeModule.ip
        val espId = activeModule.id

        // 🔑 CLÉS UNIFIÉES
        val pumpNameKey = "esp_${espId}_pump${pumpNumber}_name"
        val pumpFlowKey = "esp_${espId}_pump${pumpNumber}_flow"
        val thresholdKey = "esp_${espId}_pump${pumpNumber}_alert_threshold"

        val pumpName =
            prefs.getString(pumpNameKey, "Pompe $pumpNumber") ?: "Pompe $pumpNumber"

        val tvPump = findViewById<TextView>(R.id.tv_pump)
        val editVolume = findViewById<EditText>(R.id.edit_volume)
        val btnStart = findViewById<Button>(R.id.btn_start_dose)

        tvPump.text = pumpName

        btnStart.setOnClickListener {

            // ✅ BLOQUAGE : dose programmée en cours sur cette pompe (multi-modules OK)
            if (isScheduledDoseInProgressNow(this, espId, pumpNumber)) {
                Toast.makeText(
                    this,
                    "Impossible : $pumpName est déjà en cours (dose programmée).",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            val volumeMl = editVolume.text.toString().toFloatOrNull()
            if (volumeMl == null || volumeMl <= 0f) {
                Toast.makeText(this, "Volume invalide pour $pumpName", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val flow = prefs.getFloat(pumpFlowKey, 0f)
            if (flow <= 0f) {
                Toast.makeText(this, "$pumpName n’est pas encore étalonnée", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val durationMs = (volumeMl / flow * 1000f).roundToInt()

            // ✅ Message plus clair si la durée tombe à 0s (volume trop faible vs débit)
            if (durationMs < MIN_PUMP_DURATION_MS) {
                val minMl = flow * (MIN_PUMP_DURATION_MS / 1000f)
                AlertDialog.Builder(this)
                    .setTitle("Impossible")
                    .setMessage(
                        "Quantité trop faible : minimum ${"%.2f".format(minMl)} mL (${MIN_PUMP_DURATION_MS} ms)\n" +
                                "Débit actuel : ${"%.1f".format(flow)} mL/s"
                    )
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnClickListener
            }

            // ✅ Blocage manuel > 600s (comme demandé)
            if (durationMs > MAX_PUMP_DURATION_MS) {
                AlertDialog.Builder(this)
                    .setTitle("Impossible")
                    .setMessage("Durée trop longue : maximum ${MAX_PUMP_DURATION_SEC}s")
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnClickListener
            }

            lifecycleScope.launch {

                if (!verifyEsp32Connection(activeModule)) {
                    Toast.makeText(
                        this@ManualDoseActivity,
                        "${activeModule.displayName} non connecté.\nVérifiez le Wi-Fi.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                // 🚿 ENVOI COMMANDE
                NetworkHelper.sendManualCommandMs(
                    context = this@ManualDoseActivity,
                    ip = espIp,
                    pump = pumpNumber,
                    durationMs = durationMs
                )

                // 🔋 DÉCRÉMENTATION (EXCEPTION NORMALE POUR LE MANUEL)
                TankManager.decrement(
                    context = this@ManualDoseActivity,
                    espId = espId,
                    pumpNum = pumpNumber,
                    volumeMl = volumeMl
                )

                // =====================================================
                // 🔔 ALERTES (Telegram + notif Android)
                // =====================================================
                val level =
                    TankManager.getTankLevel(
                        context = this@ManualDoseActivity,
                        espId = espId,
                        pumpNum = pumpNumber
                    )

                val thresholdPercent =
                    prefs.getInt(thresholdKey, 20)

                val lowAlertKey =
                    "esp_${espId}_pump${pumpNumber}_low_alert_sent"

                val emptyAlertKey =
                    "esp_${espId}_pump${pumpNumber}_empty_alert_sent"

                val lowAlertSent =
                    prefs.getBoolean(lowAlertKey, false)

                val emptyAlertSent =
                    prefs.getBoolean(emptyAlertKey, false)

                val (newLow, newEmpty) =
                    TankAlertManager.checkAndNotify(
                        context = this@ManualDoseActivity,
                        espId = espId,
                        pumpNum = pumpNumber,
                        remainingMl = level.remainingMl.toFloat(),
                        capacityMl = level.capacityMl,
                        thresholdPercent = thresholdPercent,
                        lowAlertSent = lowAlertSent,
                        emptyAlertSent = emptyAlertSent
                    )

                prefs.edit()
                    .putBoolean(lowAlertKey, newLow)
                    .putBoolean(emptyAlertKey, newEmpty)
                    .apply()
            }
        }
    }

    /**
     * ✅ Retourne true si une dose programmée est censée être EN COURS maintenant
     * sur cette pompe.
     *
     * Hypothèse actuelle (ton code) :
     * - une ligne active = tous les jours (pas de jours semaine ici)
     */
    private fun isScheduledDoseInProgressNow(context: Context, espId: Long, pumpNum: Int): Boolean {
        val now = System.currentTimeMillis()

        // Début de la journée (aujourd'hui)
        val dayStart = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // ✅ Multi-modules OK : lecture par espId explicite
        val lines = ProgramStore.loadEncodedLines(context, espId, pumpNum)
        for (line in lines) {
            if (line == "000000000000") continue
            if (line.length < 12) continue
            if (line[0] != '1') continue

            val hh = line.substring(2, 4).toIntOrNull() ?: continue
            val mm = line.substring(4, 6).toIntOrNull() ?: continue
            val durationMs = line.substring(6, 12).toIntOrNull() ?: continue
            if (durationMs <= 0) continue

            val start = (dayStart.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, hh)
                set(Calendar.MINUTE, mm)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val end = start + durationMs

            // En cours si now ∈ [start, end[
            if (now >= start && now < end) return true
        }

        return false
    }

    // ------------------------------------------------------------------
    private suspend fun verifyEsp32Connection(module: EspModule): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("http://${module.ip}/id")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 800
                conn.readTimeout = 800
                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                response.startsWith("POMPE_NAME=") &&
                        response.removePrefix("POMPE_NAME=").trim() == module.internalName
            } catch (_: Exception) {
                false
            }
        }
}
