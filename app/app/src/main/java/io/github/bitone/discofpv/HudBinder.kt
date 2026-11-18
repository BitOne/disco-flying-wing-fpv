/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 * Portions copyright (c) 2019-Present PARROT DRONES SAS. All rights reserved.
 */
package io.github.bitone.discofpv

import android.graphics.Color
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

    /** Normal HUD colour (style Hud), or the warning colour. */
    private fun warn(id: Int, warning: Boolean) {
        root.findViewById<TextView>(id)?.setTextColor(if (warning) WARNING_COLOR else NORMAL_COLOR)
    }

    private var connected = false
    private var flyingState: String? = null

    private fun updateState() {
        text(R.id.hud_state, HudFormat.stateLine(connected, flyingState))
        warn(R.id.hud_state, !connected)
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
            warn(R.id.hud_battery, HudFormat.isLowBattery(it?.batteryLevel))
        }
        drone.getInstrument(Gps::class.java) {
            text(R.id.hud_gps, HudFormat.gps(it?.isFixed, it?.satelliteCount))
        }
        drone.getInstrument(FlyingIndicators::class.java) {
            // When flying, show the detailed flying state (TAKING_OFF, FLYING...).
            flyingState = if (it?.state == FlyingIndicators.State.FLYING) it.flyingState.name else it?.state?.name
            updateState()
        }
        // Link to the drone (through the controller): LINK LOST while away.
        drone.getState {
            connected = it?.connectionState == DeviceState.ConnectionState.CONNECTED
            updateState()
        }
        bindRemoteControlBattery()
    }

    private companion object {
        val NORMAL_COLOR = Color.parseColor("#B8FFB0") // style Hud
        val WARNING_COLOR = Color.parseColor("#FF6040")
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
                warn(R.id.hud_rc_battery, HudFormat.isLowBattery(it?.batteryLevel))
            }
        }
    }
}
