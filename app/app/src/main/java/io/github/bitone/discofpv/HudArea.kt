/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

/**
 * Where the headset HUD may draw. The HMD overlay covers the lens render
 * area; the 16:9 video is letterboxed in it, and through the lenses only
 * the middle of the picture is seen (FreeFlight keeps its HUD in about 40 %
 * of the width and 25 % of the height of each eye). The HUD draws in a
 * centred rectangle of [percent] of the video's width and height.
 * Independent of Android (unit-tested).
 */
object HudArea {
    const val VIDEO_ASPECT = 16.0 / 9.0

    /** (left, top, right, bottom) in pixels of an overlay [width] x [height]. */
    fun headset(width: Int, height: Int, percent: Int): FloatArray {
        val f = percent.coerceIn(10, 100) / 100f
        val videoW = width.toFloat()
        val videoH = minOf(height.toFloat(), (width / VIDEO_ASPECT).toFloat())
        val w = videoW * f
        val h = videoH * f
        val left = (width - w) / 2
        val top = (height - h) / 2
        return floatArrayOf(left, top, left + w, top + h)
    }
}
