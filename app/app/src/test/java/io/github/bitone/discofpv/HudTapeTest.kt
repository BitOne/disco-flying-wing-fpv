/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import org.junit.Assert.assertEquals
import org.junit.Test

class HudTapeTest {
    @Test fun ticks() {
        assertEquals(listOf(10.0, 15.0, 20.0), HudTape.ticks(15.2, 6.0, 5.0))
        assertEquals(listOf(-5.0, 0.0, 5.0), HudTape.ticks(0.0, 5.0, 5.0))
    }

    @Test fun headingLabels() {
        assertEquals("N", HudTape.headingLabel(0.0))
        assertEquals("N", HudTape.headingLabel(360.0))
        assertEquals("E", HudTape.headingLabel(90.0))
        assertEquals("33", HudTape.headingLabel(-30.0))
        assertEquals("03", HudTape.headingLabel(30.0))
        assertEquals("090", HudTape.heading(89.7))
        assertEquals("350", HudTape.heading(-10.0))
        assertEquals("--", HudTape.heading(null))
    }

    @Test fun modesCycle() {
        assertEquals(HudMode.MINIMAL, HudMode.CLASSIC.next())
        assertEquals(HudMode.FIGHTER, HudMode.MINIMAL.next())
        assertEquals(HudMode.CLASSIC, HudMode.FIGHTER.next())
        assertEquals(HudMode.CLASSIC, HudMode.parse("nonsense"))
        assertEquals(HudMode.FIGHTER, HudMode.parse("FIGHTER"))
    }

    @Test fun homeReadout() {
        assertEquals("HOME 68 m", HudFormat.home(68.4))
        assertEquals("HOME 12.3 km", HudFormat.home(12_345.0))
        assertEquals("HOME --", HudFormat.home(null))
    }
}
