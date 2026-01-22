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

        // ✅ Remplacement de onBackPressed() (deprecated) par OnBackPressedDispatcher
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {

                    val currentHash = ProgramStore.buildMessage(this@ScheduleActivity)
                    val reallyModified =
                        lastProgramHash != null && lastProgramHash != currentHash

                    if (!reallyModified) {
                        // Rien n'a changé → on quitte comme avant
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
                            finish()
                        }
                        .show()
                }
            }
        )
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
    // 1️⃣ bis : Vérification ESP32 puis lecture /read + comparaison
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
                val diffIndex = firstDiffIndex(localProgram, espProgram)
                showProgramDiffDialog(localProgram, espProgram, diffIndex)
            }
        }
    }

    // ------------------------------------------------------------
    // 2️⃣ ENVOI PROGRAMMATION (SANS DÉCRÉMENT)
    // ------------------------------------------------------------
    private fun sendSchedulesToESP32(active: EspModule) {

        val message = ProgramStore.buildMessage(this)
        Log.i("SCHEDULE_SEND", "➡️ Envoi programmation via NetworkHelper")

        NetworkHelper.sendProgram(this, active.ip, message) {
            // ✅ RESET DU CURSEUR : ON PART DE MAINTENANT
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

            // ✅ La programmation envoyée devient la référence
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
    // Retourne exactement 432 chars (48 lignes * 9 digits) ou null
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

    // ------------------------------------------------------------
    // 🔧 Normalisation /read : 48 lignes de 9 chiffres -> 432 chars
    // ------------------------------------------------------------
    private fun normalizeProgram432FromRead(raw: String): String? {
        val lines = raw
            .lineSequence()
            .map { it.trim() }
            .filter { it.matches(line9DigitsRegex) }
            .toList()

        // 4 pompes * 12 lignes = 48 lignes (format strict confirmé)
        if (lines.size != 48) return null

        val joined = lines.joinToString(separator = "")
        return if (joined.length == 432) joined else null
    }

    // ------------------------------------------------------------
    // 🔎 Index première différence
    // ------------------------------------------------------------
    private fun firstDiffIndex(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        for (i in 0 until n) {
            if (a[i] != b[i]) return i
        }
        return if (a.length == b.length) -1 else n
    }

    // ------------------------------------------------------------
    // 🪟 Popup si différent
    // ------------------------------------------------------------
    private fun showProgramDiffDialog(local: String, esp: String, diffIndex: Int) {
        val msg = buildString {
            append("⚠️ Programme différent.\n")
            if (diffIndex >= 0) append("Première différence à l’index : $diffIndex\n\n")
            append("ESP32 (/read) :\n")
            append(esp)
            append("\n\nAppli (buildMessage) :\n")
            append(local)
        }

        AlertDialog.Builder(this)
            .setTitle("Vérifier programme")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }
}
