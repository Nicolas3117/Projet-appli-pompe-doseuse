package com.esp32pumpwifi.app

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeSplitUtilsTest {

    @Test
    fun split_250_on_3() {
        assertEquals(listOf(83, 83, 84), VolumeSplitUtils.splitTotalVolumeTenth(250, 3))
    }

    @Test
    fun split_100_on_4() {
        assertEquals(listOf(25, 25, 25, 25), VolumeSplitUtils.splitTotalVolumeTenth(100, 4))
    }

    @Test
    fun split_10_on_3() {
        assertEquals(listOf(3, 3, 4), VolumeSplitUtils.splitTotalVolumeTenth(10, 3))
    }

    @Test
    fun split_2_on_5_allows_zero_doses_except_last() {
        assertEquals(listOf(0, 0, 0, 0, 2), VolumeSplitUtils.splitTotalVolumeTenth(2, 5))
    }

    @Test
    fun split_non_positive_dose_count_returns_empty() {
        assertEquals(emptyList<Int>(), VolumeSplitUtils.splitTotalVolumeTenth(10, 0))
    }
}
