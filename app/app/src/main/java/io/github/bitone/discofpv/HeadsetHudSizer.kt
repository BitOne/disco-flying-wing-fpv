/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Sizes the headset HUD (hud_headset.xml) from the overlay's width. The
 * HMD overlay has a fixed physical size (the lens render area, 54 mm wide),
 * so its width in pixels follows the screen's density, while sp text
 * follows the user's density setting: a fixed sp size that fits one phone
 * overflows another. Each band holds one line of up to [LINE_CHARS]
 * monospace characters plus the gaps between the values.
 */
object HeadsetHudSizer {
    /** Longest line: "EMERGENCY LANDING", "ALT 120.0 m", "AIR 15.2 m/s". */
    const val LINE_CHARS = 40

    /** Values per line at most, and the gap between two, in characters. */
    const val VALUES_PER_LINE = 4
    const val GAP_CHARS = 1.5

    /** Advance of a monospace glyph, in em (Droid Sans Mono, Roboto Mono). */
    const val MONO_ADVANCE = 0.6

    /** Margin for font metrics and rounding. */
    const val SAFETY = 0.95

    /** Text size in pixels for an overlay [widthPx] wide. */
    fun textSizePx(widthPx: Int): Float {
        val chars = LINE_CHARS + (VALUES_PER_LINE - 1) * GAP_CHARS
        return (widthPx * SAFETY / (chars * MONO_ADVANCE)).toFloat()
    }

    /** Horizontal margin on each side of a value, in pixels. */
    fun marginPx(textSizePx: Float): Int = (textSizePx * MONO_ADVANCE * GAP_CHARS / 2).toInt()

    /** Applies the sizes to [hud] whenever its width changes. */
    fun attach(hud: View) {
        hud.addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
            val width = right - left
            if (width > 0 && width != oldRight - oldLeft) apply(v, width)
        }
    }

    private fun apply(hud: View, width: Int) {
        val size = textSizePx(width)
        val margin = marginPx(size)
        for (rowId in intArrayOf(R.id.hud_top, R.id.hud_bottom)) {
            val row = hud.findViewById<ViewGroup>(rowId) ?: continue
            for (i in 0 until row.childCount) {
                val text = row.getChildAt(i) as? TextView ?: continue
                text.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
                val params = text.layoutParams as LinearLayout.LayoutParams
                params.marginStart = margin
                params.marginEnd = margin
                text.layoutParams = params
            }
        }
    }
}
