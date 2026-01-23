package com.esp32pumpwifi.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MaterielsActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_AUTO_SCAN = "extra_auto_scan"
    }

    private lateinit var listView: ListView
    private lateinit var adapter: EspModuleAdapter
    private var modules = mutableListOf<EspModule>()

    // 🔔 Telegram
    private lateinit var editTelegramToken: EditText
    private lateinit var editTelegramChatId: EditText
    private lateinit var btnSaveTelegram: Button
    private lateinit var btnClearTelegram: Button
    private lateinit var tvTelegramInfo: TextView

    // 🔽 Toggle Telegram
    private lateinit var telegramToggle: LinearLayout
    private lateinit var telegramContent: LinearLayout
    private lateinit var telegramChevron: ImageView

    // ▶️ Accès pompes
    private lateinit var btnOpenPumps: Button

    // 📊 Planning
    private lateinit var btnOpenPlanning: Button

    // Insets targets
    private lateinit var layoutActions: ConstraintLayout
    private lateinit var telegramScroll: androidx.core.widget.NestedScrollView

    private val PREFS_NAME = "prefs"
    private val KEY_TELEGRAM_TOGGLE_OPEN = "materiels_telegram_toggle_open"

    private var actionsBaseMarginBottom = 0
    private var telegramScrollBasePaddingBottom = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_materiels)

        listView = findViewById(R.id.list_modules)

        btnOpenPumps = findViewById(R.id.btn_open_pumps)
        btnOpenPlanning = findViewById(R.id.btn_open_planning)

        editTelegramToken = findViewById(R.id.edit_telegram_token)
        editTelegramChatId = findViewById(R.id.edit_telegram_chat_id)
        btnSaveTelegram = findViewById(R.id.btn_save_telegram)
        btnClearTelegram = findViewById(R.id.btn_clear_telegram)
        tvTelegramInfo = findViewById(R.id.tv_telegram_info)

        telegramToggle = findViewById(R.id.layout_telegram_toggle)
        telegramContent = findViewById(R.id.layout_telegram_content)
        telegramChevron = findViewById(R.id.icon_telegram_toggle)

        layoutActions = findViewById(R.id.layout_actions)
        telegramScroll = findViewById(R.id.scroll_telegram_content)

        actionsBaseMarginBottom =
            (layoutActions.layoutParams as android.view.ViewGroup.MarginLayoutParams).bottomMargin
        telegramScrollBasePaddingBottom = telegramScroll.paddingBottom

        // ✅ 1) Remonter la zone actions au-dessus clavier / barres système
        ViewCompat.setOnApplyWindowInsetsListener(layoutActions) { view, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val bottomInset = maxOf(imeInsets.bottom, systemInsets.bottom)

            view.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
                bottomMargin = actionsBaseMarginBottom + bottomInset
            }
            insets
        }

        // ✅ 2) Ajouter du padding dans le scroll Telegram pour que les boutons restent atteignables
        ViewCompat.setOnApplyWindowInsetsListener(telegramScroll) { view, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val bottomInset = maxOf(imeInsets.bottom, systemInsets.bottom)

            view.updatePadding(bottom = telegramScrollBasePaddingBottom + bottomInset)
            insets
        }

        ViewCompat.requestApplyInsets(layoutActions)
        ViewCompat.requestApplyInsets(telegramScroll)

        setupTelegramToggle()

        // ✅ 3) IMPORTANT : auto-scroll vers le champ quand on le focus (sinon tablette = clavier devant)
        setupTelegramAutoScroll()

        // ➕ Scan réseau manuel
        findViewById<Button>(R.id.btn_add_module).setOnClickListener {
            scanForEsp32()
        }

        // ▶️ Accéder aux pompes
        btnOpenPumps.setOnClickListener {
            val activeModule = Esp32Manager.getActive(this)
            if (activeModule == null) {
                Toast.makeText(this, "Sélectionnez d’abord un module", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, MainActivity::class.java))
        }

        // 📊 Accès Planning
        btnOpenPlanning.setOnClickListener {
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

        btnSaveTelegram.setOnClickListener { saveTelegramConfig() }
        btnClearTelegram.setOnClickListener { clearTelegramConfigWithConfirm() }

        if (intent.getBooleanExtra(EXTRA_AUTO_SCAN, false)) {
            scanForEsp32()
        }
    }

    override fun onResume() {
        super.onResume()
        loadList()
        loadTelegramForActiveModule()
    }

    // ================= TOGGLE TELEGRAM =================

    private fun setupTelegramToggle() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val initiallyOpen = prefs.getBoolean(KEY_TELEGRAM_TOGGLE_OPEN, false)

        telegramContent.visibility = if (initiallyOpen) View.VISIBLE else View.GONE
        telegramChevron.rotation = if (initiallyOpen) 0f else -90f

        telegramToggle.setOnClickListener {
            val opened = telegramContent.visibility == View.VISIBLE
            telegramContent.visibility = if (opened) View.GONE else View.VISIBLE

            telegramChevron.animate()
                .rotation(if (opened) -90f else 0f)
                .setDuration(150)
                .start()

            prefs.edit()
                .putBoolean(KEY_TELEGRAM_TOGGLE_OPEN, !opened)
                .apply()

            // ✅ Si on ouvre, on scrolle vers la zone Telegram
            if (!opened) {
                telegramScroll.post {
                    telegramScroll.smoothScrollTo(0, telegramContent.top)
                }
            }
        }
    }

    // ✅ Auto-scroll au focus des champs Telegram
    private fun setupTelegramAutoScroll() {
        val scrollTo = { target: View ->
            telegramScroll.post {
                // marge de confort pour voir le champ + un peu d’espace
                val y = target.bottom + dpToPx(24)
                telegramScroll.smoothScrollTo(0, y)
            }
        }

        editTelegramToken.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) scrollTo(v)
        }
        editTelegramChatId.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) scrollTo(v)
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    // ================= LISTE MODULES =================

    private fun loadList() {
        modules = Esp32Manager.getAll(this)

        adapter = EspModuleAdapter(
            context = this,
            modules = modules,
            onRename = { renameModule(it) },
            onDelete = { deleteModule(it) },
            onSelect = { checkConnectionBeforeSelect(it) }
        )

        listView.adapter = adapter
    }

    // ================= TELEGRAM =================

    private fun loadTelegramForActiveModule() {
        val module = Esp32Manager.getActive(this)

        if (module == null) {
            editTelegramToken.setText("")
            editTelegramChatId.setText("")
            editTelegramToken.isEnabled = false
            editTelegramChatId.isEnabled = false
            btnSaveTelegram.isEnabled = false
            btnClearTelegram.isEnabled = false
            tvTelegramInfo.text = "Sélectionnez un module pour configurer Telegram"
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        editTelegramToken.setText(
            prefs.getString("esp_${module.id}_telegram_token", "")
        )
        editTelegramChatId.setText(
            prefs.getString("esp_${module.id}_telegram_chat_id", "")
        )

        editTelegramToken.isEnabled = true
        editTelegramChatId.isEnabled = true
        btnSaveTelegram.isEnabled = true
        btnClearTelegram.isEnabled = true

        tvTelegramInfo.text =
            "Alertes Telegram pour « ${module.displayName} »"
    }

    private fun saveTelegramConfig() {
        val module = Esp32Manager.getActive(this) ?: return

        val token = editTelegramToken.text.toString().trim()
        val chatId = editTelegramChatId.text.toString().trim()

        if (token.isEmpty() || chatId.isEmpty()) {
            Toast.makeText(this, "Token et Chat ID requis", Toast.LENGTH_SHORT).show()
            return
        }

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString("esp_${module.id}_telegram_token", token)
            .putString("esp_${module.id}_telegram_chat_id", chatId)
            .apply()

        NotificationPermissionHelper.requestPermissionIfNeeded(this)

        Toast.makeText(this, "Configuration Telegram enregistrée", Toast.LENGTH_SHORT).show()
    }

    private fun clearTelegramConfigWithConfirm() {
        val module = Esp32Manager.getActive(this) ?: return

        AlertDialog.Builder(this)
            .setTitle("Effacer Telegram")
            .setMessage("Supprimer la configuration Telegram de « ${module.displayName} » ?")
            .setPositiveButton("Effacer") { _, _ ->
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .remove("esp_${module.id}_telegram_token")
                    .remove("esp_${module.id}_telegram_chat_id")
                    .apply()

                editTelegramToken.setText("")
                editTelegramChatId.setText("")
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ================= STA + AP (LOGIQUE ORIGINALE) =================

    private fun checkConnectionBeforeSelect(module: EspModule) {
        lifecycleScope.launch {

            // 1️⃣ Test STA direct
            if (testModuleConnection(module)) {
                activateModule(module)
                return@launch
            }

            // 2️⃣ Fallback AP / scan réseau
            val found = NetworkScanner.scan(this@MaterielsActivity)

            val match = found.firstOrNull {
                it.internalName == module.internalName
            }

            if (match != null) {
                module.ip = match.ip
                Esp32Manager.update(this@MaterielsActivity, module)
                activateModule(module)
                return@launch
            }

            // 3️⃣ Échec total
            showNotConnectedDialog(module)
        }
    }

    private fun activateModule(module: EspModule) {
        modules.forEach {
            it.isActive = it.id == module.id
            Esp32Manager.update(this, it)
        }
        adapter.refresh(modules)
        loadTelegramForActiveModule()

        Toast.makeText(
            this,
            "Module sélectionné : ${module.displayName}",
            Toast.LENGTH_SHORT
        ).show()
    }

    private suspend fun testModuleConnection(module: EspModule): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val conn =
                    URL("http://${module.ip}/id")
                        .openConnection() as HttpURLConnection

                conn.connectTimeout = 800
                conn.readTimeout = 800

                val response =
                    conn.inputStream.bufferedReader().readText()

                conn.disconnect()

                response.startsWith("POMPE_NAME=") &&
                        response.removePrefix("POMPE_NAME=").trim() == module.internalName
            } catch (_: Exception) {
                false
            }
        }

    private fun showNotConnectedDialog(module: EspModule) {
        AlertDialog.Builder(this)
            .setTitle("Pompe non connectée")
            .setMessage(
                "La pompe ${module.displayName} ne répond pas.\n\n" +
                        "• Vérifiez le Wi-Fi\n" +
                        "• Ou connectez-vous au réseau : ${module.internalName}"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // ================= SCAN RÉSEAU MANUEL =================

    private fun scanForEsp32() {
        Toast.makeText(this, "Recherche des modules…", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val found = NetworkScanner.scan(this@MaterielsActivity)

            if (found.isEmpty()) {
                Toast.makeText(
                    this@MaterielsActivity,
                    "Aucun ESP32 détecté",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val existing = Esp32Manager.getAll(this@MaterielsActivity)

            // Mise à jour IP si module existant
            found.forEach { scan ->
                val existingModule =
                    existing.firstOrNull { it.internalName == scan.internalName }

                if (existingModule != null && existingModule.ip != scan.ip) {
                    existingModule.ip = scan.ip
                    Esp32Manager.update(this@MaterielsActivity, existingModule)
                }
            }

            // Nouveaux modules
            val newModules =
                found.filter { scan ->
                    existing.none { it.internalName == scan.internalName }
                }

            if (newModules.isNotEmpty()) {
                val items =
                    newModules.map { "${it.internalName} (${it.ip})" }.toTypedArray()

                AlertDialog.Builder(this@MaterielsActivity)
                    .setTitle("Nouveaux ESP32 détectés")
                    .setItems(items) { _, index ->
                        val esp = newModules[index]
                        Esp32Manager.add(
                            this@MaterielsActivity,
                            EspModule(
                                displayName = esp.internalName,
                                internalName = esp.internalName,
                                ip = esp.ip,
                                isActive = false
                            )
                        )
                        loadList()
                    }
                    .show()
            } else {
                loadList()
            }
        }
    }

    // ================= DIVERS =================

    private fun renameModule(module: EspModule) {
        val input = EditText(this).apply {
            setText(module.displayName)
        }

        AlertDialog.Builder(this)
            .setTitle("Renommer le module")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                module.displayName = input.text.toString().trim()
                Esp32Manager.update(this, module)
                loadList()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun deleteModule(module: EspModule) {
        AlertDialog.Builder(this)
            .setTitle("Supprimer")
            .setMessage("Supprimer « ${module.displayName} » ?")
            .setPositiveButton("Supprimer") { _, _ ->
                Esp32Manager.delete(this, module.id)
                loadList()
                loadTelegramForActiveModule()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
