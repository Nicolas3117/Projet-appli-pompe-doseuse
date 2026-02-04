package com.esp32pumpwifi.app

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class ScheduleTabsViewModel : ViewModel() {

    private val _activeTotals =
        MutableLiveData<Map<Int, Int>>((1..4).associateWith { 0 })

    val activeTotals: LiveData<Map<Int, Int>> = _activeTotals

    fun setActiveTotal(pumpNumber: Int, totalTenth: Int) {
        val current = _activeTotals.value.orEmpty()
        if (current[pumpNumber] == totalTenth) return

        val updated = current.toMutableMap()
        updated[pumpNumber] = totalTenth
        _activeTotals.value = updated
    }
}
