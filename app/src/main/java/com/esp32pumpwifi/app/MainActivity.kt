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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var tvActiveModule: TextView
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tankSummaryContainer: LinearLayout

    private var connectionJob: Job? = null
    private val isCheckingConnection = AtomicBoolean(false)
    private var consecutiveFailures = 0

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
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }

        // 🔁 Worker global (background / app fermée)
        val periodicWork =
            PeriodicWorkRequestBuilder<TankRecalcWorker>(15, TimeUnit.MINUTES)
                .addTag("tank_recalc")
                .build()

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

        // Recalcul + UI
        TankScheduleHelper.recalculateFromLastTime(this, activeModule.id)
        updateTankSummary(activeModule)

        updateConnectionUi(null)
        setManualButtonsEnabled(false)
        startConnectionWatcher(activeModule)

        // ✅ rafraîchissement UI périodique pendant que l’écran reste ouvert
        uiRefreshJob?.cancel()
        uiRefreshJob = lifecycleScope.launch {
            while (true) {
                delay(60_000)
                Esp32Manager.getActive(this@MainActivity)?.let {
                    TankScheduleHelper.recalculateFromLastTime(this@MainActivity, it.id)
                    updateTankSummary(it)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopConnectionWatcher()
        uiRefreshJob?.cancel()
        uiRefreshJob = null
    }

    // ---------------------------------------------------------
    // 🔁 Surveillance connexion (sécurisée multi-modules)
    // ---------------------------------------------------------
    private fun startConnectionWatcher(module: EspModule) {
        if (connectionJob?.isActive == true) return

        connectionJob = lifecycleScope.launch {
            while (true) {

                // ✅ IMPORTANT : n’update l’UI que si ce module est encore le module actif
                val active = Esp32Manager.getActive(this@MainActivity)
                if (active == null || active.id != module.id) {
                    updateConnectionUi(false)
                    setManualButtonsEnabled(false)
                    delay(2000)
                    continue
                }

                // ✅ anti-concurrence
                if (!isCheckingConnection.compareAndSet(false, true)) {
                    delay(2000)
                    continue
                }

                try {
                    val result = testConnectionWithFallback(module)

                    if (result.connected) {
                        consecutiveFailures = 0
                        updateConnectionUi(true)
                        setManualButtonsEnabled(true)
                        hasShownNotFoundPopup = false
                    } else {
                        consecutiveFailures++
                        if (consecutiveFailures >= 2) {
                            updateConnectionUi(false)
                            setManualButtonsEnabled(false)
                            if (result.fallbackAttempted && !result.fallbackSucceeded) {
                                maybeShowEsp32NotFoundPopup()
                            }
                        }
                    }
                } finally {
                    isCheckingConnection.set(false)
                }

                delay(2000)
            }
        }
    }

    private fun stopConnectionWatcher() {
        connectionJob?.cancel()
        connectionJob = null
        consecutiveFailures = 0
    }

    // ---------------------------------------------------------
    // 🌐 Réseau : STA IP + fallback AP sécurisé par identité
    // ---------------------------------------------------------
    private suspend fun testConnectionWithFallback(module: EspModule): ConnectionCheckResult =
        withContext(Dispatchers.IO) {
            if (testConnectionForIp(module.ip)) {
                return@withContext ConnectionCheckResult(true, false, false)
            }

            val fallbackIp = "192.168.4.1"
            val pumpName = fetchPumpNameForIp(fallbackIp)

            // ✅ N’assigner 192.168.4.1 QUE si le /id correspond au module
            if (pumpName == module.internalName) {
                if (module.ip != fallbackIp) {
                    module.ip = fallbackIp
                    Esp32Manager.update(this@MainActivity, module)
                }
                return@withContext ConnectionCheckResult(true, true, true)
            }

            ConnectionCheckResult(false, true, false)
        }

    private fun fetchPumpNameForIp(ip: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("http://$ip/id")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 800
                readTimeout = 800
                useCaches = false
                setRequestProperty("Connection", "close")
            }
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            extractPumpName(response)
        } catch (_: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun extractPumpName(response: String): String? {
        val prefix = "POMPE_NAME="
        val index = response.indexOf(prefix)
        if (index == -1) return null
        return response.substring(index + prefix.length).trim().ifEmpty { null }
    }

    private fun testConnectionForIp(ip: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("http://$ip/id")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 800
                readTimeout = 800
                useCaches = false
                setRequestProperty("Connection", "close")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
                .startsWith("POMPE_NAME=")
        } catch (_: Exception) {
            false
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    // ---------------------------------------------------------
    // UI
    // ---------------------------------------------------------
    private fun maybeShowEsp32NotFoundPopup() {
        if (hasShownNotFoundPopup) return
        hasShownNotFoundPopup = true

        AlertDialog.Builder(this)
            .setTitle("ESP32 non trouvé")
            .setMessage("Vérifie le Wi-Fi (box ou pompe-XXXX).")
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

    // ✅ Couleurs restaurées (gris / vert / rouge)
    private fun updateConnectionUi(connected: Boolean?) {
        when (connected) {
            null -> {
                tvConnectionStatus.text = "État : vérification…"
                tvConnectionStatus.setTextColor(getColor(android.R.color.darker_gray))
            }
            true -> {
                tvConnectionStatus.text = "État : connecté ✔️"
                tvConnectionStatus.setTextColor(getColor(android.R.color.holo_green_dark))
            }
            false -> {
                tvConnectionStatus.text = "État : non connecté ❌"
                tvConnectionStatus.setTextColor(getColor(android.R.color.holo_red_dark))
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
            val btn = findViewById<Button>(
                resources.getIdentifier("btn_pump$pumpNum", "id", packageName)
            ) ?: continue

            val name = prefs.getString(
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
        for (pumpNum in 1..4) updateOneTank(activeModule.id, pumpNum)
    }

    private fun updateOneTank(espId: Long, pumpNum: Int) {
        val nameId = resources.getIdentifier("tv_tank_name_$pumpNum", "id", packageName)
        val progressId = resources.getIdentifier("pb_tank_$pumpNum", "id", packageName)
        val daysId = resources.getIdentifier("tv_tank_days_$pumpNum", "id", packageName)

        if (nameId == 0 || progressId == 0 || daysId == 0) return

        TankUiHelper.update(
            context = this,
            espId = espId,
            pumpNum = pumpNum,
            tvName = findViewById(nameId),
            progress = findViewById(progressId),
            tvDays = findViewById(daysId),
            tvPercent = null,
            tvMl = null
        )
    }
}
