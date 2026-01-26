package com.esp32pumpwifi.app

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.roundToInt

class PumpScheduleFragment : Fragment() {

    private val schedules = mutableListOf<PumpSchedule>()
    private lateinit var adapter: PumpScheduleAdapter
    private var pumpNumber: Int = 1
    private val gson = Gson()

    companion object {
        const val MAX_PUMP_DURATION_SEC = 600
        private const val MAX_SCHEDULES_PER_PUMP = 12

        fun newInstance(pumpNumber: Int): PumpScheduleFragment =
            PumpScheduleFragment().apply {
                arguments = Bundle().apply {
                    putInt("pumpNumber", pumpNumber)
                }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pumpNumber = arguments?.getInt("pumpNumber") ?: 1
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        val view =
            inflater.inflate(R.layout.fragment_pump_schedule, container, false)

        adapter =
            PumpScheduleAdapter(requireContext(), schedules) {
                saveSchedules()
                syncToProgramStore()
            }

        view.findViewById<ListView>(R.id.lv_schedules).adapter = adapter

        view.findViewById<Button>(R.id.btn_add_schedule).setOnClickListener {
            showAddScheduleDialog()
        }

        loadSchedules()
        syncToProgramStore()

        return view
    }

    // ---------------------------------------------------------------------
    // ✅ Parse + validation HH:MM (00-23 / 00-59)
    // ---------------------------------------------------------------------
    private fun parseTimeOrNull(time: String): Pair<Int, Int>? {
        val t = time.trim()
        if (!t.matches(Regex("""\d{2}:\d{2}"""))) return null
        val parts = t.split(":")
        if (parts.size != 2) return null
        val hh = parts[0].toIntOrNull() ?: return null
        val mm = parts[1].toIntOrNull() ?: return null
        if (hh !in 0..23) return null
        if (mm !in 0..59) return null
        return hh to mm
    }

    // ---------------------------------------------------------------------
    // 🔤 NOM PERSONNALISÉ DE LA POMPE
    // ---------------------------------------------------------------------
    private fun getPumpName(pump: Int): String {

        val active =
            Esp32Manager.getActive(requireContext()) ?: return "Pompe $pump"

        val prefs =
            requireContext().getSharedPreferences("prefs", Context.MODE_PRIVATE)

        return prefs.getString(
            "esp_${active.id}_pump${pump}_name",
            "Pompe $pump"
        ) ?: "Pompe $pump"
    }

    // ---------------------------------------------------------------------
    // ✅ POPUP bloquante
    // ---------------------------------------------------------------------
    private fun showBlockingPopup(message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Impossible")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    // ---------------------------------------------------------------------
    // ➕ AJOUT PROGRAMMATION
    // ---------------------------------------------------------------------
    private fun showAddScheduleDialog() {

        val dialogView =
            layoutInflater.inflate(R.layout.dialog_add_schedule, null)

        val etTime =
            dialogView.findViewById<EditText>(R.id.et_time)

        val etQuantity =
            dialogView.findViewById<EditText>(R.id.et_quantity)

        AlertDialog.Builder(requireContext())
            .setTitle("Ajouter une programmation")
            .setView(dialogView)
            .setPositiveButton("Enregistrer") { _, _ ->

                // ✅ Limite 12 programmations (UX claire avant tout)
                if (schedules.size >= MAX_SCHEDULES_PER_PUMP) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Limite atteinte")
                        .setMessage("Attention : 12 programmations maximum par pompe.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@setPositiveButton
                }

                val time = etTime.text.toString().trim()
                val qty = etQuantity.text.toString().toIntOrNull()

                // ✅ format + bornes HH/MM
                if (parseTimeOrNull(time) == null || qty == null || qty <= 0) {
                    Toast.makeText(
                        requireContext(),
                        "Format invalide",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }

                val check = detectConflicts(time, qty)

                if (check.blockingMessage != null) {
                    // ✅ Si blocage "popup", on utilise un dialog; sinon toast (UX inchangée)
                    if (check.isPopup) {
                        showBlockingPopup(check.blockingMessage)
                    } else {
                        Toast.makeText(
                            requireContext(),
                            check.blockingMessage,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@setPositiveButton
                }

                if (check.warningMessage != null) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Chevauchement détecté")
                        .setMessage(check.warningMessage)
                        .setPositiveButton("Oui") { _, _ ->
                            addSchedule(time, qty)
                        }
                        .setNegativeButton("Non", null)
                        .show()
                    return@setPositiveButton
                }

                addSchedule(time, qty)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun addSchedule(time: String, qty: Int) {
        schedules.add(
            PumpSchedule(
                pumpNumber = pumpNumber,
                time = time,
                quantity = qty,
                enabled = true
            )
        )

        adapter.notifyDataSetChanged()
        saveSchedules()
        syncToProgramStore()
    }

    // ---------------------------------------------------------------------
    // 🔍 DÉTECTION DES CONFLITS
    // ---------------------------------------------------------------------
    private fun detectConflicts(
        time: String,
        quantity: Int
    ): ConflictResult {

        val active =
            Esp32Manager.getActive(requireContext()) ?: return ConflictResult()

        val prefs =
            requireContext().getSharedPreferences("prefs", Context.MODE_PRIVATE)

        // ✅ sécurité : au cas où (shouldn’t happen, mais protège legacy / appels indirects)
        val parsed = parseTimeOrNull(time)
        if (parsed == null) {
            return ConflictResult(
                blockingMessage = "Format invalide",
                isPopup = false
            )
        }

        val (h, m) = parsed

        val startSec =
            h * 3600 + m * 60

        val flow =
            prefs.getFloat(
                "esp_${active.id}_pump${pumpNumber}_flow",
                0f
            )

        if (flow <= 0f) {
            return ConflictResult(
                blockingMessage = "Pompe non calibrée",
                isPopup = false
            )
        }

        // ✅ Règle : on BLOQUE si qty < flow (1 seconde).
        val minMl = flow
        if (quantity.toFloat() < minMl) {
            val msg =
                "Quantité trop faible : minimum ${"%.1f".format(minMl)} mL (1 seconde)\n" +
                        "Débit actuel : ${"%.1f".format(flow)} mL/s"
            return ConflictResult(
                blockingMessage = msg,
                isPopup = true
            )
        }

        val duration =
            (quantity.toFloat() / flow).roundToInt()

        // Sécurité (au cas où)
        if (duration < 1) {
            val msg =
                "Quantité trop faible : minimum ${"%.1f".format(minMl)} mL (1 seconde)\n" +
                        "Débit actuel : ${"%.1f".format(flow)} mL/s"
            return ConflictResult(
                blockingMessage = msg,
                isPopup = true
            )
        }

        // ✅ Blocage si durée > 600s (aligné firmware ESP32)
        if (duration > MAX_PUMP_DURATION_SEC) {
            val msg =
                "Durée trop longue : maximum ${MAX_PUMP_DURATION_SEC}s\n" +
                        "Réduis la quantité ou recalibre le débit."
            return ConflictResult(
                blockingMessage = msg,
                isPopup = true
            )
        }

        val endSec =
            startSec + duration

        if (endSec >= 86400) {
            return ConflictResult(
                blockingMessage = "La distribution dépasse minuit",
                isPopup = false
            )
        }

        // 🔒 MÊME POMPE — BLOQUANT
        for (s in schedules) {

            if (!s.enabled) continue

            // ignore les anciennes lignes invalides (< 1 seconde)
            if (s.quantity.toFloat() < minMl) continue

            // ✅ ignore si heure invalide (legacy/corruption)
            val parsedExisting = parseTimeOrNull(s.time) ?: continue
            val (sh, sm) = parsedExisting

            val sStart =
                sh * 3600 + sm * 60

            val sDur =
                (s.quantity.toFloat() / flow).roundToInt()

            if (sDur < 1) continue
            if (sDur > MAX_PUMP_DURATION_SEC) continue

            val sEnd =
                sStart + sDur

            if (startSec < sEnd && endSec > sStart) {
                return ConflictResult(
                    blockingMessage =
                        "Distribution simultanée détectée sur ${getPumpName(pumpNumber)}",
                    isPopup = false
                )
            }
        }

        // ⚠️ AUTRES POMPES — ACCUMULATION
        val overlappingPumps = mutableSetOf<String>()

        for (p in 1..4) {

            if (p == pumpNumber) continue

            val json =
                requireContext()
                    .getSharedPreferences("schedules", Context.MODE_PRIVATE)
                    .getString(
                        "esp_${active.id}_pump$p",
                        null
                    ) ?: continue

            val type =
                object : TypeToken<List<PumpSchedule>>() {}.type

            val list: List<PumpSchedule> =
                gson.fromJson(json, type)

            val pFlow =
                prefs.getFloat(
                    "esp_${active.id}_pump${p}_flow",
                    0f
                )

            if (pFlow <= 0f) continue

            val pMinMl = pFlow

            for (s in list) {

                if (!s.enabled) continue
                if (s.quantity.toFloat() < pMinMl) continue

                // ✅ ignore si heure invalide (legacy/corruption)
                val parsedOther = parseTimeOrNull(s.time) ?: continue
                val (sh, sm) = parsedOther

                val sStart =
                    sh * 3600 + sm * 60

                val sDur =
                    (s.quantity.toFloat() / pFlow).roundToInt()

                if (sDur < 1) continue
                if (sDur > MAX_PUMP_DURATION_SEC) continue

                val sEnd =
                    sStart + sDur

                if (startSec < sEnd && endSec > sStart) {
                    overlappingPumps.add(getPumpName(p))
                }
            }
        }

        if (overlappingPumps.isNotEmpty()) {
            return ConflictResult(
                warningMessage =
                    "La distribution chevauche les pompes suivantes :\n" +
                            overlappingPumps.joinToString(
                                separator = "\n• ",
                                prefix = "• "
                            ) +
                            "\n\nVoulez-vous continuer ?"
            )
        }

        return ConflictResult()
    }

    data class ConflictResult(
        val blockingMessage: String? = null,
        val warningMessage: String? = null,
        val isPopup: Boolean = false
    )

    // ---------------------------------------------------------------------
    // 💾 SAUVEGARDE / CHARGEMENT
    // ---------------------------------------------------------------------
    private fun saveSchedules() {

        val active =
            Esp32Manager.getActive(requireContext()) ?: return

        requireContext()
            .getSharedPreferences("schedules", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "esp_${active.id}_pump$pumpNumber",
                gson.toJson(schedules)
            )
            .apply()
    }

    private fun loadSchedules() {

        val active =
            Esp32Manager.getActive(requireContext()) ?: return

        val json =
            requireContext()
                .getSharedPreferences("schedules", Context.MODE_PRIVATE)
                .getString(
                    "esp_${active.id}_pump$pumpNumber",
                    null
                )
                ?: return

        val type =
            object : TypeToken<MutableList<PumpSchedule>>() {}.type

        val loaded: MutableList<PumpSchedule>? =
            gson.fromJson(json, type)

        schedules.clear()
        if (loaded != null) {
            schedules.addAll(loaded)
        }

        adapter.notifyDataSetChanged()
    }

    // ---------------------------------------------------------------------
    // 🔁 SYNC → ProgramStore
    // ---------------------------------------------------------------------
    private fun syncToProgramStore() {

        val active =
            Esp32Manager.getActive(requireContext()) ?: return

        val prefs =
            requireContext().getSharedPreferences("prefs", Context.MODE_PRIVATE)

        while (ProgramStore.count(requireContext(), pumpNumber) > 0) {
            ProgramStore.removeLine(requireContext(), pumpNumber, 0)
        }

        val flow =
            prefs.getFloat(
                "esp_${active.id}_pump${pumpNumber}_flow",
                0f
            )

        if (flow <= 0f) return

        val minMl = flow

        var ignoredCount = 0

        for (s in schedules) {

            if (!s.enabled) continue
            if (s.quantity.toFloat() < minMl) {
                ignoredCount++
                continue
            }

            // ✅ ignore si heure invalide (legacy/corruption)
            val parsed = parseTimeOrNull(s.time)
            if (parsed == null) {
                ignoredCount++
                continue
            }
            val (hh, mm) = parsed

            val seconds =
                (s.quantity.toFloat() / flow).roundToInt()

            if (seconds < 1) {
                ignoredCount++
                continue
            }
            if (seconds > MAX_PUMP_DURATION_SEC) {
                ignoredCount++
                continue
            }

            val durationMs = (seconds * 1000).coerceAtLeast(50)

            val line =
                ProgramLine(
                    enabled = true,
                    pump = pumpNumber,
                    hour = hh,
                    minute = mm,
                    qtyMs = durationMs
                )

            ProgramStore.addLine(
                requireContext(),
                pumpNumber,
                line
            )
        }

        if (ignoredCount > 0) {
            Toast.makeText(
                requireContext(),
                "⚠️ $ignoredCount programmation(s) ignorée(s) (quantité trop faible ou trop longue)",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun getSchedules(): List<PumpSchedule> = schedules
}
