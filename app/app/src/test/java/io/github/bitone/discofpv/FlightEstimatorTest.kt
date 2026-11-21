/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightEstimatorTest {

    @Test
    fun windFromTheWest() {
        val e = FlightEstimator()
        var r = FlightEstimator.Estimate()
        // flying north at 12 m/s air speed, drifting east at 5 m/s: a west wind
        for (t in 0..60) {
            r = e.update(t * 1000L, true, 0.0, 12.0, 12.0, 5.0, 90, null, null)
        }
        assertEquals(5.0, r.windSpeed!!, 0.01)
        assertEquals(270.0, r.windFrom!!, 0.5)
        assertEquals("WIND 5 m/s from W", FlightEstimator.windText(r.windSpeed, r.windFrom))
        // heading east into it: the same wind
        for (t in 61..200) {
            r = e.update(t * 1000L, true, 90.0, 12.0, 0.0, 17.0, 90, null, null)
        }
        assertEquals(5.0, r.windSpeed!!, 0.05)
        assertEquals(270.0, r.windFrom!!, 1.0)
    }

    @Test
    fun noWindWithoutAirspeed() {
        val e = FlightEstimator()
        val r = e.update(0, true, 0.0, null, 12.0, 5.0, 90, null, null)
        assertNull(r.windSpeed)
        assertEquals("WIND --", FlightEstimator.windText(null, null))
    }

    @Test
    fun batteryToGetHome() {
        val e = FlightEstimator()
        var r = FlightEstimator.Estimate()
        // no wind, 12 m/s; 1 % every 20 s; home 3.6 km away, south
        for (t in 0..120) {
            r = e.update(t * 1000L, true, 0.0, 12.0, 12.0, 0.0, 90 - t / 20, 3600.0, 180.0)
        }
        assertEquals(300.0, r.homeSeconds!!, 1.0)   // 3600 m at 12 m/s
        // (300 s + 60 s landing) / 20 s per %
        assertEquals(18, r.homeBattery)
        assertFalse(r.goHome)
        assertEquals("HOME 5 min, 18 %", FlightEstimator.homeText(r.homeSeconds, r.homeBattery))
        // low battery: within the reserve of what home needs
        r = e.update(121_000, true, 0.0, 12.0, 12.0, 0.0, 27, 3600.0, 180.0)
        assertTrue(r.goHome)
    }

    @Test
    fun headwindHomeTakesLonger() {
        val e = FlightEstimator()
        var r = FlightEstimator.Estimate()
        // wind from the south at 4 m/s (blowing north); home is south: headwind
        for (t in 0..60) {
            r = e.update(t * 1000L, true, 0.0, 12.0, 16.0, 0.0, 90, 1600.0, 180.0)
        }
        assertEquals(200.0, r.homeSeconds!!, 2.0)   // 1600 m at 12 - 4 = 8 m/s
        assertNull(r.homeBattery)                    // no drain measured yet
        assertEquals("HOME 3 min", FlightEstimator.homeText(r.homeSeconds, r.homeBattery))
    }

    @Test
    fun landedResetsTheDrain() {
        val e = FlightEstimator()
        for (t in 0..120) e.update(t * 1000L, true, 0.0, 12.0, 12.0, 0.0, 90 - t / 10, 1000.0, 180.0)
        val landed = e.update(121_000, false, 0.0, 0.0, 0.0, 0.0, 78, 0.0, 0.0)
        assertNull(landed.homeSeconds)
        val again = e.update(122_000, true, 0.0, 12.0, 12.0, 0.0, 78, 1000.0, 180.0)
        assertNull(again.homeBattery)
        assertNotNull(again.homeSeconds)
    }
}
