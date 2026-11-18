package io.github.bitone.discofpv

import org.junit.Assert.assertEquals
import org.junit.Test

class HudFormatTest {
    @Test fun altitude() {
        assertEquals("ALT 42.5 m", HudFormat.altitude(42.5))
        assertEquals("ALT -0.3 m", HudFormat.altitude(-0.25))
        assertEquals("ALT --", HudFormat.altitude(null))
        assertEquals("ALT --", HudFormat.altitude(Double.NaN))
    }

    @Test fun speeds() {
        assertEquals("AIR 15.2 m/s", HudFormat.airSpeed(15.2))
        assertEquals("AIR --", HudFormat.airSpeed(null))
        assertEquals("GND 12.4 m/s", HudFormat.groundSpeed(12.369))
    }

    @Test fun battery() {
        assertEquals("BAT 73 %", HudFormat.battery("BAT", 73))
        assertEquals("RC 58 %", HudFormat.battery("RC", 58))
        assertEquals("BAT --", HudFormat.battery("BAT", null))
        assertEquals("BAT --", HudFormat.battery("BAT", 101))
    }

    @Test fun gps() {
        assertEquals("GPS 10 sat", HudFormat.gps(true, 10))
        assertEquals("GPS no fix", HudFormat.gps(false, 3))
        assertEquals("GPS --", HudFormat.gps(null, null))
    }

    @Test fun flyingState() {
        assertEquals("FLYING", HudFormat.flyingState("FLYING"))
        assertEquals("TAKING OFF", HudFormat.flyingState("TAKING_OFF"))
        assertEquals("--", HudFormat.flyingState(null))
    }
}
