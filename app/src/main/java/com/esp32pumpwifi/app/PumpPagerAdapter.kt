package com.esp32pumpwifi.app

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class PumpPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {

    private val context = activity
    private val fragments = List(4) { index ->
        val pumpNumber = index + 1
        PumpScheduleFragment.newInstance(pumpNumber)
    }

    override fun getItemCount(): Int = fragments.size

    override fun createFragment(position: Int): Fragment = fragments[position]

    fun getAllSchedules(): List<List<PumpSchedule>> {
        return fragments.map { fragment ->
            fragment.getSchedules()
        }
    }

    fun updateSchedules(pumpNumber: Int, schedules: List<PumpSchedule>) {
        val index = pumpNumber - 1
        if (index !in fragments.indices) return
        fragments[index].replaceSchedules(schedules)
    }

    fun setReadOnly(readOnly: Boolean) {
        fragments.forEach { fragment ->
            fragment.setReadOnly(readOnly)
        }
    }

    fun setOnActiveTotalChangedListener(listener: (Int, Int) -> Unit) {
        fragments.forEach { fragment ->
            fragment.setOnActiveTotalChangedListener(listener)
        }
    }

    fun getPumpName(position: Int): String {

        val pumpNumber = position + 1
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

        val activeModule = Esp32Manager
            .getAll(context)
            .firstOrNull { it.isActive }

        return if (activeModule != null) {
            prefs.getString(
                "esp_${activeModule.id}_pump${pumpNumber}_name",
                "Pompe $pumpNumber"
            ) ?: "Pompe $pumpNumber"
        } else {
            "Pompe $pumpNumber"
        }
    }
}
