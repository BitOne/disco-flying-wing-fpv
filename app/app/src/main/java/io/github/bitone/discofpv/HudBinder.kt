/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 * Portions copyright (c) 2019-Present PARROT DRONES SAS. All rights reserved.
 */
package io.github.bitone.discofpv

import com.parrot.drone.groundsdk.GroundSdk
import com.parrot.drone.groundsdk.device.DeviceState
import com.parrot.drone.groundsdk.device.Drone
import com.parrot.drone.groundsdk.device.instrument.Altimeter
import com.parrot.drone.groundsdk.device.instrument.AttitudeIndicator
import com.parrot.drone.groundsdk.device.instrument.BatteryInfo
import com.parrot.drone.groundsdk.device.instrument.Compass
import com.parrot.drone.groundsdk.device.instrument.FlyingIndicators
import com.parrot.drone.groundsdk.device.instrument.Gps
import com.parrot.drone.groundsdk.device.instrument.Speedometer
import com.parrot.drone.groundsdk.device.pilotingitf.ReturnHomePilotingItf

/**
 * Collects the drone's instruments and the controller's battery into a
 * [HudModel] and passes every new model to [onModel]. Observers live as
 * long as the GroundSdk session.
 */
class HudBinder(
    private val groundSdk: GroundSdk,
    private val drone: Drone,
    private val onModel: (HudModel) -> Unit,
) {
    private var model = HudModel()

    private fun update(change: (HudModel) -> HudModel) {
        model = change(model)
        onModel(model)
    }

    fun bind() {
        drone.getState { s ->
            update { it.copy(connected = s?.connectionState == DeviceState.ConnectionState.CONNECTED) }
        }
        drone.getInstrument(Altimeter::class.java) { a ->
            update { it.copy(altitude = a?.takeOffRelativeAltitude) }
        }
        drone.getInstrument(Speedometer::class.java) { s ->
            val air = s?.airSpeed
            update {
                it.copy(groundSpeed = s?.groundSpeed, airSpeed = if (air != null && air.isAvailable) air.value else null)
            }
        }
        drone.getInstrument(BatteryInfo::class.java) { b ->
            update { it.copy(battery = b?.batteryLevel) }
        }
        drone.getInstrument(Gps::class.java) { g ->
            val location = g?.lastKnownLocation()
            update {
                it.copy(
                    gpsFixed = g?.isFixed,
                    satellites = g?.satelliteCount,
                    position = if (g?.isFixed == true && location != null) {
                        GeoPoint(location.latitude, location.longitude)
                    } else {
                        null
                    },
                )
            }
        }
        drone.getInstrument(FlyingIndicators::class.java) { f ->
            // When flying, the detailed flying state (TAKING_OFF, FLYING...).
            val name = if (f?.state == FlyingIndicators.State.FLYING) f.flyingState.name else f?.state?.name
            update { it.copy(flyingState = name) }
        }
        drone.getInstrument(AttitudeIndicator::class.java) { a ->
            update { it.copy(roll = a?.roll, pitch = a?.pitch) }
        }
        drone.getInstrument(Compass::class.java) { c ->
            update { it.copy(heading = c?.heading) }
        }
        drone.getPilotingItf(ReturnHomePilotingItf::class.java) { r ->
            val home = r?.homeLocation
            update { it.copy(home = home?.let { h -> GeoPoint(h.latitude, h.longitude) }) }
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
            groundSdk.getRemoteControl(uid)?.getInstrument(BatteryInfo::class.java) { b ->
                update { it.copy(rcBattery = b?.batteryLevel) }
            }
        }
    }
}
