/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import io.github.bitone.discofpv.ButtonSafety.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonSafetyTest {

    /** The Disco's default mapping, as the fake and FreeFlight show it. */
    private val defaults = listOf(
        Entry("APP_ACTION_SETTINGS", setOf("SETTINGS")),
        Entry("APP_ACTION_1", setOf("BUTTON_B")),
        Entry("APP_ACTION_2", setOf("BUTTON_A")),
        Entry("TAKEOFF_OR_LAND", setOf("TAKEOFF_LAND")),
        Entry("RETURN_HOME", setOf("RETURN_HOME")),
        Entry("TAKE_PICTURE", setOf("RIGHT_SLIDER_DOWN")),
    )

    @Test
    fun appButtonsAreFree() {
        for (e in listOf("SETTINGS", "BUTTON_B", "BUTTON_A", "REAR_LEFT_BUTTON")) {
            assertTrue(e, ButtonSafety.droneActionsOn(defaults, e).isEmpty())
        }
    }

    @Test
    fun aRemappedFlightActionIsNotTaken() {
        val remapped = defaults + Entry("RETURN_HOME", setOf("BUTTON_B"))
        assertEquals(listOf("RETURN_HOME"), ButtonSafety.droneActionsOn(remapped, "BUTTON_B"))
        assertEquals("B: RETURN HOME, NOT TAKEN", ButtonSafety.warning("B", listOf("RETURN_HOME")))
    }

    @Test
    fun aCombinationCountsToo() {
        val combo = defaults + Entry("EMERGENCY_CUTOFF", setOf("BUTTON_A", "REAR_RIGHT_BUTTON"))
        assertEquals(listOf("EMERGENCY_CUTOFF"), ButtonSafety.droneActionsOn(combo, "BUTTON_A"))
        assertEquals(listOf("EMERGENCY_CUTOFF"), ButtonSafety.droneActionsOn(combo, "REAR_RIGHT_BUTTON"))
    }
}
