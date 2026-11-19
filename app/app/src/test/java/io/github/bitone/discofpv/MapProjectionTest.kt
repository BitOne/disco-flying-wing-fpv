/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import org.junit.Assert.assertEquals
import org.junit.Test

class MapProjectionTest {
    private val center = GeoPoint(45.0, 5.0)
    private val north100 = GeoPoint(45.0 + 100 / MapProjection.M_PER_DEG, 5.0)

    @Test
    fun northIsUpWhenHeadingNorth() {
        val (x, y) = MapProjection(center, 0.0, 1.0).project(north100)
        assertEquals(0.0, x, 0.01)
        assertEquals(-100.0, y, 0.01)
    }

    @Test
    fun trackUpRotatesTheMap() {
        // heading east: a point to the north is on the left
        val (x, y) = MapProjection(center, 90.0, 2.0).project(north100)
        assertEquals(-200.0, x, 0.01)
        assertEquals(0.0, y, 0.01)
    }

    @Test
    fun ringKeepsHomeInView() {
        assertEquals(100.0, MapProjection.ringFor(null), 0.0)
        assertEquals(100.0, MapProjection.ringFor(30.0), 0.0)
        assertEquals(500.0, MapProjection.ringFor(300.0), 0.0)
        assertEquals(1000.0, MapProjection.ringFor(480.0), 0.0)
        assertEquals(5000.0, MapProjection.ringFor(9000.0), 0.0)
    }
}
