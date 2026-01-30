package com.esp32pumpwifi.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment

class ManualDoseTabStubFragment : Fragment() {

    private var pumpNumber: Int = 1

    companion object {
        fun newInstance(pumpNumber: Int): ManualDoseTabStubFragment =
            ManualDoseTabStubFragment().apply {
                arguments = Bundle().apply {
                    putInt("pump_number", pumpNumber)
                }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pumpNumber = arguments?.getInt("pump_number") ?: 1
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_manual_dose_stub, container, false)

        val titleView = view.findViewById<TextView>(R.id.tv_manual_dose_stub_title)
        titleView.text =
            getString(R.string.manual_dose) + " - " + getString(R.string.pump_label, pumpNumber)

        view.findViewById<Button>(R.id.btn_open_manual_dose).setOnClickListener {
            val intent = Intent(requireContext(), ManualDoseActivity::class.java)
                .putExtra("pump_number", pumpNumber)
            startActivity(intent)
        }

        return view
    }
}
