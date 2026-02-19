package com.esp32pumpwifi.app

import android.content.Intent
import android.os.Bundle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var tvActiveModule: TextView
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvRtcTime: TextView
    private lateinit var tankSummaryContainer: LinearLayout
    private lateinit var dailySummaryContainer: View
    private lateinit var manualDoseButton: Button
    private lateinit var refillTanksButton: Button

    private var connectionJob: Job? = null

    private var uiRefreshJob: Job? = null
    private var hasShownNotFoundPopup = false
    private var hasShownOver30DaysPopup = false

    // ✅ popup gratifiant J+25..29 (une seule fois par session)
    private var hasShown25to29Popup = false

    private var lastRtcIp: String? = null
    private var rtcFetchJob: Job? = null
    // pour tenter un retour STA automatique sans appuyer sur "+"
    private var lastStaProbeMs = 0L

    private companion object {
        private const val AP_IP = "192.168.4.1"
        private const val DAY_MS = 86400000L

        private const val CONNECTION_RETRY_DELAY_MS = 400L

        private const val STA_PROBE_INTERVAL_MS = 15_000L
        private const val UI_REFRESH_MS = 15_000L

        private const val ID_ENDPOINT = "/id"

        // ⚠️ On ne change pas le reste du réseau (read_ms, etc.)
        private const val HTTP_TIMEOUT_MS = 2000

        // ✅ Respiration réseau : timeouts spécifiques au watcher connexion (/id et /time)
        private const val HTTP_CONNECT_TIMEOUT_MS = 2000
        private const val HTTP_READ_TIMEOUT_MS = 4000

        // Program line: "1HHMMDDDDDD" (12 digits)
        private const val LINE_LEN = 12
        private const val MIN_DURATION_MS = 50
        private const val MAX_DURATION_MS = 600_000
        private val LINE_12_DIGITS_REGEX = Regex("^\\d{12}$")

        private const val PREFS_NAME = "prefs"
    }

    // ================== FUTUR PLAN (Option A) ==================
    private data class FuturePlan(val count: Int, val ml: Float)
    private data class PlannedTotalToday(val count: Int, val ml: Float)
    private data class DailyDone(val count: Int, val ml: Float)

    /**
     * Calcule le "reste à faire aujourd'hui" (strictement futur) à partir de ProgramStoreSynced.
     */
    private fun computeFuturePlan(espId: Long, pumpNum: Int, flow: Float): FuturePlan {
        if (flow <= 0f) return FuturePlan(0, 0f)

        val encodedLines = ProgramStoreSynced.loadEncodedLines(this, espId, pumpNum)
        if (encodedLines.isEmpty()) return FuturePlan(0, 0f)

        val nowLocal =
            Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault())
                .toLocalTime()
        val nowMinutes = nowLocal.hour * 60 + nowLocal.minute

        var futureCount = 0
        var futureMl = 0f

        for (line in encodedLines) {
            val t = line.trim()
            if (t.length != LINE_LEN) continue
            if (t.firstOrNull() != '1') continue
            if (!LINE_12_DIGITS_REGEX.matches(t)) continue
            if (!isValidEnabledProgramLine(t)) continue

            val hh = t.substring(2, 4).toIntOrNull() ?: continue
            val mm = t.substring(4, 6).toIntOrNull() ?: continue
            val durationMs = t.substring(6, 12).toIntOrNull() ?: continue

            if (hh !in 0..23 || mm !in 0..59) continue
            if (durationMs !in MIN_DURATION_MS..MAX_DURATION_MS) continue

            val minutes = hh * 60 + mm

            // Strictement dans le futur (après maintenant)
            if (minutes <= nowMinutes) continue

            futureCount++
            futureMl += (durationMs / 1000f) * flow
        }

        return FuturePlan(futureCount, futureMl)
    }

    /**
     * Calcule le total planifié de la journée (00:00..24:00), sans filtre "now".
     * (Conservé pour compat, mais on n'utilise plus ce total pour l'affichage du "jour en cours")
     */
    private fun computePlannedTotalToday(espId: Long, pumpNum: Int, flow: Float): PlannedTotalToday {
        if (flow <= 0f) return PlannedTotalToday(0, 0f)

        val encodedLines = ProgramStoreSynced.loadEncodedLines(this, espId, pumpNum)
        if (encodedLines.isEmpty()) return PlannedTotalToday(0, 0f)

        var totalCount = 0
        var totalMl = 0f

        for (line in encodedLines) {
            val t = line.trim()
            if (t.length != LINE_LEN) continue
            if (t.firstOrNull() != '1') continue
            if (!LINE_12_DIGITS_REGEX.matches(t)) continue
            if (!isValidEnabledProgramLine(t)) continue

            val hh = t.substring(2, 4).toIntOrNull() ?: continue
            val mm = t.substring(4, 6).toIntOrNull() ?: continue
            val durationMs = t.substring(6, 12).toIntOrNull() ?: continue

            if (hh !in 0..23 || mm !in 0..59) continue
            if (durationMs !in MIN_DURATION_MS..MAX_DURATION_MS) continue

            totalCount++
            totalMl += (durationMs / 1000f) * flow
        }

        return PlannedTotalToday(totalCount, totalMl)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val scrollView = findViewById<ScrollView>(R.id.pump_scroll)
        tvActiveModule = findViewById(R.id.tv_active_module)
        tvConnectionStatus = findViewById(R.id.tv_connection_status)
        tvRtcTime = findViewById(R.id.tvRtcTime)
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

            val keyboardBottom =
                if (imeBottom == 0) max(systemBarsBottom, navBarsBottom) else max(
                    imeBottom,
                    systemBarsBottom
                )

            view.updatePadding(bottom = baseBottom + keyboardBottom + extraBottomPadding)
            insets
        }

        NotificationPermissionHelper.requestPermissionIfNeeded(this)

        CriticalAlarmScheduler.ensureScheduled(this)
        maybePromptForExactAlarms()

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
            val active = Esp32Manager.getActive(this)
            if (active == null) {
                Toast.makeText(this, "Pompe doseuse hors connexion", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                val check = testConnectionWithImmediateRetry(active)
                if (!isActive) return@launch

                if (!check.connected) {
                    updateConnectionUi(false)
                    setManualButtonsEnabled(false)

                    if (check.fallbackAttempted && !check.fallbackSucceeded) {
                        maybeShowEsp32NotFoundPopup()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Pompe doseuse hors connexion",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                } else {
                    updateConnectionUi(true)
                    setManualButtonsEnabled(true)
                }

                val initialProgram = fetchProgramFromEsp(active.ip)
                if (initialProgram == null) {
                    Toast.makeText(
                        this@MainActivity,
                        "Pompe doseuse hors connexion",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                startActivity(
                    Intent(this@MainActivity, ScheduleActivity::class.java).apply {
                        putExtra(ScheduleActivity.EXTRA_INITIAL_PROGRAM_576, initialProgram)
                        putExtra(ScheduleActivity.EXTRA_MODULE_ID, active.id.toString())
                    }
                )
            }
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

    private fun maybePromptForExactAlarms() {
        if (ExactAlarmPermissionHelper.canScheduleExactAlarms(this)) return

        AlertDialog.Builder(this)
            .setTitle("Autoriser alarmes exactes")
            .setMessage(
                "Pour garantir les alertes critiques en veille, autorisez les alarmes exactes dans les réglages Android."
            )
            .setPositiveButton("Autoriser") { _, _ ->
                ExactAlarmPermissionHelper.openExactAlarmSettings(this)
            }
            .setNegativeButton("Plus tard", null)
            .show()
    }

    override fun onResume() {
        super.onResume()

        tvRtcTime.text = "RTC : --:--"
        lastRtcIp = null
        rtcFetchJob?.cancel()
        rtcFetchJob = null

        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        val activeModule = Esp32Manager.getActive(this)

        // ✅ si aucun module : comme avant
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

        // ✅ Popups d'inactivité : base "par module" (sinon tu ne les verras plus)
        val moduleKey = "esp_${activeModule.id}_last_open_ms"
        val lastOpenForModule =
            prefs.getLong(moduleKey, 0L).takeIf { it != 0L }
                ?: prefs.getLong("last_app_open_ms", 0L) // fallback "ancienne version" si besoin

        if (lastOpenForModule != 0L) {
            val days = ((now - lastOpenForModule) / DAY_MS).toInt()

            if (days in 25..29 && !hasShown25to29Popup) {
                hasShown25to29Popup = true
                AlertDialog.Builder(this)
                    .setTitle("Actualisation effectuée")
                    .setMessage(
                        """
                        Suivi des réservoirs actualisé.
                        """.trimIndent()
                    )
                    .setPositiveButton("OK", null)
                    .show()
            } else if (days >= 30 && !hasShownOver30DaysPopup) {
                maybeShowOver30DaysPopup()
            }
        }

        // ✅ IMPORTANT : update APRÈS le calcul/popup
        prefs.edit()
            .putLong("last_app_open_ms", now) // conservé (compat / autres logiques)
            .putLong(moduleKey, now)          // clé par module (source pour inactivité par module)
            .apply()

        TankScheduleHelper.recalculateFromLastTime(this, activeModule.id)

        updateTankSummary(activeModule)
        updateDailySummary(activeModule)

        updateConnectionUi(null)
        setManualButtonsEnabled(false)
        startConnectionWatcher(activeModule)

        uiRefreshJob?.cancel()
        uiRefreshJob =
            lifecycleScope.launch {
                while (isActive) {
                    val active = Esp32Manager.getActive(this@MainActivity)
                    active?.let {
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
        rtcFetchJob?.cancel()
        rtcFetchJob = null
        uiRefreshJob?.cancel()
        uiRefreshJob = null
    }

    // ================== WATCHER (one-shot) ==================
    private fun startConnectionWatcher(module: EspModule) {
        connectionJob?.cancel()
        connectionJob =
            lifecycleScope.launch {
                val result = testConnectionWithImmediateRetry(module)
                if (!isActive) return@launch

                if (result.connected) {
                    updateConnectionUi(true)
                    setManualButtonsEnabled(true)
                    hasShownNotFoundPopup = false
                    requestRtcTimeIfNeeded(module)
                } else {
                    updateConnectionUi(false)
                    setManualButtonsEnabled(false)

                    if (result.fallbackAttempted && !result.fallbackSucceeded) {
                        maybeShowEsp32NotFoundPopup()
                    }
                }
            }
    }

    private fun stopConnectionWatcher() {
        connectionJob?.cancel()
        connectionJob = null
        lastStaProbeMs = 0L
    }

    private suspend fun testConnectionWithImmediateRetry(module: EspModule): ConnectionCheckResult {
        val attempt1 = testConnectionWithFallbackAndStaReturn(module)
        if (attempt1.connected) return attempt1

        delay(CONNECTION_RETRY_DELAY_MS)
        val attempt2 = testConnectionWithFallbackAndStaReturn(module)
        if (attempt2.connected) return attempt2

        delay(CONNECTION_RETRY_DELAY_MS)
        return testConnectionWithFallbackAndStaReturn(module)
    }

    private data class ConnectionCheckResult(
        val connected: Boolean,
        val fallbackAttempted: Boolean,
        val fallbackSucceeded: Boolean
    )

    private suspend fun testConnectionWithFallbackAndStaReturn(module: EspModule): ConnectionCheckResult =
        withContext(Dispatchers.IO) {

            val primaryName = fetchPumpNameForIp(module.ip)
            if (primaryName == module.internalName) {

                if (module.ip != AP_IP) {
                    saveLastStaIp(module.id, module.ip)
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastStaProbeMs >= STA_PROBE_INTERVAL_MS) {
                        lastStaProbeMs = now

                        val lastSta = loadLastStaIp(module.id)
                        if (!lastSta.isNullOrBlank() && lastSta != AP_IP) {
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

            val fallbackName = fetchPumpNameForIp(AP_IP)
            if (fallbackName == module.internalName) {
                if (module.ip != AP_IP) {
                    module.ip = AP_IP
                    Esp32Manager.update(this@MainActivity, module)
                }
                return@withContext ConnectionCheckResult(true, true, true)
            }

            ConnectionCheckResult(false, true, false)
        }

    private fun fetchPumpNameForIp(ip: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("http://$ip$ID_ENDPOINT")
            conn =
                (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = HTTP_CONNECT_TIMEOUT_MS
                    readTimeout = HTTP_READ_TIMEOUT_MS
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

    private fun requestRtcTimeIfNeeded(module: EspModule) {
        val ip = module.ip
        if (ip.isBlank() || ip == lastRtcIp) return
        lastRtcIp = ip
        rtcFetchJob?.cancel()
        rtcFetchJob =
            lifecycleScope.launch {
                val time = fetchRtcTimeForIp(ip) ?: "--:--"
                tvRtcTime.text = "RTC : $time"
            }
    }

    private suspend fun fetchRtcTimeForIp(ip: String): String? =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            return@withContext try {
                val url = URL("http://$ip/time")
                conn =
                    (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = HTTP_CONNECT_TIMEOUT_MS
                        readTimeout = HTTP_READ_TIMEOUT_MS
                        useCaches = false
                        setRequestProperty("Connection", "close")
                    }
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                extractRtcTime(response)
            } catch (_: Exception) {
                null
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) {
                }
            }
        }

    private fun extractRtcTime(response: String): String? {
        val match =
            Regex("""\b([01]\d|2[0-3]):[0-5]\d(?::[0-5]\d)?\b""")
                .find(response)
        return match?.value
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
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString("esp_${moduleId}_last_sta_ip", ip)
            .apply()
    }

    private fun loadLastStaIp(moduleId: Long): String? =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString("esp_${moduleId}_last_sta_ip", null)

    // ================== UI ==================
    private fun maybeShowEsp32NotFoundPopup() {
        if (hasShownNotFoundPopup) return
        hasShownNotFoundPopup = true

        AlertDialog.Builder(this)
            .setTitle("Pompe doseuse non trouvée")
            .setMessage(
                "La pompe n’est pas joignable.\n\n" +
                        "1️⃣ Vérifie que ta box Internet fonctionne correctement.\n\n" +
                        "2️⃣ Si la pompe est en mode point d’accès, connecte-toi au Wi-Fi pompe-XXXX, puis appuie sur Rafraîchir.\n\n" +
                        "3️⃣ Sinon, reconnecte ton téléphone au Wi-Fi de ta box, puis appuie sur Rafraîchir."
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

    private fun maybeShowOver30DaysPopup() {
        if (hasShownOver30DaysPopup) return
        hasShownOver30DaysPopup = true

        AlertDialog.Builder(this)
            .setTitle("Application non ouverte longtemps")
            .setMessage(
                "Attention : Suite à l’inactivité sur ce module pendant plus de 30 jours,\n" +
                        "veuillez refaire les niveaux des réservoirs.\n"
            )
            .setPositiveButton("OK", null)
            .show()
    }

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
        lifecycleScope.launch(Dispatchers.IO) {
            val range = DailyDoseStore.todayRange()

            DailyDoseStore.buildAutoDoseEventsForDay(
                context = this@MainActivity,
                moduleId = activeModule.id,
                dayStartMs = range.dayStartMs,
                dayEndMs = range.dayEndMs
            )

            val doneByPump = (1..4).associateWith { pumpNum ->
                DailyDone(
                    count = DailyDoseStore.countForDay(
                        this@MainActivity,
                        activeModule.id,
                        pumpNum,
                        range.dayStartMs
                    ),
                    ml = DailyDoseStore.sumMlForDay(
                        this@MainActivity,
                        activeModule.id,
                        pumpNum,
                        range.dayStartMs
                    )
                )
            }

            withContext(Dispatchers.Main) {
                for (pumpNum in 1..4) {
                    val done = doneByPump[pumpNum] ?: DailyDone(0, 0f)
                    updateOneDaily(activeModule.id, pumpNum, done)
                }
            }
        }
    }

    private fun formatMl(value: Float): String =
        String.format(Locale.FRANCE, "%.1f", value)

    private fun isValidEnabledProgramLine(t: String): Boolean {
        if (t.length != LINE_LEN) return false
        if (!t.all(Char::isDigit)) return false
        if (t.all { it == '0' }) return false
        return t.firstOrNull() == '1'
    }

    private suspend fun fetchProgramFromEsp(ip: String): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        return@withContext try {
            conn = URL("http://$ip/read_ms").openConnection() as HttpURLConnection
            conn?.requestMethod = "GET"
            conn?.connectTimeout = HTTP_TIMEOUT_MS
            conn?.readTimeout = HTTP_TIMEOUT_MS
            conn?.useCaches = false
            conn?.setRequestProperty("Connection", "close")

            if (conn?.responseCode != HttpURLConnection.HTTP_OK) return@withContext null

            val lines = conn?.inputStream
                ?.bufferedReader()
                ?.use { reader ->
                    reader
                        .lineSequence()
                        .map { it.trim() }
                        .toList()
                }
                ?: return@withContext null

            if (lines.size != 48) return@withContext null
            if (!lines.all { it.matches(LINE_12_DIGITS_REGEX) }) return@withContext null

            val joined = lines.joinToString(separator = "")
            if (joined.length == 576) joined else null
        } catch (_: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun getNextDoseText(espId: Long, pumpNum: Int): String {
        val encodedLines = ProgramStoreSynced.loadEncodedLines(this, espId, pumpNum)
        if (encodedLines.isEmpty()) return "Aucune dose prévue"

        data class Entry(val minutes: Int, val hh: Int, val mm: Int, val durationMs: Int)

        val entries =
            encodedLines
                .asSequence()
                .mapNotNull { line ->
                    val t = line.trim()
                    if (t.length != LINE_LEN) return@mapNotNull null
                    if (t.firstOrNull() != '1') return@mapNotNull null
                    if (!LINE_12_DIGITS_REGEX.matches(t)) return@mapNotNull null
                    if (!isValidEnabledProgramLine(t)) return@mapNotNull null

                    val hh = t.substring(2, 4).toIntOrNull() ?: return@mapNotNull null
                    val mm = t.substring(4, 6).toIntOrNull() ?: return@mapNotNull null
                    val durationMs = t.substring(6, 12).toIntOrNull() ?: return@mapNotNull null
                    if (hh !in 0..23 || mm !in 0..59) return@mapNotNull null
                    if (durationMs !in MIN_DURATION_MS..MAX_DURATION_MS) return@mapNotNull null

                    val minutes = hh * 60 + mm
                    Entry(minutes = minutes, hh = hh, mm = mm, durationMs = durationMs)
                }
                .sortedBy { it.minutes }
                .toList()

        if (entries.isEmpty()) return "Aucune dose prévue"

        val nowLocal =
            Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault())
                .toLocalTime()
        val nowMinutes = nowLocal.hour * 60 + nowLocal.minute

        val next = entries.firstOrNull { it.minutes > nowMinutes } ?: entries.first()
        val formattedTime = String.format(Locale.getDefault(), "%02d:%02d", next.hh, next.mm)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val flow = prefs.getFloat("esp_${espId}_pump${pumpNum}_flow", 0f)
        val doseMl = if (flow > 0f) (next.durationMs / 1000f) * flow else 0f
        val doseText = formatMl(doseMl)

        return "Prochaine dose : $doseText\u202FmL à $formattedTime"
    }

    private fun updateOneDaily(espId: Long, pumpNum: Int, done: DailyDone) {
        val nameId = resources.getIdentifier("tv_daily_name_$pumpNum", "id", packageName)
        val progressId = resources.getIdentifier("pb_daily_$pumpNum", "id", packageName)
        val minId = resources.getIdentifier("tv_daily_min_$pumpNum", "id", packageName)
        val maxId = resources.getIdentifier("tv_daily_max_$pumpNum", "id", packageName)
        val doseId = resources.getIdentifier("tv_daily_dose_$pumpNum", "id", packageName)
        val insideId = resources.getIdentifier("tv_daily_inside_$pumpNum", "id", packageName)
        val nextDoseId = resources.getIdentifier("tv_daily_next_dose_$pumpNum", "id", packageName)

        if (nameId == 0 || progressId == 0 || minId == 0 || maxId == 0 || doseId == 0 || insideId == 0 || nextDoseId == 0) return

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val name =
            prefs.getString("esp_${espId}_pump${pumpNum}_name", "Pompe $pumpNum") ?: "Pompe $pumpNum"

        val doneDoseCountToday = done.count
        val doneMlToday = done.ml

        val flow = prefs.getFloat("esp_${espId}_pump${pumpNum}_flow", 0f)

        val future = computeFuturePlan(espId, pumpNum, flow)
        val plannedDoseCountToday = doneDoseCountToday + future.count
        val plannedMlToday = doneMlToday + future.ml

        val progressValue =
            if (plannedMlToday <= 0f || doneMlToday <= 0f) 0 else (doneMlToday / plannedMlToday * 100f).roundToInt()
        val doseText = "Dose : $doneDoseCountToday/$plannedDoseCountToday"

        val insideText =
            if (plannedMlToday <= 0f || doneMlToday <= 0f) "${formatMl(0f)} ml"
            else "${formatMl(doneMlToday)} ml"

        findViewById<TextView>(nameId).text = name

        val progressBar = findViewById<ProgressBar>(progressId)
        val insideLabel = findViewById<TextView>(insideId)

        progressBar.apply {
            max = 100
            progress = progressValue.coerceIn(0, 100)
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

            val targetX =
                when {
                    plannedMlToday <= 0f -> centeredX
                    progressValue == 0 -> centeredX
                    else -> {
                        val x = progressBar.paddingLeft + (barWidth * (progressValue / 100f))
                        x - labelWidth / 2f
                    }
                }

            insideLabel.translationX = (targetX.coerceIn(minX, maxX)) - insideLabel.left
        }
    }
}
