package com.esp32pumpwifi.app

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlin.math.roundToInt

class ManualDoseFragment : Fragment() {

    private var pumpNumber: Int = 1

    companion object {
        private const val ARG_PUMP_NUMBER = "pump_number"
        const val MAX_PUMP_DURATION_SEC = 600
        const val MIN_PUMP_DURATION_MS = 50
        const val MAX_PUMP_DURATION_MS = 600_000

        fun newInstance(pumpNumber: Int): ManualDoseFragment =
            ManualDoseFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_PUMP_NUMBER, pumpNumber)
                }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pumpNumber = arguments?.getInt(ARG_PUMP_NUMBER) ?: 1
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.activity_manual_dose, container, false)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val root = view

        val backButton = root.findViewById<Button>(R.id.btn_manual_back)
        if (requireActivity() is ManualDoseTabsActivity) {
            backButton.visibility = View.GONE
        } else {
            backButton.setOnClickListener {
                requireActivity().finish()
            }
        }

        val prefs = requireContext().getSharedPreferences("prefs", Context.MODE_PRIVATE)

        val activeModule = Esp32Manager.getActive(requireContext())
        if (activeModule == null) {
            Toast.makeText(requireContext(), "Aucun ESP32 sélectionné", Toast.LENGTH_LONG).show()
            requireActivity().finish()
            return
        }

        val espIp = activeModule.ip
        val espId = activeModule.id

        // 🔑 CLÉS UNIFIÉES
        val pumpNameKey = "esp_${espId}_pump${pumpNumber}_name"
        val pumpFlowKey = "esp_${espId}_pump${pumpNumber}_flow"
        val thresholdKey = "esp_${espId}_pump${pumpNumber}_alert_threshold"

        val pumpName =
            prefs.getString(pumpNameKey, "Pompe $pumpNumber") ?: "Pompe $pumpNumber"

        val tvPump = root.findViewById<TextView>(R.id.tv_pump)
        val editVolume = root.findViewById<EditText>(R.id.edit_volume)
        val btnStart = root.findViewById<Button>(R.id.btn_start_dose)

        tvPump.text = pumpName
        QuantityInputUtils.applyInputFilter(editVolume)

        btnStart.setOnClickListener {

            // ✅ BLOQUAGE : dose programmée en cours sur cette pompe (multi-modules OK)
            if (isScheduledDoseInProgressNow(requireContext(), espId, pumpNumber)) {
                Toast.makeText(
                    requireContext(),
                    "Impossible : $pumpName est déjà en cours (dose programmée).",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            val qtyTenth = QuantityInputUtils.parseQuantityTenth(editVolume.text.toString())
            if (qtyTenth == null) {
                Toast.makeText(requireContext(), "Volume invalide pour $pumpName", Toast.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }
            val volumeMl = QuantityInputUtils.quantityMl(qtyTenth)

            val flow = prefs.getFloat(pumpFlowKey, 0f)
            if (flow <= 0f) {
                Toast.makeText(
                    requireContext(),
                    "$pumpName n’est pas encore étalonnée",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            val durationMs = (volumeMl / flow * 1000f).roundToInt()

            // ✅ Message plus clair si la durée tombe à 0s (volume trop faible vs débit)
            if (durationMs < MIN_PUMP_DURATION_MS) {
                val minMl = flow * (MIN_PUMP_DURATION_MS / 1000f)
                AlertDialog.Builder(requireContext())
                    .setTitle("Impossible")
                    .setMessage(
                        "Quantité trop faible : minimum ${"%.2f".format(minMl)} mL (${MIN_PUMP_DURATION_MS} ms)\n" +
                                "Débit actuel : ${"%.1f".format(flow)} mL/s"
                    )
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnClickListener
            }

            // ✅ Blocage manuel > 600s (comme demandé)
            if (durationMs > MAX_PUMP_DURATION_MS) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Impossible")
                    .setMessage("Durée trop longue : maximum ${MAX_PUMP_DURATION_SEC}s")
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnClickListener
            }

            viewLifecycleOwner.lifecycleScope.launch {

                if (!verifyEsp32Connection(activeModule)) {
                    Toast.makeText(
                        requireContext(),
                        "${activeModule.displayName} non connecté.\nVérifiez le Wi-Fi.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                // 🚿 ENVOI COMMANDE
                NetworkHelper.sendManualCommandMs(
                    context = requireContext(),
                    ip = espIp,
                    pump = pumpNumber,
                    durationMs = durationMs
                )

                // 🔋 DÉCRÉMENTATION (EXCEPTION NORMALE POUR LE MANUEL)
                TankManager.decrement(
                    context = requireContext(),
                    espId = espId,
                    pumpNum = pumpNumber,
                    volumeMl = volumeMl
                )

                // =====================================================
                // 🔔 ALERTES (Telegram + notif Android)
                // =====================================================
                val level =
                    TankManager.getTankLevel(
                        context = requireContext(),
                        espId = espId,
                        pumpNum = pumpNumber
                    )

                val thresholdPercent =
                    prefs.getInt(thresholdKey, 20)

                val lowAlertKey =
                    "esp_${espId}_pump${pumpNumber}_low_alert_sent"

                val emptyAlertKey =
                    "esp_${espId}_pump${pumpNumber}_empty_alert_sent"

                val lowAlertSent =
                    prefs.getBoolean(lowAlertKey, false)

                val emptyAlertSent =
                    prefs.getBoolean(emptyAlertKey, false)

                val (newLow, newEmpty) =
                    TankAlertManager.checkAndNotify(
                        context = requireContext(),
                        espId = espId,
                        pumpNum = pumpNumber,
                        remainingMl = level.remainingMl.toFloat(),
                        capacityMl = level.capacityMl,
                        thresholdPercent = thresholdPercent,
                        lowAlertSent = lowAlertSent,
                        emptyAlertSent = emptyAlertSent
                    )

                prefs.edit()
                    .putBoolean(lowAlertKey, newLow)
                    .putBoolean(emptyAlertKey, newEmpty)
                    .apply()
            }
        }
    }

    /**
     * ✅ Retourne true si une dose programmée est censée être EN COURS maintenant
     * sur cette pompe.
     *
     * Hypothèse actuelle (ton code) :
     * - une ligne active = tous les jours (pas de jours semaine ici)
     */
    private fun isScheduledDoseInProgressNow(context: Context, espId: Long, pumpNum: Int): Boolean {
        val now = System.currentTimeMillis()

        // Début de la journée (aujourd'hui)
        val dayStart = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // ✅ Multi-modules OK : lecture par espId explicite
        val lines = ProgramStore.loadEncodedLines(context, espId, pumpNum)
        for (line in lines) {
            if (line == "000000000000") continue
            if (line.length < 12) continue
            if (line[0] != '1') continue

            val hh = line.substring(2, 4).toIntOrNull() ?: continue
            val mm = line.substring(4, 6).toIntOrNull() ?: continue
            val durationMs = line.substring(6, 12).toIntOrNull() ?: continue
            if (durationMs <= 0) continue

            val start = (dayStart.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, hh)
                set(Calendar.MINUTE, mm)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val end = start + durationMs

            // En cours si now ∈ [start, end[
            if (now >= start && now < end) return true
        }

        return false
    }

    // ------------------------------------------------------------------
    private suspend fun verifyEsp32Connection(module: EspModule): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("http://${module.ip}/id")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 800
                conn.readTimeout = 800
                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                response.startsWith("POMPE_NAME=") &&
                        response.removePrefix("POMPE_NAME=").trim() == module.internalName
            } catch (_: Exception) {
                false
            }
        }
}
