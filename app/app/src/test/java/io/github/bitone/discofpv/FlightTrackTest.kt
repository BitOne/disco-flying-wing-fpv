/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightTrackTest {

    private fun sample(t: Long, lat: Double?, alt: Double?, battery: Int? = 80, state: String? = "FLYING") =
        FlightSample(t, lat, lat?.let { 5.72 }, alt, 15.0, 12.0, 90.0, 5.0, -2.0, battery, -60, state)

    @Test
    fun csvRoundTrip() {
        val s = sample(1_700_000_000_000, 45.1885, 42.5)
        assertEquals(s.copy(heading = 90.0, roll = 5.0, pitch = -2.0), FlightSample.parse(s.toCsv()))
        val empty = sample(1, null, null, null, null)
        assertEquals(empty.copy(airSpeed = 15.0), FlightSample.parse(empty.toCsv()))
        assertNull(FlightSample.parse(FlightSample.CSV_HEADER))
        assertNull(FlightSample.parse("garbage"))
    }

    @Test
    fun stats() {
        val samples = listOf(
            sample(0, 45.0, 0.0, 90),
            sample(60_000, 45.01, 120.0, 70),
            sample(125_000, 45.0, 10.0, 55),
        )
        val s = FlightTrack.stats(samples)!!
        assertEquals(125, s.durationS)
        assertEquals(120.0, s.maxAltitude!!, 0.0)
        assertEquals(1112.0, s.maxDistance!!, 2.0)
        assertEquals(2224.0, s.distance, 4.0)
        assertEquals(55, s.minBattery)
        assertNull(FlightTrack.stats(emptyList()))
    }

    @Test
    fun gpxAndKml() {
        val samples = listOf(sample(0, 45.0, 1.0), sample(1000, null, 2.0), sample(2000, 45.001, 3.5))
        val gpx = FlightTrack.gpx("flight <1>", samples)
        assertTrue(gpx.contains("<name>flight &lt;1&gt;</name>"))
        assertTrue(gpx.contains("<trkpt lat=\"45.0010000\" lon=\"5.7200000\"><ele>3.5</ele><time>1970-01-01T00:00:02Z</time></trkpt>"))
        assertEquals(2, Regex("<trkpt").findAll(gpx).count())
        val kml = FlightTrack.kml("f", samples)
        assertTrue(kml.contains("5.7200000,45.0010000,3.5"))
        assertTrue(kml.contains("relativeToGround"))
        val csv = FlightTrack.csv(samples)
        assertTrue(csv.startsWith(FlightSample.CSV_HEADER + "\n"))
        assertEquals(4, csv.trim().lines().size)
    }

    @Test
    fun duration() {
        assertEquals("42 s", FlightTrack.duration(42))
        assertEquals("12 min 30 s", FlightTrack.duration(750))
        assertEquals("1 h 05 min", FlightTrack.duration(3900))
    }

    private class Sink : RecorderCore.Sink {
        val events = mutableListOf<String>()
        override fun start(timeMs: Long) { events += "start $timeMs" }
        override fun sample(s: FlightSample) {
            events += "sample ${s.timeMs}" + (s.altitude?.let { " $it" } ?: "")
        }
        override fun end() { events += "end" }
    }

    private fun model(state: String?, connected: Boolean = true) = HudModel(connected = connected, flyingState = state)

    @Test
    fun recordsFromTakeOffToLanding() {
        val sink = Sink()
        val core = RecorderCore(sink)
        core.onModel(model("LANDED"), 0)
        core.tick(500)
        assertFalse(core.recording)
        core.onModel(model("TAKING_OFF"), 1000)          // start, first sample
        core.onModel(model("FLYING"), 1200)
        core.tick(1500)                                  // the latest state
        // two updates in a row: the tick takes the last one
        core.onModel(model("FLYING").copy(altitude = 30.0), 1900)
        core.onModel(model("FLYING").copy(altitude = 80.0), 1901)
        core.tick(2000)
        // link lost: the flight stays open, no sample
        core.onModel(model("FLYING", connected = false), 2400)
        core.tick(2500)
        assertTrue(core.recording)
        core.onModel(model("LANDED"), 4000)
        core.tick(4500)
        core.tick(6500)
        assertTrue(core.recording)
        core.tick(7000)                                  // landed for 3 s
        assertFalse(core.recording)
        assertEquals(listOf("start 1000", "sample 1000", "sample 1500", "sample 2000 80.0", "sample 4500",
            "sample 6500", "sample 7000", "end"), sink.events)
    }

    @Test
    fun aBounceKeepsTheFlight() {
        val sink = Sink()
        val core = RecorderCore(sink)
        core.onModel(model("FLYING"), 0)
        core.onModel(model("LANDED"), 1000)
        core.onModel(model("FLYING"), 2000)
        core.onModel(model("LANDED"), 4500)
        core.tick(5000)
        assertTrue(core.recording)
        core.close()
        assertEquals("end", sink.events.last())
        assertEquals(1, sink.events.count { it.startsWith("start") })
    }
}
