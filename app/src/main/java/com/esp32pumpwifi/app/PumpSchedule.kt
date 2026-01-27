package com.esp32pumpwifi.app

import android.text.InputFilter
import android.widget.EditText
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.util.Locale
import kotlin.math.roundToInt

object QuantityInputUtils {
    private val inputRegex = Regex("""^\d+(?:[.,]\d?)?$""")

    fun applyInputFilter(editText: EditText) {
        val filter = InputFilter { source, start, end, dest, dstart, dend ->
            val newText = buildString {
                append(dest.substring(0, dstart))
                append(source.subSequence(start, end))
                append(dest.substring(dend))
            }
            if (newText.isEmpty() || inputRegex.matches(newText)) {
                null
            } else {
                ""
            }
        }
        editText.filters = arrayOf(filter)
    }

    fun parseQuantityTenth(input: String): Int? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || !inputRegex.matches(trimmed)) return null
        val normalized = trimmed.replace(',', '.')
        val qtyFloat = normalized.toFloatOrNull() ?: return null
        if (qtyFloat <= 0f) return null
        return (qtyFloat * 10f).roundToInt()
    }

    fun quantityMl(quantityTenth: Int): Float = quantityTenth / 10f

    fun formatQuantityMl(quantityTenth: Int): String =
        String.format(Locale.getDefault(), "%.1f", quantityMl(quantityTenth))
}

object PumpScheduleJson {
    fun fromJson(json: String): MutableList<PumpSchedule> {
        val list = mutableListOf<PumpSchedule>()
        val element = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return list
        if (!element.isJsonArray) return list
        element.asJsonArray.forEach { item ->
            parseSchedule(item)?.let { list.add(it) }
        }
        return list
    }

    private fun parseSchedule(item: JsonElement): PumpSchedule? {
        if (!item.isJsonObject) return null
        val obj = item.asJsonObject
        val pumpNumber = obj.get("pumpNumber")?.asInt ?: return null
        val time = obj.get("time")?.asString ?: return null
        val enabled = obj.get("enabled")?.asBoolean ?: true
        val qtyTenth = when {
            obj.has("quantityTenth") -> obj.get("quantityTenth").asInt
            obj.has("quantity") -> obj.get("quantity").asInt * 10
            else -> 0
        }
        return PumpSchedule(
            pumpNumber = pumpNumber,
            time = time,
            quantityTenth = qtyTenth,
            enabled = enabled
        )
    }

    fun toJson(schedules: List<PumpSchedule>, gson: Gson): String = gson.toJson(schedules)
}

/**
 * Représente une ligne de programmation côté application
 * (avant conversion en millisecondes pour l'ESP32)
 */
data class PumpSchedule(

    // Numéro de pompe (1 à 4)
    var pumpNumber: Int,

    // Heure de déclenchement au format "HH:mm"
    var time: String,

    // Quantité à distribuer en dixièmes de mL (UI + stockage)
    var quantityTenth: Int,

    // Ligne active ou non
    var enabled: Boolean
) {
    val quantityMl: Float
        get() = QuantityInputUtils.quantityMl(quantityTenth)
}
