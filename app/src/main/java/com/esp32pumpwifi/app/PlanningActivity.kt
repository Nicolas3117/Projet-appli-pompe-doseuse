package com.esp32pumpwifi.app

import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class PlanningActivity : AppCompatActivity() {

    private lateinit var planningView: PlanningView
    private lateinit var layoutEspSelector: LinearLayout

    /** IDs ESP actuellement visibles */
    private val selectedEspIds = mutableListOf<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_planning)

        planningView = findViewById(R.id.planningView)
        layoutEspSelector = findViewById(R.id.layoutEspSelector)

        setupEspSelector()
    }

    // ================= ESP CHECKBOX =================

    private fun setupEspSelector() {

        layoutEspSelector.removeAllViews()
        selectedEspIds.clear()

        val allModules = Esp32Manager.getAll(this)

        allModules.forEach { esp ->

            val checkBox = CheckBox(this).apply {
                text = esp.displayName
                isChecked = esp.isActive
                textSize = 14f

                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        if (!selectedEspIds.contains(esp.id)) {
                            selectedEspIds.add(esp.id)
                        }
                    } else {
                        selectedEspIds.remove(esp.id)
                    }
                    updatePlanning()
                }
            }

            layoutEspSelector.addView(checkBox)

            // état initial
            if (esp.isActive) {
                selectedEspIds.add(esp.id)
            }
        }

        // affichage initial
        updatePlanning()
    }

    // ================= MAJ PLANNING =================

    private fun updatePlanning() {
        planningView.setVisibleEspModules(selectedEspIds.toList())
    }
}
