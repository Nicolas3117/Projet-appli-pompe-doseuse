package com.esp32pumpwifi.app

import android.content.Context

private const val PREFS = "prefs"
private const val MAX_LINES_PER_PUMP = 12
private const val PUMP_COUNT = 4

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
            .filter { it != PLACEHOLDER } // ✅ ne jamais exposer le placeholder au consommateur
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
    // 🧹 Effacement total module (optionnel, utile)
    // ---------------------------------------------------------------------
    fun clearAll(context: Context, espId: Long) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        for (pump in 1..PUMP_COUNT) {
            editor.putString(keyForEsp(espId, pump), "")
        }
        editor.apply()
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
    // ✅ NOUVEAU : alimenter la vérité depuis message 576 digits
    // ---------------------------------------------------------------------
    fun setFromMessage576(
        context: Context,
        espId: Long,
        message576: String
    ): Boolean {
        if (message576.length != 576) return false
        if (!message576.all(Char::isDigit)) return false

        // Prépare un buffer par pompe (max 12 lignes)
        val perPump = Array(PUMP_COUNT) { mutableListOf<String>() }

        for (lineIndex in 0 until 48) {
            val start = lineIndex * 12
            val rawLine = message576.substring(start, start + 12)
            val line = normalizeEncodedLine(rawLine) ?: return false

            if (line == PLACEHOLDER) continue
            if (line[0] != '1') continue

            val pump = line.substring(1, 2).toIntOrNull() ?: continue
            if (pump !in 1..PUMP_COUNT) continue

            // garde-fous HH/MM/MS
            val hh = line.substring(2, 4).toIntOrNull() ?: continue
            val mm = line.substring(4, 6).toIntOrNull() ?: continue
            val ms = line.substring(6, 12).toIntOrNull() ?: continue
            if (hh !in 0..23 || mm !in 0..59) continue
            if (ms !in 50..600000) continue

            val list = perPump[pump - 1]
            if (list.size >= MAX_LINES_PER_PUMP) continue
            list.add(line)
        }

        // Écrit atomiquement pompe par pompe
        for (pump in 1..PUMP_COUNT) {
            saveEncodedLines(context, espId, pump, perPump[pump - 1])
        }

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
