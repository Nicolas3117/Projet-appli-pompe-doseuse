package com.esp32pumpwifi.app

import android.content.Context

/**
 * Gestion centralisée des niveaux de réservoirs
 * SOURCE UNIQUE DE VÉRITÉ
 *
 * ➜ Manuel + automatique
 * ➜ Alertes déclenchées ici
 * ➜ Reset complet fiable
 */
object TankManager {

    data class TankLevel(
        val capacityMl: Int,
        val remainingMl: Float,
        val percent: Int
    )

    // ------------------------------------------------------------------
    // ➖ DÉCRÉMENT DU RÉSERVOIR (MANUEL + PROGRAMMATION)
    // ------------------------------------------------------------------
    fun decrement(
        context: Context,
        espId: Long,
        pumpNum: Int,
        volumeMl: Float
    ) {
        if (volumeMl <= 0f) return

        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

        val capacityKey = "esp_${espId}_pump${pumpNum}_tank_capacity"
        val remainingKey = "esp_${espId}_pump${pumpNum}_tank_remaining"
        val lowAlertKey = "esp_${espId}_pump${pumpNum}_low_alert_sent"
        val emptyAlertKey = "esp_${espId}_pump${pumpNum}_empty_alert_sent"
        val thresholdKey = "esp_${espId}_pump${pumpNum}_low_threshold"

        val capacity = prefs.getInt(capacityKey, 0)
        if (capacity <= 0) return

        val remainingBefore =
            prefs.getFloat(remainingKey, capacity.toFloat())

        // Déjà vide → on bloque le décrément
        if (remainingBefore <= 0f) return

        val newRemaining =
            (remainingBefore - volumeMl).coerceAtLeast(0f)

        // 💾 Sauvegarde niveau
        prefs.edit()
            .putFloat(remainingKey, newRemaining)
            .apply()

        // ------------------------------------------------------------------
        // 🔔 ALERTES (NIVEAU BAS / VIDE)
        // ------------------------------------------------------------------
        val lowAlertSent =
            prefs.getBoolean(lowAlertKey, false)

        val emptyAlertSent =
            prefs.getBoolean(emptyAlertKey, false)

        val thresholdPercent =
            prefs.getInt(thresholdKey, 20)

        val (newLow, newEmpty) =
            TankAlertManager.checkAndNotify(
                context = context,
                espId = espId,
                pumpNum = pumpNum,
                remainingMl = newRemaining,
                capacityMl = capacity,
                thresholdPercent = thresholdPercent,
                lowAlertSent = lowAlertSent,
                emptyAlertSent = emptyAlertSent
            )

        prefs.edit()
            .putBoolean(lowAlertKey, newLow)
            .putBoolean(emptyAlertKey, newEmpty)
            .apply()
    }

    // ------------------------------------------------------------------
    // 🔄 RESET RÉSERVOIR (BIDON REMPLI)
    // ------------------------------------------------------------------
    fun resetTank(
        context: Context,
        espId: Long,
        pumpNum: Int
    ) {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

        val capacityKey = "esp_${espId}_pump${pumpNum}_tank_capacity"
        val remainingKey = "esp_${espId}_pump${pumpNum}_tank_remaining"
        val lowAlertKey = "esp_${espId}_pump${pumpNum}_low_alert_sent"
        val emptyAlertKey = "esp_${espId}_pump${pumpNum}_empty_alert_sent"

        // 🔑 TRÈS IMPORTANT : réarme le recalcul automatique
        val lastProcessedKey =
            "esp_${espId}_pump${pumpNum}_last_processed_time"

        val capacity = prefs.getInt(capacityKey, 0)
        if (capacity <= 0) return

        prefs.edit()
            .putFloat(remainingKey, capacity.toFloat())
            .putBoolean(lowAlertKey, false)
            .putBoolean(emptyAlertKey, false)
            .putLong(lastProcessedKey, System.currentTimeMillis())
            .apply()
    }

    // ------------------------------------------------------------------
    // 📖 LECTURE DU NIVEAU (UI & LOGIQUE)
    // ------------------------------------------------------------------
    fun getTankLevel(
        context: Context,
        espId: Long,
        pumpNum: Int
    ): TankLevel {

        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

        val capacity =
            prefs.getInt(
                "esp_${espId}_pump${pumpNum}_tank_capacity",
                0
            )

        val remaining =
            prefs.getFloat(
                "esp_${espId}_pump${pumpNum}_tank_remaining",
                capacity.toFloat()
            ).coerceAtLeast(0f)

        val percent =
            if (capacity > 0)
                ((remaining / capacity) * 100).toInt()
            else 0

        return TankLevel(
            capacityMl = capacity,
            remainingMl = remaining,
            percent = percent
        )
    }
}
