/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Moving map geometry (track up): positions relative to the drone, in
 * metres east and north, rotated so the drone's heading points up, then
 * scaled to pixels. Flat earth, fine at the Disco's 2 km range.
 * Independent of Android (unit-tested).
 */
class MapProjection(
    private val center: GeoPoint,
    private val heading: Double, // degrees, the map's up
    private val pxPerMetre: Double,
) {
    /** Screen offset (x right, y down, pixels) of [p] from the centre. */
    fun project(p: GeoPoint): Pair<Double, Double> {
        val north = (p.latitude - center.latitude) * M_PER_DEG
        val east = (p.longitude - center.longitude) * M_PER_DEG * cos(center.latitude * PI / 180)
        val h = heading * PI / 180
        // rotate by -heading: the heading direction becomes up
        val right = east * cos(h) - north * sin(h)
        val up = east * sin(h) + north * cos(h)
        return Pair(right * pxPerMetre, -up * pxPerMetre)
    }

    companion object {
        const val M_PER_DEG = 111_320.0

        /** Map scales, metres for the ring radius. */
        val RING_METRES = doubleArrayOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0)

        /**
         * The smallest ring that keeps [distance] (home, or the last fix)
         * inside the map, at least 100 m.
         */
        fun ringFor(distance: Double?): Double {
            val d = distance ?: 0.0
            return RING_METRES.firstOrNull { it >= d * 1.1 && it >= 100.0 } ?: RING_METRES.last()
        }
    }
}
