package com.esp32pumpwifi.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

class ScheduleActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var adapter: PumpPagerAdapter
    private lateinit var toolbar: MaterialToolbar

    private val pumpNames = mutableMapOf<Int, String>()
    private val tabsViewModel: ScheduleTabsViewModel by viewModels()

    // ✅ Empreinte de la programmation envoyée / chargée (vérité)
    private var lastProgramHash: String? = null

    // ✅ /read_ms : 48 lignes de 12 chiffres
    private val line12DigitsRegex = Regex("""\d{12}""")

    // ✅ Auto-check : 1 fois par ouverture d’activité
    private var didAutoCheckOnResume = false

    // ✅ Anti double-finish / double popup (back spam)
    private var exitInProgress = false
    private var isReadOnly = false
    private var isUnsynced = false
    private var unsyncedDialogShowing = false
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationContentDescription("← Retour")
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        findViewById<TextView>(R.id.tv_header_back)
            .setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val activeModule = Esp32Manager.getActive(this)
        if (activeModule == null) {
            Toast.makeText(this, "Veuillez sélectionner un module", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)

        findViewById<ImageButton>(R.id.btn_schedule_helper).setOnClickListener {
            val pumpNumber = viewPager.currentItem + 1
            val intent = Intent(this, ScheduleHelperActivity::class.java).apply {
                putExtra(ScheduleHelperActivity.EXTRA_PUMP_NUMBER, pumpNumber)
                putExtra(ScheduleHelperActivity.EXTRA_MODULE_ID, activeModule.id.toString())
            }
            startActivityForResult(intent, REQUEST_SCHEDULE_HELPER)
        }

        adapter = PumpPagerAdapter(this)
        viewPager.adapter = adapter

        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val moduleId = activeModule.id

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            val pumpNumber = position + 1
            val pumpName = prefs.getString(
                "esp_${moduleId}_pump${pumpNumber}_name",
                "Pompe $pumpNumber"
            ) ?: "Pompe $pumpNumber"

            val (shortText, fullText) = formatActiveTotalShort(0)
            tab.text = buildTabText(pumpName, shortText)
            tab.contentDescription = buildTabContentDescription(pumpName, fullText)
            pumpNames[pumpNumber] = pumpName
        }.attach()

        tabsViewModel.activeTotals.observe(this) { totals ->
            totals.forEach { (pumpNumber, totalTenth) ->
                updateTabTotal(pumpNumber, totalTenth)
            }
        }

        // ✅ IMPORTANT : ne pas initialiser lastProgramHash sur le brouillon (ProgramStore)
        // La vérité arrive via /read_ms (à l’ouverture) ou /program_ms OK (après envoi).
        lastProgramHash = null

        // ✅ Sortie : toujours check final
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (exitInProgress) return

                    if (isUnsynced) {
                        showUnsyncedDialog()
                        return
                    }

                    val currentHash = ProgramStore.buildMessageMs(this@ScheduleActivity)
                    val locallyModified = lastProgramHash != null && lastProgramHash != currentHash

                    if (!locallyModified) {
                        finalCheckOnExitThenFinish()
                        return
                    }

                    AlertDialog.Builder(this@ScheduleActivity)
                        .setTitle("Programmation modifiée")
                        .setMessage(
                            "La programmation a été modifiée.\n" +
                                    "Pour garantir la cohérence entre l’application et la pompe, " +
                                    "elle sera envoyée automatiquement à la fermeture de cette page."
                        )
                        .setPositiveButton("Envoyer et quitter") { dialog, _ ->
                            dialog.dismiss()
                            exitInProgress = true

                            val active = Esp32Manager.getActive(this@ScheduleActivity)
                            if (active == null) {
                                Toast.makeText(
                                    this@ScheduleActivity,
                                    "Pompe non connectée — impossible d’envoyer",
                                    Toast.LENGTH_LONG
                                ).show()
                                exitInProgress = false
                                return@setPositiveButton
                            }

                            lifecycleScope.launch {
                                val ok = verifyEsp32Connection(active)
                                if (!ok) {
                                    setUnsyncedState(true, "Synchronisation impossible")
                                    showUnsyncedDialog()
                                    exitInProgress = false
                                    return@launch
                                }

                                Toast.makeText(
                                    this@ScheduleActivity,
                                    "Envoi…",
                                    Toast.LENGTH_SHORT
                                ).show()

                                val sent = sendSchedulesToESP32(active)
                                if (sent) {
                                    finish()
                                } else {
                                    setUnsyncedState(true, "Synchronisation impossible")
                                    showUnsyncedDialog()
                                    exitInProgress = false
                                }
                            }
                        }
                        .setNegativeButton("Rester") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .create()
                        .also { dlg ->
                            if (isFinishing || isDestroyed) return
                            dlg.show()
                        }
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        if (didAutoCheckOnResume) return
        didAutoCheckOnResume = true
        autoCheckProgramOnOpen()
    }

    override fun onPause() {
        super.onPause()
        didAutoCheckOnResume = false
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SCHEDULE_HELPER || resultCode != RESULT_OK || data == null) return
        handleScheduleHelperResult(data)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_schedule, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        val sendItem = menu?.findItem(R.id.action_send)
        if (isReadOnly || isUnsynced) {
            sendItem?.isEnabled = false
            sendItem?.isVisible = false
        } else {
            sendItem?.isEnabled = true
            sendItem?.isVisible = true
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_send -> {
                verifyIpThenSend()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun verifyIpThenSend() {
        if (isReadOnly || isUnsynced) {
            Toast.makeText(
                this,
                "Synchronisation impossible — envoi désactivé",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val active = Esp32Manager.getActive(this)
        if (active == null) {
            Toast.makeText(this, "Aucun module sélectionné", Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch {
            val ok = verifyEsp32Connection(active)
            if (!ok) {
                Toast.makeText(
                    this@ScheduleActivity,
                    "${active.displayName} non connecté.\nVérifiez le Wi-Fi ou le mode AP.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            Toast.makeText(
                this@ScheduleActivity,
                "Envoi…",
                Toast.LENGTH_SHORT
            ).show()

            val sent = sendSchedulesToESP32(active)
            if (!sent) {
                setUnsyncedState(true, "Synchronisation impossible")
                showUnsyncedDialog()
            }
        }
    }

    private fun handleScheduleHelperResult(data: Intent) {
        val pumpNumber = data.getIntExtra(ScheduleHelperActivity.EXTRA_PUMP_NUMBER, -1)
        if (pumpNumber !in 1..4) return

        val active = Esp32Manager.getActive(this) ?: return
        val moduleId = data.getStringExtra(ScheduleHelperActivity.EXTRA_MODULE_ID)
        if (moduleId != null && moduleId != active.id.toString()) return

        val timeMsList =
            data.getLongArrayListExtra(ScheduleHelperActivity.EXTRA_SCHEDULE_MS)?.filterNotNull()
                ?: return
        if (timeMsList.isEmpty()) return

        val volumePerDose = data.getDoubleExtra(ScheduleHelperActivity.EXTRA_VOLUME_PER_DOSE, 0.0)
        if (volumePerDose <= 0.0) return

        val antiOverlapMinutes =
            data.getIntExtra(ScheduleHelperActivity.EXTRA_ANTI_CHEV_MINUTES, 0)

        val (updatedSchedules, addedCount, ignoredCount) = addSchedulesFromHelper(
            espId = active.id,
            pumpNumber = pumpNumber,
            timeMsList = timeMsList,
            volumePerDose = volumePerDose,
            antiOverlapMinutes = antiOverlapMinutes
        )

        adapter.updateSchedules(pumpNumber, updatedSchedules)
        tabsViewModel.setActiveTotal(pumpNumber, sumActiveTotalTenth(updatedSchedules))

        Toast.makeText(
            this,
            "$addedCount doses ajoutées, $ignoredCount ignorées (anti-chevauchement)",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun addSchedulesFromHelper(
        espId: Long,
        pumpNumber: Int,
        timeMsList: List<Long>,
        volumePerDose: Double,
        antiOverlapMinutes: Int
    ): Triple<List<PumpSchedule>, Int, Int> {
        val schedulesPrefs = getSharedPreferences("schedules", Context.MODE_PRIVATE)
        val existingJson = schedulesPrefs.getString("esp_${espId}_pump$pumpNumber", null)
        val existingSchedules: MutableList<PumpSchedule> =
            existingJson?.let { PumpScheduleJson.fromJson(it) } ?: mutableListOf()
        val existingTimes = existingSchedules.map { it.time }.toMutableSet()
        val quantityTenth = (volumePerDose * 10.0).roundToInt()
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val flow = prefs.getFloat("esp_${espId}_pump${pumpNumber}_flow", 0f)
        val durationMs = ScheduleOverlapUtils.durationMsFromQuantity(quantityTenth, flow)
        val antiOverlapMs = antiOverlapMinutes * MS_PER_MINUTE

        var addedCount = 0
        var ignoredCount = 0
        for (timeMs in timeMsList) {
            val time = formatTimeMs(timeMs)
            if (!existingTimes.add(time)) continue
            if (durationMs == null) {
                ignoredCount++
                continue
            }
            val startMs = timeMs - antiOverlapMs
            val endMs = timeMs + durationMs.toLong() + antiOverlapMs
            val overlapResult = ScheduleOverlapUtils.findOverlaps(
                context = this,
                espId = espId,
                pumpNumber = pumpNumber,
                candidateWindow = ScheduleOverlapUtils.ScheduleWindow(startMs, endMs),
                samePumpSchedules = existingSchedules
            )
            if (overlapResult.samePumpConflict || overlapResult.overlappingPumpNames.isNotEmpty()) {
                ignoredCount++
                continue
            }
            existingSchedules.add(
                PumpSchedule(
                    pumpNumber = pumpNumber,
                    time = time,
                    quantityTenth = quantityTenth,
                    enabled = true
                )
            )
            addedCount++
        }

        val sorted = existingSchedules.sortedBy { it.time }
        if (addedCount > 0) {
            schedulesPrefs.edit()
                .putString("esp_${espId}_pump$pumpNumber", PumpScheduleJson.toJson(sorted, gson))
                .apply()
        }

        return Triple(sorted, addedCount, ignoredCount)
    }

    private fun formatTimeMs(timeMs: Long): String {
        val hours = (timeMs / MS_PER_HOUR).toInt()
        val minutes = ((timeMs % MS_PER_HOUR) / MS_PER_MINUTE).toInt()
        return String.format(Locale.getDefault(), "%02d:%02d", hours, minutes)
    }

    // ------------------------------------------------------------
    // ✅ À l’ouverture : /read_ms + compare
    // ------------------------------------------------------------
    private fun autoCheckProgramOnOpen() {
        val active = Esp32Manager.getActive(this) ?: return

        lifecycleScope.launch {
            val espProgram = fetchProgramFromEsp(active.ip)

            if (espProgram == null || espProgram.length < 48 * 12) {
                setUnsyncedState(true, "Synchronisation impossible")
                showUnsyncedDialog()
                return@launch
            }

            val activeSchedulesResult = buildActiveSchedulesFromEsp(espProgram, active.id)
            if (activeSchedulesResult.needsCalibration) {
                setUnsyncedState(false, null)
                setReadOnlyState("Débit non calibré — volumes indisponibles")
            } else {
                setUnsyncedState(false, null)
                setConnectionState(true)
            }

            // ✅ FIX MINIMAL :
            syncProgramStoreFromEsp(espProgram)

            val mergedByPump = mergeSchedulesFromEsp(espProgram, active.id, activeSchedulesResult)
            persistMergedSchedules(active.id, mergedByPump)
            updateUiSchedules(mergedByPump)

            // ✅ Vérité de référence = programme ESP lu
            lastProgramHash = espProgram
        }
    }

    // ------------------------------------------------------------
    // ✅ À la fermeture : /read_ms + compare
    // ------------------------------------------------------------
    private fun finalCheckOnExitThenFinish() {
        if (exitInProgress) return
        exitInProgress = true

        val active = Esp32Manager.getActive(this)
        if (active == null) {
            finish()
            return
        }

        lifecycleScope.launch {
            Log.i("SCHEDULE_EXIT", "Exit check: tentative /read_ms sur ${active.ip}")

            val espProgram = fetchProgramFromEsp(active.ip)
            val localProgram = ProgramStore.buildMessageMs(this@ScheduleActivity)

            if (espProgram == null) {
                AlertDialog.Builder(this@ScheduleActivity)
                    .setTitle("Pompe déconnectée")
                    .setMessage("⚠️ Pompe déconnectée.\nLa programmation n'est peut-être pas enregistrée sur la pompe.")
                    .setPositiveButton("OK") { _, _ -> finish() }
                    .setOnDismissListener { finish() }
                    .create()
                    .also { dlg ->
                        if (isFinishing || isDestroyed) return@launch
                        dlg.show()
                    }
                return@launch
            }

            if (espProgram != localProgram) {
                val diffs = computeAllDiffs(localProgram, espProgram)
                showAllDiffsDialog(diffs)
            } else {
                finish()
            }
        }
    }

    // ------------------------------------------------------------
    // 2️⃣ ENVOI PROGRAMMATION (timeout applicatif)
    // ------------------------------------------------------------
    private suspend fun sendSchedulesToESP32(active: EspModule, timeoutMs: Long = 4000L): Boolean {
        val message = ProgramStore.buildMessageMs(this)
        Log.i("SCHEDULE_SEND", "➡️ Envoi programmation via NetworkHelper")

        return try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    NetworkHelper.sendProgramMs(this@ScheduleActivity, active.ip, message) {
                        val now = System.currentTimeMillis()
                        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)

                        for (pumpNum in 1..4) {
                            prefs.edit()
                                .putLong("esp_${active.id}_pump${pumpNum}_last_processed_time", now)
                                .apply()
                        }

                        // ✅ Vérité ESP32 : après /program_ms OK, on fige EXACTEMENT le message envoyé
                        syncProgramStoreFromEsp(message)

                        // ✅ Référence de vérité = message réellement envoyé
                        lastProgramHash = message

                        if (continuation.isActive) {
                            continuation.resume(true)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------------
    // 🔍 Vérification ESP32 (/id)
    // ------------------------------------------------------------
    private suspend fun verifyEsp32Connection(module: EspModule): Boolean =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("http://${module.ip}/id")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2000
                    readTimeout = 2000
                    useCaches = false
                    setRequestProperty("Connection", "close")
                }

                val response = conn.inputStream.bufferedReader().use { it.readText() }

                response.startsWith("POMPE_NAME=") &&
                        response.removePrefix("POMPE_NAME=").trim() ==
                        module.internalName

            } catch (_: Exception) {
                false
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) {
                }
            }
        }

    // ------------------------------------------------------------
    // 📥 Lecture programme sur ESP32 : GET /read_ms
    // ------------------------------------------------------------
    private suspend fun fetchProgramFromEsp(ip: String): String? =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("http://$ip/read_ms")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2000
                    readTimeout = 2000
                    useCaches = false
                    setRequestProperty("Connection", "close")
                }

                val raw = conn.inputStream.bufferedReader().use { it.readText() }
                normalizeProgram576FromRead(raw)

            } catch (_: Exception) {
                null
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) {
                }
            }
        }

    private fun normalizeProgram576FromRead(raw: String): String? {
        val lines = raw
            .lineSequence()
            .map { it.trim() }
            .filter { it.matches(line12DigitsRegex) }
            .toList()

        if (lines.size != 48) return null

        val joined = lines.joinToString(separator = "")
        return if (joined.length == 576) joined else null
    }

    private fun decodePumpFromLine12(line12: String): Int? {
        if (line12.length != 12 || line12 == "000000000000") return null
        val pump = line12.substring(1, 2).toIntOrNull() ?: return null
        return if (pump in 1..4) pump else null
    }

    private fun decodeTimeFromLine12(line12: String): String? {
        if (line12.length != 12 || line12 == "000000000000") return null
        return try {
            val hh = line12.substring(2, 4).toInt()
            val mm = line12.substring(4, 6).toInt()
            if (hh !in 0..23 || mm !in 0..59) null else "%02d:%02d".format(hh, mm)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeMsFromLine12(line12: String): Int? {
        if (line12.length != 12 || line12 == "000000000000") return null
        val ms = line12.substring(6, 12).toIntOrNull() ?: return null
        return if (ms in 50..600000) ms else null
    }

    private fun estimateVolumeMl(pump: Int, durationMs: Int): Int? {
        val active = Esp32Manager.getActive(this) ?: return null
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val flow = prefs.getFloat("esp_${active.id}_pump${pump}_flow", 0f) // mL/s
        if (flow <= 0f) return null
        return (flow * (durationMs / 1000f)).toInt()
    }

    private fun formatReadableLine(line12: String): String {
        if (line12.length != 12 || line12 == "000000000000") return "Aucune programmation"

        val pump = decodePumpFromLine12(line12) ?: return "Programmation invalide"
        val time = decodeTimeFromLine12(line12) ?: "—"
        val ms = decodeMsFromLine12(line12)

        val volume = if (ms != null) estimateVolumeMl(pump, ms) else null

        return when {
            ms == null -> "Pompe $pump – $time"
            volume != null -> "Pompe $pump – $time – $volume mL"
            else -> "Pompe $pump – $time – ${ms} ms"
        }
    }

    private data class LineDiff(
        val globalLine: Int,
        val localLine12: String,
        val espLine12: String
    )

    private fun computeAllDiffs(local: String, esp: String): List<LineDiff> {
        val a = local.padEnd(576, '0').take(576)
        val b = esp.padEnd(576, '0').take(576)

        val diffs = mutableListOf<LineDiff>()
        for (line in 0 until 48) {
            val start = line * 12
            val la = a.substring(start, start + 12)
            val lb = b.substring(start, start + 12)
            if (la != lb) diffs.add(LineDiff(line, la, lb))
        }
        return diffs
    }

    private fun showAllDiffsDialog(diffs: List<LineDiff>) {
        if (diffs.isEmpty()) {
            finish()
            return
        }

        val maxToShow = 30
        val shown = diffs.take(maxToShow)

        val msg = buildString {
            append("⚠️ Programme différent\n")
            append("Différences : ${diffs.size}\n\n")

            for (d in shown) {
                val localReadable = formatReadableLine(d.localLine12)
                val espReadable = formatReadableLine(d.espLine12)

                append("➡️ Appli : $localReadable\n")
                append("➡️ Pompe : $espReadable\n\n")
            }

            if (diffs.size > maxToShow) {
                append("… +${diffs.size - maxToShow} autre(s) différence(s)\n")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Vérifier programme")
            .setMessage(msg)
            .setPositiveButton("OK") { _, _ -> finish() }
            .setOnDismissListener { finish() }
            .create()
            .also { dlg ->
                if (isFinishing || isDestroyed) return
                dlg.show()
            }
    }

    // ------------------------------------------------------------
    // 🔄 Synchronisation /read_ms OU /program_ms OK → ProgramStoreSynced
    // ------------------------------------------------------------
    private fun syncProgramStoreFromEsp(espProgram: String) {
        val active = Esp32Manager.getActive(this) ?: return
        val ok = ProgramStoreSynced.setFromMessage576(this, active.id, espProgram)
        if (!ok) {
            Log.w(
                "SCHEDULE_SYNC",
                "ProgramStoreSynced.setFromMessage576 failed (len=${espProgram.length})"
            )
        }
    }

    private fun setConnectionState(connected: Boolean) {
        toolbar.subtitle = if (connected) null else "Non connecté"
        setReadOnlyMode(!connected, toolbar.subtitle?.toString())
    }

    private fun setUnsyncedState(on: Boolean, subtitle: String? = null) {
        isUnsynced = on
        setReadOnlyMode(isReadOnly, subtitle)
        invalidateOptionsMenu()
    }

    private fun setReadOnlyMode(readOnly: Boolean, subtitle: String?) {
        val subtitleChanged = toolbar.subtitle?.toString() != subtitle
        if (isReadOnly == readOnly && !subtitleChanged) return
        isReadOnly = readOnly
        toolbar.subtitle = subtitle
        adapter.setReadOnly(readOnly || isUnsynced)
        invalidateOptionsMenu()
    }

    private fun setReadOnlyState(message: String) {
        setReadOnlyMode(true, message)
    }

    private fun showUnsyncedDialog() {
        if (unsyncedDialogShowing) return
        unsyncedDialogShowing = true

        AlertDialog.Builder(this)
            .setTitle("⚠️ Synchronisation impossible")
            .setMessage(
                "Un problème est survenu et la programmation n’a pas pu être synchronisée avec la pompe.\n" +
                        "Tant que la synchronisation n’est pas faite, aucune modification ne sera possible pour éviter toute incohérence."
            )
            .setPositiveButton("Rester (recommandé)") { dialog, _ -> dialog.dismiss() }
            .setNegativeButton("Quitter (mode dégradé)") { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .setOnDismissListener { unsyncedDialogShowing = false }
            .create()
            .also { dlg ->
                if (isFinishing || isDestroyed) {
                    unsyncedDialogShowing = false
                    return
                }
                dlg.show()
            }
    }

    // ------------------------------------------------------------
    // ✅ Le reste de tes fonctions merge/persist/buildActive...
    // ------------------------------------------------------------
    private fun mergeSchedulesFromEsp(
        espProgram: String,
        espId: Long,
        activeSchedulesResult: ActiveSchedulesResult
    ): Map<Int, List<PumpSchedule>> {
        val activeFromEsp = activeSchedulesResult.schedulesByPump
        val schedulesPrefs = getSharedPreferences("schedules", Context.MODE_PRIVATE)
        val mergedByPump = mutableMapOf<Int, List<PumpSchedule>>()

        for (pump in 1..4) {
            val localJson = schedulesPrefs.getString("esp_${espId}_pump$pump", null)
            val localSchedules = localJson?.let { PumpScheduleJson.fromJson(it) } ?: emptyList()
            val suspendedLocal = localSchedules.filter { !it.enabled }

            val activeSchedules = activeFromEsp[pump].orEmpty()
            val activeKeys = activeSchedules
                .map { it.time to it.quantityTenth }
                .toSet()

            val filteredSuspendedLocal = suspendedLocal.filter {
                it.time to it.quantityTenth !in activeKeys
            }

            val merged = (filteredSuspendedLocal + activeSchedules)
                .sortedBy { it.time }

            mergedByPump[pump] = merged
        }

        return mergedByPump
    }

    private data class ActiveSchedulesResult(
        val schedulesByPump: Map<Int, List<PumpSchedule>>,
        val needsCalibration: Boolean
    )

    private fun buildActiveSchedulesFromEsp(
        espProgram: String,
        espId: Long
    ): ActiveSchedulesResult {
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val byPump = (1..4).associateWith { mutableListOf<PumpSchedule>() }.toMutableMap()
        val seen = (1..4).associateWith { mutableSetOf<Pair<String, Int>>() }.toMutableMap()
        var needsCalibration = false

        if (espProgram.length < 48 * 12) {
            return ActiveSchedulesResult(emptyMap(), false)
        }

        for (lineIndex in 0 until 48) {
            val start = lineIndex * 12
            val line = espProgram.substring(start, start + 12)
            if (line == "000000000000" || line.length != 12) continue
            if (line[0] != '1') continue

            val pump = line.substring(1, 2).toIntOrNull() ?: continue
            if (pump !in 1..4) continue

            val hour = line.substring(2, 4).toIntOrNull() ?: continue
            val minute = line.substring(4, 6).toIntOrNull() ?: continue
            val durationMs = line.substring(6, 12).toIntOrNull() ?: continue

            if (hour !in 0..23 || minute !in 0..59) continue
            if (durationMs !in 50..600000) continue

            val flow = prefs.getFloat("esp_${espId}_pump${pump}_flow", 0f)
            if (flow <= 0f) {
                needsCalibration = true
                continue
            }

            val qtyTenth = (flow * (durationMs / 1000f) * 10f).roundToInt()

            val time = "%02d:%02d".format(hour, minute)
            val key = time to qtyTenth
            if (seen[pump]?.add(key) != true) continue

            byPump[pump]?.add(
                PumpSchedule(
                    pumpNumber = pump,
                    time = time,
                    quantityTenth = qtyTenth,
                    enabled = true
                )
            )
        }

        return ActiveSchedulesResult(byPump.mapValues { it.value.toList() }, needsCalibration)
    }

    private fun persistMergedSchedules(
        espId: Long,
        mergedByPump: Map<Int, List<PumpSchedule>>
    ) {
        val schedulesPrefs = getSharedPreferences("schedules", Context.MODE_PRIVATE)
        val editor = schedulesPrefs.edit()
        for ((pump, schedules) in mergedByPump) {
            editor.putString(
                "esp_${espId}_pump$pump",
                PumpScheduleJson.toJson(schedules, gson)
            )
        }
        editor.apply()
    }

    private fun updateUiSchedules(mergedByPump: Map<Int, List<PumpSchedule>>) {
        for (pump in 1..4) {
            val schedules = mergedByPump[pump].orEmpty()
            adapter.updateSchedules(pump, schedules)
            tabsViewModel.setActiveTotal(pump, sumActiveTotalTenth(schedules))
        }
    }

    private fun sumActiveTotalTenth(schedules: List<PumpSchedule>): Int {
        return schedules.filter { it.enabled }.sumOf { it.quantityTenth }
    }

    private fun updateTabTotal(pumpNumber: Int, totalTenth: Int) {
        val (shortText, fullText) = formatActiveTotalShort(totalTenth)
        val pumpName = pumpNames[pumpNumber] ?: "Pompe $pumpNumber"
        val tab = tabLayout.getTabAt(pumpNumber - 1) ?: return
        tab.text = buildTabText(pumpName, shortText)
        tab.contentDescription = buildTabContentDescription(pumpName, fullText)
    }

    // ✅ Gardé (si utilisé ailleurs), mais plus utilisé dans les tabs
    private fun formatActiveTotal(totalTenth: Int): String {
        val totalText = if (totalTenth % 10 == 0) {
            "${totalTenth / 10}"
        } else {
            String.format(Locale.getDefault(), "%.1f", totalTenth / 10f)
        }
        return "Total actif : $totalText mL"
    }

    // ✅ Format compact pour tabs + texte complet pour tooltip
    private fun formatActiveTotalShort(totalTenth: Int): Pair<String, String> {
        val valueText = if (totalTenth % 10 == 0) {
            "${totalTenth / 10}"
        } else {
            String.format(Locale.getDefault(), "%.1f", totalTenth / 10f)
        }

        val full = "Total actif : $valueText mL"
        val short = "$valueText mL"
        return short to full
    }

    private fun buildTabText(pumpName: String, shortText: String): String {
        return "$pumpName\n$shortText"
    }

    private fun buildTabContentDescription(pumpName: CharSequence, fullText: String): String {
        return "$pumpName. $fullText"
    }

    companion object {
        private const val REQUEST_SCHEDULE_HELPER = 2001
        private const val MS_PER_MINUTE = 60_000L
        private const val MS_PER_HOUR = 3_600_000L
    }
}
