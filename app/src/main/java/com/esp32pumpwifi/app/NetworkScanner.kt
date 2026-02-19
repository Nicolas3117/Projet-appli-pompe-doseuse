package com.esp32pumpwifi.app

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object NetworkScanner {

    // ============================================================
    // 📱 IP DU TÉLÉPHONE
    // ============================================================
    private fun getDeviceIp(context: Context): String? {
        return try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager

            val ipInt = wm.connectionInfo.ipAddress
            if (ipInt == 0) return null

            String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
        } catch (_: Exception) {
            null
        }
    }

    // ============================================================
    // 🌐 TEST D’UNE IP ESP32
    // ============================================================
    private fun testEsp32Ip(ip: String, timeout: Int): ScannedEsp32? {
        return try {
            val url = URL("http://$ip/id")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = timeout
                readTimeout = timeout
                requestMethod = "GET"
            }

            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            if (response.startsWith("POMPE_NAME=")) {
                val internal = response.removePrefix("POMPE_NAME=").trim()
                ScannedEsp32(internalName = internal, ip = ip)
            } else null

        } catch (_: Exception) {
            null
        }
    }

    // ============================================================
    // 🔍 SCAN COMPLET AP + STA
    // ============================================================
    suspend fun scan(
        context: Context,
        timeout: Int = 700
    ): List<ScannedEsp32> = withContext(Dispatchers.IO) {

        val foundMap = ConcurrentHashMap<String, ScannedEsp32>()
        val phoneIp = getDeviceIp(context)


        // =====================================================
        // 🟦 MODE AP (téléphone connecté à la pompe)
        // =====================================================
        if (phoneIp != null && phoneIp.startsWith("192.168.4.")) {


            // 👉 on teste 192.168.4.1 à 192.168.4.5 (safe & rapide)
            for (host in 1..5) {
                val ip = "192.168.4.$host"
                val esp = testEsp32Ip(ip, timeout)
                if (esp != null) {
                    foundMap[esp.internalName] = esp
                }
            }

            return@withContext foundMap.values.toList()
        }

        // =====================================================
        // 🟩 MODE STA (réseau local)
        // =====================================================
        if (phoneIp != null && phoneIp.contains(".")) {

            val subnet = phoneIp.substringBeforeLast(".") + "."

            coroutineScope {
                (1..254).map { host ->
                    async {
                        val ip = "$subnet$host"
                        val esp = testEsp32Ip(ip, timeout)
                        if (esp != null) {
                            foundMap[esp.internalName] = esp
                        }
                    }
                }.awaitAll()
            }
        }

        foundMap.values.toList()
    }
}

// ============================================================
// 📦 STRUCTURE DE RÉSULTAT
// ============================================================
data class ScannedEsp32(
    val internalName: String,
    val ip: String
)
