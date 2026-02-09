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
     * Avec ViewPager2 + FragmentStateAdapter, le tag interne est "f" + itemId.
     * MAIS selon timing/lifecycle, findFragmentByTag peut être null alors que le fragment existe déjà.
     * -> On fait donc :
     *    1) lookup stable via tag "f" + getItemId(position)
     *    2) fallback via scan fragmentManager.fragments (uniquement PumpScheduleFragment)
     */
    fun getFragment(pumpNumber: Int): PumpScheduleFragment? {
        val position = pumpNumber - 1
        if (position !in 0 until itemCount) return null

        // 1) lookup officiel FragmentStateAdapter
        val tag = "f${getItemId(position)}"
        val byTag = fragmentManager.findFragmentByTag(tag) as? PumpScheduleFragment
        if (byTag != null) return byTag

        // 2) fallback : scan des fragments existants
        return fragmentManager.fragments
            .filterIsInstance<PumpScheduleFragment>()
            .firstOrNull { it.arguments?.getInt("pumpNumber") == pumpNumber }
    }

    /**
     * Centralise la mise à jour UI.
     * - Si fragment présent : replaceSchedules() -> refresh + save + sync ProgramStore + update totals.
     * - Si fragment absent : ne crash pas, et on ne force rien (les prefs ont déjà été persistées).
     */
    fun updateSchedules(pumpNumber: Int, schedules: List<PumpSchedule>) {
        val fragment = getFragment(pumpNumber)
        fragment?.replaceSchedules(schedules)
    }

    fun setReadOnly(readOnly: Boolean) {
        this.readOnly = readOnly
        for (pumpNumber in 1..itemCount) {
            getFragment(pumpNumber)?.setReadOnly(readOnly)
        }
    }
}
