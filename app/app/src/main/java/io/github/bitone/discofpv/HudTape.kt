/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Scale arithmetic of the fighter HUD tapes, independent of drawing (unit-tested). */
object HudTape {

    /** Multiples of [step] within [center] +- [halfRange], in increasing order. */
    fun ticks(center: Double, halfRange: Double, step: Double): List<Double> {
        val first = ceil((center - halfRange) / step).toLong()
        val last = floor((center + halfRange) / step).toLong()
        return (first..last).map { it * step }
    }

    /** Heading tape label: cardinal letters, else tens of degrees ("03" for 30). */
    fun headingLabel(degrees: Double): String {
        val d = ((degrees.roundToInt() % 360) + 360) % 360
        return when (d) {
            0 -> "N"
            90 -> "E"
            180 -> "S"
            270 -> "W"
            else -> String.format(Locale.US, "%02d", d / 10)
        }
    }

    /** Heading readout, three digits ("090"). */
    fun heading(degrees: Double?): String =
        degrees?.let { String.format(Locale.US, "%03d", ((it.roundToInt() % 360) + 360) % 360) } ?: HudFormat.UNKNOWN

    /** Tape readout, whole units. */
    fun value(v: Double?): String = v?.takeIf { it.isFinite() }?.roundToInt()?.toString() ?: HudFormat.UNKNOWN
}
