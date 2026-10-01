package com.music.bitchord.ui.replay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayUnitsTest {

    @After
    fun backToMinutes() = ReplayUnits.set(ReplayUnits.Unit.MINUTES)

    private fun hours(h: Double) = (h * 3_600_000).toLong()

    @Test
    fun minutesAreGroupedWholeMinutes() {
        ReplayUnits.set(ReplayUnits.Unit.MINUTES)
        assertEquals("77,040", formatMinutes(hours(1284.0)))
        assertEquals("45", formatMinutes(45 * 60_000L))
    }

    @Test
    fun hoursKeepADecimalOnlyWhileItSaysSomething() {
        ReplayUnits.set(ReplayUnits.Unit.HOURS)
        assertEquals("4.5", formatMinutes(hours(4.5)))
        assertEquals("3", formatMinutes(hours(3.0)))
        assertEquals("0.8", formatMinutes(45 * 60_000L))
        assertEquals("1,284", formatMinutes(hours(1284.6)))
    }
}
