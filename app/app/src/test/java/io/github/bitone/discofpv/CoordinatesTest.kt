/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CoordinatesTest {

    private fun near(lat: Double, lon: Double, input: String) {
        val p = Coordinates.parse(input)
        assertNotNull(input, p)
        assertEquals(input, lat, p!!.latitude, 1e-4)
        assertEquals(input, lon, p.longitude, 1e-4)
    }

    @Test
    fun decimalDegrees() {
        near(45.1885, 5.7245, "45.1885, 5.7245")
        near(45.1885, 5.7245, "45.1885 5.7245")
        near(45.1885, 5.7245, "  45.1885,5.7245 ")
        near(-33.8568, 151.2153, "-33.8568, 151.2153")
        near(45.1885, 5.7245, "45,1885; 5,7245")
        near(45.1885, 5.7245, "45,1885 5,7245")
    }

    @Test
    fun hemispheres() {
        near(45.1885, 5.7245, "45.1885N 5.7245E")
        near(45.1885, -1.5, "N 45.1885 W 1.5")
        near(-33.8568, 151.2153, "33.8568 S, 151.2153 E")
        // longitude first, but the letters say so
        near(45.1885, 5.7245, "5.7245E 45.1885N")
    }

    @Test
    fun degreesMinutesSeconds() {
        near(45.18850, 5.72450, "45°11'18.6\"N 5°43'28.2\"E")
        near(45.18850, -5.72450, "45°11'18.6\"N, 5°43'28.2\"W")
        near(45.1885, 5.7245, "45°11.31'N 5°43.47'E")
        near(45.1885, 5.7245, "N 45° 11.310' E 005° 43.470'")
    }

    @Test
    fun links() {
        near(45.1885, 5.7245, "geo:45.1885,5.7245?z=15")
        near(45.1885, 5.7245, "https://www.google.com/maps/@45.1885,5.7245,15z")
        near(45.1885, 5.7245, "https://maps.google.com/?q=45.1885,5.7245")
        near(45.1885, 5.7245, "https://www.openstreetmap.org/?mlat=45.1885&mlon=5.7245#map=15/45.1885/5.7245")
        near(45.1885, 5.7245, "https://www.openstreetmap.org/#map=15/45.1885/5.7245")
    }

    @Test
    fun notAPosition() {
        assertNull(Coordinates.parse(""))
        assertNull(Coordinates.parse("Grenoble"))
        assertNull(Coordinates.parse("45.1885"))
        assertNull(Coordinates.parse("95, 5"))
        assertNull(Coordinates.parse("45, 190"))
        assertNull(Coordinates.parse("1 2 3"))
        assertNull(Coordinates.parse("45N 5N"))
    }

    @Test
    fun format() {
        assertEquals("45.18850, 5.72450", Coordinates.format(GeoPoint(45.1885, 5.7245)))
    }
}
