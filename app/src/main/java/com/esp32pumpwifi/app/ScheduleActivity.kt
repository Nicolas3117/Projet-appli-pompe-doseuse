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

    // ✅ /read : 48 lignes de 9 chiffres
    private val line9DigitsRegex = Regex("""\d{9}""")

    // ✅ Auto-check : 1 fois par ouverture d’activité
    private var didAutoCheckOnResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

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

        // ✅ Référence de départ
        lastProgramHash = ProgramStore.buildMessage(this)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {

                    val currentHash = ProgramStore.buildMessage(this@ScheduleActivity)
                    val reallyModified =
                        lastProgramHash != null && lastProgramHash != currentHash

                    if (!reallyModified) {
                        finish()
                        return
                    }

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
                            finalCheckOnExitThenFinish()
                        }
                        .show()
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

    // ------------------------------------------------------------
    // 🛫 MENU
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
            R.id.action_read -> {
                verifyIpThenReadAndCompare()
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
    // 1️⃣ bis : Vérification ESP32 puis lecture /read + comparaison (manuel via bouton)
    // ------------------------------------------------------------
    private fun verifyIpThenReadAndCompare() {

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

            val espProgram = fetchProgramFromEsp(active.ip)
            if (espProgram == null) {
                Toast.makeText(
                    this@ScheduleActivity,
                    "Impossible de lire le programme sur l’ESP32 (/read).",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val localProgram = ProgramStore.buildMessage(this@ScheduleActivity)

            if (espProgram == localProgram) {
                Toast.makeText(
                    this@ScheduleActivity,
                    "✅ Programme identique (ESP ↔ appli)",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                val diffs = computeAllDiffs(localProgram, espProgram)
                showAllDiffsDialog(diffs)
            }
        }
    }

    // ------------------------------------------------------------
    // ✅ Auto-check à l’ouverture (silencieux si identique)
    // ------------------------------------------------------------
    private fun autoCheckProgramOnOpen() {
        val active = Esp32Manager.getActive(this) ?: return

        lifecycleScope.launch {
            val ok = verifyEsp32Connection(active)
            if (!ok) return@launch

            val espProgram = fetchProgramFromEsp(active.ip) ?: return@launch
            val localProgram = ProgramStore.buildMessage(this@ScheduleActivity)

            if (espProgram != localProgram) {
                val diffs = computeAllDiffs(localProgram, espProgram)
                showAllDiffsDialog(diffs)
            }
        }
    }

    // ------------------------------------------------------------
    // ✅ Dernier check quand l’utilisateur quitte malgré “non envoyée”
    // ------------------------------------------------------------
    private fun finalCheckOnExitThenFinish() {
        val active = Esp32Manager.getActive(this)
        if (active == null) {
            finish()
            return
        }

        lifecycleScope.launch {
            val ok = verifyEsp32Connection(active)
            if (!ok) {
                finish()
                return@launch
            }

            val espProgram = fetchProgramFromEsp(active.ip)
            val localProgram = ProgramStore.buildMessage(this@ScheduleActivity)

            if (espProgram != null && espProgram != localProgram) {
                AlertDialog.Builder(this@ScheduleActivity)
                    .setTitle("Programmation différente")
                    .setMessage("⚠️ Programmation différente entre l'appli et la pompe.")
                    .setPositiveButton("OK") { _, _ -> finish() }
                    .show()
            } else {
                finish()
            }
        }
    }

    // ------------------------------------------------------------
    // 2️⃣ ENVOI PROGRAMMATION
    // ------------------------------------------------------------
    private fun sendSchedulesToESP32(active: EspModule) {

        val message = ProgramStore.buildMessage(this)
        Log.i("SCHEDULE_SEND", "➡️ Envoi programmation via NetworkHelper")

        NetworkHelper.sendProgram(this, active.ip, message) {
            val now = System.currentTimeMillis()
            val prefs = getSharedPreferences("prefs", MODE_PRIVATE)

            for (pumpNum in 1..4) {
                prefs.edit()
                    .putLong(
                        "esp_${active.id}_pump${pumpNum}_last_processed_time",
                        now
                    )
                    .apply()
            }

            lastProgramHash = ProgramStore.buildMessage(this@ScheduleActivity)
        }
    }

    // ------------------------------------------------------------
    // 🔍 Vérification ESP32
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
    // 📥 Lecture programme sur ESP32 : GET /read
    // ------------------------------------------------------------
    private suspend fun fetchProgramFromEsp(ip: String): String? =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("http://$ip/read")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2000
                    readTimeout = 2000
                    useCaches = false
                    setRequestProperty("Connection", "close")
                }

                val raw = conn.inputStream.bufferedReader().use { it.readText() }
                normalizeProgram432FromRead(raw)

            } catch (_: Exception) {
                null
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) {
                }
            }
        }

    private fun normalizeProgram432FromRead(raw: String): String? {
        val lines = raw
            .lineSequence()
            .map { it.trim() }
            .filter { it.matches(line9DigitsRegex) }
            .toList()

        if (lines.size != 48) return null

        val joined = lines.joinToString(separator = "")
        return if (joined.length == 432) joined else null
    }

    // ------------------------------------------------------------
    // 🔎 Décodage ligne 9 chiffres
    // ------------------------------------------------------------
    private fun decodePumpFromLine9(line9: String): Int? {
        if (line9.length != 9 || line9 == "000000000") return null
        val pump = line9.substring(1, 2).toIntOrNull() ?: return null
        return if (pump in 1..4) pump else null
    }

    private fun decodeTimeFromLine9(line9: String): String? {
        if (line9.length != 9 || line9 == "000000000") return null
        return try {
            val hh = line9.substring(2, 4).toInt()
            val mm = line9.substring(4, 6).toInt()
            if (hh !in 0..23 || mm !in 0..59) null else "%02d:%02d".format(hh, mm)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeSecsFromLine9(line9: String): Int? {
        if (line9.length != 9 || line9 == "000000000") return null
        val secs = line9.substring(6, 9).toIntOrNull() ?: return null
        return if (secs in 1..600) secs else null
    }

    private fun estimateVolumeMl(pump: Int, secs: Int): Int? {
        val active = Esp32Manager.getActive(this) ?: return null
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val flow = prefs.getFloat("esp_${active.id}_pump${pump}_flow", 0f) // mL/s
        if (flow <= 0f) return null
        return (flow * secs).toInt()
    }

    /**
     * ✅ Format “propre” :
     * - "Pompe 3 – 16:30 – 5 mL"
     * - ou "Pompe 3 – 16:30 – 120 s" (si pas de débit)
     * - ou "Aucune programmation"
     */
    private fun formatReadableLine(line9: String): String {
        if (line9.length != 9 || line9 == "000000000") return "Aucune programmation"

        val pump = decodePumpFromLine9(line9) ?: return "Programmation invalide"
        val time = decodeTimeFromLine9(line9) ?: "—"
        val secs = decodeSecsFromLine9(line9)

        val volume = if (secs != null) estimateVolumeMl(pump, secs) else null

        return when {
            secs == null -> "Pompe $pump – $time"
            volume != null -> "Pompe $pump – $time – $volume mL"
            else -> "Pompe $pump – $time – ${secs} s"
        }
    }

    // ------------------------------------------------------------
    // ✅ Toutes les différences (48 lignes)
    // ------------------------------------------------------------
    private data class LineDiff(
        val globalLine: Int,   // 0..47
        val localLine9: String,
        val espLine9: String
    )

    private fun computeAllDiffs(local: String, esp: String): List<LineDiff> {
        val a = local.padEnd(432, '0').take(432)
        val b = esp.padEnd(432, '0').take(432)

        val diffs = mutableListOf<LineDiff>()
        for (line in 0 until 48) {
            val start = line * 9
            val la = a.substring(start, start + 9)
            val lb = b.substring(start, start + 9)
            if (la != lb) {
                diffs.add(LineDiff(line, la, lb))
            }
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
                val localReadable = formatReadableLine(d.localLine9)
                val espReadable = formatReadableLine(d.espLine9)

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
            .show()
    }
}
