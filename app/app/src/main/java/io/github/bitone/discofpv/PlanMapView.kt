/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * The flight plan's map (T9.8): the chosen background around [center],
 * dragged with a finger; a tap without a drag adds a waypoint there
 * ([onTap]). Draws the route (home, the numbered waypoints, back home
 * when the plan returns), the Disco and the waypoints already flown.
 */
class PlanMapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var tiles: TileLayer? = null
        set(value) { field = value; invalidate() }
    var center: GeoPoint? = null
        set(value) { field = value; invalidate() }
    var zoom = 15
        set(value) { field = value.coerceIn(MIN_ZOOM, tiles?.source?.maxZoom ?: 19); invalidate() }
    var plan = FlightPlan()
        set(value) { field = value; invalidate() }
    var home: GeoPoint? = null
        set(value) { field = value; invalidate() }
    var disco: GeoPoint? = null
        set(value) { field = value; invalidate() }
    /** Waypoints flown so far (1-based count). */
    var done = 0
        set(value) { field = value; invalidate() }
    /** Shown without a position to centre on. */
    var noPosition = ""
        set(value) { field = value; invalidate() }
    /** Shown at the bottom without a map background. */
    var message = ""
        set(value) { field = value; invalidate() }
    var onTap: ((GeoPoint) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val tileScale = (density / 2f).coerceAtLeast(1f)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val clip = Path()
    private val bounds = RectF()
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val background = Paint().apply { color = context.getColor(R.color.sky_bottom) }
    private val route = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        color = context.getColor(R.color.accent)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }
    private val flown = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.ok) }
    private val homePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.camera) }
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12 * density
        color = 0xFF000000.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val grid = Paint().apply {
        color = 0x22FFFFFF
        strokeWidth = 1f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14 * density
        color = context.getColor(R.color.text_secondary)
        textAlign = Paint.Align.CENTER
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11 * density
        color = 0xFFFFFFFF.toInt()
        setShadowLayer(3f, 0f, 0f, 0xFF000000.toInt())
    }

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragged = false

    private fun z(): Int = zoom.coerceAtMost(tiles?.source?.maxZoom ?: 19)

    /** Screen position of a point. */
    private fun screen(p: GeoPoint, c: GeoPoint): Pair<Float, Float> {
        val (gx, gy) = TileMath.pixel(c, z())
        val (px, py) = TileMath.pixel(p, z())
        return Pair(width / 2f + ((px - gx) * tileScale).toFloat(), height / 2f + ((py - gy) * tileScale).toFloat())
    }

    private fun geo(x: Float, y: Float, c: GeoPoint): GeoPoint {
        val (gx, gy) = TileMath.pixel(c, z())
        return TileMath.point(gx + (x - width / 2f) / tileScale, gy + (y - height / 2f) / tileScale, z())
    }

    override fun onDraw(canvas: Canvas) {
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        clip.reset()
        clip.addRoundRect(bounds, 16 * density, 16 * density, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(bounds, background)
        val c = center
        if (c == null) {
            canvas.drawText(noPosition, width / 2f, height / 2f, text)
            canvas.restore()
            return
        }
        val layer = tiles
        val zz = z()
        if (layer != null) {
            val (gx, gy) = TileMath.pixel(c, zz)
            val halfW = width / 2f / tileScale
            val halfH = height / 2f / tileScale
            val (x0, y0) = TileMath.tileOf(gx - halfW, gy - halfH)
            val (x1, y1) = TileMath.tileOf(gx + halfW, gy + halfH)
            val max = (1 shl zz) - 1
            val size = TileMath.TILE_PX.toFloat()
            canvas.save()
            canvas.translate(width / 2f, height / 2f)
            canvas.scale(tileScale, tileScale)
            for (x in x0..x1) {
                for (y in y0.coerceAtLeast(0)..y1.coerceAtMost(max)) {
                    val bmp = layer.get(zz, Math.floorMod(x, max + 1), y) ?: continue
                    val left = (x * size - gx).toFloat()
                    val top = (y * size - gy).toFloat()
                    canvas.drawBitmap(bmp, null, RectF(left, top, left + size, top + size), tilePaint)
                }
            }
            canvas.restore()
            val (tx, ty) = TileMath.tileOf(gx, gy)
            val attribution = layer.attribution(zz, tx, ty)
            if (attribution.isNotEmpty()) canvas.drawText(attribution, 14 * density, height - 14 * density, label)
        }
        // a 100 m grid, and a scale, readable without a background
        val mpp = TileMath.metresPerPixel(c.latitude, zz) / tileScale
        val step = (GRID_M / mpp).toFloat()
        if (step > 12 * density) {
            val (cx, cy) = TileMath.pixel(c, zz)
            val ox = (width / 2f - ((cx * tileScale) % step).toFloat())
            val oy = (height / 2f - ((cy * tileScale) % step).toFloat())
            var x = ox % step
            while (x < width) { canvas.drawLine(x, 0f, x, height.toFloat(), grid); x += step }
            var y = oy % step
            while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, grid); y += step }
        }
        val barM = listOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0).lastOrNull { it / mpp < width / 3 } ?: 50.0
        val barPx = (barM / mpp).toFloat()
        val bx = width - 14 * density - barPx
        val by = height - 14 * density
        canvas.drawRect(bx, by - 3 * density, bx + barPx, by, label)
        canvas.drawText(HudFormat.distance(barM), bx, by - 6 * density, label)
        if (layer == null) canvas.drawText(message, width / 2f, height - 40 * density, text)
        // the route
        val points = listOfNotNull(home) + plan.waypoints.map { it.point } +
            listOfNotNull(home.takeIf { plan.end == FlightPlan.End.RETURN_HOME })
        val path = Path()
        points.forEachIndexed { i, p ->
            val (x, y) = screen(p, c)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, route)
        home?.let { val (x, y) = screen(it, c); canvas.drawCircle(x, y, 9 * density, homePaint) }
        plan.waypoints.forEachIndexed { i, w ->
            val (x, y) = screen(w.point, c)
            canvas.drawCircle(x, y, 11 * density, if (i < done) flown else dot)
            canvas.drawText("${i + 1}", x, y + number.textSize / 3, number)
        }
        disco?.let {
            val (x, y) = screen(it, c)
            val r = 8 * density
            val tri = Path().apply { moveTo(x, y - r); lineTo(x + r, y + r); lineTo(x - r, y + r); close() }
            canvas.drawPath(tri, label)
        }
        canvas.drawText("z$zz", 14 * density, 14 * density + label.textSize, label)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val c = center ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x; downY = event.y
                lastX = event.x; lastY = event.y
                dragged = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) dragged = true
                if (dragged) {
                    val (gx, gy) = TileMath.pixel(c, z())
                    center = TileMath.point(gx - (event.x - lastX) / tileScale, gy - (event.y - lastY) / tileScale, z())
                }
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!dragged) onTap?.invoke(geo(event.x, event.y, c))
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        const val MIN_ZOOM = 3
        const val GRID_M = 100.0
    }
}
