/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Web Mercator ("slippy map") tile geometry, the XYZ scheme of OpenStreetMap
 * and most map and photo services: 256 px tiles, zoom z has 2^z x 2^z of
 * them. Independent of Android (unit-tested).
 */
object TileMath {
    const val TILE_PX = 256
    private const val EARTH_CIRCUMFERENCE = 40_075_016.686

    /** Global pixel coordinates (x east, y south) of a point at [zoom]. */
    fun pixel(p: GeoPoint, zoom: Int): Pair<Double, Double> {
        val n = TILE_PX * 2.0.pow(zoom)
        val lat = p.latitude.coerceIn(-85.05112878, 85.05112878) * PI / 180
        val x = (p.longitude + 180) / 360 * n
        val y = (1 - ln(tan(lat) + 1 / cos(lat)) / PI) / 2 * n
        return Pair(x, y)
    }

    /** The point at global pixel coordinates (inverse of [pixel]). */
    fun point(x: Double, y: Double, zoom: Int): GeoPoint {
        val n = TILE_PX * 2.0.pow(zoom)
        val lon = x / n * 360 - 180
        val lat = atan(sinh(PI * (1 - 2 * y / n))) * 180 / PI
        return GeoPoint(lat, lon)
    }

    /** Ground metres per pixel at [latitude] and [zoom]. */
    fun metresPerPixel(latitude: Double, zoom: Int): Double =
        EARTH_CIRCUMFERENCE * cos(latitude * PI / 180) / (TILE_PX * 2.0.pow(zoom))

    /**
     * The zoom whose tiles are drawn at [metresPerScreenPixel]: the
     * smallest with at least that resolution (tiles drawn at most 2x
     * enlarged... and down-scaled otherwise), within [minZoom]..[maxZoom].
     */
    fun zoomFor(latitude: Double, metresPerScreenPixel: Double, minZoom: Int = 1, maxZoom: Int = 19): Int {
        for (z in minZoom..maxZoom) {
            if (metresPerPixel(latitude, z) <= metresPerScreenPixel) return z
        }
        return maxZoom
    }

    /** Tile containing global pixel coordinates. */
    fun tileOf(x: Double, y: Double): Pair<Int, Int> =
        Pair(floor(x / TILE_PX).toInt(), floor(y / TILE_PX).toInt())

    /** Fills a URL template: {z}, {x}, {y} (and {s}: a, b or c). */
    fun url(template: String, z: Int, x: Int, y: Int): String =
        template.replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
            .replace("{s}", "abc"[Math.floorMod(x + y, 3)].toString())

    /** Tiles of [zoom] covering a disc of [radiusMetres] around [center]. */
    fun tilesAround(center: GeoPoint, radiusMetres: Double, zoom: Int): List<Triple<Int, Int, Int>> {
        val (cx, cy) = pixel(center, zoom)
        val r = radiusMetres / metresPerPixel(center.latitude, zoom)
        val (x0, y0) = tileOf(cx - r, cy - r)
        val (x1, y1) = tileOf(cx + r, cy + r)
        val max = 2.0.pow(zoom).toInt() - 1
        val out = mutableListOf<Triple<Int, Int, Int>>()
        for (x in x0.coerceAtLeast(0)..x1.coerceAtMost(max)) {
            for (y in y0.coerceAtLeast(0)..y1.coerceAtMost(max)) out += Triple(zoom, x, y)
        }
        return out
    }
}
