package com.esp32pumpwifi.app

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL

class SelectIpActivity : AppCompatActivity() {

    private lateinit var inputIp: EditText
    private lateinit var inputDisplayName: EditText
    private var moduleId: Long? = null

    private val uiScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_select_ip)

        inputIp = findViewById(R.id.edit_ip)
        inputDisplayName = findViewById(R.id.edit_name)
        val btnSave = findViewById<Button>(R.id.btn_save_module)

        // ------------------------------------------------------------
        // MODE ÉDITION
        // ------------------------------------------------------------
        moduleId = intent.getLongExtra("module_id", -1L).takeIf { it != -1L }

        moduleId?.let { id ->
            Esp32Manager.getAll(this)
                .firstOrNull { it.id == id }
                ?.let {
                    inputIp.setText(it.ip)
                    inputDisplayName.setText(it.displayName)
                }
        }

        // ------------------------------------------------------------
        // SAUVEGARDE
        // ------------------------------------------------------------
        btnSave.setOnClickListener {

            val ip = inputIp.text.toString().trim()
            val displayName = inputDisplayName.text.toString().trim()
                .ifEmpty { "Module ESP32" }

            if (ip.isBlank()) {
                Toast.makeText(this, "Adresse IP obligatoire", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // ========================================================
            // ✏ MODIFICATION
            // ========================================================
            if (moduleId != null) {

                val module = Esp32Manager.getAll(this)
                    .firstOrNull { it.id == moduleId }

                if (module != null) {
                    module.displayName = displayName
                    module.ip = ip
                    Esp32Manager.update(this, module)
                }

                Toast.makeText(this, "Module modifié ✓", Toast.LENGTH_SHORT).show()
                finish()
                return@setOnClickListener
            }

            // ========================================================
            // ➕ AJOUT MANUEL → LECTURE /id OBLIGATOIRE
            // ========================================================
            uiScope.launch {

                val internalName = readInternalName(ip)

                if (internalName == null) {
                    Toast.makeText(
                        this@SelectIpActivity,
                        "Impossible de contacter l’ESP32.\n" +
                                "Vérifiez l’IP ou connectez-vous à son Wi-Fi (mode AP).",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                val module = EspModule(
                    displayName = displayName,
                    internalName = internalName, // ✅ VRAI IDENTIFIANT
                    ip = ip,
                    isActive = false
                )

                Esp32Manager.add(this@SelectIpActivity, module)

                Toast.makeText(
                    this@SelectIpActivity,
                    "Module ajouté : $internalName ✓",
                    Toast.LENGTH_SHORT
                ).show()

                finish()
            }
        }
    }

    // ------------------------------------------------------------
    // 🔍 Lecture de /id sur l’ESP32
    // ------------------------------------------------------------
    private suspend fun readInternalName(ip: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("http://$ip/id")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 800
                    readTimeout = 800
                }

                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                if (response.startsWith("POMPE_NAME=")) {
                    response.removePrefix("POMPE_NAME=").trim()
                } else null

            } catch (_: Exception) {
                null
            }
        }
}
