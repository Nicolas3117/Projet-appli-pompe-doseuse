package com.esp32pumpwifi.app

import android.content.Context
import android.graphics.drawable.LayerDrawable
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Helper UI des réservoirs
 *
 * ⚠️ IMPORTANT
 * - AUCUNE notification ici
 * - AUCUN flag persistant
 * - AFFICHAGE UNIQUEMENT
 */
object TankUiHelper {

    private fun formatMl(value: Float): String =
        String.format(Locale.FRANCE, "%.1f", value)

    fun update(
        context: Context,
        espId: Long,
        pumpNum: Int,
        tvName: TextView,
        progress: ProgressBar,
        tvDays: TextView,
        tvPercent: TextView? = null,
        tvMl: TextView? = null
    ) {
        val prefs =
            context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

        val name =
            prefs.getString(
                "esp_${espId}_pump${pumpNum}_name",
                "Pompe $pumpNum"
            ) ?: "Pompe $pumpNum"

        val level =
            TankManager.getTankLevel(
                context,
                espId,
                pumpNum
            )

        // --------------------------------------------------
        // 🏷️ Nom
        // --------------------------------------------------
        tvName.text = name

        // --------------------------------------------------
        // 📊 Progression
        // --------------------------------------------------
        progress.max = 100
        progress.progress = level.percent

        val colorRes = when {
            level.percent >= 50 -> R.color.tank_green
            level.percent >= 20 -> R.color.tank_orange
            else -> R.color.tank_red
        }

        val drawable = progress.progressDrawable.mutate()
        if (drawable is LayerDrawable) {
            drawable.findDrawableByLayerId(android.R.id.progress)
                ?.setTint(
                    ContextCompat.getColor(
                        context,
                        colorRes
                    )
                )
        }

        // --------------------------------------------------
        // 📈 Pourcentage
        // --------------------------------------------------
        tvPercent?.text =
            if (level.capacityMl > 0)
                "${level.percent} %"
            else
                "-- %"

        // --------------------------------------------------
        // 🧪 Volume
        // --------------------------------------------------
        tvMl?.text =
            if (level.capacityMl > 0)
                "${formatMl(level.remainingMl)} / ${formatMl(level.capacityMl.toFloat())} mL"
            else
                "—"

        // --------------------------------------------------
        // 📅 Jours restants (INFORMATIF UNIQUEMENT)
        // --------------------------------------------------
        val daily =
            TankScheduleHelper.getDailyConsumption(
                context,
                espId,
                pumpNum
            )

        val daysRemaining =
            if (daily > 0f && level.remainingMl > 0)
                (level.remainingMl / daily).toInt()
            else
                null

        tvDays.text =
            daysRemaining?.let {
                "≈ $it jours restants"
            } ?: "—"
    }
}
