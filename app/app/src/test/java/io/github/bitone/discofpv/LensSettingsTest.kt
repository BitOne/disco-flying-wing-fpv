/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import org.junit.Assert.assertEquals
import org.junit.Test

class LensSettingsTest {

    @Test
    fun stepsStayOnTheGridAndInRange() {
        var s = LensSettings()
        repeat(7) { s = s.stepLeft(1) }
        assertEquals("LEFT 0.35", s.leftText())
        assertEquals(0.35, s.left, 1e-12)
        repeat(30) { s = s.stepLeft(1) }
        assertEquals(1.0, s.left, 0.0)
        s = s.stepRight(-1)
        assertEquals(0.0, s.right, 0.0)
    }

    @Test
    fun verticalIsSignedMillimetres() {
        var s = LensSettings()
        repeat(3) { s = s.stepVertical(-1) }
        assertEquals("HEIGHT -1.5 mm", s.verticalText())
        repeat(100) { s = s.stepVertical(1) }
        assertEquals("HEIGHT +10.0 mm", s.verticalText())
    }

    @Test
    fun valuesReadBackFromFloatsSnapToTheGrid() {
        // preferences store floats: 0.35f is 0.3499999940395355
        assertEquals("RIGHT 0.40", LensSettings(right = 0.35f.toDouble()).stepRight(1).rightText())
    }
}
