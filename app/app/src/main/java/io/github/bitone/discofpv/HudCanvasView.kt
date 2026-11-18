/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Base for the drawn HUD modes: holds the [HudModel], shared paints sized
 * from the view (so the same view works full screen and in the headset
 * overlay), and an accessibility description of what is shown (also what
 * the end-to-end tests read).
 */
abstract class HudCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var model: HudModel = HudModel()
        set(value) {
            field = value
            // only on change: each change is an accessibility event, and a
            // stream of them keeps UI tree readers from getting a stable tree
            val description = describe(value)
            if (description != contentDescription) contentDescription = description
            invalidate()
        }

    /** What the view shows, as text. */
    protected abstract fun describe(model: HudModel): String

    protected val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = HUD_COLOR
        setShadowLayer(3f, 1f, 1f, SHADOW_COLOR)
    }

    protected val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = HUD_COLOR
        setShadowLayer(3f, 1f, 1f, SHADOW_COLOR)
    }

    protected val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HUD_COLOR
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setShadowLayer(3f, 1f, 1f, SHADOW_COLOR)
    }

    /** Sets paint sizes from the view height (call at the start of onDraw). */
    protected fun scalePaints(textFraction: Float) {
        text.textSize = height * textFraction
        line.strokeWidth = maxOf(2f, height / 300f)
    }

    protected fun colorFor(warning: Boolean) = if (warning) WARNING_COLOR else HUD_COLOR

    companion object {
        val HUD_COLOR: Int = Color.parseColor("#B8FFB0")
        val WARNING_COLOR: Int = Color.parseColor("#FF6040")
        val SHADOW_COLOR: Int = Color.parseColor("#E0000000")
    }
}
