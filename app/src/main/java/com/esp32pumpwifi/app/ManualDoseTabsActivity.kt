package com.esp32pumpwifi.app

import android.content.Context
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class ManualDoseTabsActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var adapter: ManualDoseTabsPagerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manual_dose_tabs)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.manual_dose)
        toolbar.setNavigationContentDescription("← Retour")
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        findViewById<TextView>(R.id.tv_header_back)
            .setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val activeModule = Esp32Manager.getActive(this)
        if (activeModule == null) {
            Toast.makeText(this, "Veuillez sélectionner un module", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        viewPager = findViewById(R.id.view_pager_manual_dose)
        tabLayout = findViewById(R.id.tab_layout_manual_dose)

        adapter = ManualDoseTabsPagerAdapter(this)
        viewPager.adapter = adapter

        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val moduleId = activeModule.id

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = prefs.getString(
                "esp_${moduleId}_pump${position + 1}_name",
                "Pompe ${position + 1}"
            )
        }.attach()
    }
}
