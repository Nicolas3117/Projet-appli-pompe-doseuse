package com.esp32pumpwifi.app

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class ScheduleActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var adapter: PumpPagerAdapter

    // ✅ Empreinte de la programmation envoyée / chargée
    private var lastProgramHash: String? = null

    // ✅ /read_ms : 48 lignes de 12 chiffres
    private val line12DigitsRegex = Regex("""\d{12}""")

    // ✅ Auto-check : 1 fois par ouverture d’activité
    private var didAutoCheckOnResume = false

    // ✅ Anti double-finish / double popup (back spam)
    private var exitInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationContentDescription("← Retour")
        toolbar.setNavigationOnClickListener { finish() }

        val activeModule = Esp32Manager.getActive(this)
        if (activeModule == null) {
            Toast.makeText(this, "Veuillez sélectionner un module", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)

        adapter = PumpPagerAdapter(this)
        viewPager.adapter = adapter

        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val moduleId = activeModule.id

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = prefs.getString(
                "esp_${moduleId}_pump${position + 1}_name",
                "Pompe ${position + 1}"
            )
        }.attach()

        // ✅ Référence de départ (programme "considéré envoyé/chargé")
        lastProgramHash = ProgramStore.buildMessageMs(this)

        // ✅ Sortie : toujours check final
        // Si modif locale -> popup "non envoyée" avant
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (exitInProgress) return

                    val currentHash = ProgramStore.buildMessageMs(this@ScheduleActivity)
                    val locallyModified = lastProgramHash != null && lastProgramHash != currentHash

                    if (!locallyModified) {
                        // ✅ Même sans modif : check final /read puis exit
                        finalCheckOnExitThenFinish()
                        return
                    }

                    // ✅ Popup existante : "Programmation non envoyée"
                    AlertDialog.Builder(this@ScheduleActivity)
                        .setTitle("Programmation non envoyée")
                        .setMessage(
                            "Vous avez modifié la programmation.\n" +
                                    "Pensez à l’envoyer avant de quitter."
                        )
                        .setPositiveButton("Rester") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .setNegativeButton("Quitter") { _, _ ->
                            // ✅ Peu importe : check final /read puis exit
                            finalCheckOnExitThenFinish()
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

        // ✅ À l’ouverture : /read_ms + compare (avec popup si KO)
        autoCheckProgramOnOpen()
    }

    override fun onPause() {
        super.onPause()
        didAutoCheckOnResume = false
    }

    // ------------------------------------------------------------
    // 🛫 MENU (on garde uniquement Envoyer)
    // ------------------------------------------------------------
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_schedule, menu)
        return true
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

    // ------------------------------------------------------------
    // 1️⃣ Vérification ESP32 puis envoi
    // ------------------------------------------------------------
    private fun verifyIpThenSend() {
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

            sendSchedulesToESP32(active)
        }
    }

    // ------------------------------------------------------------
    // ✅ À l’ouverture : /read_ms + compare
    // - /read_ms KO => popup courte (pompe déconnectée)
    // - identique => rien
    // - différent => popup détaillée
    // ------------------------------------------------------------
    private fun autoCheckProgramOnOpen() {
        val active = Esp32Manager.getActive(this) ?: return

        lifecycleScope.launch {
            val espProgram = fetchProgramFromEsp(active.ip)

            if (espProgram == null) {
                AlertDialog.Builder(this@ScheduleActivity)
                    .setTitle("Pompe déconnectée")
                    .setMessage("⚠️ Pompe déconnectée.\nImpossible de lire la programmation.")
                    .setPositiveButton("OK", null)
                    .create()
                    .also { dlg ->
                        if (isFinishing || isDestroyed) return@launch
                        dlg.show()
                    }
                return@launch
            }

            val localProgram = ProgramStore.buildMessageMs(this@ScheduleActivity)

            if (espProgram != localProgram) {
                val diffs = computeAllDiffs(localProgram, espProgram)
                showAllDiffsDialog(diffs) // ✅ popup détaillée
            }
        }
    }

    // ------------------------------------------------------------
    // ✅ À la fermeture : /read_ms + compare
    // - /read_ms KO => popup courte + finish
    // - identique => finish
    // - différent => popup courte + finish
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
                AlertDialog.Builder(this@ScheduleActivity)
                    .setTitle("Programmation différente")
                    .setMessage("⚠️ Programmation différente entre l'appli et la pompe.")
                    .setPositiveButton("OK") { _, _ -> finish() }
                    .setOnDismissListener { finish() }
                    .create()
                    .also { dlg ->
                        if (isFinishing || isDestroyed) return@launch
                        dlg.show()
                    }
            } else {
                finish()
            }
        }
    }

    // ------------------------------------------------------------
    // 2️⃣ ENVOI PROGRAMMATION
    // ------------------------------------------------------------
    private fun sendSchedulesToESP32(active: EspModule) {
        val message = ProgramStore.buildMessageMs(this)
        Log.i("SCHEDULE_SEND", "➡️ Envoi programmation via NetworkHelper")

        NetworkHelper.sendProgramMs(this, active.ip, message) {
            val now = System.currentTimeMillis()
            val prefs = getSharedPreferences("prefs", MODE_PRIVATE)

            for (pumpNum in 1..4) {
                prefs.edit()
                    .putLong("esp_${active.id}_pump${pumpNum}_last_processed_time", now)
                    .apply()
            }

            // ✅ Après envoi, on met à jour la référence "envoyée"
            lastProgramHash = ProgramStore.buildMessageMs(this@ScheduleActivity)
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

    // ------------------------------------------------------------
    // 🔎 Décodage ligne 12 chiffres + affichage propre
    // ------------------------------------------------------------
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

    /**
     * ✅ Format “propre” :
     * - "Pompe 3 – 16:30 – 5 mL"
     * - ou "Pompe 3 – 16:30 – 120 s" (si pas de débit)
     * - ou "Aucune programmation"
     */
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

    // ------------------------------------------------------------
    // ✅ Toutes les différences (48 lignes)
    // ------------------------------------------------------------
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

    // ------------------------------------------------------------
    // 🪟 Popup : affiche toutes les différences (détaillée)
    // ------------------------------------------------------------
    private fun showAllDiffsDialog(diffs: List<LineDiff>) {
        if (diffs.isEmpty()) return

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
            .setPositiveButton("OK", null)
            .create()
            .also { dlg ->
                if (isFinishing || isDestroyed) return
                dlg.show()
            }
    }
}
