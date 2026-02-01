package com.esp32pumpwifi.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Stockage persistant pour le suivi quotidien (programmation uniquement).
 *
 * ✅ Compat API 24+ : pas de java.time (LocalDate)
 */
object DailyProgramTrackingStore {
    private const val PREFS_NAME = "daily_program_tracking"
    private const val KEY_LAST_DATE = "daily_last_date"
    private const val DONE_DOSE_PREFIX = "daily_done_dose_esp_"
    private const val DONE_ML_PREFIX = "daily_done_ml_esp_"

    /**
     * Réinitialise les compteurs si un nouveau jour est détecté (programmation uniquement).
     */
    fun resetIfNewDay(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = todayKey() // yyyy-MM-dd (API 24 safe)
        val lastDate = prefs.getString(KEY_LAST_DATE, null)

        if (lastDate == null || lastDate != today) {
            val keysToRemove = prefs.all.keys.filter { key ->
                key.startsWith(DONE_DOSE_PREFIX) || key.startsWith(DONE_ML_PREFIX)
            }
            val editor = prefs.edit()
            keysToRemove.forEach { key -> editor.remove(key) }
            editor.putString(KEY_LAST_DATE, today)
            editor.apply()
        }
    }

    /**
     * Ajoute une dose programmée au suivi quotidien (programmation uniquement).
     */
    fun addScheduledDose(context: Context, espId: Long, pumpNum: Int, volumeMl: Float) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val doseKey = doseKey(espId, pumpNum)
        val mlKey = mlKey(espId, pumpNum)

        val currentDoseCount = prefs.getInt(doseKey, 0)
        val currentMl = prefs.getFloat(mlKey, 0f)

        prefs.edit()
            .putInt(doseKey, currentDoseCount + 1)
            .putFloat(mlKey, currentMl + volumeMl)
            .apply()
    }

    /**
     * Retourne le nombre de doses effectuées aujourd'hui (programmation uniquement).
     */
    fun getDoneDoseCountToday(context: Context, espId: Long, pumpNum: Int): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(doseKey(espId, pumpNum), 0)
    }

    /**
     * Retourne le volume total réalisé aujourd'hui (programmation uniquement).
     */
    fun getDoneMlToday(context: Context, espId: Long, pumpNum: Int): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(mlKey(espId, pumpNum), 0f)
    }

    private fun doseKey(espId: Long, pumpNum: Int): String {
        return "${DONE_DOSE_PREFIX}${espId}_pump_${pumpNum}"
    }

    private fun mlKey(espId: Long, pumpNum: Int): String {
        return "${DONE_ML_PREFIX}${espId}_pump_${pumpNum}"
    }

    private fun todayKey(): String {
        val cal = Calendar.getInstance()
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return sdf.format(cal.time)
    }
}
