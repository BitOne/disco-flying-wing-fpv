/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import java.util.Locale

/**
 * The warnings the HUD shows, from the drone's alarms and states, most
 * severe first. Independent of the SDK (alarms are passed by name, as
 * GroundSdk's Alarms.Alarm.Kind) and unit-tested.
 */
object HudAlerts {
    /** What the binder knows, as plain values. */
    data class Inputs(
        val connected: Boolean = false,
        /** GroundSdk alarm kinds at WARNING level, and at CRITICAL level. */
        val warningAlarms: Set<String> = emptySet(),
        val criticalAlarms: Set<String> = emptySet(),
        val magnetometerCalibrated: Boolean? = null,
        val pitotCalibrated: Boolean? = null,
        /** The fix was there earlier in the session and is gone. */
        val gpsLost: Boolean = false,
        /** Wi-Fi signal of the drone, dBm. */
        val rssi: Int? = null,
        /** A hobby radio on the Disco's receiver is on (RcReceiver), and its receiver connected. */
        val radioEnabled: Boolean = false,
        val radioConnected: Boolean = false,
    )

    /** Signal below which the link is shown as weak, dBm. */
    const val WEAK_RSSI = -80

    private val ALARM_TEXT = mapOf(
        "MOTOR_CUT_OUT" to "MOTOR CUT OUT",
        "MOTOR_ERROR" to "MOTOR ERROR",
        "USER_EMERGENCY" to "EMERGENCY",
        "POWER" to "BATTERY",
        "BATTERY_TOO_HOT" to "BATTERY HOT",
        "BATTERY_TOO_COLD" to "BATTERY COLD",
        "VERTICAL_CAMERA" to "VERTICAL CAMERA KO",
        "STRONG_WIND" to "STRONG WIND",
        "STRONG_VIBRATIONS" to "VIBRATIONS",
        "AUTOMATIC_LANDING_BATTERY_ISSUE" to "AUTO LANDING",
    )

    /** Kinds that are noise for a fixed wing (quadcopter hovering helpers). */
    private val IGNORED = setOf(
        "HOVERING_DIFFICULTIES_NO_GPS_TOO_DARK",
        "HOVERING_DIFFICULTIES_NO_GPS_TOO_HIGH",
    )

    fun alarmText(kind: String): String = ALARM_TEXT[kind] ?: kind.replace('_', ' ').uppercase(Locale.US)

    fun of(i: Inputs): List<String> {
        if (!i.connected) return emptyList() // LINK LOST says it all
        val out = mutableListOf<String>()
        i.criticalAlarms.filter { it !in IGNORED }.sorted().forEach { out += alarmText(it) }
        i.warningAlarms.filter { it !in IGNORED && it !in i.criticalAlarms }.sorted().forEach { out += alarmText(it) }
        // the radio on and its receiver gone: the Disco's own failsafe
        // applies, the pilot must know at once; on and there: a reminder
        // that the radio's take-control switch decides who flies
        if (i.radioEnabled && !i.radioConnected) out.add(0, "RADIO LOST")
        if (i.gpsLost) out += "GPS LOST"
        if (i.rssi != null && i.rssi < WEAK_RSSI) out += "WEAK LINK"
        if (i.magnetometerCalibrated == false) out += "CALIBRATE COMPASS"
        if (i.pitotCalibrated == false) out += "PITOT"
        if (i.radioEnabled && i.radioConnected) out += "RADIO ON"
        return out
    }

    /** "LAST FIX 45.18850 5.72450, 12 s ago". */
    fun lastFix(fix: LastFix, nowMs: Long): String {
        val age = ((nowMs - fix.timeMs) / 1000).coerceAtLeast(0)
        val ago = when {
            age < 120 -> "$age s"
            age < 7200 -> "${age / 60} min"
            else -> "${age / 3600} h"
        }
        return String.format(Locale.US, "LAST FIX %.5f %.5f, %s ago", fix.point.latitude, fix.point.longitude, ago)
    }

    /** Camera tilt (and pan) as shown when not centred: "CAM -20", "CAM R30 -20". */
    fun camera(tilt: Double?, pan: Double? = null): String? {
        val t = tilt?.takeIf { kotlin.math.abs(it) >= 1.0 }
        val p = pan?.takeIf { kotlin.math.abs(it) >= 1.0 }
        if (t == null && p == null) return null
        val side = p?.let { (if (it > 0) "R" else "L") + Math.round(kotlin.math.abs(it)) + " " } ?: ""
        return "CAM $side${Math.round(t ?: 0.0)}"
    }
}
