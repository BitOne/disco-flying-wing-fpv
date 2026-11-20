/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RcSetupTest {

    private val required = setOf("ROLL", "PITCH", "GAZ", "TAKE_CONTROL")

    @Test
    fun stepsInOrderNeutralFirst() {
        assertEquals(0, RcSetup.current(false, emptySet()))
        assertEquals(1, RcSetup.current(true, emptySet()))
        assertEquals(3, RcSetup.current(true, setOf("ROLL", "PITCH")))
        assertEquals(5, RcSetup.current(true, required)) // the optional ones next
        assertEquals(-1, RcSetup.current(true, required + "RETURN_HOME" + "TAKEOFF_LAND"))
    }

    @Test
    fun readyOnceTheRequiredActionsAreCalibrated() {
        assertFalse(RcSetup.ready(true, setOf("ROLL", "PITCH", "GAZ"), required))
        assertFalse(RcSetup.ready(false, required, required))
        assertTrue(RcSetup.ready(true, required, required))
    }

    @Test
    fun channelsAsBars() {
        assertEquals(" 1 roll            -40 [      ----|          ]", RcSetup.bar(1, "ROLL", -40))
        assertEquals(" 5 take control    100 [          |++++++++++]", RcSetup.bar(5, "TAKE_CONTROL", 100))
        assertEquals("12 ch 12             0 [          |          ]", RcSetup.bar(12, null, 0))
    }

    @Test
    fun statusSaysWhoCanFly() {
        assertEquals("No receiver on the Disco", RcSetup.status(false, "", false))
        assertEquals("Receiver SBUS connected, radio off", RcSetup.status(true, "SBUS", false))
    }
}
