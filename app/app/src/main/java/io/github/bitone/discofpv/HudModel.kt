/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Everything the HUD shows, independent of Android and the SDK (each HUD
 * mode renders it its own way). Null means unknown.
 */
data class HudModel(
    val connected: Boolean = false,
    val flyingState: String? = null,
    val altitude: Double? = null,      // m, relative to take-off
    val airSpeed: Double? = null,      // m/s
    val groundSpeed: Double? = null,   // m/s
    val battery: Int? = null,          // %
    val rcBattery: Int? = null,        // %
    val gpsFixed: Boolean? = null,
    val satellites: Int? = null,
    val roll: Double? = null,          // degrees, right wing down positive
    val pitch: Double? = null,         // degrees, nose up positive
    val heading: Double? = null,       // degrees, 0 north, clockwise
    val position: GeoPoint? = null,
    val home: GeoPoint? = null,
) {
    /** Distance to home in metres, when both positions are known. */
    val homeDistance: Double?
        get() = if (position != null && home != null) Geo.distance(position, home) else null

    /**
     * Direction of home relative to the nose, degrees in (-180, 180]: 0 ahead,
     * 90 on the right. Needs both positions and the heading.
     */
    val homeRelativeBearing: Double?
        get() {
            if (position == null || home == null || heading == null) return null
            return Geo.normalize180(Geo.bearing(position, home) - heading)
        }
}

data class GeoPoint(val latitude: Double, val longitude: Double)

/** Spherical earth geometry, good enough at model aircraft distances. */
object Geo {
    private const val EARTH_RADIUS = 6_371_000.0

    private fun rad(deg: Double) = deg * PI / 180

    /** Great-circle distance, metres (haversine). */
    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val dLat = rad(b.latitude - a.latitude)
        val dLon = rad(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(rad(a.latitude)) * cos(rad(b.latitude)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS * asin(min(1.0, sqrt(h)))
    }

    /** Initial bearing from [a] to [b], degrees in [0, 360), 0 north, clockwise. */
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val dLon = rad(b.longitude - a.longitude)
        val y = sin(dLon) * cos(rad(b.latitude))
        val x = cos(rad(a.latitude)) * sin(rad(b.latitude)) -
            sin(rad(a.latitude)) * cos(rad(b.latitude)) * cos(dLon)
        return (atan2(y, x) * 180 / PI + 360) % 360
    }

    /** Angle in degrees brought into (-180, 180]. */
    fun normalize180(deg: Double): Double {
        var d = deg % 360
        if (d > 180) d -= 360
        if (d <= -180) d += 360
        return d
    }
}
