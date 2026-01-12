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
        return if (item.itemId == R.id.action_send) {
            verifyIpThenSend()
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    // ------------------------------------------------------------
    // 1️⃣ Vérification ESP32
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
            try {
                val url = URL("http://${module.ip}/id")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 800
                conn.readTimeout = 800

                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                response.startsWith("POMPE_NAME=") &&
                        response.removePrefix("POMPE_NAME=").trim() ==
                        module.internalName

            } catch (_: Exception) {
                false
            }
        }
}
