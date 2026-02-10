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
import kotlinx.coroutines.withTimeoutOrNull
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

    // ✅ bouton helper (Aide à la programmation)
    private lateinit var btnScheduleHelper: ImageButton

    private val pumpNames = mutableMapOf<Int, String>()
    private val tabsViewModel: ScheduleTabsViewModel by viewModels()

    private val line12DigitsRegex = Regex("""^\d{12}$""")

    private var didAutoCheckOnResume = false
    private var skipAutoCheckOnce = false
    private var exitInProgress = false

    private var isReadOnly = false
    private var isUnsynced = false
    private var unsyncedDialogShowing = false

    private val gson = Gson()
    private var lockedModule: EspModule? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        findViewById<TextView>(R.id.tv_header_back)
            .setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val activeModule = Esp32Manager.getActive(this)
        if (activeModule == null) {
            Toast.makeText(this, "Veuillez sélectionner un module", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val expectedModuleId = intent.getStringExtra(EXTRA_MODULE_ID)

        // ✅ anti mélange multi-modules AVANT tout
        if (expectedModuleId != null && activeModule.id.toString() != expectedModuleId) {
            setUnsyncedState(true, "Synchronisation impossible")
            showUnsyncedDialog()
            return
        }

        // ✅ lock module IMMEDIATEMENT (évite NPE + mélange)
        lockedModule = activeModule

        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)

        // ✅ init bouton helper
        btnScheduleHelper = findViewById(R.id.btn_schedule_helper)

        btnScheduleHelper.setOnClickListener {
            // 🔒 garde-fou STRICT
            if (isReadOnly || isUnsynced) {
                Toast.makeText(
                    this,
                    "Synchronisation impossible — modification désactivée",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            val locked = lockedModule
            if (locked == null) {
                setUnsyncedState(true, "Synchronisation impossible")
                showUnsyncedDialog()
                return@setOnClickListener
            }

            val pumpNumber = viewPager.currentItem + 1
            skipAutoCheckOnce = true

            val intent = Intent(this, ScheduleHelperActivity::class.java).apply {
                putExtra(ScheduleHelperActivity.EXTRA_PUMP_NUMBER, pumpNumber)
                putExtra(ScheduleHelperActivity.EXTRA_MODULE_ID, locked.id.toString())
            }
            startActivityForResult(intent, REQUEST_SCHEDULE_HELPER)
        }

        // ✅ maintenant lockedModule est non-null
        adapter = PumpPagerAdapter(this, lockedModule!!.id)
        viewPager.adapter = adapter

        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val moduleId = lockedModule!!.id

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            val pumpNumber = position + 1
            val pumpName = prefs.getString(
                "esp_${moduleId}_pump${pumpNumber}_name",
                "Pompe $pumpNumber"
            ) ?: "Pompe $pumpNumber"

            tab.text = buildTabText(pumpName, "0 mL")
            pumpNames[pumpNumber] = pumpName
        }.attach()

        tabsViewModel.activeTotals.observe(this) { totals ->
            totals.forEach { (pump, total) ->
                updateTabTotal(pump, total)
            }
        }

        // Baseline : si extra invalide => UNSYNCED (mais on laisse l’autoCheck tenter de resync)
        val initialProgram576 = intent.getStringExtra(EXTRA_INITIAL_PROGRAM_576)?.trim()
        if (!isValidMessage576(initialProgram576)) {
            setUnsyncedState(true, "Synchronisation impossible")
            showUnsyncedDialog()
        } else {
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (exitInProgress) return

                    if (isUnsynced) {
                        showUnsyncedDialog()
                        return
                    }

                    exitInProgress = true
                    lifecycleScope.launch {
                        val shouldFinish = runFinalExitCheck()
                        exitInProgress = false
                        if (shouldFinish) finish()
                    }
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()

        // ✅ Retour du helper : on évite l’autoCheck destructif
        // MAIS on vérifie quand même que l’ESP n’a pas changé
        if (skipAutoCheckOnce) {
            skipAutoCheckOnce = false
            didAutoCheckOnResume = true

            lifecycleScope.launch {
                val active = lockedModule ?: run {
                    setUnsyncedState(true, "Synchronisation impossible")
                    showUnsyncedDialog()
                    return@launch
                }

                val espProgram = fetchProgramFromEsp(active.ip)
                if (espProgram == null) {
                    // ✅ pas de divergence silencieuse
                    setUnsyncedState(true, "Synchronisation impossible")
                    showUnsyncedDialog()
                    return@launch
                }

                val localProgram = ProgramStore.buildMessageMs(this@ScheduleActivity, active.id)

                if (espProgram != localProgram) {
                    setUnsyncedState(true, "Synchronisation impossible")
                    showUnsyncedDialog()
                }
            }

            return
        }

        // ✅ Auto-check normal à l’ouverture
        if (didAutoCheckOnResume) return
        didAutoCheckOnResume = true
        autoCheckProgramOnOpen()
    }

    override fun onPause() {
        super.onPause()
        didAutoCheckOnResume = false
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SCHEDULE_HELPER || resultCode != RESULT_OK || data == null) return
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
                lifecycleScope.launch { sendIfPossible() }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // -------------------------------------------------------
    // 🔒 CENTRALISATION READ-ONLY / UNSYNCED
    // -------------------------------------------------------

    private fun setReadOnlyMode(readOnly: Boolean, subtitle: String?) {
        val subtitleChanged = toolbar.subtitle?.toString() != subtitle
        if (isReadOnly == readOnly && !subtitleChanged) return

        isReadOnly = readOnly
        toolbar.subtitle = subtitle

        adapter.setReadOnly(readOnly || isUnsynced)

        // ✅ DÉSACTIVATION VISUELLE DU HELPER
        btnScheduleHelper.isEnabled = !(readOnly || isUnsynced)
        btnScheduleHelper.alpha = if (btnScheduleHelper.isEnabled) 1f else 0.4f

        invalidateOptionsMenu()
    }

    private fun setUnsyncedState(on: Boolean, subtitle: String? = null) {
        isUnsynced = on
        setReadOnlyMode(isReadOnly, subtitle)
    }

    private fun setReadOnlyState(message: String) {
        setReadOnlyMode(true, message)
    }

    // -------------------------------------------------------

    private suspend fun runFinalExitCheck(): Boolean {
        if (isUnsynced) {
            showUnsyncedDialog()
            return false
        }

        val active = lockedModule ?: run {
            setUnsyncedState(true, "Synchronisation impossible")
            showUnsyncedDialog()
            return false
        }

        val espProgram = withTimeoutOrNull(4000L) { fetchProgramFromEsp(active.ip) }
        if (espProgram == null) {
            setUnsyncedState(true, "Synchronisation impossible")
            showUnsyncedDialog()
            return false
        }

        val localProgram = ProgramStore.buildMessageMs(this, active.id)
        if (espProgram == localProgram) return true

        return suspendCancellableCoroutine { cont ->
            AlertDialog.Builder(this)
                .setTitle("Programmation différente")
                .setMessage("La programmation locale est différente de celle de la pompe.")
                .setNeutralButton("Rester") { dialog, _ ->
                    dialog.dismiss()
                    if (cont.isActive) cont.resume(false)
                }
                .setNegativeButton("Quitter") { dialog, _ ->
                    dialog.dismiss()
                    if (cont.isActive) cont.resume(true)
                }
                .setPositiveButton("Sauvegarder-Envoyer et quitter") { dialog, _ ->
                    dialog.dismiss()
                    lifecycleScope.launch {
                        val ok = sendIfPossible()
                        if (!ok) {
                            setUnsyncedState(true, "Synchronisation impossible")
                            Toast.makeText(
                                this@ScheduleActivity,
                                "Envoi impossible : passage en mode lecture seule.",
                                Toast.LENGTH_LONG
                            ).show()
                            showUnsyncedDialog()
                        }
                        if (cont.isActive) cont.resume(ok)
                    }
                }
                .setOnCancelListener {
                    if (cont.isActive) cont.resume(false)
                }
                .show()
        }
    }

    private fun buildTabText(pumpName: String, shortText: String): String =
        "$pumpName\n$shortText"

    private fun updateTabTotal(pumpNumber: Int, totalTenth: Int) {
        val value =
            if (totalTenth % 10 == 0) "${totalTenth / 10}"
            else String.format(Locale.getDefault(), "%.1f", totalTenth / 10f)

        val tab = tabLayout.getTabAt(pumpNumber - 1) ?: return
        tab.text = buildTabText(pumpNames[pumpNumber] ?: "Pompe $pumpNumber", "$value mL")
    }

    // -------------------------------------------------------

    private suspend fun sendIfPossible(): Boolean {
        val active = lockedModule ?: run {
            setUnsyncedState(true, "Synchronisation impossible")
            Toast.makeText(
                this@ScheduleActivity,
                "Envoi impossible : passage en mode lecture seule.",
                Toast.LENGTH_LONG
            ).show()
            showUnsyncedDialog()
            return false
        }
        return sendSchedulesToESP32(active)
    }

    private suspend fun sendSchedulesToESP32(
        active: EspModule,
        timeoutMs: Long = 4000L
    ): Boolean {
        val message = ProgramStore.buildMessageMs(this, active.id)
        return try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    NetworkHelper.sendProgramMs(
                        this@ScheduleActivity,
                        active.ip,
                        message
                    ) {
                        if (cont.isActive) cont.resume(true)
                    }
                }
            }
        } catch (_: Exception) {
            setUnsyncedState(true, "Synchronisation impossible")
            showUnsyncedDialog()
            false
        }
    }

    /**
     * ✅ Lecture /read_ms
     * Parsing STRICT ligne par ligne pour éviter les faux-positifs.
     */
    private suspend fun fetchProgramFromEsp(ip: String): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL("http://$ip/read_ms").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000

            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null

            val lines = conn.inputStream.bufferedReader().use { reader ->
                reader
                    .lineSequence()
                    .map { it.trim() }
                    .toList()
            }

            if (lines.size != 48) return@withContext null
            if (!lines.all { it.matches(line12DigitsRegex) }) return@withContext null

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

    private fun isValidMessage576(message: String?): Boolean {
        if (message == null) return false
        if (message.length != 576) return false
        return message.chunked(12).all { it.matches(line12DigitsRegex) }
    }

    /**
     * ✅ Implémentation minimale : ESP32 source de vérité
     * - charge le programme 576 (extra si valide, sinon /read_ms strict)
     * - setFromMessage576 (synced store)
     * - reconstruit les schedules actives ESP + merge locales disabled + cap 12
     * - push vers fragments (adapter.updateSchedules) + réaligne ProgramStore + baseline hash
     */
    private fun autoCheckProgramOnOpen() {
        lifecycleScope.launch {
            val active = lockedModule ?: run {
                setUnsyncedState(true, "Synchronisation impossible")
                showUnsyncedDialog()
                return@launch
            }

            val initial = intent.getStringExtra(EXTRA_INITIAL_PROGRAM_576)?.trim()
            val program576 = if (isValidMessage576(initial)) {
                initial!!
            } else {
                withTimeoutOrNull(4000L) { fetchProgramFromEsp(active.ip) }
            }

            if (program576 == null) {
                setUnsyncedState(true, "Synchronisation impossible")
                showUnsyncedDialog()
                return@launch
            }

            val okSynced =
                ProgramStoreSynced.setFromMessage576(this@ScheduleActivity, active.id, program576)
            if (!okSynced) {
                setUnsyncedState(true, "Synchronisation impossible")
                showUnsyncedDialog()
                return@launch
            }

            // Merge & push UI pump par pump
            val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
            val schedulesPrefs = getSharedPreferences("schedules", Context.MODE_PRIVATE)
            val editor = schedulesPrefs.edit()

            for (pump in 1..4) {
                // Actives ESP: depuis ProgramStoreSynced (déjà filtrées enable=1 + garde-fous)
                val espLines = ProgramStoreSynced.loadEncodedLines(this@ScheduleActivity, active.id, pump)

                val flow = prefs.getFloat("esp_${active.id}_pump${pump}_flow", 0f)

                val espActiveSchedules: List<PumpSchedule> = espLines.mapNotNull { line12 ->
                    if (line12.length != 12 || !line12.all(Char::isDigit)) return@mapNotNull null
                    if (line12[0] != '1') return@mapNotNull null
                    val p = line12.substring(1, 2).toIntOrNull() ?: return@mapNotNull null
                    if (p != pump) return@mapNotNull null

                    val hh = line12.substring(2, 4).toIntOrNull() ?: return@mapNotNull null
                    val mm = line12.substring(4, 6).toIntOrNull() ?: return@mapNotNull null
                    val ms = line12.substring(6, 12).toIntOrNull() ?: return@mapNotNull null
                    if (hh !in 0..23 || mm !in 0..59) return@mapNotNull null
                    if (ms !in 50..600000) return@mapNotNull null

                    val qtyTenth = if (flow > 0f) {
                        val qtyMl = flow * (ms / 1000f)
                        (qtyMl * 10f).roundToInt().coerceAtLeast(1)
                    } else {
                        0
                    }

                    PumpSchedule(
                        pumpNumber = pump,
                        time = String.format(Locale.getDefault(), "%02d:%02d", hh, mm),
                        quantityTenth = qtyTenth,
                        enabled = true
                    )
                }

                val localJson = schedulesPrefs.getString("esp_${active.id}_pump$pump", null)
                val localList = if (localJson.isNullOrBlank()) emptyList() else PumpScheduleJson.fromJson(localJson)

                val disabledLocal = localList
                    .filter { it.pumpNumber == pump && !it.enabled }
                    .sortedWith(compareBy<PumpSchedule> {
                        val t = ScheduleOverlapUtils.parseTimeOrNull(it.time)
                        if (t == null) Int.MAX_VALUE else (t.first * 60 + t.second)
                    }.thenBy { it.time })

                val merged: List<PumpSchedule> = if (espActiveSchedules.size >= 12) {
                    espActiveSchedules.take(12)
                } else {
                    (espActiveSchedules + disabledLocal).take(12)
                }

                editor.putString(
                    "esp_${active.id}_pump$pump",
                    PumpScheduleJson.toJson(merged, gson)
                )

                adapter.updateSchedules(pump, merged)

                while (ProgramStore.count(this@ScheduleActivity, active.id, pump) > 0) {
                    ProgramStore.removeLine(this@ScheduleActivity, active.id, pump, 0)
                }

                espLines.take(12).forEach { line12 ->
                    if (line12.length != 12 || !line12.all(Char::isDigit)) return@forEach
                    if (line12[0] != '1') return@forEach
                    val p = line12.substring(1, 2).toIntOrNull() ?: return@forEach
                    if (p != pump) return@forEach

                    val hh = line12.substring(2, 4).toIntOrNull() ?: return@forEach
                    val mm = line12.substring(4, 6).toIntOrNull() ?: return@forEach
                    val ms = line12.substring(6, 12).toIntOrNull() ?: return@forEach
                    if (hh !in 0..23 || mm !in 0..59) return@forEach
                    if (ms !in 50..600000) return@forEach

                    ProgramStore.addLine(
                        this@ScheduleActivity,
                        active.id,
                        pump,
                        ProgramLine(
                            enabled = true,
                            pump = pump,
                            hour = hh,
                            minute = mm,
                            qtyMs = ms
                        )
                    )
                }
            }

            editor.apply()

            setUnsyncedState(false, null)
            setReadOnlyMode(false, null)
        }
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

    companion object {
        const val EXTRA_INITIAL_PROGRAM_576 = "EXTRA_INITIAL_PROGRAM_576"
        const val EXTRA_MODULE_ID = "EXTRA_MODULE_ID"
        private const val REQUEST_SCHEDULE_HELPER = 2001
    }
}
