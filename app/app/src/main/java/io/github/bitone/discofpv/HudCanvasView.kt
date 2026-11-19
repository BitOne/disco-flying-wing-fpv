/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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

    /**
     * The part of the view the HUD draws in (pixels): all of it full
     * screen; in the headset, the middle of the video, the part seen
     * through the lenses (HudArea).
     */
    var area: RectF? = null
        set(value) {
            field = value
            invalidate()
        }

    private val drawArea = RectF()

    /** Width and height of the drawing area. */
    protected val w: Float get() = drawArea.width()
    protected val h: Float get() = drawArea.height()

    /** Draws the HUD in a [w] x [h] area at the origin. */
    protected abstract fun drawHud(canvas: Canvas)

    final override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawArea.set(area ?: RectF(0f, 0f, width.toFloat(), height.toFloat()))
        if (drawArea.isEmpty) return
        canvas.save()
        canvas.translate(drawArea.left, drawArea.top)
        drawHud(canvas)
        canvas.restore()
    }

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

    /** Sets paint sizes from the area height (call at the start of drawHud). */
    protected fun scalePaints(textFraction: Float) {
        text.textSize = maxOf(MIN_TEXT_PX, h * textFraction)
        line.strokeWidth = maxOf(2f, h / 300f)
    }

    protected fun colorFor(warning: Boolean) = if (warning) WARNING_COLOR else HUD_COLOR

    companion object {
        /** Smallest readable text, pixels. */
        const val MIN_TEXT_PX = 12f
        val HUD_COLOR: Int = Color.parseColor("#B8FFB0")
        val WARNING_COLOR: Int = Color.parseColor("#FF6040")
        val SHADOW_COLOR: Int = Color.parseColor("#E0000000")
    }
}
