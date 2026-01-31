package com.esp32pumpwifi.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
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
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var tvActiveModule: TextView
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tankSummaryContainer: LinearLayout
    private lateinit var dailySummaryContainer: View
    private lateinit var manualDoseButton: Button
    private lateinit var refillTanksButton: Button

    private var connectionJob: Job? = null
    private val isCheckingConnection = AtomicBoolean(false)
    private var consecutiveFailures = 0

    private var uiRefreshJob: Job? = null
    private var hasShownNotFoundPopup = false

    // pour tenter un retour STA automatique sans appuyer sur "+"
    private var lastStaProbeMs = 0L
    private val STA_PROBE_INTERVAL_MS = 10_000L
    private val UI_REFRESH_MS = 15_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val scrollView = findViewById<ScrollView>(R.id.pump_scroll)
        tvActiveModule = findViewById(R.id.tv_active_module)
        tvConnectionStatus = findViewById(R.id.tv_connection_status)
        tankSummaryContainer = findViewById(R.id.layout_tank_summary)
        dailySummaryContainer = findViewById(R.id.layout_daily_summary)
        manualDoseButton = findViewById(R.id.btn_manual_dose)
        refillTanksButton = findViewById(R.id.btn_refill_tanks)

        val baseBottom = scrollView.paddingBottom
        val extraBottomPadding = resources.getDimensionPixelSize(R.dimen.pump_control_bottom_extra)
        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val systemBarsBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val navBarsBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val keyboardBottom = if (imeBottom == 0) {
                max(systemBarsBottom, navBarsBottom)
            } else {
                max(imeBottom, systemBarsBottom)
            }
            val target = baseBottom + keyboardBottom + extraBottomPadding
            view.updatePadding(bottom = target)
            insets
        }

        NotificationPermissionHelper.requestPermissionIfNeeded(this)

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

        findViewById<Button>(R.id.btn_materials).setOnClickListener {
            startActivity(Intent(this, MaterielsActivity::class.java))
        }

        findViewById<Button>(R.id.btn_planning).setOnClickListener {
            val selectedEspIds =
                Esp32Manager.getAll(this)
                    .filter { it.isActive }
                    .map { it.id }
                    .toLongArray()

            if (selectedEspIds.isEmpty()) {
                Toast.makeText(this, "Sélectionnez au moins un module", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            startActivity(
                Intent(this, PlanningActivity::class.java).apply {
                    putExtra("esp_ids", selectedEspIds)
                }
            )
        }

        findViewById<Button>(R.id.btn_schedule).setOnClickListener {
            startActivity(Intent(this, ScheduleActivity::class.java))
        }

        findViewById<Button>(R.id.btn_calibration).setOnClickListener {
            startActivity(Intent(this, CalibrationTabsActivity::class.java))
        }

        refillTanksButton.setOnClickListener {
            startActivity(Intent(this, TanksTabsActivity::class.java))
        }

        manualDoseButton.setOnClickListener {
            startActivity(Intent(this, ManualDoseTabsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()

        val activeModule = Esp32Manager.getActive(this)

        if (activeModule == null) {
            tvActiveModule.text = "Sélectionné : aucun module"
            updateConnectionUi(null)
            setManualButtonsEnabled(false)
            tankSummaryContainer.visibility = View.GONE
            dailySummaryContainer.visibility = View.GONE
            stopConnectionWatcher()
            return
        }

        tvActiveModule.text = "Sélectionné : ${activeModule.displayName}"
        DailyProgramTrackingStore.resetIfNewDay(this)
        TankScheduleHelper.recalculateFromLastTime(this, activeModule.id)
        updateTankSummary(activeModule)
        updateDailySummary(activeModule)

        updateConnectionUi(null)
        setManualButtonsEnabled(false)
        startConnectionWatcher()

        uiRefreshJob?.cancel()
        uiRefreshJob = lifecycleScope.launch {
            while (true) {
                Esp32Manager.getActive(this@MainActivity)?.let {
                    TankScheduleHelper.recalculateFromLastTime(this@MainActivity, it.id)
                    updateTankSummary(it)
                    updateDailySummary(it)
                }
                delay(UI_REFRESH_MS)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopConnectionWatcher()
        uiRefreshJob?.cancel()
        uiRefreshJob = null
    }

    // ================== WATCHER (corrigé) ==================
    // IMPORTANT : ne capture plus un "module" figé.
    // On relit le module actif à chaque boucle => pas de faux statuts pour d'autres modules.
    private fun startConnectionWatcher() {
        if (connectionJob?.isActive == true) return

        connectionJob = lifecycleScope.launch {
            while (true) {

                val active = Esp32Manager.getActive(this@MainActivity)
                if (active == null) {
                    updateConnectionUi(false)
                    setManualButtonsEnabled(false)
                    delay(2000)
                    continue
                }

                if (!isCheckingConnection.compareAndSet(false, true)) {
                    delay(2000)
                    continue
                }

                try {
                    val result = testConnectionWithFallbackAndStaReturn(active)

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
        lastStaProbeMs = 0L
    }

    // ================== RÉSEAU ==================
    /**
     * Règle :
     * - Connecté = /id répond ET POMPE_NAME == module.internalName
     * - Fallback AP = test 192.168.4.1 avec identité
     * - Retour STA auto = si on est en AP, tester périodiquement le dernier IP STA connu
     */
    private suspend fun testConnectionWithFallbackAndStaReturn(module: EspModule): ConnectionCheckResult =
        withContext(Dispatchers.IO) {

            // 1) test sur IP actuelle (STA ou AP) avec identité stricte
            val primaryPumpName = fetchPumpNameForIp(module.ip)
            if (primaryPumpName == module.internalName) {

                // mémorise last STA IP si on est en STA (pas 192.168.4.1)
                if (module.ip != "192.168.4.1") {
                    saveLastStaIp(module.id, module.ip)
                } else {
                    // on est en AP : tenter un retour STA automatique (sans scan +)
                    val now = System.currentTimeMillis()
                    if (now - lastStaProbeMs >= STA_PROBE_INTERVAL_MS) {
                        lastStaProbeMs = now
                        val lastSta = loadLastStaIp(module.id)
                        if (!lastSta.isNullOrBlank() && lastSta != "192.168.4.1") {
                            val staName = fetchPumpNameForIp(lastSta)
                            if (staName == module.internalName) {
                                module.ip = lastSta
                                Esp32Manager.update(this@MainActivity, module)
                                saveLastStaIp(module.id, lastSta)
                                return@withContext ConnectionCheckResult(true, false, false)
                            }
                        }
                    }
                }

                return@withContext ConnectionCheckResult(true, false, false)
            }

            // 2) fallback AP
            val fallbackIp = "192.168.4.1"
            val fallbackPumpName = fetchPumpNameForIp(fallbackIp)

            if (fallbackPumpName == module.internalName) {
                if (module.ip != fallbackIp) {
                    module.ip = fallbackIp
                    Esp32Manager.update(this@MainActivity, module)
                }
                return@withContext ConnectionCheckResult(true, true, true)
            }

            // 3) rien trouvé pour CE module
            ConnectionCheckResult(false, true, false)
        }

    private fun fetchPumpNameForIp(ip: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("http://$ip/id")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 2000
                readTimeout = 2000
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
        val start = index + prefix.length
        val end = response.indexOf('\n', start).let { if (it == -1) response.length else it }
        return response.substring(start, end).trim().ifEmpty { null }
    }

    private fun saveLastStaIp(moduleId: Long, ip: String) {
        getSharedPreferences("prefs", MODE_PRIVATE)
            .edit()
            .putString("esp_${moduleId}_last_sta_ip", ip)
            .apply()
    }

    private fun loadLastStaIp(moduleId: Long): String? {
        return getSharedPreferences("prefs", MODE_PRIVATE)
            .getString("esp_${moduleId}_last_sta_ip", null)
    }

    // ================== UI ==================
    private fun maybeShowEsp32NotFoundPopup() {
        if (hasShownNotFoundPopup) return
        hasShownNotFoundPopup = true

        AlertDialog.Builder(this)
            .setTitle("Pompe doseuse non trouvée")
            .setMessage(
                "La pompe n’est pas joignable.\n\n" +
                        "Connecte-toi au Wi-Fi pompe-XXXX (mode AP) et appuie sur Rafraîchir, ou\n\n" +
                        "Reviens sur le Wi-Fi de ta box, puis appuie sur Rafraîchir."
            )
            .setPositiveButton("Rafraîchir") { _, _ ->
                startActivity(
                    Intent(this, MaterielsActivity::class.java)
                        .putExtra(MaterielsActivity.EXTRA_AUTO_SCAN, true)
                )
            }
            .setNeutralButton("Paramètre Wi-Fi") { _, _ ->
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private data class ConnectionCheckResult(
        val connected: Boolean,
        val fallbackAttempted: Boolean,
        val fallbackSucceeded: Boolean
    )

    // ✅ Couleurs OK
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
        manualDoseButton.isEnabled = enabled
        refillTanksButton.isEnabled = enabled
    }

    private fun updateTankSummary(activeModule: EspModule) {
        tankSummaryContainer.visibility = View.VISIBLE
        for (pumpNum in 1..4) updateOneTank(activeModule.id, pumpNum)
    }

    private fun updateOneTank(espId: Long, pumpNum: Int) {
        val nameId = resources.getIdentifier("tv_tank_name_$pumpNum", "id", packageName)
        val progressId = resources.getIdentifier("pb_tank_$pumpNum", "id", packageName)
        val daysId = resources.getIdentifier("tv_tank_days_$pumpNum", "id", packageName)
        val percentId = resources.getIdentifier("tv_tank_percent_$pumpNum", "id", packageName)
        val mlId = resources.getIdentifier("tv_tank_ml_$pumpNum", "id", packageName)

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

    private fun updateDailySummary(activeModule: EspModule) {
        dailySummaryContainer.visibility = View.VISIBLE
        for (pumpNum in 1..4) {
            updateOneDaily(activeModule.id, pumpNum)
        }
    }

    private fun getPlannedDoseCount(espId: Long, pumpNum: Int): Int {
        val encodedLines = ProgramStore.loadEncodedLines(this, espId, pumpNum)
        val activeLines = encodedLines.filter { line ->
            val trimmed = line.trim()
            // 12 chars digités requis par le format ESP32 (sinon on ignore).
            val isValidFormat = trimmed.length == 12 && trimmed.all(Char::isDigit)
            // Ignorer les placeholders (ligne vide/0) et ne garder que enabled=1.
            val isPlaceholder = trimmed.all { it == '0' }
            val isEnabled = trimmed.firstOrNull() == '1'
            isValidFormat && !isPlaceholder && isEnabled
        }
        return activeLines.size.coerceAtMost(12)
    }

    private fun getNextDoseText(espId: Long, pumpNum: Int): String {
        fun parseTimeToMinutesOrNull(time: String): Int? {
            val trimmed = time.trim()
            if (!trimmed.matches(Regex("""\d{2}:\d{2}"""))) return null
            val parts = trimmed.split(":")
            if (parts.size != 2) return null
            val hours = parts[0].toIntOrNull() ?: return null
            val minutes = parts[1].toIntOrNull() ?: return null
            if (hours !in 0..23) return null
            if (minutes !in 0..59) return null
            return hours * 60 + minutes
        }

        val json = getSharedPreferences("schedules", MODE_PRIVATE)
            .getString("esp_${espId}_pump$pumpNum", null)
            ?: return "Aucune dose prévue"

        val schedules = PumpScheduleJson.fromJson(json)
            .asSequence()
            .filter { it.enabled && it.pumpNumber == pumpNum }
            .mapNotNull { schedule ->
                parseTimeToMinutesOrNull(schedule.time)?.let { minutes -> minutes to schedule }
            }
            .sortedBy { it.first }
            .toList()

        if (schedules.isEmpty()) return "Aucune dose prévue"

        val now = Calendar.getInstance()
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        val (nextMinutes, nextSchedule) =
            schedules.firstOrNull { it.first > nowMinutes } ?: schedules.first()
        val nextHours = nextMinutes / 60
        val nextMins = nextMinutes % 60
        val formattedTime = String.format(Locale.getDefault(), "%02d:%02d", nextHours, nextMins)
        val doseMl = nextSchedule.quantityMl
        val doseText = formatMl(doseMl)
        return "Prochaine dose : $doseText mL à $formattedTime"
    }

    private fun formatMl(value: Float): String =
        String.format(Locale.FRANCE, "%.1f", value)

    private fun updateOneDaily(espId: Long, pumpNum: Int) {
        val nameId = resources.getIdentifier("tv_daily_name_$pumpNum", "id", packageName)
        val progressId = resources.getIdentifier("pb_daily_$pumpNum", "id", packageName)
        val minId = resources.getIdentifier("tv_daily_min_$pumpNum", "id", packageName)
        val maxId = resources.getIdentifier("tv_daily_max_$pumpNum", "id", packageName)
        val doseId = resources.getIdentifier("tv_daily_dose_$pumpNum", "id", packageName)
        val insideId = resources.getIdentifier("tv_daily_inside_$pumpNum", "id", packageName)
        val nextDoseId = resources.getIdentifier("tv_daily_next_dose_$pumpNum", "id", packageName)

        if (nameId == 0 || progressId == 0 || minId == 0 || maxId == 0 || doseId == 0 || insideId == 0 || nextDoseId == 0) return

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val name = prefs.getString(
            "esp_${espId}_pump${pumpNum}_name",
            "Pompe $pumpNum"
        ) ?: "Pompe $pumpNum"

        val plannedDoseCountToday = getPlannedDoseCount(espId, pumpNum)

        val flow = prefs.getFloat("esp_${espId}_pump${pumpNum}_flow", 0f)
        val plannedMlToday =
            if (flow > 0f && plannedDoseCountToday > 0)
                TankScheduleHelper.getDailyConsumption(this, espId, pumpNum)
            else
                0f

        val rawDoneDoseCountToday =
            DailyProgramTrackingStore.getDoneDoseCountToday(this, espId, pumpNum)
        val rawDoneMlToday = DailyProgramTrackingStore.getDoneMlToday(this, espId, pumpNum)

        val doneDoseCountToday: Int
        val doneMlToday: Float
        val progressValue: Int
        val doseText: String

        if (plannedDoseCountToday == 0 || plannedMlToday == 0f) {
            doneDoseCountToday = 0
            doneMlToday = 0f
            progressValue = 0
            doseText = "Dose : 0/0"
        } else {
            doneDoseCountToday = min(rawDoneDoseCountToday, plannedDoseCountToday)
            doneMlToday = min(rawDoneMlToday, plannedMlToday)
            progressValue = (doneMlToday / plannedMlToday * 100f).roundToInt()
            doseText = "Dose : $doneDoseCountToday/$plannedDoseCountToday"
        }

        val insideText = if (plannedMlToday == 0f || doneMlToday == 0f) {
            "${formatMl(0f)} ml"
        } else {
            "${formatMl(doneMlToday)} ml"
        }

        findViewById<TextView>(nameId).text = name
        val progressBar = findViewById<ProgressBar>(progressId)
        val insideLabel = findViewById<TextView>(insideId)
        progressBar.apply {
            max = 100
            progress = progressValue
        }
        findViewById<TextView>(minId).text = "0 ml"
        findViewById<TextView>(maxId).text = "${formatMl(plannedMlToday)} ml"
        findViewById<TextView>(doseId).text = doseText
        insideLabel.text = insideText
        findViewById<TextView>(nextDoseId).text = getNextDoseText(espId, pumpNum)
        progressBar.post {
            val barWidth = progressBar.width - progressBar.paddingLeft - progressBar.paddingRight
            if (barWidth <= 0) return@post
            val labelWidth = insideLabel.width
            val minX = progressBar.paddingLeft.toFloat()
            val maxX = (progressBar.width - progressBar.paddingRight - labelWidth).toFloat()
            val centeredX = progressBar.paddingLeft + (barWidth - labelWidth) / 2f
            val targetX = when {
                plannedMlToday == 0f -> centeredX
                progressValue == 0 -> centeredX
                else -> {
                    val x = progressBar.paddingLeft + (barWidth * (progressValue / 100f))
                    x - labelWidth / 2f
                }
            }
            val clampedX = targetX.coerceIn(minX, maxX)
            insideLabel.translationX = clampedX - insideLabel.left
        }
    }
}
