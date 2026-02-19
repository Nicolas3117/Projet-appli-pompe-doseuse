package com.esp32pumpwifi.app

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

sealed class WifiSaveResult {
    object Success : WifiSaveResult()
    object ProbableSuccess : WifiSaveResult()
    data class Failure(val error: Throwable?) : WifiSaveResult()
}

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

    // ✅ Popup quand pompe occupée (BUSY)
    private fun showPumpBusyDialog(context: Context, pump: Int) {
        CoroutineScope(Dispatchers.Main).launch {
            AlertDialog.Builder(context)
                .setTitle("Pompe occupée")
                .setMessage(
                    "Une distribution est déjà en cours sur ${getPumpDisplayName(context, pump)}.\n\n" +
                            "Le volume demandé n’a pas été distribué."
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    // ✅ Popup pour erreurs de programmation (message du firmware)
    private fun showProgramErrorDialog(context: Context, details: String) {
        CoroutineScope(Dispatchers.Main).launch {
            AlertDialog.Builder(context)
                .setTitle("Programmation refusée")
                .setMessage(
                    "L’ESP32 a refusé la programmation.\n\n" +
                            details.trim()
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    // ✅ Récupère un nom lisible (si custom) sans casser
    private fun getPumpDisplayName(context: Context, pump: Int): String {
        val moduleId = Esp32Manager.getActive(context)?.id // Long
        return getPumpDisplayName(context, moduleId, pump)
    }

    // ✅ FIX: moduleId est Long? (EspModule.id)
    private fun getPumpDisplayName(context: Context, moduleId: Long?, pump: Int): String {
        val fallback = "Pompe $pump"
        if (moduleId == null) return fallback

        return try {
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            val key = "esp_${moduleId}_pump${pump}_name"
            prefs.getString(key, fallback)
                ?.trim()
                .takeUnless { it.isNullOrBlank() }
                ?: fallback
        } catch (_: Exception) {
            fallback
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

        CoroutineScope(Dispatchers.IO).launch {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(urlString).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 1500
                conn.readTimeout = 1500

                val responseCode = conn.responseCode
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }.trim()

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("HTTP $responseCode")
                }

                if (responseText.equals("BUSY", ignoreCase = true)) {
                    showPumpBusyDialog(context, pump)
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    val moduleId = Esp32Manager.getActive(context)?.id // Long
                    val pumpName = getPumpDisplayName(context, moduleId, pump)
                    showSuccessToast(context, "Dosage envoyé à $pumpName")
                }

            } catch (e: Exception) {
                showNoReturnToast(context)
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) { }
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
        durationMs: Int,
        onResult: (Boolean) -> Unit = {}
    ) {
        val message = "${pump}_${durationMs}"
        val encodedMessage = URLEncoder.encode(message, "UTF-8")
        val urlString = "http://$ip/manual_ms?message=$encodedMessage"

        showSendingToast(context)

        CoroutineScope(Dispatchers.IO).launch {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(urlString).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 1500
                conn.readTimeout = 1500

                val responseCode = conn.responseCode
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }.trim()

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("HTTP $responseCode")
                }

                if (responseText.equals("BUSY", ignoreCase = true)) {
                    showPumpBusyDialog(context, pump)
                    withContext(Dispatchers.Main) { onResult(false) }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    val moduleId = Esp32Manager.getActive(context)?.id // Long
                    val pumpName = getPumpDisplayName(context, moduleId, pump)
                    showSuccessToast(context, "Dosage envoyé à $pumpName")
                }
                withContext(Dispatchers.Main) { onResult(true) }

            } catch (e: Exception) {
                showNoReturnToast(context)
                withContext(Dispatchers.Main) { onResult(false) }
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) { }
            }
        }
    }

    // =====================================================
    // 📅 PROGRAMMATION ESP32 — /program_ms
    // =====================================================
    fun sendProgramMs(
        context: Context,
        ip: String,
        message: String,
        onSuccess: () -> Unit = {}
    ) {
        if (message.length != 576) {
            Toast.makeText(
                context,
                "Erreur interne : message invalide (${message.length})",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val encodedMessage = URLEncoder.encode(message, "UTF-8")
        val urlString = "http://$ip/program_ms?message=$encodedMessage"


        var idx = 0
        var lineNum = 1
        while (idx + 12 <= message.length) {
            val line = message.substring(idx, idx + 12)
            idx += 12
            lineNum += 1
        }

        CoroutineScope(Dispatchers.IO).launch {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(urlString).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 3000
                conn.readTimeout = 3000

                val responseCode = conn.responseCode

                val responseText = try {
                    val stream =
                        if (responseCode in 200..299) conn.inputStream else conn.errorStream
                    stream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Exception) {
                    ""
                }


                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val r = responseText.trim()
                    if (r.equals("OK", ignoreCase = true) || r.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            showSuccessToast(context, "Programmation envoyée")
                            onSuccess()
                        }
                        return@launch
                    }

                    showProgramErrorDialog(context, "Réponse inattendue : $r")
                    return@launch
                }

                val details = responseText.trim()
                if (details.isNotEmpty()) {
                    showProgramErrorDialog(context, details)
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "Erreur réseau (programmation)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

            } catch (e: Exception) {

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
                } catch (_: Exception) { }
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

    // ✅ postSaveWifi : HTTP 200 = succès, certaines erreurs réseau = "probable succès" (reboot ESP32).
    suspend fun postSaveWifi(baseIp: String, ssid: String, password: String): WifiSaveResult =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("http://$baseIp/save_wifi")
                val body =
                    "ssid=${URLEncoder.encode(ssid, "UTF-8")}" +
                            "&password=${URLEncoder.encode(password, "UTF-8")}"

                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 8000
                conn.readTimeout = 12000
                conn.doOutput = true
                conn.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8"
                )

                conn.outputStream.use { output ->
                    output.write(body.toByteArray(Charsets.UTF_8))
                }

                val responseCode = conn.responseCode
                val responseText = try {
                    val stream =
                        if (responseCode in 200..299) conn.inputStream else conn.errorStream
                    stream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Exception) {
                    ""
                }

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    WifiSaveResult.Success
                } else {
                    WifiSaveResult.Failure(
                        IllegalStateException("Erreur HTTP $responseCode: ${responseText.trim()}")
                    )
                }
            } catch (e: Exception) {
                if (e is SocketTimeoutException || e is EOFException || e is IOException) {
                    WifiSaveResult.ProbableSuccess
                } else if ((e.message ?: "").contains("broken pipe", ignoreCase = true)) {
                    WifiSaveResult.ProbableSuccess
                } else {
                    WifiSaveResult.Failure(e)
                }
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) { }
            }
        }
}
