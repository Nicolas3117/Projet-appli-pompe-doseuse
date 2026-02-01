package com.esp32pumpwifi.app

import android.content.Context

private const val PREFS = "prefs"

private const val MAX_LINES_PER_PUMP = 12

/** Ligne OFF = désactivée (12 caractères EXACTS) */
private const val PLACEHOLDER = "000000000000"

object ProgramStoreSynced {

    // ---------------------------------------------------------------------
    // 🔑 Clés prefs
    // ---------------------------------------------------------------------
    private fun keyForEsp(espId: Long, pump: Int) =
        "esp_${espId}_pump${pump}_program_synced"

    private fun key(context: Context, espId: Long, pump: Int): String =
        keyForEsp(espId, pump)

    // ---------------------------------------------------------------------
    // 📥 Chargement lignes encodées (12 chars EXACTS) — espId explicite
    // ---------------------------------------------------------------------
    fun loadEncodedLines(
        context: Context,
        espId: Long,
        pump: Int
    ): MutableList<String> {
        val raw = context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(context, espId, pump), "")
            ?: ""

        if (raw.isBlank()) return mutableListOf()

        return raw.split(';')
            .map { it.trim() }
            .mapNotNull { normalizeEncodedLine(it) }
            .toMutableList()
    }

    // ---------------------------------------------------------------------
    // 💾 Sauvegarde lignes encodées — espId explicite
    // ---------------------------------------------------------------------
    private fun saveEncodedLines(
        context: Context,
        espId: Long,
        pump: Int,
        lines: List<String>
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(context, espId, pump), lines.joinToString(";"))
            .apply()
    }

    // ---------------------------------------------------------------------
    // 🧹 Effacement d'une pompe
    // ---------------------------------------------------------------------
    fun clearPump(
        context: Context,
        espId: Long,
        pump: Int
    ) {
        saveEncodedLines(context, espId, pump, emptyList())
    }

    // ---------------------------------------------------------------------
    // ➕ Ajout ligne encodée (max 12 par pompe)
    // ---------------------------------------------------------------------
    fun addEncodedLine(
        context: Context,
        espId: Long,
        pump: Int,
        encodedLine: String
    ): Boolean {
        val normalized = normalizeEncodedLine(encodedLine) ?: return false
        if (normalized == PLACEHOLDER) return false

        val list = loadEncodedLines(context, espId, pump)
        if (list.size >= MAX_LINES_PER_PUMP) return false

        list.add(normalized)
        saveEncodedLines(context, espId, pump, list)
        return true
    }

    // ---------------------------------------------------------------------
    // 🔄 Normalisation des lignes (12 digits, conversion legacy 9 digits)
    // ---------------------------------------------------------------------
    private fun normalizeEncodedLine(raw: String): String? {
        if (raw.isBlank()) return null
        val trimmed = raw.trim()
        if (!trimmed.all(Char::isDigit)) return null

        if (trimmed.length == 12) return trimmed

        if (trimmed.length == 9) {
            if (trimmed == "000000000") return PLACEHOLDER

            val enabled = trimmed.substring(0, 1)
            val pump = trimmed.substring(1, 2)
            val hh = trimmed.substring(2, 4)
            val mm = trimmed.substring(4, 6)
            val secs = trimmed.substring(6, 9).toIntOrNull() ?: return null
            val ms = (secs * 1000).coerceIn(50, 600000)
            val msPart = ms.toString().padStart(6, '0')
            return "$enabled$pump$hh$mm$msPart"
        }

        return null
    }
}
