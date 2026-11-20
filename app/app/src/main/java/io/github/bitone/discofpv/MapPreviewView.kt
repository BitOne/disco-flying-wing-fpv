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

/**
 * The map screen's preview: the chosen background's tiles around [center]
 * at [zoom], north up, dragged with a finger, with the area a download
 * takes ([radiusMetres] around the centre) drawn as a circle. Tiles come
 * from [tiles] as the moving map gets them (memory, disk cache, network or
 * the offline file).
 */
class MapPreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var tiles: TileLayer? = null
        set(value) { field = value; invalidate() }
    var center: GeoPoint? = null
        set(value) { field = value; invalidate() }
    var zoom = DEFAULT_ZOOM
        set(value) { field = value.coerceIn(MIN_ZOOM, maxZoom()); invalidate() }
    var radiusMetres = 1500.0
    /** Shown when there is nothing to draw. */
    var message: String = ""
        set(value) { field = value; invalidate() }
    /** Called when a drag ends, with the new centre. */
    var onMoved: ((GeoPoint) -> Unit)? = null

    private val density = resources.displayMetrics.density
    /** Tiles drawn a bit larger than their 256 pixels on dense screens, to stay readable. */
    private val tileScale = (density / 2f).coerceAtLeast(1f)
    private val corner = 16 * density
    private val clip = Path()
    private val bounds = RectF()
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val area = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = context.getColor(R.color.accent)
    }
    private val areaFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFF8C1A }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2 * density
        color = context.getColor(R.color.camera)
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
    private val background = Paint().apply { color = context.getColor(R.color.sky_bottom) }

    private var lastX = 0f
    private var lastY = 0f

    private fun maxZoom() = tiles?.source?.maxZoom ?: 19

    override fun onDraw(canvas: Canvas) {
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        clip.reset()
        clip.addRoundRect(bounds, corner, corner, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(bounds, background)
        val layer = tiles
        val c = center
        if (layer == null || c == null) {
            canvas.drawText(message, width / 2f, height / 2f, text)
            canvas.restore()
            return
        }
        val z = zoom.coerceAtMost(layer.source.maxZoom)
        val (gx, gy) = TileMath.pixel(c, z)
        val halfW = width / 2f / tileScale
        val halfH = height / 2f / tileScale
        val (x0, y0) = TileMath.tileOf(gx - halfW, gy - halfH)
        val (x1, y1) = TileMath.tileOf(gx + halfW, gy + halfH)
        val max = (1 shl z) - 1
        val size = TileMath.TILE_PX.toFloat()
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(tileScale, tileScale)
        for (x in x0..x1) {
            for (y in y0.coerceAtLeast(0)..y1.coerceAtMost(max)) {
                val bmp = layer.get(z, Math.floorMod(x, max + 1), y) ?: continue
                val left = (x * size - gx).toFloat()
                val top = (y * size - gy).toFloat()
                canvas.drawBitmap(bmp, null, RectF(left, top, left + size, top + size), tilePaint)
            }
        }
        canvas.restore()
        // the download area, and its centre
        val r = (radiusMetres / TileMath.metresPerPixel(c.latitude, z) * tileScale).toFloat()
        canvas.drawCircle(width / 2f, height / 2f, r, areaFill)
        canvas.drawCircle(width / 2f, height / 2f, r, area)
        val k = 9 * density
        canvas.drawLine(width / 2f - k, height / 2f, width / 2f + k, height / 2f, cross)
        canvas.drawLine(width / 2f, height / 2f - k, width / 2f, height / 2f + k, cross)
        val (tx, ty) = TileMath.tileOf(gx, gy)
        val attribution = layer.attribution(z, tx, ty)
        val pad = 14 * density
        if (attribution.isNotEmpty()) canvas.drawText(attribution, pad, height - pad, label)
        canvas.drawText("z$z", pad, pad + label.textSize, label)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val c = center ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // the screen scrolls; the map takes its own drags
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val (gx, gy) = TileMath.pixel(c, zoom)
                center = TileMath.point(gx - (event.x - lastX) / tileScale, gy - (event.y - lastY) / tileScale, zoom)
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                center?.let { onMoved?.invoke(it) }
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    companion object {
        const val DEFAULT_ZOOM = 14
        const val MIN_ZOOM = 3
    }
}
