/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {

    @Test
    fun hudSizeCyclesInTensOfPercent() {
        var s = AppSettings(headsetHudPercent = 80)
        s = s.nextHudPercent()
        assertEquals(90, s.headsetHudPercent)
        s = s.nextHudPercent().nextHudPercent()
        assertEquals(AppSettings.MIN_HUD_PERCENT, s.headsetHudPercent)
    }

    @Test
    fun mapButtonsCycleThroughAll() {
        var b = AppSettings.MapButton.NONE
        repeat(AppSettings.MapButton.entries.size) { b = b.next() }
        assertEquals(AppSettings.MapButton.NONE, b)
    }

    @Test
    fun viewPitchAddsTheCameraTilt() {
        assertEquals(-15.0, HudModel(pitch = 5.0, cameraTilt = -20.0).viewPitch!!, 0.0)
        assertEquals(5.0, HudModel(pitch = 5.0).viewPitch!!, 0.0)
    }
}
