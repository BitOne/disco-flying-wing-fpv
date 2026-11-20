/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Moving map, track up: the Disco at the centre pointing up, its trail,
 * home (on the edge with its distance when beyond the range ring), north,
 * and a range ring sized to keep home in view. Without a GPS position it is
 * centred on the last fix, drawn as a cross with its coordinates and age.
 * Over the video in a dark disc, optionally on map tiles ([tiles]: web
 * tiles, or an offline map file drawn on the phone).
 */
class MapHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : HudCanvasView(context, attrs) {

    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#90000000")
    }
    private val path = Path()
    private val clip = Path()
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Map tiles under the trail (null: none). */
    var tiles: TileLayer? = null
        set(value) {
            field = value
            invalidate()
        }

    override fun describe(model: HudModel): String {
        val ring = MapProjection.ringFor(rangeOf(model))
        return "HUD MAP, ${HudFormat.home(model.homeDistance)}, RING ${ring.toInt()} m" +
            (model.lastFix?.takeIf { model.position == null }?.let { ", LAST FIX" } ?: "")
    }

    /** The distance the ring must cover: home, from the drone or last fix. */
    private fun rangeOf(m: HudModel): Double? {
        val c = m.mapPosition ?: return null
        val home = m.home ?: return null
        return Geo.distance(c, home)
    }

    /**
     * Tiles in the disc, rotated with the map (track up): the zoom whose
     * pixels are just finer than the screen's, scaled down to it.
     */
    private fun drawTiles(
        canvas: Canvas, layer: TileLayer, center: GeoPoint, heading: Double,
        metresPerPx: Double, cx: Float, cy: Float, r: Float,
    ) {
        val z = TileMath.zoomFor(center.latitude, metresPerPx, maxZoom = layer.source.maxZoom)
        val scale = (TileMath.metresPerPixel(center.latitude, z) / metresPerPx).toFloat()
        val (gx, gy) = TileMath.pixel(center, z)
        val reach = r / scale // disc radius in tile pixels
        val (x0, y0) = TileMath.tileOf(gx - reach, gy - reach)
        val (x1, y1) = TileMath.tileOf(gx + reach, gy + reach)
        canvas.save()
        clip.reset()
        clip.addCircle(cx, cy, r * 1.08f, Path.Direction.CW)
        canvas.clipPath(clip)
        canvas.translate(cx, cy)
        canvas.rotate(-heading.toFloat())
        canvas.scale(scale, scale)
        val size = TileMath.TILE_PX.toFloat()
        for (x in x0..x1) {
            for (y in y0..y1) {
                val bmp = layer.get(z, x, y) ?: continue
                val left = (x * size - gx).toFloat()
                val top = (y * size - gy).toFloat()
                canvas.drawBitmap(bmp, null, android.graphics.RectF(left, top, left + size, top + size), tilePaint)
            }
        }
        canvas.restore()
        // whose picture is under the Disco (several sources for satellite)
        val (tx, ty) = TileMath.tileOf(gx, gy)
        val attribution = layer.attribution(z, tx, ty)
        if (attribution.isNotEmpty()) {
            val small = text.textSize
            text.textSize = small * 0.55f
            text.color = HUD_COLOR
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(attribution, cx, cy + r * 0.95f, text)
            text.textSize = small
        }
    }

    override fun drawHud(canvas: Canvas) {
        scalePaints(0.045f)
        val m = model
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) * 0.44f
        canvas.drawCircle(cx, cy, r * 1.08f, disc)
        val center = m.mapPosition
        text.textAlign = Paint.Align.CENTER
        if (center == null) {
            text.color = WARNING_COLOR
            canvas.drawText("NO POSITION", cx, cy + text.textSize / 3, text)
            return
        }
        val lost = m.position == null
        val heading = if (lost) 0.0 else m.heading ?: 0.0
        val ringMetres = MapProjection.ringFor(rangeOf(m))
        val proj = MapProjection(center, heading, r / ringMetres)

        tiles?.let { drawTiles(canvas, it, center, heading, ringMetres / r, cx, cy, r) }

        // range ring and its half
        line.color = HUD_COLOR
        canvas.drawCircle(cx, cy, r, line)
        canvas.drawCircle(cx, cy, r / 2, line)
        text.color = HUD_COLOR
        canvas.drawText(String.format(Locale.US, "%d m", ringMetres.toInt()), cx, cy - r - text.textSize * 0.4f, text)

        // north on the ring
        val north = proj.project(GeoPoint(center.latitude + 1.0, center.longitude))
        val nd = hypot(north.first, north.second)
        canvas.drawText("N", cx + (north.first / nd * r).toFloat(),
            cy + (north.second / nd * r).toFloat() + text.textSize / 3, text)

        // trail, within the disc (old points can be far away)
        if (m.trail.size >= 2) {
            path.reset()
            m.trail.forEachIndexed { i, p ->
                val (x, y) = proj.project(p)
                if (i == 0) path.moveTo(cx + x.toFloat(), cy + y.toFloat())
                else path.lineTo(cx + x.toFloat(), cy + y.toFloat())
            }
            canvas.save()
            clip.reset()
            clip.addCircle(cx, cy, r * 1.08f, Path.Direction.CW)
            canvas.clipPath(clip)
            canvas.drawPath(path, line)
            canvas.restore()
        }

        // home, clamped to the ring
        m.home?.let { home ->
            var (x, y) = proj.project(home)
            val d = hypot(x, y)
            if (d > r) {
                x = x / d * r
                y = y / d * r
            }
            val hx = cx + x.toFloat()
            val hy = cy + y.toFloat()
            fill.color = HUD_COLOR
            canvas.drawCircle(hx, hy, text.textSize * 0.7f, fill)
            text.color = Color.BLACK
            canvas.drawText("H", hx, hy + text.textSize / 3, text)
        }

        // the drone (or its last fix)
        val s = text.textSize * 0.8f
        if (lost) {
            line.color = WARNING_COLOR
            canvas.drawLine(cx - s, cy - s, cx + s, cy + s, line)
            canvas.drawLine(cx - s, cy + s, cx + s, cy - s, line)
            line.color = HUD_COLOR
            text.color = WARNING_COLOR
            val fix = m.lastFix
            if (fix != null) {
                canvas.drawText(HudAlerts.lastFix(fix, System.currentTimeMillis()), cx, cy + r + text.textSize * 1.2f, text)
            }
        } else {
            path.reset()
            path.moveTo(cx, cy - s * 1.4f)
            path.lineTo(cx + s, cy + s)
            path.lineTo(cx, cy + s * 0.4f)
            path.lineTo(cx - s, cy + s)
            path.close()
            fill.color = HUD_COLOR
            canvas.drawPath(path, fill)
            text.color = HUD_COLOR
            canvas.drawText(HudFormat.home(m.homeDistance), cx, cy + r + text.textSize * 1.2f, text)
        }
        // heading reference: up is the track
        text.textAlign = Paint.Align.LEFT
        text.color = HUD_COLOR
        if (!lost && m.heading != null) {
            canvas.drawText(HudTape.heading(m.heading), cx + r * cos(-Math.PI / 4).toFloat(),
                cy - r * sin(Math.PI / 4).toFloat(), text)
        }
    }
}
