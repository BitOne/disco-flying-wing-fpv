/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TileMathTest {

    @Test
    fun knownTile() {
        // Grenoble at zoom 12: the tile OpenStreetMap shows there
        val (x, y) = TileMath.pixel(GeoPoint(45.1885, 5.7245), 12)
        assertEquals(Pair(2113, 1470), TileMath.tileOf(x, y))
    }

    @Test
    fun pixelAndPointRoundTrip() {
        val p = GeoPoint(21.2665, -157.8205)
        val (x, y) = TileMath.pixel(p, 17)
        val back = TileMath.point(x, y, 17)
        assertEquals(p.latitude, back.latitude, 1e-7)
        assertEquals(p.longitude, back.longitude, 1e-7)
    }

    @Test
    fun zoomJustFinerThanTheScreen() {
        // about 1.19 m per pixel at zoom 17 at the equator, 0.84 at 45 deg
        assertEquals(1.194, TileMath.metresPerPixel(0.0, 17), 0.001)
        assertEquals(17, TileMath.zoomFor(45.0, 1.0))
        assertEquals(19, TileMath.zoomFor(45.0, 0.01))
        assertEquals(12, TileMath.zoomFor(45.0, 30.0))
    }

    @Test
    fun urlTemplates() {
        assertEquals("https://t/17/1/2.png", TileMath.url("https://t/{z}/{x}/{y}.png", 17, 1, 2))
        assertEquals("https://b.t/0/1/0", TileMath.url("https://{s}.t/{z}/{x}/{y}", 0, 1, 0))
    }

    @Test
    fun tilesAroundCoverTheDisc() {
        val tiles = TileMath.tilesAround(GeoPoint(45.1885, 5.7245), 500.0, 15)
        assertEquals(true, tiles.size in 4..16)
        assertEquals(true, tiles.all { it.first == 15 })
    }

    @Test
    fun customSourceNeedsAPlaceholder() {
        assertNull(AppSettings(mapTiles = AppSettings.MapTiles.CUSTOM, customTileUrl = "http://x").tileSource())
        assertEquals(TileSource.OSM, AppSettings(mapTiles = AppSettings.MapTiles.OSM).tileSource())
    }

    @Test
    fun redactHidesQueryValues() {
        assertEquals("https://t.example/{z}/{x}/{y}.jpg?key=…&style=…",
            TileMath.redact("https://t.example/{z}/{x}/{y}.jpg?key=s3cr3t&style=sat"))
        assertEquals("https://t.example/a.png?flag", TileMath.redact("https://t.example/a.png?flag"))
        assertEquals("http://10.0.2.2:8091/{z}/{x}/{y}.png", TileMath.redact("http://10.0.2.2:8091/{z}/{x}/{y}.png"))
    }
}
