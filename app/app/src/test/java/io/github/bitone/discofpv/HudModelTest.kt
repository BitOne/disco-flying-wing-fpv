/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HudModelTest {
    private val home = GeoPoint(45.1885, 5.7245)

    @Test fun distanceMatchesTheFakeScenario() {
        // 0.0005 deg north and east of home at 45 deg: 55.6 m N, 39.2 m E
        assertEquals(68.0, Geo.distance(GeoPoint(45.1890, 5.7250), home), 0.5)
    }

    @Test fun bearings() {
        assertEquals(0.0, Geo.bearing(home, GeoPoint(45.2, 5.7245)), 0.01)
        assertEquals(90.0, Geo.bearing(home, GeoPoint(45.1885, 5.8)), 0.1)
        assertEquals(180.0, Geo.bearing(home, GeoPoint(45.1, 5.7245)), 0.01)
        assertEquals(270.0, Geo.bearing(home, GeoPoint(45.1885, 5.6)), 0.1)
    }

    @Test fun homeRelativeToTheNose() {
        val north = GeoPoint(45.1895, 5.7245) // home is due south
        assertEquals(180.0, HudModel(position = north, home = home, heading = 0.0).homeRelativeBearing!!, 0.01)
        assertEquals(0.0, HudModel(position = north, home = home, heading = 180.0).homeRelativeBearing!!, 0.01)
        assertEquals(90.0, HudModel(position = north, home = home, heading = 90.0).homeRelativeBearing!!, 0.01)
        assertEquals(-90.0, HudModel(position = north, home = home, heading = 270.0).homeRelativeBearing!!, 0.01)
        assertNull(HudModel(position = north, home = home).homeRelativeBearing)
        assertNull(HudModel(position = north, heading = 0.0).homeDistance)
    }

    @Test fun normalize() {
        assertEquals(-170.0, Geo.normalize180(190.0), 1e-9)
        assertEquals(180.0, Geo.normalize180(-180.0), 1e-9)
        assertEquals(10.0, Geo.normalize180(370.0), 1e-9)
    }
}
