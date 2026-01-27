package com.esp32pumpwifi.app

import android.content.Context
import kotlin.math.floor

/**
 * Estimation du nombre de jours restants avant réservoir vide
 *
 * ⚠️ IMPORTANT
 * - AUCUNE notification ici
 * - AUCUNE logique d’alerte
 * - null = estimation impossible ou non pertinente
 */
object TankDaysEstimator {

    fun estimateDaysRemaining(
        context: Context,
        espId: Long,
        pumpNum: Int,
        remainingMl: Float
    ): Int? {

        // 🔴 Réservoir vide → aucune estimation pertinente
        if (remainingMl <= 0f) return null

        val prefs =
            context.getSharedPreferences(
                "schedules",
                Context.MODE_PRIVATE
            )

        val json =
            prefs.getString(
                "esp_${espId}_pump$pumpNum",
                null
            ) ?: return null   // aucune programmation

        val schedules: List<PumpSchedule> =
            PumpScheduleJson.fromJson(json)

        // 🔢 consommation journalière réelle
        val dailyConsumption =
            schedules
                .filter { it.enabled }
                .sumOf { it.quantityMl.toDouble() }
                .toFloat()

        if (dailyConsumption <= 0f) return null

        return floor(
            remainingMl / dailyConsumption
        ).toInt()
    }
}
