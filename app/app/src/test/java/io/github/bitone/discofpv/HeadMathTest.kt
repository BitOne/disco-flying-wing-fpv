/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import org.junit.Assert.assertEquals
import org.junit.Test

class HeadMathTest {
    /** Rotation matrix (device to world) of a phone looking at [yaw], [pitch] degrees. */
    private fun lookingAt(yaw: Double, pitch: Double): FloatArray {
        val y = Math.toRadians(yaw)
        val p = Math.toRadians(pitch)
        // look direction; only the third column (minus it) matters here
        val lx = Math.sin(y) * Math.cos(p)
        val ly = Math.cos(y) * Math.cos(p)
        val lz = Math.sin(p)
        return floatArrayOf(1f, 0f, (-lx).toFloat(), 0f, 1f, (-ly).toFloat(), 0f, 0f, (-lz).toFloat())
    }

    @Test
    fun lookDirection() {
        val (yaw, pitch) = HeadMath.look(lookingAt(30.0, -20.0))
        assertEquals(30.0, yaw, 1e-4)
        assertEquals(-20.0, pitch, 1e-4)
    }

    @Test
    fun cameraRelativeToTheCentredViewAndLimited() {
        val ref = Pair(350.0 - 360, 5.0)
        assertEquals(Pair(30.0, -25.0), HeadMath.cameraFor(Pair(20.0, -20.0), ref, 80.0))
        assertEquals(Pair(-80.0, 80.0), HeadMath.cameraFor(Pair(200.0, 90.0), Pair(0.0, 0.0), 80.0))
    }

    @Test
    fun horizonLookingSideways() {
        // ahead: the attitude plus the tilt
        val ahead = HeadMath.viewAttitude(10.0, 5.0, 0.0, -20.0)
        assertEquals(10.0, ahead.first, 1e-9)
        assertEquals(-15.0, ahead.second, 1e-9)
        // along the right wing, a right roll lowers the view
        val right = HeadMath.viewAttitude(10.0, 0.0, 90.0, 0.0)
        assertEquals(0.0, right.first, 1e-9)
        assertEquals(-10.0, right.second, 1e-9)
    }
}
