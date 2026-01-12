package com.esp32pumpwifi.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object TelegramSender {

    private const val TAG = "TELEGRAM"

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
            context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

        val pumpName =
            prefs.getString(
                "esp_${espId}_pump${pumpNum}_name",
                "Pompe $pumpNum"
            ) ?: "Pompe $pumpNum"

        return "$moduleName – $pumpName"
    }

    // ============================================================
    // 📡 ENVOI BRUT TELEGRAM
    // ============================================================
    fun sendMessage(
        context: Context,
        espId: Long,
        message: String
    ) {

        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

        val token =
            prefs.getString("esp_${espId}_telegram_token", null)

        val chatId =
            prefs.getString("esp_${espId}_telegram_chat_id", null)

        if (token.isNullOrEmpty() || chatId.isNullOrEmpty()) {
            Log.w(TAG, "Telegram non configuré (token/chatId manquant)")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val encodedMessage =
                    URLEncoder.encode(
                        message,
                        StandardCharsets.UTF_8.toString()
                    )

                val url =
                    "https://api.telegram.org/bot$token/sendMessage" +
                            "?chat_id=$chatId" +
                            "&text=$encodedMessage"

                val conn =
                    URL(url).openConnection() as HttpURLConnection

                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "GET"

                val code = conn.responseCode
                conn.disconnect()

                Log.i(TAG, "Message Telegram envoyé (code=$code)")

            } catch (e: Exception) {
                Log.e(TAG, "Erreur Telegram : ${e.message}", e)
            }
        }
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
        val fullName =
            getFullPumpName(context, espId, pumpNum)

        sendMessage(
            context,
            espId,
            "⚠️ NIVEAU BAS\n$fullName\n$percent % restant"
        )
    }

    // ============================================================
    // 🚨 RÉSERVOIR VIDE
    // ============================================================
    fun sendEmptyTank(
        context: Context,
        espId: Long,
        pumpNum: Int
    ) {
        val fullName =
            getFullPumpName(context, espId, pumpNum)

        sendMessage(
            context,
            espId,
            "🚨 RÉSERVOIR VIDE\n$fullName\nDistribution impossible"
        )
    }
}
