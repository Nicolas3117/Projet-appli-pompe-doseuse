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

                val time = etTime.text.toString().trim()
                val qty = etQuantity.text.toString().toIntOrNull()

                if (!time.matches(Regex("""\d{2}:\d{2}""")) || qty == null || qty <= 0) {
                    Toast.makeText(
                        requireContext(),
                        "Format invalide",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }

                val check = detectConflicts(time, qty)

                if (check.blockingMessage != null) {
                    Toast.makeText(
                        requireContext(),
                        check.blockingMessage,
                        Toast.LENGTH_LONG
                    ).show()
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
    // 🔍 DÉTECTION DES CONFLITS (CORRIGÉ MULTI-POMPES)
    // ---------------------------------------------------------------------
    private fun detectConflicts(
        time: String,
        quantity: Int
    ): ConflictResult {

        val active =
            Esp32Manager.getActive(requireContext()) ?: return ConflictResult()

        val prefs =
            requireContext().getSharedPreferences("prefs", Context.MODE_PRIVATE)

        val (h, m) =
            time.split(":").map { it.toInt() }

        val startSec =
            h * 3600 + m * 60

        val flow =
            prefs.getFloat(
                "esp_${active.id}_pump${pumpNumber}_flow",
                0f
            )

        if (flow <= 0f) {
            return ConflictResult(
                blockingMessage = "${getPumpName(pumpNumber)} non calibrée"
            )
        }

        val duration =
            (quantity / flow).roundToInt()

        val endSec =
            startSec + duration

        if (endSec >= 86400) {
            return ConflictResult(
                blockingMessage = "La distribution dépasse minuit"
            )
        }

        // 🔒 MÊME POMPE — BLOQUANT
        for (s in schedules) {

            if (!s.enabled) continue

            val (sh, sm) =
                s.time.split(":").map { it.toInt() }

            val sStart =
                sh * 3600 + sm * 60

            val sDur =
                (s.quantity / flow).roundToInt()

            val sEnd =
                sStart + sDur

            if (startSec < sEnd && endSec > sStart) {
                return ConflictResult(
                    blockingMessage =
                        "Distribution simultanée détectée sur ${getPumpName(pumpNumber)}"
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

            for (s in list) {

                if (!s.enabled) continue

                val (sh, sm) =
                    s.time.split(":").map { it.toInt() }

                val sStart =
                    sh * 3600 + sm * 60

                val sDur =
                    (s.quantity / pFlow).roundToInt()

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
        val warningMessage: String? = null
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

        for (s in schedules) {

            if (!s.enabled) continue

            val (hh, mm) =
                s.time.split(":").map { it.toInt() }

            val seconds =
                (s.quantity / flow).roundToInt()

            val line =
                ProgramLine(
                    enabled = true,
                    pump = pumpNumber,
                    hour = hh,
                    minute = mm,
                    qtySeconds = seconds
                )

            ProgramStore.addLine(
                requireContext(),
                pumpNumber,
                line
            )
        }
    }

    fun getSchedules(): List<PumpSchedule> = schedules
}
