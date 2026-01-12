package com.esp32pumpwifi.app

import android.content.Context
import android.util.Log

private const val PREFS = "prefs"

private const val MAX_LINES_PER_PUMP = 12
private const val PUMP_COUNT = 4

/** Ligne OFF = désactivée (9 caractères EXACTS) */
private const val PLACEHOLDER = "000000000"

object ProgramStore {

    // ---------------------------------------------------------------------
    // 🔑 Clé prefs pour UNE pompe (1..4)
    // ---------------------------------------------------------------------
    private fun legacyKey(pump: Int) = "pump${pump}_program"

    private fun keyForEsp(espId: Long, pump: Int) = "esp_${espId}_pump${pump}_program"

    private fun key(context: Context, pump: Int): String {
        val active = Esp32Manager.getActive(context) ?: return legacyKey(pump)
        val espKey = keyForEsp(active.id, pump)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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

    // ---------------------------------------------------------------------
    // 🔤 NOM PERSONNALISÉ POMPE (par ESP32)
    // Convention utilisée ailleurs : esp_${espId}_pump${pump}_name
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
    // 📥 Chargement lignes encodées (9 chars EXACTS)
    // ---------------------------------------------------------------------
    fun loadEncodedLines(context: Context, pump: Int): MutableList<String> {

        val raw = context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(context, pump), "")
            ?: ""

        if (raw.isBlank()) return mutableListOf()

        return raw.split(';')
            .map { it.trim() }
            .filter { it.length == 9 && it.all(Char::isDigit) }
            .toMutableList()
    }

    // ---------------------------------------------------------------------
    // 💾 Sauvegarde lignes encodées
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
    // ➕ Ajout ligne (max 12 par pompe)
    // ---------------------------------------------------------------------
    fun addLine(
        context: Context,
        pump: Int,
        line: ProgramLine
    ): Boolean {

        val list = loadEncodedLines(context, pump)
        if (list.size >= MAX_LINES_PER_PUMP) return false

        val encoded = line.toEsp9()
        list.add(encoded)

        saveEncodedLines(context, pump, list)

        Log.e("PROGRAM_STORE", "➕ P$pump ADD → $encoded")
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
    // 📊 Nombre de lignes (UI)
    // ---------------------------------------------------------------------
    fun count(context: Context, pump: Int): Int =
        loadEncodedLines(context, pump).size

    // ---------------------------------------------------------------------
    // 🔍 OUTILS INTERNES — calcul de plage
    // ---------------------------------------------------------------------
    private fun decodeStartMinutes(line: String): Int {
        val hh = line.substring(2, 4).toInt()
        val mm = line.substring(4, 6).toInt()
        return hh * 60 + mm
    }

    private fun decodeDurationMinutes(line: String): Int {
        return line.substring(6, 9).toInt()
    }

    private fun overlap(start1: Int, end1: Int, start2: Int, end2: Int): Boolean {
        return start1 < end2 && end1 > start2
    }

    // ---------------------------------------------------------------------
    // 🔒 SÉCURITÉ 1 — INTERDICTION même pompe (BLOQUANT)
    // (ancienne version conservée)
    // ---------------------------------------------------------------------
    fun hasBlockingConflict(
        context: Context,
        pump: Int,
        newLine: ProgramLine
    ): String? {

        val newEncoded = newLine.toEsp9()
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
    // 🔒 SÉCURITÉ 1 — INTERDICTION même pompe (BLOQUANT)
    // (nouvelle version : renvoie un message avec nom personnalisé)
    // ---------------------------------------------------------------------
    fun getBlockingConflictMessage(
        context: Context,
        espId: Long,
        pump: Int,
        newLine: ProgramLine
    ): String? {

        val conflictLine = hasBlockingConflict(context, pump, newLine) ?: return null
        val pumpName = getPumpName(context, espId, pump)

        // On peut garder simple : le message est prêt pour Toast
        return "Distribution simultanée détectée sur $pumpName"
    }

    // ---------------------------------------------------------------------
    // ⚠️ SÉCURITÉ 2 — AVERTISSEMENT autres pompes
    // (ancienne version conservée)
    // ---------------------------------------------------------------------
    fun getCrossPumpConflicts(
        context: Context,
        pump: Int,
        newLine: ProgramLine
    ): List<Pair<Int, String>> {

        val conflicts = mutableListOf<Pair<Int, String>>()

        val newEncoded = newLine.toEsp9()
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
    // ⚠️ SÉCURITÉ 2 — AVERTISSEMENT autres pompes
    // (nouvelle version : renvoie liste des pompes en conflit avec noms)
    // ---------------------------------------------------------------------
    fun getCrossPumpConflictNames(
        context: Context,
        espId: Long,
        pump: Int,
        newLine: ProgramLine
    ): List<String> {

        val raw = getCrossPumpConflicts(context, pump, newLine)
        if (raw.isEmpty()) return emptyList()

        // noms uniques (si plusieurs overlaps sur même pompe)
        return raw.map { (p, _) -> getPumpName(context, espId, p) }
            .distinct()
    }

    // ---------------------------------------------------------------------
    // 🚀 CONSTRUCTION MESSAGE FINAL POUR /program (INCHANGÉ)
    // ---------------------------------------------------------------------
    fun buildMessage(context: Context): String {

        // 4 pompes * 12 lignes * 9 chars = 432 chars
        val totalLines = PUMP_COUNT * MAX_LINES_PER_PUMP
        val sb = StringBuilder(totalLines * PLACEHOLDER.length)

        Log.e("PROGRAM_BUILD", "================ BUILD /program ================")

        for (pump in 1..PUMP_COUNT) {

            val lines = loadEncodedLines(context, pump)
                .take(MAX_LINES_PER_PUMP)

            Log.e("PROGRAM_BUILD", "Pompe $pump : ${lines.size} ligne(s)")

            for (line in lines) {
                sb.append(line)
                Log.e("PROGRAM_BUILD", "  ✔ $line")
            }

            repeat(MAX_LINES_PER_PUMP - lines.size) {
                sb.append(PLACEHOLDER)
                Log.e("PROGRAM_BUILD", "  ⬜ $PLACEHOLDER")
            }
        }

        val result = sb.toString()

        Log.e("PROGRAM_BUILD", "------------------------------------------------")
        Log.e("PROGRAM_BUILD", "LONGUEUR = ${result.length} (ATTENDU 432)")
        Log.e("PROGRAM_BUILD", "MESSAGE = $result")
        Log.e("PROGRAM_BUILD", "================================================")

        return result
    }

    // ---------------------------------------------------------------------
    // 🧹 Effacement total (toutes pompes)
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
}
