package com.esp32pumpwifi.app

import android.content.Context
import java.util.Calendar
import kotlin.math.max

object TankScheduleHelper {

    private const val PLACEHOLDER = "000000000000"

    // ------------------------------------------------------------------
    // 📊 CONSOMMATION JOURNALIÈRE
    // ------------------------------------------------------------------
    fun getDailyConsumption(
        context: Context,
        espId: Long,
        pumpNum: Int
    ): Float {

        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

        val flow = prefs.getFloat("esp_${espId}_pump${pumpNum}_flow", 0f)
        if (flow <= 0f) return 0f

        var totalMl = 0f

        // ✅ IMPORTANT : lecture par espId explicite (multi-modules safe)
        val encodedLines = ProgramStoreSynced.loadEncodedLines(context, espId, pumpNum)

        for (line in encodedLines) {
            if (line == PLACEHOLDER) continue
            if (line.isEmpty() || line[0] != '1') continue

            val durationMs = line.substring(6, 12).toIntOrNull() ?: continue
            if (durationMs <= 0) continue

            totalMl += (durationMs / 1000f) * flow
        }

        return totalMl
    }

    // ------------------------------------------------------------------
    // 🔄 RECALCUL GLOBAL (SAFE multi-modules)
    // ------------------------------------------------------------------
    fun recalculateFromLastTime(
        context: Context,
        espId: Long
    ) {

        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        for (pumpNum in 1..4) {

            val flow = prefs.getFloat("esp_${espId}_pump${pumpNum}_flow", 0f)
            if (flow <= 0f) continue

            val lastKey = "esp_${espId}_pump${pumpNum}_last_processed_time"

            var lastProcessed = prefs.getLong(lastKey, 0L)
            if (lastProcessed == 0L) {
                prefs.edit().putLong(lastKey, now).apply()
                continue
            }

            // ✅ IMPORTANT : lecture par espId explicite (multi-modules safe)
            val encodedLines = ProgramStoreSynced.loadEncodedLines(context, espId, pumpNum)

            // ✅ FIX: trie par heure/minute (évite de “sauter” des doses si l’ordre n’est pas garanti)
            val sortedLines =
                encodedLines
                    .filter { it != PLACEHOLDER && it.isNotEmpty() && it[0] == '1' && it.length >= 12 }
                    .sortedWith(
                        compareBy(
                            { it.substring(2, 4).toIntOrNull() ?: -1 },
                            { it.substring(4, 6).toIntOrNull() ?: -1 }
                        )
                    )

            for (line in sortedLines) {

                // (garde-fous)
                if (line == PLACEHOLDER) continue
                if (line.isEmpty() || line[0] != '1') continue
                if (line.length < 12) continue

                val hh = line.substring(2, 4).toIntOrNull() ?: continue
                val mm = line.substring(4, 6).toIntOrNull() ?: continue
                val durationMs = line.substring(6, 12).toIntOrNull() ?: continue
                if (durationMs <= 0) continue

                val volumeMl = (durationMs / 1000f) * flow

                var dayCursor =
                    Calendar.getInstance().apply {
                        timeInMillis = max(lastProcessed, now - 30L * 86400000L)
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }

                while (dayCursor.timeInMillis <= now) {

                    val start =
                        (dayCursor.clone() as Calendar).apply {
                            set(Calendar.HOUR_OF_DAY, hh)
                            set(Calendar.MINUTE, mm)
                        }

                    val endMillis = start.timeInMillis + durationMs

                    if (endMillis > lastProcessed && endMillis <= now) {

                        // ➖ DÉCRÉMENT SEUL (SOURCE UNIQUE)
                        TankManager.decrement(
                            context = context,
                            espId = espId,
                            pumpNum = pumpNum,
                            volumeMl = volumeMl
                        )

                        lastProcessed = endMillis
                        prefs.edit().putLong(lastKey, lastProcessed).apply()
                    }

                    dayCursor.add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            // =====================================================
            // 🔔 UNE SEULE ALERTE — ÉTAT FINAL
            // =====================================================
            val level = TankManager.getTankLevel(context, espId, pumpNum)

            // ✅ Compat : lit d'abord alert_threshold, sinon low_threshold, sinon 20
            val threshold =
                prefs.getInt(
                    "esp_${espId}_pump${pumpNum}_alert_threshold",
                    prefs.getInt("esp_${espId}_pump${pumpNum}_low_threshold", 20)
                )

            val lowAlertKey = "esp_${espId}_pump${pumpNum}_low_alert_sent"
            val emptyAlertKey = "esp_${espId}_pump${pumpNum}_empty_alert_sent"

            val (newLow, newEmpty) =
                TankAlertManager.checkAndNotify(
                    context = context,
                    espId = espId,
                    pumpNum = pumpNum,
                    remainingMl = level.remainingMl,
                    capacityMl = level.capacityMl,
                    thresholdPercent = threshold,
                    lowAlertSent = prefs.getBoolean(lowAlertKey, false),
                    emptyAlertSent = prefs.getBoolean(emptyAlertKey, false)
                )

            prefs.edit()
                .putBoolean(lowAlertKey, newLow)
                .putBoolean(emptyAlertKey, newEmpty)
                .apply()
        }
    }
}
