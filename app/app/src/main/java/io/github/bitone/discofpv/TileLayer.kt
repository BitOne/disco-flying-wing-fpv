/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Map tiles (XYZ, Web Mercator) for the moving map: from memory, from the
 * disk cache (kept, so a flying field seen once works offline), else
 * downloaded in the background; [onTile] is called when one arrives.
 * [prefetch] downloads an area ahead, for the sources whose terms allow it.
 * A source with a [TileSource.mapFile] is drawn on the phone instead
 * ([MapsforgeTiles]), with no network and no disk cache. A source with
 * [TileSource.imagery] takes each tile from the first of its providers that
 * covers it and has data there (see [Imagery]).
 */
class TileLayer(context: Context, val source: TileSource, private val onTile: () -> Unit) {

    private val dir = File(context.filesDir, "tiles/${source.id}")
    private val memory = object : LruCache<String, Bitmap>(MEMORY_TILES) {}
    private val pending = mutableSetOf<String>()
    private val failed = mutableMapOf<String, Long>()
    // the Mapsforge renderer is single-threaded
    private val io = if (source.mapFile != null) Executors.newSingleThreadExecutor() else Executors.newFixedThreadPool(2)
    private val appContext = context.applicationContext
    private var offline: MapsforgeTiles? = null
    private var offlineFailed = false
    private val main = Handler(Looper.getMainLooper())
    /** The imagery provider each loaded tile came from, for the attribution. */
    private val providers = java.util.concurrent.ConcurrentHashMap<String, Imagery>()

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
        source.mapFile?.let { return renderOffline(it, z, x, y) }
        if (source.imagery.isNotEmpty()) return loadImagery(z, x, y)
        val f = file(z, x, y)
        if (!f.exists() && !download(TileMath.url(source.url, z, x, y), f)) return null
        return BitmapFactory.decodeFile(f.path)
    }

    /** The tile from the first provider with data there. Background thread. */
    private fun loadImagery(z: Int, x: Int, y: Int): Bitmap? {
        for (im in Imagery.candidates(source.imagery, z, x, y)) {
            val bmp = loadFrom(im, z, x, y) ?: continue
            providers[key(z, x, y)] = im
            return bmp
        }
        return null
    }

    /**
     * The tile from [im], cached under its id; beyond its maximum zoom, the
     * part of its deepest tile enlarged. Null where it has no data: an
     * error answer (kept as an empty file, not asked again) or a blank tile.
     */
    private fun loadFrom(im: Imagery, z: Int, x: Int, y: Int): Bitmap? {
        if (z > im.maxZoom) {
            val d = z - im.maxZoom
            val parent = loadFrom(im, im.maxZoom, x shr d, y shr d) ?: return null
            val sub = TileMath.TILE_PX shr d
            val part = Bitmap.createBitmap(parent, (x and ((1 shl d) - 1)) * sub, (y and ((1 shl d) - 1)) * sub, sub, sub)
            return Bitmap.createScaledBitmap(part, TileMath.TILE_PX, TileMath.TILE_PX, true)
        }
        val f = File(dir, "${im.id}/$z/$x/$y.tile")
        if (!f.exists() && !download(TileMath.url(im.url, z, x, y), f, keepMissing = true)) return null
        if (f.length() == 0L) return null
        val bmp = BitmapFactory.decodeFile(f.path) ?: return null
        return if (Imagery.isBlank(bmp)) null else bmp
    }

    /** The attribution of what is drawn at tile [z]/[x]/[y]. */
    fun attribution(z: Int, x: Int, y: Int): String =
        providers[key(z, x, y)]?.attribution ?: source.attribution

    /** From the map file, opened on first use. Background (io) thread. */
    private fun renderOffline(uri: String, z: Int, x: Int, y: Int): Bitmap? {
        if (offlineFailed) return null
        val tiles = offline ?: try {
            MapsforgeTiles(appContext, Uri.parse(uri)).also { offline = it }
        } catch (e: Exception) {
            Log.w(TAG, "map file $uri: ${e.message}")
            offlineFailed = true
            return null
        }
        return tiles.render(z, x, y)
    }

    /**
     * Downloads [url] to [f]. [keepMissing]: a 400 or 404 answer (no data there)
     * leaves an empty [f], so it isn't asked again.
     */
    private fun download(url: String, f: File, keepMissing: Boolean = false): Boolean {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 10000
            // tile servers' policies ask for an identifying User-Agent
            c.setRequestProperty("User-Agent", USER_AGENT)
            val code = c.responseCode
            if (code != 200) {
                Log.w(TAG, "tile $url: HTTP $code")
                c.disconnect()
                if (keepMissing && (code == 400 || code == 404)) {
                    f.parentFile?.mkdirs()
                    f.createNewFile()
                }
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
                if (source.imagery.isNotEmpty()) {
                    loadImagery(z, x, y)
                } else {
                    val f = file(z, x, y)
                    if (!f.exists()) download(TileMath.url(source.url, z, x, y), f)
                }
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
        // the renderer belongs to the io thread: close it there once it's done
        Thread {
            io.awaitTermination(5, TimeUnit.SECONDS)
            offline?.close()
        }.start()
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
 * area ahead for offline use. [mapFile]: a Mapsforge map file (document
 * URI) drawn on the phone, instead of [url].
 */
data class TileSource(
    val id: String,
    val label: String,
    val url: String,
    val attribution: String,
    val maxZoom: Int,
    val prefetch: Boolean,
    val mapFile: String? = null,
    val imagery: List<Imagery> = emptyList(),
) {
    companion object {
        /** OpenStreetMap's map: viewed tiles only, no bulk download (tile usage policy). */
        val OSM = TileSource("osm", "OpenStreetMap",
            "https://tile.openstreetmap.org/{z}/{x}/{y}.png", "© OpenStreetMap contributors", 19, false)

        /** IGN's aerial photos of France (Géoplateforme, Licence Ouverte), 20 cm. */
        val IGN_PHOTO = TileSource("ign-photo", "IGN aerial photos (France)",
            Imagery.IGN_PHOTO_URL, "IGN Géoplateforme", 19, true)

        /** Any XYZ source, for example a satellite layer with the user's own key. */
        fun custom(url: String) = TileSource("custom-" + Integer.toHexString(url.hashCode()), "custom",
            url, "", 19, true)

        /** A Mapsforge map file of OpenStreetMap data (ODbL), drawn offline. */
        fun offline(uri: String) = TileSource("mapsforge-" + Integer.toHexString(uri.hashCode()), "offline map",
            "", "© OpenStreetMap contributors", OFFLINE_MAX_ZOOM, false, uri)

        /**
         * Satellite and aerial photos, the best open source for each tile:
         * national orthophotos (20 cm or better) where there are some, else
         * the worldwide Sentinel-2 mosaic (10 m). All allow offline use.
         */
        val SATELLITE = TileSource("satellite", "satellite (best available)", "",
            Imagery.SENTINEL2.attribution, 19, true, imagery = Imagery.ALL)

        /** Beyond this, the vector data has no more detail: tiles are scaled up. */
        const val OFFLINE_MAX_ZOOM = 18
    }
}
