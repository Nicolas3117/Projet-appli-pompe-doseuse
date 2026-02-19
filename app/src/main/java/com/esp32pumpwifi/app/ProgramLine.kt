package com.esp32pumpwifi.app


/**
 * Représente UNE ligne de programmation envoyée à l'ESP32
 *
 * ⚠️ FORMAT STRICT ESP32 (12 caractères EXACTS) :
 *  E = Enable (0 / 1)
 *  P = Pompe (1..4)
 *  HH = Heure (00..23)
 *  MM = Minute (00..59)
 *  MMMMMM = Durée en millisecondes (000050..600000)
 *
 * Exemple : "110445012000"
 */
data class ProgramLine(
    val enabled: Boolean,   // true = actif
    val pump: Int,          // 1..4
    val hour: Int,          // 0..23
    val minute: Int,        // 0..59
    val qtyMs: Int          // 50..600000
)

/**
 * Conversion ULTRA SÉCURISÉE vers le format ESP32 (12 caractères)
 */
fun ProgramLine.toEsp12(): String {

    val e = if (enabled) 1 else 0

    // 🔒 Sécurités STRICTES
    val p = pump.coerceIn(1, 4)

    val hh = hour
        .coerceIn(0, 23)
        .toString()
        .padStart(2, '0')

    val mm = minute
        .coerceIn(0, 59)
        .toString()
        .padStart(2, '0')

    val mmmmmm = qtyMs
        .coerceIn(50, 600000)
        .toString()
        .padStart(6, '0')

    val result = "$e$p$hh$mm$mmmmmm"

    // 🔍 LOG CRUCIAL POUR DEBUG ESP32

    return result
}
