/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

/**
 * Where the centred view is (the camera along the nose) while the camera
 * looks elsewhere (head tracking, the controller's slider): a ring at the
 * nose's place in the picture, or an arrow on the edge of the HUD area
 * pointing to it when it is out of sight. Shown in every HUD mode.
 */
class CenterIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : HudCanvasView(context, attrs) {

    private val arrow = Path()

    override fun describe(model: HudModel): String =
        HudAlerts.camera(model.cameraTilt, model.cameraPan)?.let { "CENTER $it" } ?: ""

    override fun drawHud(canvas: Canvas) {
        val pan = model.cameraPan ?: 0.0
        val tilt = model.cameraTilt ?: 0.0
        if (abs(pan) < SHOW_DEG && abs(tilt) < SHOW_DEG) return
        scalePaints(0.04f)
        val pxPerDeg = w / FOV_DEG
        // the nose: left of the centre when the camera looks right, above
        // when it looks down
        val x = w / 2 - (pan * pxPerDeg).toFloat()
        val y = h / 2 + (tilt * pxPerDeg).toFloat()
        // clear of the HUD lines along the area's top and bottom edges
        val margin = text.textSize * 4f
        line.color = HUD_COLOR
        fill.color = HUD_COLOR
        if (x in margin..(w - margin) && y in margin..(h - margin)) {
            val r = text.textSize * 0.8f
            canvas.drawCircle(x, y, r, line)
            canvas.drawLine(x - r * 1.8f, y, x - r, y, line)
            canvas.drawLine(x + r, y, x + r * 1.8f, y, line)
            return
        }
        // out of sight: an arrow on the edge, towards it
        val dx = x - w / 2
        val dy = y - h / 2
        val k = minOf((w / 2 - margin) / max(abs(dx), 1f), (h / 2 - margin) / max(abs(dy), 1f))
        val ex = w / 2 + dx * k
        val ey = h / 2 + dy * k
        val s = text.textSize * 0.9f
        canvas.save()
        canvas.rotate(Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat(), ex, ey)
        arrow.reset()
        arrow.moveTo(ex + s, ey)
        arrow.lineTo(ex - s, ey - s * 0.7f)
        arrow.lineTo(ex - s * 0.5f, ey)
        arrow.lineTo(ex - s, ey + s * 0.7f)
        arrow.close()
        canvas.drawPath(arrow, fill)
        canvas.restore()
        text.textAlign = Paint.Align.CENTER
        text.color = HUD_COLOR
        canvas.drawText("CENTER", ex - dx * 0.12f * k, ey - dy * 0.12f * k + text.textSize / 3, text)
    }

    private companion object {
        /** Horizontal field of view of the stream, degrees (CameraSettingsChanged). */
        const val FOV_DEG = 80f
        const val SHOW_DEG = 2.0
    }
}
