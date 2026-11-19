/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HudAreaTest {

    @Test
    fun centredInTheLetterboxedVideo() {
        // overlay 1600 x 1200: video 1600 x 900 in the middle
        assertArrayEquals(floatArrayOf(0f, 150f, 1600f, 1050f), HudArea.headset(1600, 1200, 100), 0.01f)
        // 50 %: 800 x 450 in the middle
        assertArrayEquals(floatArrayOf(400f, 375f, 1200f, 825f), HudArea.headset(1600, 1200, 50), 0.01f)
    }

    @Test
    fun aWideOverlayLimitsTheVideoHeight() {
        val a = HudArea.headset(2000, 900, 100)
        assertEquals(900f, a[3] - a[1], 0.01f)
    }
}
