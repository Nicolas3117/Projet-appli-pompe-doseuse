package com.esp32pumpwifi.app

import android.content.Context
import android.util.Log

private const val PREFS = "prefs"

private const val MAX_LINES_PER_PUMP = 12
private const val PUMP_COUNT = 4

/** Ligne OFF = désactivée (12 caractères EXACTS) */
private const val PLACEHOLDER = "000000000000"

object ProgramStore {

    // ---------------------------------------------------------------------
    // 🔑 Clés prefs
    // ---------------------------------------------------------------------
    private fun legacyKey(pump: Int) = "pump${pump}_program"
    private fun keyForEsp(espId: Long, pump: Int) = "esp_${espId}_pump${pump}_program"

    /**
     * Clé basée sur le module actif.
     * ✅ Inclut la migration legacy -> espKey (une seule fois) :
     * - si legacy existe et espKey vide, on copie legacy -> espKey
     * - puis on supprime legacy
     */
    private fun key(context: Context, pump: Int): String {
        val active = Esp32Manager.getActive(context) ?: return legacyKey(pump)

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val espKey = keyForEsp(active.id, pump)

        // Migration douce depuis l'ancien stockage global (mono-ESP)
        val legacy = legacyKey(pump)
        val legacyValue = prefs.getString(legacy, null)
        if (!legacyValue.isNullOrBlank()) {
            val editor = prefs.edit()
            if (prefs.getString(espKey, null).isNullOrBlank()) {
                editor.putString(espKey, legacyValue)
            }
            editor.remove(legacy)
            editor.apply()
        }

        return espKey
    }

    /**
     * Clé explicite par espId.
     * ⚠️ IMPORTANT : ici on NE MIGRE PAS le legacy.
     * Le recalcul multi-modules doit lire EXACTEMENT la clé du module demandé
     * sans dépendre du module actif ni déplacer des données legacy au mauvais moment.
     */
    private fun key(context: Context, espId: Long, pump: Int): String {
        return keyForEsp(espId, pump)
    }

    // ---------------------------------------------------------------------
    // 🔤 NOM PERSONNALISÉ POMPE (par ESP32)
    // Convention : esp_${espId}_pump${pump}_name
    // Fallback : "Pompe X"
    // ---------------------------------------------------------------------
    private fun getPumpName(
        context: Context,
        espId: Long,
        pump: Int
    ): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = "esp_${espId}_pump${pump}_name"
        return prefs.getString(k, "Pompe $pump") ?: "Pompe $pump"
    }

    // ---------------------------------------------------------------------
    // 📥 Chargement lignes encodées (12 chars EXACTS) — module actif
    // ---------------------------------------------------------------------
    fun loadEncodedLines(context: Context, pump: Int): MutableList<String> {
        val raw = context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(context, pump), "")
            ?: ""

        if (raw.isBlank()) return mutableListOf()

        return raw.split(';')
            .map { it.trim() }
            .mapNotNull { normalizeEncodedLine(it) }
            .toMutableList()
    }

    // ---------------------------------------------------------------------
    // 📥 Chargement lignes encodées (12 chars EXACTS) — espId explicite
    // (utilisé pour recalcul multi-modules)
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
    // 💾 Sauvegarde lignes encodées — module actif
    // ---------------------------------------------------------------------
    private fun saveEncodedLines(
        context: Context,
        pump: Int,
        lines: List<String>
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(context, pump), lines.joinToString(";"))
            .apply()
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
    // ➕ Ajout ligne (max 12 par pompe)
    // ---------------------------------------------------------------------
    fun addLine(
        context: Context,
        pump: Int,
        line: ProgramLine
    ): Boolean {
        val list = loadEncodedLines(context, pump)
        if (list.size >= MAX_LINES_PER_PUMP) return false

        val encoded = line.toEsp12()
        list.add(encoded)

        saveEncodedLines(context, pump, list)

        Log.e("PROGRAM_STORE", "➕ P$pump ADD → $encoded")
        return true
    }

    // ---------------------------------------------------------------------
    // ➕ Ajout ligne (max 12 par pompe) — espId explicite
    // ---------------------------------------------------------------------
    fun addLine(
        context: Context,
        espId: Long,
        pump: Int,
        line: ProgramLine
    ): Boolean {
        val list = loadEncodedLines(context, espId, pump)
        if (list.size >= MAX_LINES_PER_PUMP) return false

        val encoded = line.toEsp12()
        list.add(encoded)

        saveEncodedLines(context, espId, pump, list)

        Log.e("PROGRAM_STORE", "➕ P$pump ADD → $encoded (espId=$espId)")
        return true
    }

    // ---------------------------------------------------------------------
    // ❌ Suppression ligne
    // ---------------------------------------------------------------------
    fun removeLine(
        context: Context,
        pump: Int,
        index: Int
    ): Boolean {
        val list = loadEncodedLines(context, pump)
        if (index !in list.indices) return false

        val removed = list.removeAt(index)
        saveEncodedLines(context, pump, list)

        Log.e("PROGRAM_STORE", "❌ P$pump REMOVE → $removed")
        return true
    }

    // ---------------------------------------------------------------------
    // ❌ Suppression ligne — espId explicite
    // ---------------------------------------------------------------------
    fun removeLine(
        context: Context,
        espId: Long,
        pump: Int,
        index: Int
    ): Boolean {
        val list = loadEncodedLines(context, espId, pump)
        if (index !in list.indices) return false

        val removed = list.removeAt(index)
        saveEncodedLines(context, espId, pump, list)

        Log.e("PROGRAM_STORE", "❌ P$pump REMOVE → $removed (espId=$espId)")
        return true
    }

    // ---------------------------------------------------------------------
    // 📊 Nombre de lignes (UI)
    // ---------------------------------------------------------------------
    fun count(context: Context, pump: Int): Int =
        loadEncodedLines(context, pump).size

    // ---------------------------------------------------------------------
    // 📊 Nombre de lignes (UI) — espId explicite
    // ---------------------------------------------------------------------
    fun count(context: Context, espId: Long, pump: Int): Int =
        loadEncodedLines(context, espId, pump).size

    // ---------------------------------------------------------------------
    // 🔍 OUTILS INTERNES — calcul de plage
    // ---------------------------------------------------------------------
    private fun decodeStartMinutes(line: String): Int {
        val hh = line.substring(2, 4).toInt()
        val mm = line.substring(4, 6).toInt()
        return hh * 60 + mm
    }

    private fun decodeDurationMinutes(line: String): Int {
        val durationMs = line.substring(6, 12).toInt()
        return kotlin.math.ceil(durationMs / 60000.0).toInt()
    }

    private fun overlap(start1: Int, end1: Int, start2: Int, end2: Int): Boolean {
        return start1 < end2 && end1 > start2
    }

    // ---------------------------------------------------------------------
    // 🕒 TRI OFFICIEL (POUR L’ENVOI) — stable, sans modifier le stockage
    // ---------------------------------------------------------------------
    private fun sortLinesForSend(lines: List<String>): List<String> {
        // Tri stable :
        // 1) lignes valides non-placeholder d'abord
        // 2) par heure (decodeStartMinutes)
        // 3) à égalité, on conserve l’ordre d’origine
        return lines
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<String>>(
                    { it.value == PLACEHOLDER }, // false (vraies lignes) avant true (placeholder)
                    {
                        // Si une ligne est malformée (ça ne devrait pas arriver grâce au filtre),
                        // on la met à la fin des "vraies lignes".
                        try {
                            if (it.value == PLACEHOLDER) Int.MAX_VALUE else decodeStartMinutes(it.value)
                        } catch (_: Exception) {
                            Int.MAX_VALUE
                        }
                    },
                    { it.index } // stabilité
                )
            )
            .map { it.value }
    }

    // ---------------------------------------------------------------------
    // ✅ DERNIÈRE PROTECTION — validation HH/MM/SECS sur une ligne encodée
    // - accepte PLACEHOLDER
    // - HH 00..23
    // - MM 00..59
    // - MS 000050..600000
    // ---------------------------------------------------------------------
    private fun isValidEncodedLineForSend(line: String): Boolean {
        if (line == PLACEHOLDER) return true
        if (line.length != 12 || !line.all(Char::isDigit)) return false

        val hh = line.substring(2, 4).toIntOrNull() ?: return false
        val mm = line.substring(4, 6).toIntOrNull() ?: return false
        val ms = line.substring(6, 12).toIntOrNull() ?: return false

        if (hh !in 0..23) return false
        if (mm !in 0..59) return false
        if (ms !in 50..600000) return false

        return true
    }

    // ---------------------------------------------------------------------
    // ✅ DERNIÈRE PROTECTION — strict ESP32 (12 digits + active/pump)
    // - accepte PLACEHOLDER
    // - sinon active doit être '1'
    // - pompe doit être 1..4
    // ---------------------------------------------------------------------
    private fun sanitizeEncodedLineForEsp32Strict(line: String): String {
        if (line == PLACEHOLDER) return PLACEHOLDER
        if (line.length != 12 || !line.all(Char::isDigit)) return PLACEHOLDER
        if (line[0] != '1') return PLACEHOLDER
        if (line[1] !in '1'..'4') return PLACEHOLDER
        return line
    }

    // ---------------------------------------------------------------------
    // 🔒 SÉCURITÉ 1 — INTERDICTION même pompe (BLOQUANT)
    // ---------------------------------------------------------------------
    fun hasBlockingConflict(
        context: Context,
        pump: Int,
        newLine: ProgramLine
    ): String? {
        val newEncoded = newLine.toEsp12()
        val newStart = decodeStartMinutes(newEncoded)
        val newEnd = newStart + decodeDurationMinutes(newEncoded)

        val existingLines = loadEncodedLines(context, pump)

        for (line in existingLines) {
            if (line == PLACEHOLDER) continue

            val start = decodeStartMinutes(line)
            val end = start + decodeDurationMinutes(line)

            if (overlap(newStart, newEnd, start, end)) {
                return line
            }
        }

        return null
    }

    // ---------------------------------------------------------------------
    // 🔒 SÉCURITÉ 1 — message prêt (nom personnalisé)
    // ---------------------------------------------------------------------
    fun getBlockingConflictMessage(
        context: Context,
        espId: Long,
        pump: Int,
        newLine: ProgramLine
    ): String? {
        val conflictLine = hasBlockingConflict(context, pump, newLine) ?: return null
        val pumpName = getPumpName(context, espId, pump)
        return "Distribution simultanée détectée sur $pumpName"
    }

    // ---------------------------------------------------------------------
    // ⚠️ SÉCURITÉ 2 — AVERTISSEMENT autres pompes
    // ---------------------------------------------------------------------
    fun getCrossPumpConflicts(
        context: Context,
        pump: Int,
        newLine: ProgramLine
    ): List<Pair<Int, String>> {
        val conflicts = mutableListOf<Pair<Int, String>>()

        val newEncoded = newLine.toEsp12()
        val newStart = decodeStartMinutes(newEncoded)
        val newEnd = newStart + decodeDurationMinutes(newEncoded)

        for (p in 1..PUMP_COUNT) {
            if (p == pump) continue

            val lines = loadEncodedLines(context, p)
            for (line in lines) {
                if (line == PLACEHOLDER) continue

                val start = decodeStartMinutes(line)
                val end = start + decodeDurationMinutes(line)

                if (overlap(newStart, newEnd, start, end)) {
                    conflicts.add(p to line)
                }
            }
        }

        return conflicts
    }

    // ---------------------------------------------------------------------
    // ⚠️ SÉCURITÉ 2 — noms uniques des pompes en conflit
    // ---------------------------------------------------------------------
    fun getCrossPumpConflictNames(
        context: Context,
        espId: Long,
        pump: Int,
        newLine: ProgramLine
    ): List<String> {
        val raw = getCrossPumpConflicts(context, pump, newLine)
        if (raw.isEmpty()) return emptyList()

        return raw.map { (p, _) -> getPumpName(context, espId, p) }
            .distinct()
    }

    // ---------------------------------------------------------------------
    // 🚀 CONSTRUCTION MESSAGE FINAL POUR /program_ms (module actif)
    // ✅ ICI : TRI OFFICIEL + DERNIÈRE PROTECTION AVANT ENVOI
    // ---------------------------------------------------------------------
    fun buildMessageMs(context: Context): String {
        // 4 pompes * 12 lignes * 12 chars = 576 chars
        val totalLines = PUMP_COUNT * MAX_LINES_PER_PUMP
        val sb = StringBuilder(totalLines * PLACEHOLDER.length)

        Log.e("PROGRAM_BUILD", "================ BUILD /program_ms ================")

        for (pump in 1..PUMP_COUNT) {

            // Charge les lignes stockées (ordre de saisie)
            val rawLines = loadEncodedLines(context, pump)

            // ✅ Filtre ultime : HH/MM valides + ms 50..600000 (tolère PLACEHOLDER)
            val filteredLines = rawLines.filter { line ->
                isValidEncodedLineForSend(line) && line != PLACEHOLDER
            }

            // ✅ Tri officiel pour l’envoi (copie triée)
            val sortedLines = sortLinesForSend(filteredLines)

            // ✅ Puis on limite à 12
            val lines = sortedLines.take(MAX_LINES_PER_PUMP)

            Log.e("PROGRAM_BUILD", "Pompe $pump : ${lines.size} ligne(s)")

            for (line in lines) {
                val sanitized = sanitizeEncodedLineForEsp32Strict(line)
                sb.append(sanitized)
                Log.e("PROGRAM_BUILD", "  ✔ $sanitized")
            }

            repeat(MAX_LINES_PER_PUMP - lines.size) {
                sb.append(PLACEHOLDER)
                Log.e("PROGRAM_BUILD", "  ⬜ $PLACEHOLDER")
            }
        }

        val result = sb.toString()

        Log.e("PROGRAM_BUILD", "------------------------------------------------")
        Log.e("PROGRAM_BUILD", "LONGUEUR = ${result.length} (ATTENDU 576)")
        Log.e("PROGRAM_BUILD", "MESSAGE = $result")
        Log.e("PROGRAM_BUILD", "================================================")

        return result
    }

    // ---------------------------------------------------------------------
    // 🧹 Effacement total (toutes pompes) — module actif
    // ---------------------------------------------------------------------
    fun clearAll(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        for (p in 1..PUMP_COUNT) {
            editor.putString(key(context, p), "")
        }
        editor.apply()

        Log.e("PROGRAM_STORE", "🧹 ALL PROGRAMS CLEARED")
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
