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

    // =====================================================
    // 🚿 DOSAGE MANUEL (INCHANGÉ – OK)
    // =====================================================
    fun sendManualCommand(
        context: Context,
        ip: String,
        pump: Int,
        seconds: Int
    ) {
        val message = pump.toString() + String.format("%08d", seconds)
        val urlString = "http://$ip/manual?message=$message"

        Toast.makeText(context, "Envoi…", Toast.LENGTH_SHORT).show()
        Log.e("ESP32_MANUAL", "➡️ $urlString")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL(urlString).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 1500
                conn.readTimeout = 1500

                conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                withContext(Dispatchers.Main) {
                    showSuccessToast(
                        context,
                        "Commande envoyée à la pompe $pump"
                    )
                }

            } catch (e: Exception) {
                Log.e("ESP32_MANUAL", "❌ Erreur envoi", e)

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "⚠️ Aucun retour du module",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // =====================================================
    // 📅 PROGRAMMATION ESP32 (VERSION CORRIGÉE)
    // =====================================================
    fun sendProgram(
        context: Context,
        ip: String,
        message: String,
        onSuccess: () -> Unit = {}
    ) {

        // 🔒 SÉCURITÉ ABSOLUE
        if (message.length != 432) {
            Log.e(
                "ESP32_PROGRAM",
                "❌ MESSAGE INVALIDE : ${message.length} chars (attendu 432)"
            )

            Toast.makeText(
                context,
                "Erreur interne : message invalide (${message.length})",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // ✅ URL-ENCODAGE (CRITIQUE)
        val encodedMessage = URLEncoder.encode(message, "UTF-8")
        val urlString = "http://$ip/program?message=$encodedMessage"

        // ================= LOGS ULTRA CLAIRS =================
        Log.e("ESP32_PROGRAM", "================ PROGRAM SEND ================")
        Log.e("ESP32_PROGRAM", "IP       = $ip")
        Log.e("ESP32_PROGRAM", "LENGTH   = ${message.length}")
        Log.e("ESP32_PROGRAM", "URL LEN  = ${urlString.length}")

        for (i in 0 until message.length step 9) {
            val line = message.substring(i, i + 9)
            Log.e(
                "ESP32_PROGRAM",
                "L${(i / 9) + 1}. '$line'"
            )
        }

        Log.e("ESP32_PROGRAM", "================================================")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL(urlString).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 3000
                conn.readTimeout = 3000

                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                Log.e("ESP32_PROGRAM", "ESP32 RESPONSE = '$response'")

                withContext(Dispatchers.Main) {
                    showSuccessToast(
                        context,
                        "Programmation envoyée à l’ESP32"
                    )
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
            }
        }
    }

    // =====================================================
    // 🟢 TOAST SUCCÈS
    // =====================================================
    fun showSuccessToast(context: Context, message: String) {
        val layout = LayoutInflater.from(context)
            .inflate(R.layout.toast_success, null)

        val text = layout.findViewById<TextView>(R.id.toast_text)
        text.text = message

        Toast(context).apply {
            duration = Toast.LENGTH_SHORT
            view = layout
            show()
        }
    }
}
