/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The MAVLink file itself needs Android (GroundSdk's classes): app-flight-plan.yaml checks it, flown by the fake. */
class FlightPlanTest {

    private val home = GeoPoint(45.0, 5.0)
    private val plan = FlightPlan(listOf(
        FlightPlan.Waypoint(GeoPoint(45.01, 5.0), 80.0),
        FlightPlan.Waypoint(GeoPoint(45.01, 5.01), 120.0),
    ))

    @Test
    fun lengthAndChecks() {
        // 1112 m north, 787 m east, then back home (about 1362 m)
        assertEquals(3261.0, plan.length(home), 10.0)
        // landing at the last waypoint: no way back
        assertEquals(1899.0, plan.copy(end = FlightPlan.End.LAND).length(home), 10.0)
        assertEquals(1362.0, plan.farthest(home)!!, 5.0)
        assertTrue(plan.problems(home, 150.0, 2000.0, true).isEmpty())
        assertEquals(listOf("Waypoint 2 is above the maximum altitude (100 m)"), plan.problems(home, 100.0, 2000.0, true))
        assertEquals(1, plan.problems(home, 150.0, 1000.0, true).size)
        assertTrue(plan.problems(home, 150.0, 1000.0, false).isEmpty())
        assertEquals(1, plan.copy(waypoints = listOf(FlightPlan.Waypoint(home, 10.0))).problems(home, null, null, false).size)
    }

    @Test
    fun progressAndSaving() {
        // item 0 is the take-off
        assertEquals(null, plan.waypointDone(0))
        assertEquals(1, plan.waypointDone(1))
        assertEquals(2, plan.waypointDone(3))
        // take-off, 2 waypoints, return home: the 4th item (index 3) ends it
        assertEquals(4, plan.itemCount())
        assertEquals(false, plan.finished(2))
        assertEquals(true, plan.finished(3))
        assertEquals(true, plan.copy(end = FlightPlan.End.CIRCLE).finished(2))
        val again = FlightPlan.parse("45.0100000,5.0000000,80.0;45.0100000,5.0100000,120.0", "LAND")
        assertEquals(plan.copy(end = FlightPlan.End.LAND), again)
        assertEquals(FlightPlan(), FlightPlan.parse(null, null))
    }
}
