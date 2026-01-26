package com.esp32pumpwifi.app

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object NetworkHelper {

    // -----------------------------------------------------
    // Utilitaires UI (garantit l’exécution sur le thread UI)
    // -----------------------------------------------------
    private fun showSendingToast(context: Context) {
        CoroutineScope(Dispatchers.Main).launch {
            Toast.makeText(context, "Envoi…", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showNoReturnToast(context: Context) {
        CoroutineScope(Dispatchers.Main).launch {
            Toast.makeText(context, "⚠️ Aucun retour du module", Toast.LENGTH_LONG).show()
        }
    }

    // =====================================================
    // 🚿 DOSAGE MANUEL (SECONDES) — /manual
    // =====================================================
    fun sendManualCommand(
        context: Context,
        ip: String,
        pump: Int,
        seconds: Int
    ) {
        val message = "${pump}_${seconds}"
        val encodedMessage = URLEncoder.encode(message, "UTF-8")
        val urlString = "http://$ip/manual?message=$encodedMessage"

        showSendingToast(context)
        Log.e("ESP32_MANUAL", "➡️ $urlString")

        CoroutineScope(Dispatchers.IO).launch {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(urlString).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 1500
                conn.readTimeout = 1500

                val responseCode = conn.responseCode
                conn.inputStream.bufferedReader().use { it.readText() }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("HTTP $responseCode")
                }

                withContext(Dispatchers.Main) {
                    showSuccessToast(context, "Commande envoyée à la pompe $pump")
                }

            } catch (e: Exception) {
                Log.e("ESP32_MANUAL", "❌ Erreur envoi", e)
                showNoReturnToast(context)
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) {
                }
            }
        }
    }

    // =====================================================
    // 🚿 DOSAGE MANUEL (MILLISECONDES) — /manual_ms
    // =====================================================
    fun sendManualCommandMs(
        context: Context,
        ip: String,
        pump: Int,
        durationMs: Int
    ) {
        val message = "${pump}_${durationMs}"
        val encodedMessage = URLEncoder.encode(message, "UTF-8")
        val urlString = "http://$ip/manual_ms?message=$encodedMessage"

        showSendingToast(context)
        Log.e("ESP32_MANUAL_MS", "➡️ $urlString")

        CoroutineScope(Dispatchers.IO).launch {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(urlString).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 1500
                conn.readTimeout = 1500

                val responseCode = conn.responseCode
                conn.inputStream.bufferedReader().use { it.readText() }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("HTTP $responseCode")
                }

                withContext(Dispatchers.Main) {
                    showSuccessToast(context, "Commande envoyée à la pompe $pump")
                }

            } catch (e: Exception) {
                Log.e("ESP32_MANUAL_MS", "❌ Erreur envoi", e)
                showNoReturnToast(context)
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) {
                }
            }
        }
    }

    // =====================================================
    // 📅 PROGRAMMATION ESP32 (inchangé, juste safe)
    // =====================================================
    fun sendProgram(
        context: Context,
        ip: String,
        message: String,
        onSuccess: () -> Unit = {}
    ) {
        // 🔒 SÉCURITÉ
        if (message.length != 432) {
            Log.e("ESP32_PROGRAM", "❌ MESSAGE INVALIDE : ${message.length} chars (attendu 432)")
            Toast.makeText(
                context,
                "Erreur interne : message invalide (${message.length})",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val encodedMessage = URLEncoder.encode(message, "UTF-8")
        val urlString = "http://$ip/program?message=$encodedMessage"

        Log.e("ESP32_PROGRAM", "================ PROGRAM SEND ================")
        Log.e("ESP32_PROGRAM", "IP       = $ip")
        Log.e("ESP32_PROGRAM", "LENGTH   = ${message.length}")
        Log.e("ESP32_PROGRAM", "URL LEN  = ${urlString.length}")

        var idx = 0
        var lineNum = 1
        while (idx + 9 <= message.length) {
            val line = message.substring(idx, idx + 9)
            Log.e("ESP32_PROGRAM", "L$lineNum. '$line'")
            idx += 9
            lineNum += 1
        }
        Log.e("ESP32_PROGRAM", "================================================")

        CoroutineScope(Dispatchers.IO).launch {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(urlString).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 3000
                conn.readTimeout = 3000

                val responseCode = conn.responseCode
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                Log.e("ESP32_PROGRAM", "ESP32 RESPONSE = '$response'")

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("HTTP $responseCode")
                }

                withContext(Dispatchers.Main) {
                    showSuccessToast(context, "Programmation envoyée à l’ESP32")
                    onSuccess()
                }

            } catch (e: Exception) {
                Log.e("ESP32_PROGRAM", "❌ ERREUR ENVOI PROGRAM", e)

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Erreur réseau (programmation)",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) {
                }
            }
        }
    }

    // =====================================================
    // 🟢 TOAST SUCCÈS (custom)
    // =====================================================
    fun showSuccessToast(context: Context, message: String) {
        val layout = LayoutInflater.from(context).inflate(R.layout.toast_success, null)
        val text = layout.findViewById<TextView>(R.id.toast_text)
        text.text = message

        Toast(context).apply {
            duration = Toast.LENGTH_SHORT
            view = layout
            show()
        }
    }
}
