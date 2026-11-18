/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import java.util.Locale

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
    fun flyingState(state: String?): String = state?.replace('_', ' ')?.uppercase(Locale.US) ?: UNKNOWN
}
