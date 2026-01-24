package com.esp32pumpwifi.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object TelegramSender {

    private const val TAG = "TELEGRAM"
    private const val PREFS_NAME = "prefs"

    private data class TelegramConfig(
        val token: String,
        val chatId: String
    )

    fun isConfigured(context: Context, espId: Long): Boolean =
        getConfig(context, espId) != null

    // ============================================================
    // 🧠 NOM COMPLET : MODULE + POMPE
    // ============================================================
    private fun getFullPumpName(
        context: Context,
        espId: Long,
        pumpNum: Int
    ): String {

        val module =
            Esp32Manager.getAll(context)
                .firstOrNull { it.id == espId }

        val moduleName =
            module?.displayName ?: "Module"

        val prefs =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val pumpName =
            prefs.getString(
                "esp_${espId}_pump${pumpNum}_name",
                "Pompe $pumpNum"
            ) ?: "Pompe $pumpNum"

        return "$moduleName – $pumpName"
    }

    fun buildLowLevelAlert(
        context: Context,
        espId: Long,
        pumpNum: Int,
        percent: Int,
        timestamp: Long = System.currentTimeMillis()
    ): TelegramAlert {
        val fullName = getFullPumpName(context, espId, pumpNum)
        return TelegramAlert.lowLevel(
            espId = espId,
            pumpNum = pumpNum,
            percent = percent,
            fullPumpName = fullName,
            timestamp = timestamp
        )
    }

    fun buildEmptyTankAlert(
        context: Context,
        espId: Long,
        pumpNum: Int,
        timestamp: Long = System.currentTimeMillis()
    ): TelegramAlert {
        val fullName = getFullPumpName(context, espId, pumpNum)
        return TelegramAlert.emptyTank(
            espId = espId,
            pumpNum = pumpNum,
            fullPumpName = fullName,
            timestamp = timestamp
        )
    }

    // ============================================================
    // 📡 ENVOI BLOQUANT TELEGRAM (utilisé par la queue + worker)
    // ============================================================
    internal fun sendAlertBlocking(
        context: Context,
        alert: TelegramAlert
    ): Boolean {
        val config = getConfig(context, alert.espId)
        if (config == null) {
            Log.w(TAG, "Telegram non configuré (token/chatId manquant)")
            return false
        }

        var conn: HttpURLConnection? = null
        return try {
            val encodedMessage =
                URLEncoder.encode(
                    alert.message,
                    StandardCharsets.UTF_8.toString()
                )

            // ✅ GET conservé (simple), mais on lit la réponse JSON et on vérifie "ok": true
            val url =
                "https://api.telegram.org/bot${config.token}/sendMessage" +
                        "?chat_id=${config.chatId}" +
                        "&text=$encodedMessage"

            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
                useCaches = false
                setRequestProperty("Connection", "close")
            }

            val code = conn.responseCode

            // Lire le body (inputStream si 2xx sinon errorStream)
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            // Telegram renvoie du JSON avec { "ok": true/false, ... }
            val ok = try {
                if (body.isBlank()) false
                else JSONObject(body).optBoolean("ok", false)
            } catch (_: Exception) {
                false
            }

            if (ok) {
                Log.i(TAG, "Message Telegram envoyé (HTTP=$code)")
                true
            } else {
                Log.w(TAG, "Échec Telegram (HTTP=$code)")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur Telegram (${e.javaClass.simpleName})")
            false
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    // ============================================================
    // 📡 ENVOI BRUT TELEGRAM (API existante conservée)
    // ============================================================
    fun sendMessage(
        context: Context,
        espId: Long,
        message: String
    ) {
        if (!isConfigured(context, espId)) {
            Log.w(TAG, "Telegram non configuré (token/chatId manquant)")
            return
        }

        val timestamp = System.currentTimeMillis()
        val alert = TelegramAlert(
            id = "GENERIC:$espId:-1:$timestamp",
            espId = espId,
            pumpNum = -1,
            type = "GENERIC",
            message = message,
            timestamp = timestamp
        )

        TelegramAlertQueue.trySendNowOrQueue(context, alert)
    }

    // ============================================================
    // ⚠️ NIVEAU BAS
    // ============================================================
    fun sendLowLevel(
        context: Context,
        espId: Long,
        pumpNum: Int,
        percent: Int
    ) {
        val alert = buildLowLevelAlert(context, espId, pumpNum, percent)
        TelegramAlertQueue.trySendNowOrQueue(context, alert)
    }

    // ============================================================
    // 🚨 RÉSERVOIR VIDE
    // ============================================================
    fun sendEmptyTank(
        context: Context,
        espId: Long,
        pumpNum: Int
    ) {
        val alert = buildEmptyTankAlert(context, espId, pumpNum)
        TelegramAlertQueue.trySendNowOrQueue(context, alert)
    }

    private fun getConfig(context: Context, espId: Long): TelegramConfig? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val token = prefs.getString("esp_${espId}_telegram_token", null)
        val chatId = prefs.getString("esp_${espId}_telegram_chat_id", null)

        if (token.isNullOrEmpty() || chatId.isNullOrEmpty()) {
            return null
        }

        return TelegramConfig(token = token, chatId = chatId)
    }
}
