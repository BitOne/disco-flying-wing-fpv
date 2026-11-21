/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A recorded flight (T9.4): one sample per line of a CSV file, written on
 * the phone while the Disco flies ([FlightRecorder]), and its exports.
 * Altitudes are relative to the take-off point, as the Disco reports them.
 */
data class FlightSample(
    val timeMs: Long,
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Double?,
    val airSpeed: Double?,
    val groundSpeed: Double?,
    val heading: Double?,
    val roll: Double?,
    val pitch: Double?,
    val battery: Int?,
    val rssi: Int?,
    val state: String?,
) {
    val point: GeoPoint? get() =
        if (latitude != null && longitude != null) GeoPoint(latitude, longitude) else null

    fun toCsv(): String = listOf(
        timeMs, latitude?.let { "%.7f".format(Locale.ROOT, it) }, longitude?.let { "%.7f".format(Locale.ROOT, it) },
        altitude?.let { "%.1f".format(Locale.ROOT, it) }, airSpeed?.let { "%.1f".format(Locale.ROOT, it) },
        groundSpeed?.let { "%.1f".format(Locale.ROOT, it) }, heading?.let { "%.0f".format(Locale.ROOT, it) },
        roll?.let { "%.0f".format(Locale.ROOT, it) }, pitch?.let { "%.0f".format(Locale.ROOT, it) },
        battery, rssi, state,
    ).joinToString(",") { it?.toString() ?: "" }

    companion object {
        const val CSV_HEADER =
            "time_ms,latitude,longitude,altitude_m,air_speed_mps,ground_speed_mps,heading_deg,roll_deg,pitch_deg," +
                "battery_pct,rssi_dbm,state"

        fun of(m: HudModel, timeMs: Long) = FlightSample(
            timeMs, m.position?.latitude, m.position?.longitude, m.altitude, m.airSpeed, m.groundSpeed,
            m.heading, m.roll, m.pitch, m.battery, m.rssi, m.flyingState,
        )

        /** A CSV line back to a sample; null for the header or a broken line. */
        fun parse(line: String): FlightSample? {
            val f = line.split(',')
            if (f.size < 12) return null
            val t = f[0].toLongOrNull() ?: return null
            fun d(i: Int) = f[i].toDoubleOrNull()
            fun n(i: Int) = f[i].toIntOrNull()
            return FlightSample(t, d(1), d(2), d(3), d(4), d(5), d(6), d(7), d(8), n(9), n(10), f[11].ifEmpty { null })
        }
    }
}

/** What a flight list shows. */
data class FlightStats(
    val startMs: Long,
    val durationS: Long,
    val maxAltitude: Double?,
    val maxDistance: Double?,  // m from the first position
    val distance: Double,      // m flown
    val minBattery: Int?,
)

object FlightTrack {

    fun stats(samples: List<FlightSample>): FlightStats? {
        if (samples.isEmpty()) return null
        val points = samples.mapNotNull { it.point }
        val start = points.firstOrNull()
        var flown = 0.0
        for (i in 1 until points.size) flown += Geo.distance(points[i - 1], points[i])
        return FlightStats(
            startMs = samples.first().timeMs,
            durationS = (samples.last().timeMs - samples.first().timeMs) / 1000,
            maxAltitude = samples.mapNotNull { it.altitude }.maxOrNull(),
            maxDistance = start?.let { s -> points.maxOf { Geo.distance(s, it) } },
            distance = flown,
            minBattery = samples.mapNotNull { it.battery }.minOrNull(),
        )
    }

    private fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** GPX 1.1 track; the elevation is the height above the take-off point. */
    fun gpx(name: String, samples: List<FlightSample>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"Disco FPV\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("  <trk>\n    <name>").append(xml(name)).append("</name>\n")
        append("    <desc>Elevation: height above the take-off point (m)</desc>\n    <trkseg>\n")
        for (s in samples) {
            val p = s.point ?: continue
            append("      <trkpt lat=\"%.7f\" lon=\"%.7f\">".format(Locale.ROOT, p.latitude, p.longitude))
            s.altitude?.let { append("<ele>%.1f</ele>".format(Locale.ROOT, it)) }
            append("<time>").append(iso(s.timeMs)).append("</time></trkpt>\n")
        }
        append("    </trkseg>\n  </trk>\n</gpx>\n")
    }

    /** KML line, altitudes relative to the ground (Google Earth shows it in 3D). */
    fun kml(name: String, samples: List<FlightSample>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n  <Document>\n")
        append("    <name>").append(xml(name)).append("</name>\n")
        append("    <Style id=\"track\"><LineStyle><color>ff00a5ff</color><width>3</width></LineStyle></Style>\n")
        append("    <Placemark>\n      <name>").append(xml(name)).append("</name>\n")
        append("      <styleUrl>#track</styleUrl>\n      <LineString>\n        <altitudeMode>relativeToGround</altitudeMode>\n")
        append("        <coordinates>\n")
        for (s in samples) {
            val p = s.point ?: continue
            append("          %.7f,%.7f,%.1f\n".format(Locale.ROOT, p.longitude, p.latitude, s.altitude ?: 0.0))
        }
        append("        </coordinates>\n      </LineString>\n    </Placemark>\n  </Document>\n</kml>\n")
    }

    fun csv(samples: List<FlightSample>): String =
        (listOf(FlightSample.CSV_HEADER) + samples.map { it.toCsv() }).joinToString("\n", postfix = "\n")

    /** "12 min 30 s", "1 h 05 min". */
    fun duration(seconds: Long): String = when {
        seconds >= 3600 -> "%d h %02d min".format(Locale.ROOT, seconds / 3600, seconds % 3600 / 60)
        seconds >= 60 -> "%d min %02d s".format(Locale.ROOT, seconds / 60, seconds % 60)
        else -> "$seconds s"
    }
}
