/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Renders tiles from a real Mapsforge file, pushed beforehand to the app's
 * external files dir (skipped without it):
 * adb push rhone-alpes.map /sdcard/Android/data/io.github.bitone.discofpv/files/
 * The tiles are saved next to it as PNGs, to look at.
 */
class MapsforgeTilesTest {

    @Test
    fun rendersTilesOfTheFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = context.getExternalFilesDir(null)!!
        val map = File(dir, "rhone-alpes.map")
        assumeTrue("no $map", map.exists())
        MapsforgeTiles(context, Uri.fromFile(map)).use { tiles ->
            // Lyon, at the zooms the moving map uses
            val lyon = GeoPoint(45.7640, 4.8357)
            for (z in listOf(13, 15, 17)) {
                val (gx, gy) = TileMath.pixel(lyon, z)
                val (x, y) = TileMath.tileOf(gx, gy)
                val t0 = System.nanoTime()
                val bmp = tiles.render(z, x, y)
                val ms = (System.nanoTime() - t0) / 1_000_000
                assertNotNull("tile $z/$x/$y", bmp)
                bmp!!
                assertTrue(bmp.width == TileMath.TILE_PX && bmp.height == TileMath.TILE_PX)
                val colours = HashSet<Int>()
                for (px in 0 until bmp.width step 8) for (py in 0 until bmp.height step 8) colours += bmp.getPixel(px, py)
                assertTrue("tile $z/$x/$y is flat", colours.size > 3)
                File(dir, "tile-$z-$x-$y.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                android.util.Log.i("MapsforgeTilesTest", "tile $z/$x/$y in $ms ms, ${colours.size} colours")
            }
            // Paris is outside the file
            val (px, py) = TileMath.pixel(GeoPoint(48.8566, 2.3522), 15)
            val (x, y) = TileMath.tileOf(px, py)
            assertNull(tiles.render(15, x, y))
        }
    }
}
