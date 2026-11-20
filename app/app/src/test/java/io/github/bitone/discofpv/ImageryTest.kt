/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageryTest {

    private fun idsAt(lat: Double, lon: Double, z: Int = 16): List<String> {
        val (gx, gy) = TileMath.pixel(GeoPoint(lat, lon), z)
        val (x, y) = TileMath.tileOf(gx, gy)
        return Imagery.candidates(Imagery.ALL, z, x, y).map { it.id }
    }

    @Test
    fun nationalPhotosFirstThenTheWorld() {
        assertEquals(listOf("ign-fr", "s2cloudless-2016"), idsAt(45.764, 4.836)) // Lyon
        assertEquals(listOf("ign-fr", "swisstopo", "s2cloudless-2016"), idsAt(46.948, 7.447)) // Bern: IGN answers 404
        assertEquals(listOf("pnoa", "s2cloudless-2016"), idsAt(40.45, -3.7)) // Madrid
        assertEquals(listOf("pdok", "s2cloudless-2016"), idsAt(52.37, 4.89)) // Amsterdam
        assertEquals(listOf("usgs", "s2cloudless-2016"), idsAt(21.2665, -157.8205)) // SITL's home, Honolulu
        assertEquals(listOf("s2cloudless-2016"), idsAt(-33.86, 151.21)) // Sydney
    }

    @Test
    fun theWorldIsLast() {
        assertEquals(Imagery.SENTINEL2, Imagery.ALL.last())
        assertTrue(Imagery.ALL.dropLast(1).all { it.maxZoom > Imagery.SENTINEL2.maxZoom })
    }

    @Test
    fun blankTilesAreRecognised() {
        val white = IntArray(64) { 0xffffffff.toInt() }
        assertTrue(Imagery.isBlank(white))
        // a JPEG of one colour: a little noise
        val noisy = IntArray(64) { i -> 0xff000000.toInt() or ((240 + i % 7) * 0x010101) }
        assertTrue(Imagery.isBlank(noisy))
        val photo = IntArray(64) { i -> 0xff000000.toInt() or ((40 + i * 3) * 0x010101) }
        assertFalse(Imagery.isBlank(photo))
    }

    @Test
    fun satelliteSourceAllowsDownloadingAhead() {
        val s = AppSettings(mapTiles = AppSettings.MapTiles.SATELLITE).tileSource()!!
        assertEquals(Imagery.ALL, s.imagery)
        assertTrue(s.prefetch)
    }
}
