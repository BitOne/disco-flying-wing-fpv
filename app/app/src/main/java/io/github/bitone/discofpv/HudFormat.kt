/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import java.util.Locale
import kotlin.math.roundToLong

/** HUD text formatting, independent of Android and the SDK (unit-tested). */
object HudFormat {
    const val UNKNOWN = "--"

    private fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)

    /** Take-off relative altitude, metres. */
    fun altitude(metres: Double?): String =
        "ALT " + (metres?.takeIf { it.isFinite() }?.let { "${oneDecimal(it)} m" } ?: UNKNOWN)

    /** Air speed (pitot), m/s. */
    fun airSpeed(metresPerSecond: Double?): String =
        "AIR " + (metresPerSecond?.takeIf { it.isFinite() }?.let { "${oneDecimal(it)} m/s" } ?: UNKNOWN)

    /** Ground speed, m/s. */
    fun groundSpeed(metresPerSecond: Double?): String =
        "GND " + (metresPerSecond?.takeIf { it.isFinite() }?.let { "${oneDecimal(it)} m/s" } ?: UNKNOWN)

    /** Battery level, percent, with a label ("BAT" for the drone, "RC" for the controller). */
    fun battery(label: String, percent: Int?): String =
        "$label " + (percent?.takeIf { it in 0..100 }?.let { "$it %" } ?: UNKNOWN)

    /** GPS: satellite count when fixed. */
    fun gps(fixed: Boolean?, satellites: Int?): String = when (fixed) {
        null -> "GPS $UNKNOWN"
        false -> "GPS no fix"
        true -> "GPS ${satellites ?: 0} sat"
    }

    /** Flying state name as shown on the HUD (enum names upper-cased, "_" to " "). */
    fun flyingState(state: String?): String = when (state) {
        // GroundSdk's hovering; a flying wing holds position by circling
        "WAITING" -> "CIRCLING"
        null -> UNKNOWN
        else -> state.replace('_', ' ').uppercase(Locale.US)
    }

    /** Distance to home, metres (kilometres from 10 km). */
    fun home(metres: Double?): String = "HOME " + when {
        metres == null || !metres.isFinite() -> UNKNOWN
        metres < 10_000 -> "${metres.roundToLong()} m"
        else -> String.format(Locale.US, "%.1f km", metres / 1000)
    }

    /** Shown instead of the flying state while the drone is not connected. */
    const val LINK_LOST = "LINK LOST"

    /** State line: the flying state while connected, else [LINK_LOST]. */
    fun stateLine(connected: Boolean, flyingState: String?): String =
        if (connected) flyingState(flyingState) else LINK_LOST

    /** Battery level at or below which the HUD shows it as a warning. */
    const val LOW_BATTERY_PERCENT = 20

    fun isLowBattery(percent: Int?): Boolean = percent != null && percent in 0..LOW_BATTERY_PERCENT
}
