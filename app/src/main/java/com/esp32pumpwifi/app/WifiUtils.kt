package com.esp32pumpwifi.app

import android.content.Context
import android.net.wifi.WifiManager

object WifiUtils {

    /**
     * 📡 Retourne l'adresse IP de la passerelle Wi-Fi
     *
     * - Mode STA → IP de la box (ex: 192.168.1.1)
     * - Mode AP ESP32 → 192.168.4.1
     */
    fun getGatewayIp(context: Context): String? {
        return try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager

            val gateway = wm.dhcpInfo.gateway
            if (gateway == 0) return null

            val ip = String.format(
                "%d.%d.%d.%d",
                gateway and 0xff,
                gateway shr 8 and 0xff,
                gateway shr 16 and 0xff,
                gateway shr 24 and 0xff
            )

            ip

        } catch (e: Exception) {
            null
        }
    }
}
