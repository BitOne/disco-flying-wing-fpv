/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Head tracking and view geometry, independent of Android (unit-tested).
 *
 * The phone sits in the headset with its screen facing the eyes, so the
 * head looks along the phone's -Z axis. Android's rotation matrices map
 * device coordinates to world ones (x east, y north, z up): the look
 * direction is minus the matrix's third column.
 */
object HeadMath {
    private fun deg(rad: Double) = rad * 180 / PI
    private fun rad(deg: Double) = deg * PI / 180

    /** (yaw, pitch) of the look direction, degrees: yaw clockwise from north, pitch up. */
    fun look(rotation: FloatArray): Pair<Double, Double> {
        val x = -rotation[2].toDouble()
        val y = -rotation[5].toDouble()
        val z = -rotation[8].toDouble()
        val yaw = deg(atan2(x, y))
        val pitch = deg(asin(z.coerceIn(-1.0, 1.0)))
        return Pair(yaw, pitch)
    }

    /**
     * Camera pan and tilt (degrees) for the head's [look] relative to the
     * [reference] look (the centred view), within +-[limit].
     */
    fun cameraFor(look: Pair<Double, Double>, reference: Pair<Double, Double>, limit: Double): Pair<Double, Double> {
        val pan = Geo.normalize180(look.first - reference.first).coerceIn(-limit, limit)
        val tilt = (look.second - reference.second).coerceIn(-limit, limit)
        return Pair(pan, tilt)
    }

    /**
     * The horizon as the camera sees it, looking [pan] degrees right of
     * the nose and [tilt] up: (roll, pitch) of the view. Looking along the
     * right wing, a right roll lowers the view (pitch -roll); looking
     * ahead it is the aircraft's attitude plus the tilt.
     */
    fun viewAttitude(roll: Double, pitch: Double, pan: Double, tilt: Double): Pair<Double, Double> {
        val p = rad(pan)
        val viewRoll = roll * cos(p) + pitch * sin(p)
        val viewPitch = pitch * cos(p) - roll * sin(p) + tilt
        return Pair(viewRoll, viewPitch)
    }
}
