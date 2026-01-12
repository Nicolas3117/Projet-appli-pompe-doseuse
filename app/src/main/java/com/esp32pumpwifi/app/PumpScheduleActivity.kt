package com.esp32pumpwifi.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * Activité de programmation des pompes
 * - 1 onglet par pompe
 * - Chaque onglet affiche un PumpScheduleFragment
 */
class PumpScheduleActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pump_schedule)

        val tabLayout: TabLayout =
            findViewById(R.id.tab_layout)

        val viewPager: ViewPager2 =
            findViewById(R.id.view_pager)

        // Adapter ViewPager (4 pompes)
        viewPager.adapter =
            PumpPagerAdapter(this)

        // Liaison onglets ↔ pages
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = "Pompe ${position + 1}"
        }.attach()
    }
}
