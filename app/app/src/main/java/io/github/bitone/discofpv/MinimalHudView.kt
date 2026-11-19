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
 * distance at the top, the drone battery at the bottom, each in 13 % of the
 * HUD area. LINK LOST replaces the arrow while the drone is away; the last
 * GPS fix shows above the battery when the position is lost.
 */
class MinimalHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : HudCanvasView(context, attrs) {

    private val arrow = Path()

    override fun describe(model: HudModel): String =
        "HUD MINIMAL, ${HudFormat.battery("BAT", model.battery)}, ${HudFormat.home(model.homeDistance)}" +
            (model.lastFix?.takeIf { model.position == null }?.let { ", LAST FIX" } ?: "")

    override fun drawHud(canvas: Canvas) {
        scalePaints(0.055f)
        val m = model
        val band = h * 0.13f
        val cx = w / 2f

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

        // without a position: where the Disco was last seen
        val fix = m.lastFix
        if (m.position == null && fix != null) {
            val small = text.textSize
            text.textSize = small * 0.7f
            text.textAlign = Paint.Align.CENTER
            text.color = WARNING_COLOR
            canvas.drawText(HudAlerts.lastFix(fix, System.currentTimeMillis()), cx,
                h - band * 1.3f, text)
            text.textSize = small
        }

        // bottom: battery
        text.textAlign = Paint.Align.CENTER
        text.color = colorFor(HudFormat.isLowBattery(m.battery))
        canvas.drawText(HudFormat.battery("BAT", m.battery), cx, h - band / 2 + text.textSize / 3, text)
    }
}
