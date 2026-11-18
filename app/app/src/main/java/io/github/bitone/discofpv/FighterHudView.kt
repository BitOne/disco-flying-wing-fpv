/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import kotlin.math.abs

/**
 * Jet fighter style HUD: artificial horizon with a pitch ladder (rolls and
 * moves with the attitude) around a fixed aircraft symbol, speed tape on the
 * left (air speed, or ground speed without a pitot reading), altitude tape on
 * the right, heading tape at the top with a home marker, battery, state and
 * GPS along the bottom. Everything is sized from the view, so it works full
 * screen and in each eye of the headset.
 */
class FighterHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : HudCanvasView(context, attrs) {

    private val dashed = Paint(line)
    private val boxFill = Paint().apply { style = Paint.Style.FILL; color = Color.parseColor("#A0000000") }
    private val path = Path()
    private val rect = RectF()

    override fun describe(model: HudModel): String {
        val speed = model.airSpeed ?: model.groundSpeed
        // not the heading: it turns all the time while circling
        return "HUD FIGHTER, SPD ${HudTape.value(speed)}, ALT ${HudTape.value(model.altitude)}, " +
            HudFormat.battery("BAT", model.battery)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        scalePaints(0.04f)
        dashed.set(line)
        dashed.pathEffect = DashPathEffect(floatArrayOf(height / 60f, height / 90f), 0f)
        val m = model
        if (m.connected) {
            drawHorizon(canvas, m)
            drawAircraftSymbol(canvas)
            drawSpeedTape(canvas, m)
            drawAltitudeTape(canvas, m)
            drawHeadingTape(canvas, m)
        }
        drawBottomRow(canvas, m)
    }

    private fun drawHorizon(canvas: Canvas, m: HudModel) {
        val cx = width / 2f
        val cy = height / 2f
        val pxPerDeg = height / 50f
        canvas.save()
        // between the tapes and under the heading tape
        canvas.clipRect(width * 0.2f, height * 0.25f, width * 0.8f, height * 0.8f)
        canvas.rotate(-(m.roll ?: 0.0).toFloat(), cx, cy)
        canvas.translate(0f, ((m.pitch ?: 0.0) * pxPerDeg).toFloat())
        // horizon, open in the middle
        canvas.drawLine(cx - width * 0.30f, cy, cx - width * 0.05f, cy, line)
        canvas.drawLine(cx + width * 0.05f, cy, cx + width * 0.30f, cy, line)
        // pitch ladder every 10 degrees, dashed below the horizon
        text.textAlign = Paint.Align.CENTER
        text.color = HUD_COLOR
        for (p in -30..30 step 10) {
            if (p == 0) continue
            val y = cy - p * pxPerDeg
            val paint = if (p > 0) line else dashed
            val tick = (if (p > 0) 1 else -1) * height * 0.015f // ends point to the horizon
            for (side in intArrayOf(-1, 1)) {
                val inner = cx + side * width * 0.05f
                val outer = cx + side * width * 0.13f
                canvas.drawLine(inner, y, outer, y, paint)
                canvas.drawLine(outer, y, outer, y + tick, paint)
                canvas.drawText(abs(p).toString(), outer + side * width * 0.025f, y + text.textSize / 3, text)
            }
        }
        canvas.restore()
    }

    private fun drawAircraftSymbol(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val u = width * 0.012f
        path.reset()
        path.moveTo(cx - 5 * u, cy)
        path.lineTo(cx - 2 * u, cy)
        path.lineTo(cx - u, cy + 1.5f * u)
        path.lineTo(cx, cy)
        path.lineTo(cx + u, cy + 1.5f * u)
        path.lineTo(cx + 2 * u, cy)
        path.lineTo(cx + 5 * u, cy)
        canvas.drawPath(path, line)
    }

    /** A vertical tape: scale on one side of [edgeX], value box at the middle. */
    private fun drawTape(
        canvas: Canvas,
        edgeX: Float,
        side: Int, // -1: scale to the left of the edge (speed), 1: to the right (altitude)
        value: Double?,
        halfRange: Double,
        minorStep: Double,
        majorStep: Double,
        label: String,
        minValue: Double?,
    ) {
        val cy = height / 2f
        val half = height * 0.25f
        canvas.drawLine(edgeX, cy - half, edgeX, cy + half, line)
        text.color = HUD_COLOR
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(label, edgeX + side * width * 0.035f, cy - half - text.textSize * 0.6f, text)
        val v = value ?: return
        val pxPerUnit = half / halfRange
        text.textAlign = if (side < 0) Paint.Align.RIGHT else Paint.Align.LEFT
        for (t in HudTape.ticks(v, halfRange, minorStep)) {
            if (minValue != null && t < minValue) continue
            val y = (cy - (t - v) * pxPerUnit).toFloat()
            val major = abs(t % majorStep) < 1e-6
            val len = width * (if (major) 0.018f else 0.009f)
            canvas.drawLine(edgeX, y, edgeX + side * len, y, line)
            if (major) {
                canvas.drawText(t.toInt().toString(), edgeX + side * width * 0.025f, y + text.textSize / 3, text)
            }
        }
        // current value box, on the tape side
        val boxW = width * 0.075f
        val boxH = text.textSize * 1.4f
        val x0 = if (side < 0) edgeX - boxW else edgeX
        rect.set(x0, cy - boxH / 2, x0 + boxW, cy + boxH / 2)
        canvas.drawRect(rect, boxFill)
        canvas.drawRect(rect, line)
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(HudTape.value(v), rect.centerX(), cy + text.textSize / 3, text)
    }

    private fun drawSpeedTape(canvas: Canvas, m: HudModel) {
        val air = m.airSpeed
        drawTape(canvas, width * 0.19f, -1, air ?: m.groundSpeed, 15.0, 1.0, 5.0,
            if (air != null) "AIR" else "GND", 0.0)
        if (air != null && m.groundSpeed != null) {
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText("GS ${HudTape.value(m.groundSpeed)}", width * 0.19f,
                height * 0.75f + text.textSize * 1.4f, text)
        }
    }

    private fun drawAltitudeTape(canvas: Canvas, m: HudModel) {
        drawTape(canvas, width * 0.81f, 1, m.altitude, 50.0, 5.0, 10.0, "ALT", null)
    }

    private fun drawHeadingTape(canvas: Canvas, m: HudModel) {
        val heading = m.heading ?: return
        val cx = width / 2f
        val y = height * 0.17f
        val halfRange = 30.0
        val pxPerDeg = width * 0.2f / halfRange
        canvas.drawLine(cx - width * 0.2f, y, cx + width * 0.2f, y, line)
        text.color = HUD_COLOR
        text.textAlign = Paint.Align.CENTER
        for (t in HudTape.ticks(heading, halfRange, 5.0)) {
            val x = (cx + (t - heading) * pxPerDeg).toFloat()
            val major = abs(t % 10) < 1e-6
            canvas.drawLine(x, y, x, y + height * (if (major) 0.02f else 0.01f), line)
            if (major) canvas.drawText(HudTape.headingLabel(t), x, y + height * 0.02f + text.textSize, text)
        }
        // current heading above the centre mark
        val boxW = width * 0.06f
        val boxH = text.textSize * 1.4f
        rect.set(cx - boxW / 2, y - boxH - height * 0.01f, cx + boxW / 2, y - height * 0.01f)
        canvas.drawRect(rect, boxFill)
        canvas.drawRect(rect, line)
        canvas.drawText(HudTape.heading(heading), cx, rect.centerY() + text.textSize / 3, text)
        // home marker on the tape (clamped to its ends) and distance
        val rel = m.homeRelativeBearing ?: return
        val x = (cx + rel.coerceIn(-halfRange, halfRange) * pxPerDeg).toFloat()
        val s = height * 0.018f
        path.reset()
        path.moveTo(x, y - s * 2.2f)
        path.lineTo(x + s, y - s * 1.1f)
        path.lineTo(x, y)
        path.lineTo(x - s, y - s * 1.1f)
        path.close()
        canvas.drawPath(path, fill)
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(HudFormat.home(m.homeDistance), cx + width * 0.21f, y + text.textSize / 3, text)
    }

    private fun drawBottomRow(canvas: Canvas, m: HudModel) {
        val y = height * 0.9f
        text.textAlign = Paint.Align.LEFT
        text.color = colorFor(HudFormat.isLowBattery(m.battery))
        canvas.drawText(HudFormat.battery("BAT", m.battery), width * 0.12f, y, text)
        text.textAlign = Paint.Align.CENTER
        text.color = colorFor(!m.connected)
        canvas.drawText(HudFormat.stateLine(m.connected, m.flyingState), width / 2f, y, text)
        text.textAlign = Paint.Align.RIGHT
        text.color = HUD_COLOR
        canvas.drawText(HudFormat.gps(m.gpsFixed, m.satellites), width * 0.88f, y, text)
    }
}
