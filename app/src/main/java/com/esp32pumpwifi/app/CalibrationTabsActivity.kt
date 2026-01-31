package com.esp32pumpwifi.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class CalibrationTabsActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private lateinit var adapter: CalibrationTabsPagerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration_tabs)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.btn_calibration)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationContentDescription("← Retour")
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        findViewById<TextView>(R.id.tv_header_back)
            .setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val activeModule = Esp32Manager.getActive(this)
        if (activeModule == null) {
            Toast.makeText(this, "Aucun module actif", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val moduleId = activeModule.id
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)

        viewPager = findViewById(R.id.view_pager_calibration)
        tabLayout = findViewById(R.id.tab_layout_calibration)

        adapter = CalibrationTabsPagerAdapter(this)
        viewPager.adapter = adapter

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = getPumpDisplayName(prefs, moduleId, position + 1)
        }.attach()
    }

    private fun getPumpDisplayName(prefs: SharedPreferences, espId: Long, pumpNum: Int): String {
        val fallback = "Pompe $pumpNum"
        val name = prefs.getString("esp_${espId}_pump${pumpNum}_name", fallback)
        return if (name.isNullOrBlank()) fallback else name
    }
}
