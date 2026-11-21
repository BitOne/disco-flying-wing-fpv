/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.content.Context
import com.parrot.drone.groundsdk.mavlink.LandCommand
import com.parrot.drone.groundsdk.mavlink.MavlinkCommand
import com.parrot.drone.groundsdk.mavlink.NavigateToWaypointCommand
import com.parrot.drone.groundsdk.mavlink.ReturnToLaunchCommand
import com.parrot.drone.groundsdk.mavlink.TakeOffCommand
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A flight plan (T9.8): waypoints with their height above the take-off
 * point, and what the Disco does at the end. Turned into the MAVLink file
 * the Disco plays (GroundSdk's MavlinkFiles: take-off, the waypoints,
 * then return home or land; nothing for "circle there", where the Disco
 * circles once the plan is done). Free, kept on the phone.
 */
data class FlightPlan(val waypoints: List<Waypoint> = emptyList(), val end: End = End.RETURN_HOME) {

    data class Waypoint(val point: GeoPoint, val altitude: Double)

    enum class End { RETURN_HOME, LAND, CIRCLE }

    fun commands(): List<MavlinkCommand> {
        val out = mutableListOf<MavlinkCommand>(TakeOffCommand())
        for (w in waypoints) {
            out += NavigateToWaypointCommand(w.point.latitude, w.point.longitude, w.altitude, 0.0, 0.0, ACCEPT_M)
        }
        when (end) {
            End.RETURN_HOME -> out += ReturnToLaunchCommand()
            End.LAND -> out += LandCommand()
            End.CIRCLE -> {}
        }
        return out
    }

    /** Length of the route from [home], back to it when the plan returns. */
    fun length(home: GeoPoint?): Double {
        val points = listOfNotNull(home) + waypoints.map { it.point } +
            listOfNotNull(home.takeIf { end == End.RETURN_HOME })
        return points.zipWithNext { a, b -> Geo.distance(a, b) }.sum()
    }

    /** The waypoint farthest from [home], metres. */
    fun farthest(home: GeoPoint?): Double? =
        home?.let { h -> waypoints.maxOfOrNull { Geo.distance(h, it.point) } }

    /** What breaks the Disco's flying zone, for a warning before the start. */
    fun problems(home: GeoPoint?, maxAltitude: Double?, maxDistance: Double?, distanceLimit: Boolean): List<String> {
        val out = mutableListOf<String>()
        if (maxAltitude != null) {
            waypoints.forEachIndexed { i, w ->
                if (w.altitude > maxAltitude) out += "Waypoint ${i + 1} is above the maximum altitude (${maxAltitude.roundToInt()} m)"
            }
        }
        val far = farthest(home)
        if (distanceLimit && maxDistance != null && far != null && far > maxDistance) {
            out += "A waypoint is beyond the maximum distance (${maxDistance.roundToInt()} m)"
        }
        waypoints.forEachIndexed { i, w ->
            if (w.altitude < MIN_ALTITUDE) out += "Waypoint ${i + 1} is below ${MIN_ALTITUDE.roundToInt()} m"
        }
        return out
    }

    /** MAVLink items in the file: take-off, the waypoints, and the end (none to circle). */
    fun itemCount(): Int = 1 + waypoints.size + if (end == End.CIRCLE) 0 else 1

    /** The last item done: the plan is over. */
    fun finished(item: Int): Boolean = waypoints.isNotEmpty() && item >= itemCount() - 1

    /** The waypoint number (1-based) of the MAVLink item executed last, null before the first. */
    fun waypointDone(item: Int): Int? = if (item < 1) null else minOf(item, waypoints.size)

    fun save(context: Context) {
        val text = waypoints.joinToString(";") {
            String.format(Locale.ROOT, "%.7f,%.7f,%.1f", it.point.latitude, it.point.longitude, it.altitude)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_POINTS, text).putString(KEY_END, end.name).apply()
    }

    companion object {
        const val ACCEPT_M = 30.0
        const val MIN_ALTITUDE = 30.0
        const val DEFAULT_ALTITUDE = 80.0
        private const val PREFS = "flight_plan"
        private const val KEY_POINTS = "points"
        private const val KEY_END = "end"

        fun parse(points: String?, end: String?): FlightPlan {
            val wps = points.orEmpty().split(';').mapNotNull { p ->
                val f = p.split(',').mapNotNull { it.toDoubleOrNull() }
                if (f.size == 3) Waypoint(GeoPoint(f[0], f[1]), f[2]) else null
            }
            val e = End.values().firstOrNull { it.name == end } ?: End.RETURN_HOME
            return FlightPlan(wps, e)
        }

        fun load(context: Context): FlightPlan {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return parse(prefs.getString(KEY_POINTS, null), prefs.getString(KEY_END, null))
        }
    }
}
