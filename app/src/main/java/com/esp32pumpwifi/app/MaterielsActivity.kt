package com.esp32pumpwifi.app

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.text.InputType
import android.util.Log
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

    // 📡 Wi-Fi
    private lateinit var btnConfigureWifi: View
    private var lastWifiSsid: String? = null

    // Insets targets
    private lateinit var layoutActions: ConstraintLayout
    private lateinit var telegramScroll: androidx.core.widget.NestedScrollView

    private val PREFS_NAME = "prefs"
    private val KEY_TELEGRAM_TOGGLE_OPEN = "materiels_telegram_toggle_open"

    private var actionsBaseMarginBottom = 0
    private var telegramScrollBasePaddingBottom = 0
    private var telegramScrollImeBottom = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_materiels)

        listView = findViewById(R.id.list_modules)

        btnOpenPumps = findViewById(R.id.btn_open_pumps)
        btnOpenPlanning = findViewById(R.id.btn_open_planning)
        btnConfigureWifi = findViewById(R.id.btn_configure_wifi)

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

            telegramScrollImeBottom = bottomInset
            view.updatePadding(bottom = telegramScrollBasePaddingBottom + bottomInset)
            insets
        }

        ViewCompat.requestApplyInsets(layoutActions)
        ViewCompat.requestApplyInsets(telegramScroll)

        setupTelegramToggle()
        setupTelegramAutoScroll()

        // ➕ Scan réseau manuel
        findViewById<View>(R.id.btn_add_module).setOnClickListener {
            scanForEsp32()
        }

        // ▶️ Accéder aux pompes
        btnOpenPumps.setOnLongClickListener {

            val modules = Esp32Manager.getAll(this)
            val module = modules.firstOrNull()

            if (module == null) {
                Toast.makeText(this, "Aucun module", Toast.LENGTH_SHORT).show()
                return@setOnLongClickListener true
            }

            val moduleId = module.id

            val message = """
        🔧 TEST TELEGRAM
        Module : ${module.displayName}
        Timestamp : ${System.currentTimeMillis()}
    """.trimIndent()

            TelegramSender.sendMessage(this, moduleId, message)

            Toast.makeText(
                this,
                "Alerte Telegram test envoyée (ou mise en queue)",
                Toast.LENGTH_SHORT
            ).show()

            true
        }

        btnOpenPumps.setOnClickListener {
            val activeModule = Esp32Manager.getActive(this)
            if (activeModule == null) {
                Toast.makeText(this, "Sélectionnez d’abord un module", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, MainActivity::class.java))
        }

        // 📡 Configurer Wi-Fi
        btnConfigureWifi.setOnClickListener {
            showWifiConfigDialog()
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
        updateWifiButtonState()
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

            if (!opened) {
                telegramScroll.post {
                    telegramScroll.smoothScrollTo(0, telegramContent.top)
                }
            }
        }
    }

    private fun setupTelegramAutoScroll() {

        fun scrollToTarget(target: View) {
            val run = { ensureTelegramFieldVisible(target) }

            telegramScroll.post {
                ViewCompat.requestApplyInsets(telegramScroll)
                run()
            }

            telegramScroll.postDelayed({
                ViewCompat.requestApplyInsets(telegramScroll)
                run()
            }, 120L)
        }

        fun attach(target: View) {
            target.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) scrollToTarget(v)
            }
            target.setOnClickListener { v ->
                scrollToTarget(v)
            }
        }

        attach(editTelegramToken)
        attach(editTelegramChatId)
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun ensureTelegramFieldVisible(target: View) {
        val margin = dpToPx(24)

        val rect = Rect()
        target.getDrawingRect(rect)

        rect.top -= margin
        rect.bottom += margin

        telegramScroll.offsetDescendantRectToMyCoords(target, rect)
        telegramScroll.requestChildRectangleOnScreen(target, rect, true)
    }

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
        updateWifiButtonState()
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

            if (testModuleConnection(module)) {
                activateModule(module)
                return@launch
            }

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
        updateWifiButtonState()

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

                conn.connectTimeout = 2000
                conn.readTimeout = 3000

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

    private fun updateWifiButtonState() {
        val hasActiveModule = Esp32Manager.getActive(this) != null
        btnConfigureWifi.isEnabled = hasActiveModule
        btnConfigureWifi.visibility = if (hasActiveModule) View.VISIBLE else View.GONE
    }

    private fun showWifiSuccessDialog() {
        AlertDialog.Builder(this@MaterielsActivity)
            .setTitle("Configuration Wi-Fi")
            .setMessage(
                "Le Wi-Fi a bien été enregistré.\n\n" +
                        "1️⃣ Reconnectez votre téléphone ou tablette au Wi-Fi de votre box ou routeur.\n" +
                        "2️⃣ Revenez dans l’application, page Matériels, puis appuyez sur Rafraîchir."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showWifiConfigDialog() {
        val activeModule = Esp32Manager.getActive(this)
        if (activeModule == null) {
            Toast.makeText(this, "Sélectionnez un module actif", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_configure_wifi, null)
        val editSsid = dialogView.findViewById<EditText>(R.id.edit_wifi_ssid)
        val editPassword = dialogView.findViewById<EditText>(R.id.edit_wifi_password)
        val checkboxShowPassword = dialogView.findViewById<CheckBox>(R.id.checkbox_show_password)
        val errorText = dialogView.findViewById<TextView>(R.id.text_wifi_error)
        val progressLayout = dialogView.findViewById<View>(R.id.layout_wifi_progress)
        val progressText = dialogView.findViewById<TextView>(R.id.text_wifi_progress)

        lastWifiSsid?.let { editSsid.setText(it) }

        checkboxShowPassword.setOnCheckedChangeListener { _, isChecked ->
            val selectionStart = editPassword.selectionStart
            val selectionEnd = editPassword.selectionEnd
            editPassword.inputType =
                if (isChecked) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            editPassword.setSelection(selectionStart, selectionEnd)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Configurer Wi-Fi")
            .setView(dialogView)
            .setPositiveButton("Envoyer", null)
            .setNegativeButton("Annuler", null)
            .create()

        dialog.setOnShowListener {
            val sendButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

            sendButton.setOnClickListener {
                val ssid = editSsid.text.toString().trim()
                val password = editPassword.text.toString()

                if (ssid.isEmpty()) {
                    editSsid.error = "SSID requis"
                    return@setOnClickListener
                }

                lastWifiSsid = ssid
                errorText.visibility = View.GONE
                progressLayout.visibility = View.VISIBLE
                progressText.text = "Vérification…"
                sendButton.isEnabled = false

                lifecycleScope.launch {
                    val module = Esp32Manager.getActive(this@MaterielsActivity)
                    if (module == null) {
                        errorText.text = "Module injoignable"
                        errorText.visibility = View.VISIBLE
                        progressLayout.visibility = View.GONE
                        sendButton.isEnabled = true
                        return@launch
                    }

                    val reachable = ensureActiveModuleReachable(module)
                    if (reachable == null) {
                        errorText.text = "Module injoignable"
                        errorText.visibility = View.VISIBLE
                        progressLayout.visibility = View.GONE
                        sendButton.isEnabled = true
                        return@launch
                    }

                    progressText.text = "Envoi…"
                    val result = NetworkHelper.postSaveWifi(
                        baseIp = reachable.ip,
                        ssid = ssid,
                        password = password
                    )

                    when (result) {
                        is WifiSaveResult.Success -> {
                            dialog.dismiss()
                            showWifiSuccessDialog()
                        }

                        is WifiSaveResult.ProbableSuccess -> {
                            Log.w("WIFI", "Succès probable (ESP32 reboot trop rapide)")
                            dialog.dismiss()
                            showWifiSuccessDialog()
                        }

                        is WifiSaveResult.Failure -> {
                            errorText.text = result.error?.message ?: "Erreur lors de l’envoi"
                            errorText.visibility = View.VISIBLE
                            progressLayout.visibility = View.GONE
                            sendButton.isEnabled = true
                        }
                    }
                }
            }
        }

        dialog.show()
    }

    private suspend fun ensureActiveModuleReachable(module: EspModule): EspModule? {
        if (testModuleConnection(module)) {
            return module
        }

        val found = NetworkScanner.scan(this@MaterielsActivity)
        val match = found.firstOrNull { it.internalName == module.internalName }
        if (match != null) {
            module.ip = match.ip
            Esp32Manager.update(this@MaterielsActivity, module)
            if (testModuleConnection(module)) {
                return module
            }
        }

        return null
    }

    // ================= SCAN RÉSEAU MANUEL =================

    private fun scanForEsp32() {

        val btnScan = findViewById<View>(R.id.btn_add_module)
        val icon = findViewById<ImageView>(R.id.icon_refresh)

        fun startIconSpin() {
            btnScan.isEnabled = false

            icon.animate().cancel()
            icon.rotation = 0f

            icon.animate()
                .rotationBy(360f)
                .setDuration(800)
                .withEndAction {
                    if (!btnScan.isEnabled) {
                        icon.rotation = 0f
                        startIconSpin()
                    }
                }
                .start()
        }

        fun stopIconSpin() {
            icon.animate().cancel()
            icon.rotation = 0f
            btnScan.isEnabled = true
        }

        startIconSpin()

        Toast.makeText(this, "Recherche des modules…", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {

            val found = NetworkScanner.scan(this@MaterielsActivity)

            stopIconSpin()

            if (found.isEmpty()) {
                Toast.makeText(
                    this@MaterielsActivity,
                    "Aucun ESP32 détecté",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val existing = Esp32Manager.getAll(this@MaterielsActivity)

            found.forEach { scan ->
                val existingModule =
                    existing.firstOrNull { it.internalName == scan.internalName }

                if (existingModule != null && existingModule.ip != scan.ip) {
                    existingModule.ip = scan.ip
                    Esp32Manager.update(this@MaterielsActivity, existingModule)
                }
            }

            val newModules =
                found.filter { scan ->
                    existing.none { it.internalName == scan.internalName }
                }

            if (newModules.isNotEmpty()) {
                val items =
                    newModules.map { "${it.internalName} (${it.ip})" }.toTypedArray()

                AlertDialog.Builder(this@MaterielsActivity)
                    .setTitle("Nouvelle pompe détectée")
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
                val trimmedName = input.text.toString().trim()
                module.displayName =
                    if (trimmedName.isBlank()) module.internalName else trimmedName
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
