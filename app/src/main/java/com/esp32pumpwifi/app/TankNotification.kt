package com.esp32pumpwifi.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.nio.charset.StandardCharsets

object TankNotification {

    private const val EMPTY_CHANNEL_ID = "tank_empty_alert"
    private const val LOW_CHANNEL_ID = "tank_low_alert"

    // =====================================================
    // ✅ NOM MODULE (displayName)
    // =====================================================
    private fun getModuleName(context: Context, espId: Long): String {
        return Esp32Manager.getById(context, espId)?.displayName ?: "Module"
    }

    // =====================================================
    // ✅ NOM POMPE (PERSONNALISÉ) — pompe seulement
    // =====================================================
    private fun getPumpNameOnly(context: Context, espId: Long, pumpNum: Int): String {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        return prefs.getString(
            "esp_${espId}_pump${pumpNum}_name",
            "Pompe $pumpNum"
        ) ?: "Pompe $pumpNum"
    }

    // =====================================================
    // 🚨 RÉSERVOIR VIDE
    // - Titre : "🚨 Réservoir vide — Pompe X"
    // - Texte : "Module • 0%"
    // =====================================================
    fun showTankEmpty(
        context: Context,
        espId: Long,
        pumpNum: Int
    ) {
        ensureChannels(context)

        val pumpName = getPumpNameOnly(context, espId, pumpNum)
        val moduleName = getModuleName(context, espId)

        val notification = NotificationCompat.Builder(context, EMPTY_CHANNEL_ID)
            // TODO optionnel: remplace par ton icône gyrophare custom si tu en as une
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚨 Réservoir vide — $pumpName")
            .setContentText("$moduleName • 0%")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context)
            .notify(stableNotificationId(espId, pumpNum, "EMPTY"), notification)
    }

    // =====================================================
    // ⚠️ NIVEAU BAS
    // - Titre : "⚠️ Niveau bas — Pompe X"
    // - Texte : "Module • 18%"
    // =====================================================
    fun showTankLowLevel(
        context: Context,
        espId: Long,
        pumpNum: Int,
        percent: Int
    ) {
        ensureChannels(context)

        val pumpName = getPumpNameOnly(context, espId, pumpNum)
        val moduleName = getModuleName(context, espId)

        val notification = NotificationCompat.Builder(context, LOW_CHANNEL_ID)
            // TODO optionnel: remplace par ton icône panneau attention custom si tu en as une
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("⚠️ Niveau bas — $pumpName")
            .setContentText("$moduleName • ${percent}%")
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context)
            .notify(stableNotificationId(espId, pumpNum, "LOW"), notification)
    }

    private fun stableNotificationId(espId: Long, pumpNum: Int, type: String): Int {
        val key = "$espId:$pumpNum:$type"
        val hash = key.toByteArray(StandardCharsets.UTF_8)
            .fold(0x811C9DC5.toInt()) { acc, byte ->
                (acc xor (byte.toInt() and 0xFF)) * 16777619
            }
        return hash and 0x7FFFFFFF
    }

    // =====================================================
    // 🔧 CANAUX (SON + VIBRATION)
    // =====================================================
    fun ensureChannels(context: Context) {
        createChannel(
            context,
            EMPTY_CHANNEL_ID,
            "Réservoir vide",
            NotificationManager.IMPORTANCE_HIGH
        )
        createChannel(
            context,
            LOW_CHANNEL_ID,
            "Niveau bas",
            NotificationManager.IMPORTANCE_DEFAULT
        )
    }

    private fun createChannel(
        context: Context,
        channelId: String,
        name: String,
        importance: Int
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // ⚠️ Android ne met pas à jour le nom d’un canal existant
        if (manager.getNotificationChannel(channelId) != null) return

        val soundUri =
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val channel = NotificationChannel(
            channelId,
            name,
            importance
        ).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
            setSound(soundUri, audioAttributes)
        }

        manager.createNotificationChannel(channel)
    }
}
