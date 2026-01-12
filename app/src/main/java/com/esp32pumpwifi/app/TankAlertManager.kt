package com.esp32pumpwifi.app

import android.content.Context
import kotlin.math.roundToInt

object TankAlertManager {

    /**
     * @return Pair(lowAlertSent, emptyAlertSent)
     */
    fun checkAndNotify(
        context: Context,
        espId: Long,
        pumpNum: Int,
        remainingMl: Float,
        capacityMl: Int,
        thresholdPercent: Int,
        lowAlertSent: Boolean,
        emptyAlertSent: Boolean
    ): Pair<Boolean, Boolean> {

        if (capacityMl <= 0) {
            return Pair(lowAlertSent, emptyAlertSent)
        }

        val percent =
            ((remainingMl / capacityMl) * 100f)
                .roundToInt()
                .coerceIn(0, 100)

        // =====================================================
        // 🚨 RÉSERVOIR VIDE (PRIORITAIRE)
        // =====================================================
        if (percent <= 0 && !emptyAlertSent) {

            // 🔔 Notification Android
            TankNotification.showTankEmpty(
                context = context,
                espId = espId,
                pumpNum = pumpNum
            )

            // 📲 Telegram (AUTOMATIQUE)
            TelegramSender.sendEmptyTank(
                context = context,
                espId = espId,
                pumpNum = pumpNum
            )

            return Pair(
                lowAlertSent, // on respecte l’état réel
                true
            )
        }

        // =====================================================
        // ⚠️ NIVEAU BAS
        // =====================================================
        if (percent <= thresholdPercent && !lowAlertSent) {

            // 🔔 Notification Android
            TankNotification.showTankLowLevel(
                context = context,
                espId = espId,
                pumpNum = pumpNum,
                percent = percent
            )

            // 📲 Telegram (AUTOMATIQUE)
            TelegramSender.sendLowLevel(
                context = context,
                espId = espId,
                pumpNum = pumpNum,
                percent = percent
            )

            return Pair(
                true,
                emptyAlertSent
            )
        }

        // =====================================================
        // 🔕 RIEN À FAIRE
        // =====================================================
        return Pair(lowAlertSent, emptyAlertSent)
    }
}
