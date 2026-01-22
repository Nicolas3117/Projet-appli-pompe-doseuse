package com.esp32pumpwifi.app

/**
 * Convertit une heure "HH:mm" en minutes depuis minuit.
 * Ex: "02:30" -> 150
 */
fun timeToMinutes(time: String): Int {
    val parts = time.split(":")
    if (parts.size != 2) return 0

    val hours = parts[0].toIntOrNull() ?: return 0
    val minutes = parts[1].toIntOrNull() ?: return 0

    return hours * 60 + minutes
}
