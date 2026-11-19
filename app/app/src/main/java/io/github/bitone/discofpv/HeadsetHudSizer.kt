/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Lays out the headset HUD (hud_headset.xml) from the overlay's size: the
 * HUD area (HudArea: the middle of the video, the part seen through the
 * lenses, [percent] of it) for the drawn modes, the classic lines at its
 * top and bottom, the warnings below its centre. The HMD overlay has a
 * fixed physical size (the lens render area, 54 mm wide), so its width in
 * pixels follows the screen's density, while sp text follows the user's
 * density setting: text sizes come from the area's width instead. Each
 * classic line holds up to [LINE_CHARS] monospace characters plus the gaps
 * between the values.
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

    /** Text size in pixels for a line [widthPx] wide. */
    fun textSizePx(widthPx: Int): Float {
        val chars = LINE_CHARS + (VALUES_PER_LINE - 1) * GAP_CHARS
        return (widthPx * SAFETY / (chars * MONO_ADVANCE)).toFloat()
    }

    /** Horizontal margin on each side of a value, in pixels. */
    fun marginPx(textSizePx: Float): Int = (textSizePx * MONO_ADVANCE * GAP_CHARS / 2).toInt()

    /** Applies the layout to [hud] (and its [Hud]) whenever its size changes. */
    fun attach(hudView: View, hud: () -> Hud?, percent: Int) {
        hudView.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val w = right - left
            val h = bottom - top
            if (w > 0 && h > 0 && (w != oldRight - oldLeft || h != oldBottom - oldTop)) {
                apply(v, hud(), w, h, percent)
            }
        }
    }

    private fun apply(root: View, hud: Hud?, width: Int, height: Int, percent: Int) {
        val a = HudArea.headset(width, height, percent)
        val area = RectF(a[0], a[1], a[2], a[3])
        hud?.setArea(area)
        val size = textSizePx(area.width().toInt())
        val margin = marginPx(size)
        // classic lines at the top and bottom of the area
        root.findViewById<View>(R.id.hud_classic)?.setPadding(
            area.left.toInt(), area.top.toInt(), (width - area.right).toInt(), (height - area.bottom).toInt())
        for (rowId in intArrayOf(R.id.hud_top, R.id.hud_bottom)) {
            val row = root.findViewById<ViewGroup>(rowId) ?: continue
            for (i in 0 until row.childCount) {
                val text = row.getChildAt(i) as? TextView ?: continue
                text.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
                val params = text.layoutParams as LinearLayout.LayoutParams
                params.marginStart = margin
                params.marginEnd = margin
                text.layoutParams = params
            }
        }
        // warnings: below the centre of the area
        root.findViewById<TextView>(R.id.hud_alerts)?.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            translationY = area.height() * 0.22f
        }
        root.findViewById<TextView>(R.id.hud_mode_banner)?.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 1.4f)
    }
}
