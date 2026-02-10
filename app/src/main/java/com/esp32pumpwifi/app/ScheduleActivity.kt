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

    // ✅ bouton helper (Aide à la programmation)
    private lateinit var btnScheduleHelper: ImageButton

    private val pumpNames = mutableMapOf<Int, String>()
    private val tabsViewModel: ScheduleTabsViewModel by viewModels()

    private var lastProgramHash: String? = null
    private val line12DigitsRegex = Regex("""\d{12}""")

    private var didAutoCheckOnResume = false
    private var skipAutoCheckOnce = false
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

            val pumpNumber = viewPager.currentItem + 1
            skipAutoCheckOnce = true

            val intent = Intent(this, ScheduleHelperActivity::class.java).apply {
                putExtra(ScheduleHelperActivity.EXTRA_PUMP_NUMBER, pumpNumber)
                putExtra(
                    ScheduleHelperActivity.EXTRA_MODULE_ID,
                    activeModule.id.toString()
                )
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

            tab.text = buildTabText(pumpName, "0 mL")
            pumpNames[pumpNumber] = pumpName
        }.attach()

        tabsViewModel.activeTotals.observe(this) { totals ->
            totals.forEach { (pump, total) ->
                updateTabTotal(pump, total)
            }
        }

        lastProgramHash = null

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
                    val locallyModified =
                        lastProgramHash != null && lastProgramHash != currentHash

                    if (!locallyModified) {
                        finish()
                        return
                    }

                    AlertDialog.Builder(this@ScheduleActivity)
                        .setTitle("Programmation modifiée")
                        .setMessage(
                            "La programmation a été modifiée.\n" +
                                    "Elle sera envoyée automatiquement à la fermeture."
                        )
                        .setPositiveButton("Envoyer et quitter") { _, _ ->
                            exitInProgress = true
                            lifecycleScope.launch {
                                val ok = sendIfPossible()
                                if (ok) finish()
                            }
                        }
                        .setNegativeButton("Rester", null)
                        .show()
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

            // 🆕 CHECK LÉGER DE COHÉRENCE ESP ↔ LOCAL
            lifecycleScope.launch {
                val active = Esp32Manager.getActive(this@ScheduleActivity) ?: return@launch
                val espProgram = fetchProgramFromEsp(active.ip) ?: return@launch

                val localProgram = ProgramStore.buildMessageMs(this@ScheduleActivity)

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
        btnScheduleHelper.alpha =
            if (btnScheduleHelper.isEnabled) 1f else 0.4f

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
        val active = Esp32Manager.getActive(this) ?: return false
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
                        lastProgramHash = message
                        cont.resume(true)
                    }
                }
            }
        } catch (_: Exception) {
            setUnsyncedState(true, "Synchronisation impossible")
            showUnsyncedDialog()
            false
        }
    }

    private fun autoCheckProgramOnOpen() {
        // inchangé (logique ESP / merge / sync)
    }

    private fun showUnsyncedDialog() {
        if (unsyncedDialogShowing) return
        unsyncedDialogShowing = true

        AlertDialog.Builder(this)
            .setTitle("⚠️ Synchronisation impossible")
            .setMessage(
                "La programmation n’a pas pu être synchronisée avec la pompe.\n" +
                        "Les modifications sont désactivées."
            )
            .setPositiveButton("OK", null)
            .setOnDismissListener { unsyncedDialogShowing = false }
            .show()
    }

    companion object {
        private const val REQUEST_SCHEDULE_HELPER = 2001
    }
}
