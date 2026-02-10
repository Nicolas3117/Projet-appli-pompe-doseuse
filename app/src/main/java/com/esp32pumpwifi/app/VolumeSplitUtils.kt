package com.esp32pumpwifi.app

object VolumeSplitUtils {

    fun splitTotalVolumeTenth(totalTenth: Int, doseCount: Int): List<Int> {
        if (doseCount <= 0) return emptyList()
        val base = totalTenth / doseCount
        val remainder = totalTenth % doseCount
        return List(doseCount) { index ->
            if (index == doseCount - 1) base + remainder else base
        }
    }
}

