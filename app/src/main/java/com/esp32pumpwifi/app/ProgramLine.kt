package com.esp32pumpwifi.app

import android.util.Log

/**
 * Représente UNE ligne de programmation envoyée à l'ESP32
 *
 * ⚠️ FORMAT STRICT ESP32 (9 caractères EXACTS) :
 *  E = Enable (0 / 1)
 *  P = Pompe (1..4)
 *  HH = Heure (00..23)
 *  MM = Minute (00..59)
 *  QQQ = Durée en secondes (000..999)
 *
 * Exemple : "110445009"
 */
data class ProgramLine(
    val enabled: Boolean,   // true = actif
    val pump: Int,          // 1..4
    val hour: Int,          // 0..23
    val minute: Int,        // 0..59
    val qtySeconds: Int     // 0..999
)

/**
 * Conversion ULTRA SÉCURISÉE vers le format ESP32 (9 caractères)
 */
fun ProgramLine.toEsp9(): String {

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

    val qqq = qtySeconds
        .coerceIn(0, 999)
        .toString()
        .padStart(3, '0')

    val result = "$e$p$hh$mm$qqq"

    // 🔍 LOG CRUCIAL POUR DEBUG ESP32
    Log.e(
        "PROGRAM_LINE",
        "➡️ ESP32 LINE = [$result] " +
                "(pump=$p, $hh:$mm, secs=$qqq, enabled=$e)"
    )

    return result
}
