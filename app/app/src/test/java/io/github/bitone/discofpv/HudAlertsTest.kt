/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HudAlertsTest {

    @Test
    fun noAlertsWhenAllIsWell() {
        assertEquals(emptyList<String>(), HudAlerts.of(HudAlerts.Inputs(connected = true, rssi = -45,
            magnetometerCalibrated = true, pitotCalibrated = true)))
    }

    @Test
    fun criticalAlarmsComeFirstAndOnce() {
        val alerts = HudAlerts.of(HudAlerts.Inputs(
            connected = true,
            warningAlarms = setOf("POWER", "MOTOR_ERROR"),
            criticalAlarms = setOf("MOTOR_ERROR", "MOTOR_CUT_OUT"),
            gpsLost = true,
            rssi = -88,
            magnetometerCalibrated = false,
            pitotCalibrated = false,
        ))
        assertEquals(listOf("MOTOR CUT OUT", "MOTOR ERROR", "BATTERY", "GPS LOST", "WEAK LINK",
            "CALIBRATE COMPASS", "PITOT"), alerts)
    }

    @Test
    fun quadcopterHoveringAlarmsAreIgnoredAndUnknownKindsReadable() {
        assertEquals(listOf("SOME NEW ALARM"), HudAlerts.of(HudAlerts.Inputs(connected = true,
            warningAlarms = setOf("HOVERING_DIFFICULTIES_NO_GPS_TOO_DARK", "SOME_NEW_ALARM"))))
    }

    @Test
    fun nothingButLinkLostWhileDisconnected() {
        assertEquals(emptyList<String>(), HudAlerts.of(HudAlerts.Inputs(connected = false, gpsLost = true)))
    }

    @Test
    fun lastFixWithItsAge() {
        val fix = LastFix(GeoPoint(45.1885, 5.7245), 50.0, 1_000_000)
        assertEquals("LAST FIX 45.18850 5.72450, 12 s ago", HudAlerts.lastFix(fix, 1_012_000))
        assertEquals("LAST FIX 45.18850 5.72450, 5 min ago", HudAlerts.lastFix(fix, 1_000_000 + 300_000))
        assertEquals("LAST FIX 45.18850 5.72450, 3 h ago", HudAlerts.lastFix(fix, 1_000_000 + 3 * 3_600_000))
    }

    @Test
    fun cameraTiltShownWhenNotLevel() {
        assertNull(HudAlerts.camera(null))
        assertNull(HudAlerts.camera(0.4))
        assertEquals("CAM -20", HudAlerts.camera(-19.6))
    }

    @Test
    fun theRadioOnAndItsReceiverLost() {
        val on = HudAlerts.Inputs(connected = true, radioEnabled = true, radioConnected = true)
        assertEquals(listOf("RADIO ON"), HudAlerts.of(on))
        val lost = on.copy(radioConnected = false, gpsLost = true)
        assertEquals(listOf("RADIO LOST", "GPS LOST"), HudAlerts.of(lost))
        assertEquals(emptyList<String>(), HudAlerts.of(HudAlerts.Inputs(connected = true)))
    }
}
