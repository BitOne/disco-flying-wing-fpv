/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import org.mapsforge.core.model.Tile
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.layer.renderer.DatabaseRenderer
import org.mapsforge.map.layer.renderer.RendererJob
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import org.mapsforge.map.rendertheme.rule.RenderThemeFuture
import java.io.FileInputStream

/**
 * Tiles drawn on the phone from a Mapsforge vector map file (OpenStreetMap
 * data, one file per region from download.mapsforge.org), so the map works
 * with no network at all. The file is the one the user picked (a document
 * URI, read in place). Not thread-safe: call [render] from one thread.
 *
 * Drawn like OpenStreetMap's raster tiles (scale 1 in 256 px tiles) but
 * without labels or symbols: on a small disc turning with the track, text
 * can't be read, while roads, water, woods and buildings can.
 */
class MapsforgeTiles(context: Context, uri: Uri) : AutoCloseable {

    private val fd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
        ?: throw IllegalArgumentException("cannot open $uri")
    private val mapFile: MapFile
    private val display: DisplayModel
    private val theme: RenderThemeFuture
    private val renderer: DatabaseRenderer

    init {
        AndroidGraphicFactory.createInstance(context.applicationContext)
        // createInstance scales lines and text by the screen density: undo
        // it, the tiles are scaled to the screen by the map itself
        DisplayModel.setDeviceScaleFactor(1f)
        display = DisplayModel().apply { setFixedTileSize(TileMath.TILE_PX) }
        mapFile = MapFile(FileInputStream(fd.fileDescriptor), 0L, null)
        theme = RenderThemeFuture(AndroidGraphicFactory.INSTANCE, MapsforgeThemes.DEFAULT, display)
        theme.run() // parses the theme now, on the caller's thread
        renderer = DatabaseRenderer(mapFile, AndroidGraphicFactory.INSTANCE, null,
            null, false, false, null)
    }

    /** The tile, or null outside the file's area or on error. */
    fun render(z: Int, x: Int, y: Int): Bitmap? {
        val tile = Tile(x, y, z.toByte(), TileMath.TILE_PX)
        if (!mapFile.supportsTile(tile)) return null
        return try {
            val job = RendererJob(tile, mapFile, theme, display, 1f, false, false)
            val out = renderer.executeJob(job) ?: return null
            // a copy the view owns; the renderer's own is released
            val copy = AndroidGraphicFactory.getBitmap(out).copy(Bitmap.Config.ARGB_8888, false)
            out.decrementRefCount()
            copy
        } catch (e: Exception) {
            Log.w(TAG, "tile $z/$x/$y: ${e.message}")
            null
        }
    }

    override fun close() {
        mapFile.close()
        fd.close()
    }

    private companion object {
        const val TAG = "MapsforgeTiles"
    }
}
