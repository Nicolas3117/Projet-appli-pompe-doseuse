package com.esp32pumpwifi.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var tvActiveModule: TextView
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tankSummaryContainer: LinearLayout

    private var connectionJob: Job? = null

    // ✅ AJOUT : rafraîchissement UI périodique
    private var uiRefreshJob: Job? = null

    private var hasShownNotFoundPopup = false

    private val pumpButtons by lazy {
        listOf(
            findViewById<Button>(R.id.btn_pump1),
            findViewById<Button>(R.id.btn_pump2),
            findViewById<Button>(R.id.btn_pump3),
            findViewById<Button>(R.id.btn_pump4)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvActiveModule = findViewById(R.id.tv_active_module)
        tvConnectionStatus = findViewById(R.id.tv_connection_status)
        tankSummaryContainer = findViewById(R.id.layout_tank_summary)

        // 🔔 Permission notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }

        // 🔁 Worker global (background / app fermée)
        val periodicWork =
            PeriodicWorkRequestBuilder<TankRecalcWorker>(
                15, TimeUnit.MINUTES
            ).addTag("tank_recalc").build()

        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork(
                "tank_recalc",
                ExistingPeriodicWorkPolicy.KEEP,
                periodicWork
            )

        // Navigation
        findViewById<Button>(R.id.btn_materials).setOnClickListener {
            startActivity(Intent(this, MaterielsActivity::class.java))
        }

        findViewById<Button>(R.id.btn_schedule).setOnClickListener {
            startActivity(Intent(this, ScheduleActivity::class.java))
        }

        findViewById<Button>(R.id.btn_calibration).setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }

        pumpButtons[0].setOnClickListener { openManualDose(1) }
        pumpButtons[1].setOnClickListener { openManualDose(2) }
        pumpButtons[2].setOnClickListener { openManualDose(3) }
        pumpButtons[3].setOnClickListener { openManualDose(4) }
    }

    override fun onResume() {
        super.onResume()

        val activeModule = Esp32Manager.getActive(this)

        if (activeModule == null) {
            tvActiveModule.text = "Sélectionné : aucun module"
            updateConnectionUi(null)
            setManualButtonsEnabled(false)
            tankSummaryContainer.visibility = View.GONE
            stopConnectionWatcher()
            return
        }

        tvActiveModule.text = "Sélectionné : ${activeModule.displayName}"
        updatePumpButtons(activeModule)

        // 1️⃣ Recalcul immédiat
        TankScheduleHelper.recalculateFromLastTime(
            this,
            activeModule.id
        )

        // 2️⃣ Affichage après recalcul
        updateTankSummary(activeModule)

        updateConnectionUi(null)
        setManualButtonsEnabled(false)
        startConnectionWatcher(activeModule)

        // -------------------------------------------------
        // ✅ AJOUT : rafraîchissement périodique pendant
        // que l’écran reste ouvert
        // -------------------------------------------------
        uiRefreshJob?.cancel()
        uiRefreshJob = lifecycleScope.launch {
            while (true) {
                delay(60_000) // ⏱ toutes les 60 secondes

                val module = Esp32Manager.getActive(this@MainActivity) ?: continue

                // recalcul réel (programmation)
                TankScheduleHelper.recalculateFromLastTime(
                    this@MainActivity,
                    module.id
                )

                // rafraîchissement UI
                updateTankSummary(module)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopConnectionWatcher()

        // ✅ arrêt propre du refresh UI
        uiRefreshJob?.cancel()
        uiRefreshJob = null
    }

    // ---------------------------------------------------------
    // 🔁 Surveillance connexion
    // ---------------------------------------------------------
    private fun startConnectionWatcher(module: EspModule) {
        if (connectionJob?.isActive == true) return

        connectionJob = lifecycleScope.launch {
            while (true) {
                val result = testConnectionWithFallback(module)
                updateConnectionUi(result.connected)
                setManualButtonsEnabled(result.connected)
                if (result.connected) {
                    hasShownNotFoundPopup = false
                }
                if (result.fallbackAttempted && !result.fallbackSucceeded) {
                    maybeShowEsp32NotFoundPopup()
                }
                delay(2000)
            }
        }
    }

    private fun stopConnectionWatcher() {
        connectionJob?.cancel()
        connectionJob = null
    }

    private suspend fun testConnectionWithFallback(
        module: EspModule
    ): ConnectionCheckResult =
        withContext(Dispatchers.IO) {
            val primaryConnected = testConnectionForIp(module.ip)
            if (primaryConnected) {
                return@withContext ConnectionCheckResult(true, false, false)
            }

            val fallbackIp = "192.168.4.1"
            if (module.ip == fallbackIp) {
                return@withContext ConnectionCheckResult(false, true, false)
            }
            val fallbackConnected = testConnectionForIp(fallbackIp)
            if (fallbackConnected) {
                if (module.ip != fallbackIp) {
                    module.ip = fallbackIp
                    Esp32Manager.update(this@MainActivity, module)
                }
                return@withContext ConnectionCheckResult(true, true, true)
            }

            ConnectionCheckResult(false, true, false)
        }

    private fun testConnectionForIp(ip: String): Boolean {
        return try {
            val url = URL("http://$ip/id")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 800
                readTimeout = 800
            }
            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            response.startsWith("POMPE_NAME=")
        } catch (_: Exception) {
            false
        }
    }

    private fun maybeShowEsp32NotFoundPopup() {
        if (hasShownNotFoundPopup) return
        hasShownNotFoundPopup = true

        AlertDialog.Builder(this)
            .setTitle("ESP32 non trouvé")
            .setMessage(
                "Vérifie que ton téléphone est connecté au bon Wi-Fi " +
                    "(box ou réseau pompe-XXXX)."
            )
            .setPositiveButton("Rafraîchir") { _, _ ->
                startActivity(
                    Intent(this, MaterielsActivity::class.java)
                        .putExtra(MaterielsActivity.EXTRA_AUTO_SCAN, true)
                )
            }
            .setNegativeButton("Annuler", null)
            .setNeutralButton("Paramètres Wi-Fi") { _, _ ->
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
            .show()
    }

    private data class ConnectionCheckResult(
        val connected: Boolean,
        val fallbackAttempted: Boolean,
        val fallbackSucceeded: Boolean
    )

    // ---------------------------------------------------------
    // UI connexion
    // ---------------------------------------------------------
    private fun updateConnectionUi(connected: Boolean?) {
        when (connected) {
            null -> {
                tvConnectionStatus.text = "État : vérification…"
                tvConnectionStatus.setTextColor(
                    getColor(android.R.color.darker_gray)
                )
            }
            true -> {
                tvConnectionStatus.text = "État : connecté ✔️"
                tvConnectionStatus.setTextColor(
                    getColor(android.R.color.holo_green_dark)
                )
            }
            false -> {
                tvConnectionStatus.text = "État : non connecté ❌"
                tvConnectionStatus.setTextColor(
                    getColor(android.R.color.holo_red_dark)
                )
            }
        }
    }

    private fun setManualButtonsEnabled(enabled: Boolean) {
        pumpButtons.forEach { it.isEnabled = enabled }
    }

    private fun openManualDose(pumpNumber: Int) {
        startActivity(
            Intent(this, ManualDoseActivity::class.java)
                .putExtra("pump_number", pumpNumber)
        )
    }

    // ---------------------------------------------------------
    // 🏷️ Boutons pompes
    // ---------------------------------------------------------
    private fun updatePumpButtons(activeModule: EspModule) {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)

        for (pumpNum in 1..4) {
            val btnId =
                resources.getIdentifier(
                    "btn_pump$pumpNum",
                    "id",
                    packageName
                )
            val btn = findViewById<Button>(btnId) ?: continue

            val name =
                prefs.getString(
                    "esp_${activeModule.id}_pump${pumpNum}_name",
                    "Pompe $pumpNum"
                ) ?: "Pompe $pumpNum"

            btn.text = "Dosage manuel – $name"
        }
    }

    // ---------------------------------------------------------
    // 🟢 Résumé réservoirs
    // ---------------------------------------------------------
    private fun updateTankSummary(activeModule: EspModule) {
        tankSummaryContainer.visibility = View.VISIBLE
        for (pumpNum in 1..4) {
            updateOneTank(activeModule.id, pumpNum)
        }
    }

    private fun updateOneTank(espId: Long, pumpNum: Int) {

        val nameId =
            resources.getIdentifier("tv_tank_name_$pumpNum", "id", packageName)
        val progressId =
            resources.getIdentifier("pb_tank_$pumpNum", "id", packageName)
        val daysId =
            resources.getIdentifier("tv_tank_days_$pumpNum", "id", packageName)
        val percentId =
            resources.getIdentifier("tv_tank_percent_$pumpNum", "id", packageName)
        val mlId =
            resources.getIdentifier("tv_tank_ml_$pumpNum", "id", packageName)

        if (nameId == 0 || progressId == 0 || daysId == 0) return

        TankUiHelper.update(
            context = this,
            espId = espId,
            pumpNum = pumpNum,
            tvName = findViewById(nameId),
            progress = findViewById(progressId),
            tvDays = findViewById(daysId),
            tvPercent = if (percentId != 0) findViewById(percentId) else null,
            tvMl = if (mlId != 0) findViewById(mlId) else null
        )
    }
}
