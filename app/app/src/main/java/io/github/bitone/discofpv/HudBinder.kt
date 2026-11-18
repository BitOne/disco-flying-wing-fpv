/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 * Portions copyright (c) 2019-Present PARROT DRONES SAS. All rights reserved.
 */
package io.github.bitone.discofpv

import android.view.View
import android.widget.TextView
import com.parrot.drone.groundsdk.GroundSdk
import com.parrot.drone.groundsdk.device.DeviceState
import com.parrot.drone.groundsdk.device.Drone
import com.parrot.drone.groundsdk.device.instrument.Altimeter
import com.parrot.drone.groundsdk.device.instrument.BatteryInfo
import com.parrot.drone.groundsdk.device.instrument.FlyingIndicators
import com.parrot.drone.groundsdk.device.instrument.Gps
import com.parrot.drone.groundsdk.device.instrument.Speedometer

/**
 * Keeps the HUD text views found under [root] (layout `hud.xml` ids) up to
 * date from the drone's instruments and the controller's battery.
 * Observers live as long as the GroundSdk session.
 */
class HudBinder(private val groundSdk: GroundSdk, private val drone: Drone, private val root: View) {

    private fun text(id: Int, value: String) {
        root.findViewById<TextView>(id)?.text = value
    }

    fun bind() {
        drone.getInstrument(Altimeter::class.java) {
            text(R.id.hud_altitude, HudFormat.altitude(it?.takeOffRelativeAltitude))
        }
        drone.getInstrument(Speedometer::class.java) {
            text(R.id.hud_ground_speed, HudFormat.groundSpeed(it?.groundSpeed))
            val air = it?.airSpeed
            text(R.id.hud_air_speed, HudFormat.airSpeed(if (air != null && air.isAvailable) air.value else null))
        }
        drone.getInstrument(BatteryInfo::class.java) {
            text(R.id.hud_battery, HudFormat.battery("BAT", it?.batteryLevel))
        }
        drone.getInstrument(Gps::class.java) {
            text(R.id.hud_gps, HudFormat.gps(it?.isFixed, it?.satelliteCount))
        }
        drone.getInstrument(FlyingIndicators::class.java) {
            // When flying, show the detailed flying state (TAKING_OFF, FLYING...).
            val name = if (it?.state == FlyingIndicators.State.FLYING) it.flyingState.name else it?.state?.name
            text(R.id.hud_state, HudFormat.flyingState(name))
        }
        bindRemoteControlBattery()
    }

    /** Controller battery: the first connected remote control. */
    private fun bindRemoteControlBattery() {
        var watchedUid: String? = null
        groundSdk.getRemoteControlList({ it.state.connectionState == DeviceState.ConnectionState.CONNECTED }) { list ->
            val uid = list?.firstOrNull()?.uid ?: return@getRemoteControlList
            if (uid == watchedUid) return@getRemoteControlList
            watchedUid = uid
            groundSdk.getRemoteControl(uid)?.getInstrument(BatteryInfo::class.java) {
                text(R.id.hud_rc_battery, HudFormat.battery("RC", it?.batteryLevel))
            }
        }
    }
}
