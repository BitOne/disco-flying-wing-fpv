/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet

/**
 * Minimal HUD: an arrow pointing home (relative to the nose) with the
 * distance at the top, the drone battery at the bottom. Both stay in the
 * top and bottom 13 % of the view, the bands around the video in the
 * headset. LINK LOST replaces the arrow while the drone is away.
 */
class MinimalHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : HudCanvasView(context, attrs) {

    private val arrow = Path()

    override fun describe(model: HudModel): String =
        "HUD MINIMAL, ${HudFormat.battery("BAT", model.battery)}, ${HudFormat.home(model.homeDistance)}"

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        scalePaints(0.055f)
        val m = model
        val band = height * 0.13f
        val cx = width / 2f

        // top: home arrow and distance, or LINK LOST
        val cy = band / 2
        if (!m.connected) {
            text.color = WARNING_COLOR
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(HudFormat.LINK_LOST, cx, cy + text.textSize / 3, text)
        } else {
            val size = band * 0.38f
            val bearing = m.homeRelativeBearing
            fill.color = HUD_COLOR
            if (bearing != null) {
                canvas.save()
                canvas.rotate(bearing.toFloat(), cx - size * 1.5f, cy)
                arrow.reset()
                arrow.moveTo(cx - size * 1.5f, cy - size) // tip: home ahead points up
                arrow.lineTo(cx - size * 1.5f + size * 0.7f, cy + size)
                arrow.lineTo(cx - size * 1.5f, cy + size * 0.5f)
                arrow.lineTo(cx - size * 1.5f - size * 0.7f, cy + size)
                arrow.close()
                canvas.drawPath(arrow, fill)
                canvas.restore()
            }
            text.color = HUD_COLOR
            text.textAlign = Paint.Align.LEFT
            canvas.drawText(HudFormat.home(m.homeDistance), cx - size * 0.3f, cy + text.textSize / 3, text)
        }

        // bottom: battery
        text.textAlign = Paint.Align.CENTER
        text.color = colorFor(HudFormat.isLowBattery(m.battery))
        canvas.drawText(HudFormat.battery("BAT", m.battery), cx, height - band / 2 + text.textSize / 3, text)
    }
}
