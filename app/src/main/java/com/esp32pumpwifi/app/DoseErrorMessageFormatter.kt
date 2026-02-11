package com.esp32pumpwifi.app

private const val DAY_MS: Long = 86_400_000L

object DoseErrorMessageFormatter {

    fun antiInterferenceGap(
        antiMin: Int,
        blockingPumpName: String,
        nextAllowedStartMs: Long?
    ): String {
        val header = "Anti-interférence chimique (${antiMin.coerceAtLeast(0)} min) : $blockingPumpName bloque ce créneau."
        val nextLine = nextAllowedStartMs
            ?.let { normalizeInDay(ceilToMinute(it)) }
            ?.let { ScheduleAddMergeUtils.toTimeString(it) }
            ?.let { "Prochaine heure possible : $it." }
        return if (nextLine == null) header else "$header\n$nextLine"
    }

    fun helperNoSpace(doseCount: Int, antiMin: Int): String {
        return "Impossible de placer $doseCount doses avec anti-interférence chimique $antiMin min sur cette plage.\n" +
            "Essayez de réduire le nombre de doses, d’élargir la plage,\n" +
            "ou de désactiver l’anti-interférence chimique sur la page Calibration (anti = 0)."
    }

    fun helperMaxDoses(antiMin: Int, maxDosesPossible: Int): String {
        return "Avec anti-interférence chimique $antiMin min,\n" +
            "maximum $maxDosesPossible doses sur cette plage.\n" +
            "Réduisez les doses ou élargissez la plage."
    }

    private fun normalizeInDay(valueMs: Long): Long {
        return (valueMs % DAY_MS + DAY_MS) % DAY_MS
    }
}
