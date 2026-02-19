package com.esp32pumpwifi.app

import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * Adapter ViewPager2 des 4 pompes.
 *
 * ✅ Patch minimal :
 * - support optionnel d’un espId verrouillé pour éviter tout mélange multi-modules
 * - si lockedEspId == null : comportement inchangé (fragment utilisera getActive() comme avant)
 */
class PumpPagerAdapter(
    activity: AppCompatActivity,
    private val lockedEspId: Long? = null
) : FragmentStateAdapter(activity) {

    private val fragmentManager = activity.supportFragmentManager
    private var readOnly = false
    private val fragmentRefs = mutableMapOf<Int, PumpScheduleFragment>()
    private val pendingSchedules = mutableMapOf<Int, List<PumpSchedule>>()
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        fragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: FragmentManager,
                    f: Fragment,
                    v: android.view.View,
                    savedInstanceState: android.os.Bundle?
                ) {
                    if (f !is PumpScheduleFragment) return
                    val pumpNumber = f.getPumpNumber()
                    fragmentRefs[pumpNumber] = f
                    f.setReadOnly(readOnly)
                    val pending = pendingSchedules.remove(pumpNumber)
                    if (pending != null) {
                        f.replaceSchedules(pending)
                    }
                }

                override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
                    if (f !is PumpScheduleFragment) return
                    val pumpNumber = f.getPumpNumber()
                    fragmentRefs.remove(pumpNumber)
                }
            },
            true
        )
    }

    override fun getItemCount(): Int = 4

    override fun createFragment(position: Int): Fragment {
        val pumpNumber = position + 1

        // ✅ Si espId verrouillé fourni : on crée le fragment avec espId -> anti mélange multi-modules
        val fragment = if (lockedEspId != null) {
            PumpScheduleFragment.newInstance(pumpNumber, lockedEspId)
        } else {
            PumpScheduleFragment.newInstance(pumpNumber)
        }

        return fragment.also {
            // OK même avant onCreateView : le fragment appliquera l’état lors de applyReadOnlyState()
            if (readOnly) it.setReadOnly(true)
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

        fragmentRefs[pumpNumber]?.let { return it }

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
        val fragmentFoundMethod = when {
            fragmentRefs[pumpNumber] != null -> "registry"
            fragmentManager.findFragmentByTag("f${getItemId(pumpNumber - 1)}") != null -> "tag"
            else -> "scan"
        }
        if (fragment == null || !fragment.isAdded || fragment.view == null) {
            pendingSchedules[pumpNumber] = schedules
            return
        }

        val apply = Runnable {
            fragment.replaceSchedules(schedules)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply.run()
        } else {
            mainHandler.post(apply)
        }
    }

    fun setReadOnly(readOnly: Boolean) {
        this.readOnly = readOnly
        for (pumpNumber in 1..itemCount) {
            getFragment(pumpNumber)?.setReadOnly(readOnly)
        }
    }
}
