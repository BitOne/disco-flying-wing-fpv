/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.graphics.Bitmap

/**
 * One source of satellite or aerial photos (XYZ [url]) and the area it
 * covers, roughly (a box: outside its country a service answers an error or
 * a blank tile, and the next one is used). All are open data whose terms
 * allow caching and downloading ahead, with [attribution]; [maxZoom] is the
 * deepest zoom with real detail, enlarged beyond.
 */
data class Imagery(
    val id: String,
    val url: String,
    val attribution: String,
    val maxZoom: Int,
    val boxes: List<DoubleArray>, // south, west, north, east
) {
    fun covers(p: GeoPoint): Boolean = boxes.any { b ->
        p.latitude in b[0]..b[2] && p.longitude in b[1]..b[3]
    }

    companion object {
        /** IGN's WMTS (also [TileSource.IGN_PHOTO]; not read from it, to keep the two companions' init independent). */
        const val IGN_PHOTO_URL = "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0" +
            "&LAYER=ORTHOIMAGERY.ORTHOPHOTOS&STYLE=normal&TILEMATRIXSET=PM" +
            "&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&FORMAT=image/jpeg"

        private fun box(s: Double, w: Double, n: Double, e: Double) = doubleArrayOf(s, w, n, e)

        /** France (metropolitan, Corsica): IGN BD ORTHO, 20 cm, Licence Ouverte. */
        val IGN_FR = Imagery("ign-fr", IGN_PHOTO_URL, "IGN Géoplateforme", 19,
            listOf(box(41.3, -5.3, 51.2, 9.7)))

        /** Switzerland: swisstopo SWISSIMAGE, 10 cm, free use with the source named. */
        val SWISSTOPO = Imagery("swisstopo",
            "https://wmts.geo.admin.ch/1.0.0/ch.swisstopo.swissimage/default/current/3857/{z}/{x}/{y}.jpeg",
            "© swisstopo", 19, listOf(box(45.8, 5.9, 47.85, 10.55)))

        /** Spain (with the Balearic and Canary Islands): IGN PNOA, 25 cm, CC BY 4.0. */
        val PNOA = Imagery("pnoa",
            "https://www.ign.es/wmts/pnoa-ma?request=GetTile&service=WMTS&VERSION=1.0.0" +
                "&Layer=OI.OrthoimageCoverage&Style=default&Format=image/jpeg" +
                "&TileMatrixSet=GoogleMapsCompatible&TileMatrix={z}&TileRow={y}&TileCol={x}",
            "PNOA cedido por © Instituto Geográfico Nacional", 19,
            listOf(box(35.1, -9.5, 43.9, 4.4), box(27.5, -18.3, 29.5, -13.3)))

        /** The Netherlands: Beeldmateriaal Nederland via PDOK, 8 cm, CC BY 4.0. */
        val PDOK = Imagery("pdok",
            "https://service.pdok.nl/hwh/luchtfotorgb/wmts/v1_0/Actueel_orthoHR/EPSG:3857/{z}/{x}/{y}.jpeg",
            "Beeldmateriaal.nl (PDOK), CC BY 4.0", 19, listOf(box(50.7, 3.2, 53.7, 7.3)))

        /** The United States (contiguous, Hawaii): USGS The National Map, public domain. */
        val USGS = Imagery("usgs",
            "https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer/tile/{z}/{y}/{x}",
            "USGS The National Map", 16,
            listOf(box(24.4, -125.0, 49.5, -66.9), box(18.8, -160.4, 22.4, -154.7)))

        /** The whole world: EOX Sentinel-2 cloudless 2016, 10 m, CC BY 4.0. */
        val SENTINEL2 = Imagery("s2cloudless-2016",
            "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless_3857/default/g/{z}/{y}/{x}.jpg",
            "Sentinel-2 cloudless by EOX (Copernicus Sentinel data 2016)", 15,
            listOf(box(-90.0, -180.0, 90.0, 180.0)))

        /** In order of preference: the finest first, the world last. */
        val ALL = listOf(IGN_FR, SWISSTOPO, PNOA, PDOK, USGS, SENTINEL2)

        /** The providers of [all] covering the centre of tile [z]/[x]/[y], in order. */
        fun candidates(all: List<Imagery>, z: Int, x: Int, y: Int): List<Imagery> {
            val half = TileMath.TILE_PX / 2.0
            val c = TileMath.point(x * TileMath.TILE_PX + half, y * TileMath.TILE_PX + half, z)
            return all.filter { it.covers(c) }
        }

        /**
         * A tile with no picture: services answer one outside their area
         * (white, or a JPEG of one colour with a little noise).
         */
        fun isBlank(pixels: IntArray): Boolean {
            var lo = 255
            var hi = 0
            for (p in pixels) {
                val l = ((p shr 16 and 0xff) * 3 + (p shr 8 and 0xff) * 6 + (p and 0xff)) / 10
                lo = minOf(lo, l)
                hi = maxOf(hi, l)
            }
            return hi - lo < BLANK_SPREAD
        }

        fun isBlank(bmp: Bitmap): Boolean {
            val samples = IntArray(SAMPLES * SAMPLES) { i ->
                bmp.getPixel((i % SAMPLES) * bmp.width / SAMPLES + 1, (i / SAMPLES) * bmp.height / SAMPLES + 1)
            }
            return isBlank(samples)
        }

        private const val SAMPLES = 8
        private const val BLANK_SPREAD = 12
    }
}
