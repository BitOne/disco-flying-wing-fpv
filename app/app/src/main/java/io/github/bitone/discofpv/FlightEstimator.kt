/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The wind and the battery needed to get home (T9.7), estimated on the
 * phone from what the Disco reports (pure, unit-tested):
 *
 * - wind = ground velocity (GPS, north and east) minus air velocity
 *   (airspeed along the heading), smoothed over about [WIND_TAU_S]; only
 *   while flying with an airspeed (the pitot) and a fix;
 * - time home = distance home / (cruise airspeed + the wind's component
 *   towards home), at least [MIN_HOME_SPEED];
 * - drain = the battery percent lost per second over the last
 *   [DRAIN_WINDOW_MS] of this flight (unknown before [DRAIN_MIN_MS] and a
 *   1 % drop);
 * - battery for home = drain x (time home + [LANDING_S] to circle down and
 *   land); "go home" when the battery left is within [RESERVE_PCT] of it.
 *
 * Estimates, shown as such: the drain varies with the throttle, the wind
 * with height.
 */
class FlightEstimator {

    data class Estimate(
        val windSpeed: Double? = null,     // m/s
        val windFrom: Double? = null,      // degrees, where the wind blows from
        val homeSeconds: Double? = null,
        val homeBattery: Int? = null,      // % needed to get home and land
        val goHome: Boolean = false,
    )

    private var windN: Double? = null
    private var windE: Double? = null
    private var lastWindMs = 0L
    private val battery = ArrayDeque<Pair<Long, Int>>()
    private var cruise = DEFAULT_CRUISE

    fun update(
        nowMs: Long,
        flying: Boolean,
        heading: Double?,
        airSpeed: Double?,
        northSpeed: Double?,
        eastSpeed: Double?,
        batteryPct: Int?,
        homeDistance: Double?,
        homeBearing: Double?,
    ): Estimate {
        if (!flying) {
            battery.clear()
            return Estimate(windSpeed(), windFrom())
        }
        // wind, from the air and ground velocities
        if (heading != null && airSpeed != null && airSpeed > 3 && northSpeed != null && eastSpeed != null) {
            val h = Math.toRadians(heading)
            val wn = northSpeed - airSpeed * cos(h)
            val we = eastSpeed - airSpeed * sin(h)
            val dt = if (lastWindMs == 0L) WIND_TAU_S else (nowMs - lastWindMs) / 1000.0
            val a = (dt / WIND_TAU_S).coerceIn(0.0, 1.0)
            windN = windN?.let { it + a * (wn - it) } ?: wn
            windE = windE?.let { it + a * (we - it) } ?: we
            lastWindMs = nowMs
            // cruise: the airspeed it flies at, smoothed
            cruise += 0.02 * (airSpeed - cruise)
        }
        // battery drain over the recent window
        if (batteryPct != null && (battery.isEmpty() || battery.last().second != batteryPct || nowMs - battery.last().first > 5000)) {
            battery.addLast(nowMs to batteryPct)
        }
        while (battery.size > 2 && nowMs - battery[1].first > DRAIN_WINDOW_MS) battery.removeFirst()
        val drain = drainPerSecond(nowMs)

        val wN = windN
        val wE = windE
        var homeS: Double? = null
        if (homeDistance != null && homeBearing != null) {
            val b = Math.toRadians(homeBearing)
            // the wind's component along the way home (tailwind positive)
            val along = if (wN != null && wE != null) wN * cos(b) + wE * sin(b) else 0.0
            homeS = homeDistance / max(MIN_HOME_SPEED, cruise + along)
        }
        val need = if (homeS != null && drain != null) (drain * (homeS + LANDING_S)).roundToInt().coerceAtLeast(1) else null
        val goHome = need != null && batteryPct != null && batteryPct <= need + RESERVE_PCT
        return Estimate(windSpeed(), windFrom(), homeS, need, goHome)
    }

    private fun drainPerSecond(nowMs: Long): Double? {
        val first = battery.firstOrNull() ?: return null
        val last = battery.last()
        val span = last.first - first.first
        val drop = first.second - last.second
        if (nowMs - first.first < DRAIN_MIN_MS || span <= 0 || drop < 1) return null
        return drop / (span / 1000.0)
    }

    private fun windSpeed(): Double? {
        val n = windN ?: return null
        val e = windE ?: return null
        return hypot(n, e)
    }

    /** The wind blows towards (n, e): it comes from the opposite direction. */
    private fun windFrom(): Double? {
        val n = windN ?: return null
        val e = windE ?: return null
        return (Math.toDegrees(atan2(-e, -n)) + 360) % 360
    }

    companion object {
        const val WIND_TAU_S = 20.0
        const val DEFAULT_CRUISE = 12.0   // m/s, the Disco's NAV_DEFAULT_AIRSPEED
        const val MIN_HOME_SPEED = 3.0
        const val DRAIN_WINDOW_MS = 180_000L
        const val DRAIN_MIN_MS = 60_000L
        const val LANDING_S = 60.0
        const val RESERVE_PCT = 10

        private val POINTS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

        /** "WIND 6 m/s from W". */
        fun windText(speed: Double?, from: Double?): String =
            if (speed == null || from == null) "WIND --"
            else "WIND ${speed.roundToInt()} m/s from ${POINTS[(((from + 22.5) % 360) / 45).toInt()]}"

        /** "HOME 3 min, 14 %". */
        fun homeText(seconds: Double?, battery: Int?): String {
            if (seconds == null) return "HOME --"
            val min = (seconds / 60).roundToInt()
            val time = if (min < 1) "<1 min" else "$min min"
            return if (battery == null) "HOME $time" else "HOME $time, $battery %"
        }
    }
}
