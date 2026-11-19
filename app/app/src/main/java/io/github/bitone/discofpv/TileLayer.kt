/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Map tiles (XYZ, Web Mercator) for the moving map: from memory, from the
 * disk cache (kept, so a flying field seen once works offline), else
 * downloaded in the background; [onTile] is called when one arrives.
 * [prefetch] downloads an area ahead, for the sources whose terms allow it.
 */
class TileLayer(context: Context, val source: TileSource, private val onTile: () -> Unit) {

    private val dir = File(context.filesDir, "tiles/${source.id}")
    private val memory = object : LruCache<String, Bitmap>(MEMORY_TILES) {}
    private val pending = mutableSetOf<String>()
    private val failed = mutableMapOf<String, Long>()
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    private fun key(z: Int, x: Int, y: Int) = "$z/$x/$y"

    private fun file(z: Int, x: Int, y: Int) = File(dir, "$z/$x/$y.tile")

    /** The tile if available now; else starts loading it and returns null. */
    fun get(z: Int, x: Int, y: Int): Bitmap? {
        val k = key(z, x, y)
        memory.get(k)?.let { return it }
        synchronized(pending) {
            if (k in pending) return null
            val failedAt = failed[k]
            if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_MS) return null
            pending += k
        }
        io.execute {
            val bitmap = load(z, x, y)
            synchronized(pending) {
                pending -= k
                if (bitmap == null) failed[k] = System.currentTimeMillis()
            }
            if (bitmap != null) {
                memory.put(k, bitmap)
                main.post(onTile)
            }
        }
        return null
    }

    /** From the disk cache, else downloaded (and cached). Background thread. */
    private fun load(z: Int, x: Int, y: Int): Bitmap? {
        val f = file(z, x, y)
        if (!f.exists() && !download(z, x, y, f)) return null
        return BitmapFactory.decodeFile(f.path)
    }

    private fun download(z: Int, x: Int, y: Int, f: File): Boolean {
        val url = TileMath.url(source.url, z, x, y)
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 10000
            // tile servers' policies ask for an identifying User-Agent
            c.setRequestProperty("User-Agent", USER_AGENT)
            if (c.responseCode != 200) {
                Log.w(TAG, "tile $url: HTTP ${c.responseCode}")
                c.disconnect()
                return false
            }
            val bytes = c.inputStream.use { it.readBytes() }
            c.disconnect()
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".part")
            tmp.writeBytes(bytes)
            tmp.renameTo(f)
        } catch (e: Exception) {
            Log.w(TAG, "tile $url: ${e.message}")
            false
        }
    }

    /**
     * Downloads the tiles of [radiusMetres] around [center] from zoom 12 to
     * the source's maximum (at most [MAX_PREFETCH] tiles), reporting
     * progress as (done, total) on the main thread. Only for sources that
     * allow it (OpenStreetMap's tile policy forbids bulk downloads).
     */
    fun prefetch(center: GeoPoint, radiusMetres: Double, progress: (Int, Int) -> Unit) {
        if (!source.prefetch) return
        val tiles = (PREFETCH_MIN_ZOOM..source.maxZoom)
            .flatMap { TileMath.tilesAround(center, radiusMetres, it) }
            .take(MAX_PREFETCH)
        Executors.newSingleThreadExecutor().execute {
            var done = 0
            for ((z, x, y) in tiles) {
                val f = file(z, x, y)
                if (!f.exists()) download(z, x, y, f)
                done++
                if (done % 10 == 0 || done == tiles.size) {
                    val d = done
                    main.post { progress(d, tiles.size) }
                }
            }
        }
    }

    fun close() {
        io.shutdownNow()
    }

    companion object {
        private const val TAG = "TileLayer"
        private const val MEMORY_TILES = 48
        private const val RETRY_MS = 15_000L
        private const val PREFETCH_MIN_ZOOM = 12
        const val MAX_PREFETCH = 2000
        const val USER_AGENT = "DiscoFPV/0.1 (+https://github.com/BitOne/disco-flying-wing-fpv)"
    }
}

/**
 * Where map tiles come from. [prefetch]: the terms allow downloading an
 * area ahead for offline use.
 */
data class TileSource(
    val id: String,
    val label: String,
    val url: String,
    val attribution: String,
    val maxZoom: Int,
    val prefetch: Boolean,
) {
    companion object {
        /** OpenStreetMap's map: viewed tiles only, no bulk download (tile usage policy). */
        val OSM = TileSource("osm", "OpenStreetMap",
            "https://tile.openstreetmap.org/{z}/{x}/{y}.png", "© OpenStreetMap contributors", 19, false)

        /** IGN's aerial photos of France (Géoplateforme, Licence Ouverte), 20 cm. */
        val IGN_PHOTO = TileSource("ign-photo", "IGN aerial photos (France)",
            "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0" +
                "&LAYER=ORTHOIMAGERY.ORTHOPHOTOS&STYLE=normal&TILEMATRIXSET=PM" +
                "&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&FORMAT=image/jpeg",
            "IGN Géoplateforme", 19, true)

        /** Any XYZ source, for example a satellite layer with the user's own key. */
        fun custom(url: String) = TileSource("custom-" + Integer.toHexString(url.hashCode()), "custom",
            url, "", 19, true)
    }
}
