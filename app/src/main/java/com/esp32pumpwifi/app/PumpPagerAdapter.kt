package com.esp32pumpwifi.app

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class PumpPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {

    private val fragmentManager = activity.supportFragmentManager
    private var readOnly = false

    override fun getItemCount(): Int = 4

    override fun createFragment(position: Int): Fragment {
        val pumpNumber = position + 1
        return PumpScheduleFragment.newInstance(pumpNumber).also { fragment ->
            // OK même avant onCreateView : le fragment appliquera l’état lors de applyReadOnlyState()
            if (readOnly) fragment.setReadOnly(true)
        }
    }

    /**
     * Avec ViewPager2 + FragmentStateAdapter, le tag interne est "f" + itemId
     * (c’est géré par FragmentStateAdapter lui-même).
     */
    private fun findFragment(position: Int): PumpScheduleFragment? {
        if (position !in 0 until itemCount) return null
        val tag = "f${getItemId(position)}"
        return fragmentManager.findFragmentByTag(tag) as? PumpScheduleFragment
    }

    fun updateSchedules(pumpNumber: Int, schedules: List<PumpSchedule>) {
        val position = pumpNumber - 1
        findFragment(position)?.replaceSchedules(schedules)
    }

    fun setReadOnly(readOnly: Boolean) {
        this.readOnly = readOnly
        for (position in 0 until itemCount) {
            findFragment(position)?.setReadOnly(readOnly)
        }
    }
}
