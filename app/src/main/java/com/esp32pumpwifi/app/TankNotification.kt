package com.esp32pumpwifi.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object TankNotification {

    private const val EMPTY_CHANNEL_ID = "tank_empty_alert"
    private const val LOW_CHANNEL_ID = "tank_low_alert"

    // =====================================================
    // 🧠 NOM COMPLET : MODULE (displayName) + POMPE
    // =====================================================
    private fun getFullPumpName(
        context: Context,
        espId: Long,
        pumpNum: Int
    ): String {

        // ✅ NOM MODULE (SOURCE OFFICIELLE)
        val moduleName =
            Esp32Manager.getById(context, espId)?.displayName
                ?: "Module"

        // ✅ NOM POMPE (PERSONNALISÉ)
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val pumpName =
            prefs.getString(
                "esp_${espId}_pump${pumpNum}_name",
                "Pompe $pumpNum"
            ) ?: "Pompe $pumpNum"

        return "$moduleName – $pumpName"
    }

    // =====================================================
    // 🚨 RÉSERVOIR VIDE
    // =====================================================
    fun showTankEmpty(
        context: Context,
        espId: Long,
        pumpNum: Int
    ) {
        createChannel(
            context,
            EMPTY_CHANNEL_ID,
            "Réservoir vide",
            NotificationManager.IMPORTANCE_HIGH
        )

        val fullName = getFullPumpName(context, espId, pumpNum)

        val notification = NotificationCompat.Builder(context, EMPTY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚨 $fullName")
            .setContentText("Réservoir vide – distribution impossible")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context)
            .notify((espId * 10 + pumpNum).toInt(), notification)
    }

    // =====================================================
    // ⚠️ NIVEAU BAS
    // =====================================================
    fun showTankLowLevel(
        context: Context,
        espId: Long,
        pumpNum: Int,
        percent: Int
    ) {
        createChannel(
            context,
            LOW_CHANNEL_ID,
            "Niveau bas",
            NotificationManager.IMPORTANCE_DEFAULT
        )

        val fullName = getFullPumpName(context, espId, pumpNum)

        val notification = NotificationCompat.Builder(context, LOW_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("⚠️ $fullName")
            .setContentText("$percent % restant")
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context)
            .notify((espId * 100 + pumpNum).toInt(), notification)
    }

    // =====================================================
    // 🔧 CANAUX (SON + VIBRATION)
    // =====================================================
    private fun createChannel(
        context: Context,
        channelId: String,
        name: String,
        importance: Int
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager

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
