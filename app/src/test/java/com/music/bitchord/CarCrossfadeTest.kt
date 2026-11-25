package com.music.bitchord

import com.music.bitchord.data.settings.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fork: Automix stands aside for a plain crossfade while Android Auto is connected. */
class CarCrossfadeTest {

    @After
    fun reset() {
        AppSettings.smartFadeEnabled.value = false
        AppSettings.carPlainCrossfade.value = true
        AppSettings.inCar.value = false
        AppSettings.crossfadeSeconds.value = 0
    }

    @Test
    fun automixRunsOffTheRoad() {
        AppSettings.smartFadeEnabled.value = true
        assertTrue(AppSettings.automixActive)
        assertEquals(0, AppSettings.effectiveCrossfadeSeconds)
    }

    @Test
    fun inTheCarAutomixBecomesAPlainFade() {
        AppSettings.smartFadeEnabled.value = true
        AppSettings.inCar.value = true
        assertFalse(AppSettings.automixActive)
        assertEquals(AppSettings.CAR_CROSSFADE_SECONDS, AppSettings.effectiveCrossfadeSeconds)
    }

    @Test
    fun aManualLengthIsKeptInTheCar() {
        AppSettings.smartFadeEnabled.value = true
        AppSettings.inCar.value = true
        AppSettings.crossfadeSeconds.value = 3
        assertEquals(3, AppSettings.effectiveCrossfadeSeconds)
    }

    @Test
    fun switchedOffAutomixKeepsRunningInTheCar() {
        AppSettings.smartFadeEnabled.value = true
        AppSettings.inCar.value = true
        AppSettings.carPlainCrossfade.value = false
        assertTrue(AppSettings.automixActive)
        assertEquals(0, AppSettings.effectiveCrossfadeSeconds)
    }

    @Test
    fun noAutomixMeansNoCarFadeEither() {
        AppSettings.inCar.value = true
        assertFalse(AppSettings.automixActive)
        assertEquals(0, AppSettings.effectiveCrossfadeSeconds)
    }
}
