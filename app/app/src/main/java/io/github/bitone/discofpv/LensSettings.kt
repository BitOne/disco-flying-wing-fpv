/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.content.Context
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Headset lens settings, as GsdkHmdView takes them: left and right lens
 * horizontal offsets in [0, 1] (0 and 1 are the smallest and largest offsets
 * the lens model supports) and the vertical offset of both lenses in mm.
 * Values are kept on their step grid, so repeated steps don't drift.
 */
data class LensSettings(val left: Double = 0.0, val right: Double = 0.0, val vertical: Double = 0.0) {

    fun stepLeft(steps: Int) = copy(left = snap(left + steps * OFFSET_STEP, OFFSET_STEP, 0.0, 1.0))

    fun stepRight(steps: Int) = copy(right = snap(right + steps * OFFSET_STEP, OFFSET_STEP, 0.0, 1.0))

    fun stepVertical(steps: Int) =
        copy(vertical = snap(vertical + steps * VERTICAL_STEP, VERTICAL_STEP, -VERTICAL_MAX, VERTICAL_MAX))

    fun leftText(): String = String.format(Locale.US, "LEFT %.2f", left)

    fun rightText(): String = String.format(Locale.US, "RIGHT %.2f", right)

    fun verticalText(): String = String.format(Locale.US, "HEIGHT %+.1f mm", vertical)

    companion object {
        const val OFFSET_STEP = 0.05
        const val VERTICAL_STEP = 0.5
        const val VERTICAL_MAX = 10.0

        private const val PREFS = "headset"
        private const val KEY_LEFT = "lens_left"
        private const val KEY_RIGHT = "lens_right"
        private const val KEY_VERTICAL = "lens_vertical"

        internal fun snap(value: Double, step: Double, min: Double, max: Double): Double =
            ((value / step).roundToLong() * step).coerceIn(min, max)

        fun load(context: Context): LensSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return LensSettings(
                left = p.getFloat(KEY_LEFT, 0f).toDouble(),
                right = p.getFloat(KEY_RIGHT, 0f).toDouble(),
                vertical = p.getFloat(KEY_VERTICAL, 0f).toDouble(),
            )
        }

        fun save(context: Context, settings: LensSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_LEFT, settings.left.toFloat())
                .putFloat(KEY_RIGHT, settings.right.toFloat())
                .putFloat(KEY_VERTICAL, settings.vertical.toFloat())
                .apply()
        }
    }
}
