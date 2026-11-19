/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 * Portions copyright (c) 2019-Present PARROT DRONES SAS. All rights reserved.
 */
package io.github.bitone.discofpv

import com.parrot.drone.groundsdk.GroundSdk
import com.parrot.drone.groundsdk.device.DeviceState
import com.parrot.drone.groundsdk.device.Drone
import com.parrot.drone.groundsdk.device.instrument.Alarms
import com.parrot.drone.groundsdk.device.instrument.Altimeter
import com.parrot.drone.groundsdk.device.instrument.AttitudeIndicator
import com.parrot.drone.groundsdk.device.instrument.BatteryInfo
import com.parrot.drone.groundsdk.device.instrument.Compass
import com.parrot.drone.groundsdk.device.instrument.FlyingIndicators
import com.parrot.drone.groundsdk.device.instrument.Gps
import com.parrot.drone.groundsdk.device.instrument.Radio
import com.parrot.drone.groundsdk.device.instrument.Speedometer
import com.parrot.drone.groundsdk.device.peripheral.Gimbal
import com.parrot.drone.groundsdk.device.peripheral.Magnetometer
import com.parrot.drone.groundsdk.device.peripheral.Pitot
import com.parrot.drone.groundsdk.device.pilotingitf.ReturnHomePilotingItf

/**
 * Collects the drone's instruments and peripherals and the controller's
 * battery into a [HudModel] (with its warnings, camera tilt, last fix and
 * trail) and passes every new model to [onModel]. With a [lastFixStore],
 * the last GPS position is also saved for the next sessions. Observers live
 * as long as the GroundSdk session.
 */
class HudBinder(
    private val groundSdk: GroundSdk,
    private val drone: Drone,
    private val lastFixStore: LastFixStore? = null,
    private val onModel: (HudModel) -> Unit,
) {
    private var model = HudModel(lastFix = lastFixStore?.load())
    private var alertInputs = HudAlerts.Inputs()
    private var hadFix = false

    private fun update(change: (HudModel) -> HudModel) {
        model = change(model)
        alertInputs = alertInputs.copy(
            connected = model.connected,
            gpsLost = hadFix && model.gpsFixed == false,
        )
        model = model.copy(alerts = HudAlerts.of(alertInputs))
        onModel(model)
    }

    private fun updateAlerts(change: (HudAlerts.Inputs) -> HudAlerts.Inputs) {
        alertInputs = change(alertInputs)
        update { it }
    }

    /** A new GPS position: last fix (saved) and trail. */
    private fun onPosition(m: HudModel, p: GeoPoint?): HudModel {
        if (p == null) return m.copy(position = null)
        hadFix = true
        val fix = LastFix(p, m.altitude, System.currentTimeMillis())
        lastFixStore?.save(fix)
        val last = m.trail.lastOrNull()
        val trail = if (last == null || Geo.distance(last, p) >= TRAIL_STEP_M) {
            (m.trail + p).takeLast(TRAIL_POINTS)
        } else {
            m.trail
        }
        return m.copy(position = p, lastFix = fix, trail = trail)
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
                onPosition(
                    it.copy(gpsFixed = g?.isFixed, satellites = g?.satelliteCount),
                    if (g?.isFixed == true && location != null) {
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
        drone.getInstrument(Alarms::class.java) { a ->
            val warning = mutableSetOf<String>()
            val critical = mutableSetOf<String>()
            if (a != null) {
                for (kind in Alarms.Alarm.Kind.values()) {
                    when (a.getAlarm(kind).level) {
                        Alarms.Alarm.Level.WARNING -> warning += kind.name
                        Alarms.Alarm.Level.CRITICAL -> critical += kind.name
                        else -> {}
                    }
                }
            }
            updateAlerts { it.copy(warningAlarms = warning, criticalAlarms = critical) }
        }
        drone.getPeripheral(Magnetometer::class.java) { mag ->
            updateAlerts { it.copy(magnetometerCalibrated = mag?.isCalibrated) }
        }
        drone.getPeripheral(Pitot::class.java) { pitot ->
            updateAlerts { it.copy(pitotCalibrated = pitot?.isCalibrated) }
        }
        drone.getInstrument(Radio::class.java) { r ->
            updateAlerts { it.copy(rssi = r?.rssi?.takeIf { v -> v < 0 }) }
        }
        drone.getPeripheral(Gimbal::class.java) { g ->
            val tilt = g?.takeIf { Gimbal.Axis.PITCH in it.supportedAxes }?.getAttitude(Gimbal.Axis.PITCH)
            update { it.copy(cameraTilt = tilt) }
        }
        bindRemoteControlBattery()
    }

    private companion object {
        /** Trail: a point every few metres, the last few kilometres. */
        const val TRAIL_STEP_M = 5.0
        const val TRAIL_POINTS = 600
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
