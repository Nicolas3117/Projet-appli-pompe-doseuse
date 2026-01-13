package com.esp32pumpwifi.app

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.checkbox.MaterialCheckBox

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

    // ================= ESP CHECKBOX (Material) =================

    private fun setupEspSelector() {

        layoutEspSelector.removeAllViews()
        selectedEspIds.clear()

        val allModules = Esp32Manager.getAll(this)

        allModules.forEach { esp ->

            val checkBox = MaterialCheckBox(this).apply {
                text = esp.displayName
                isChecked = esp.isActive
                textSize = 15f

                // ✅ Couleur du check (style Material)
                buttonTintList = ColorStateList.valueOf(
                    Color.parseColor("#2196F3")
                )

                // ✅ Un peu plus d’air (lisible)
                setPadding(32, 16, 32, 16)

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
