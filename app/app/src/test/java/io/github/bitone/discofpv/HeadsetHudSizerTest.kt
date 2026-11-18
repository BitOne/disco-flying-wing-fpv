/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import org.junit.Assert.assertTrue
import org.junit.Test

class HeadsetHudSizerTest {

    /** Width of the longest line at a given text size, gaps included. */
    private fun lineWidth(size: Float): Double {
        val margin = HeadsetHudSizer.marginPx(size)
        val glyphs = HeadsetHudSizer.LINE_CHARS * HeadsetHudSizer.MONO_ADVANCE * size
        return glyphs + 2 * margin * HeadsetHudSizer.VALUES_PER_LINE
    }

    @Test
    fun longestLineFitsTheOverlay() {
        // 54 mm at 420 dpi (emulator), 460 dpi (Fairphone 5), 560 dpi
        for (width in intArrayOf(893, 978, 1191)) {
            val size = HeadsetHudSizer.textSizePx(width)
            assertTrue("width $width: line ${lineWidth(size)}", lineWidth(size) <= width)
        }
    }

    @Test
    fun textStaysReadable() {
        // about 2 mm tall glyphs on the screen, before the lens magnifies them
        val size = HeadsetHudSizer.textSizePx(893)
        assertTrue("size $size", size >= 30f)
    }
}
