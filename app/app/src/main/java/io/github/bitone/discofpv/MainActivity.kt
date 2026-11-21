/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 * Portions copyright (c) 2019-Present PARROT DRONES SAS. All rights reserved.
 */
package io.github.bitone.discofpv

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.parrot.drone.groundsdk.GroundSdk
import com.parrot.drone.groundsdk.ManagedGroundSdk
import com.parrot.drone.groundsdk.device.DeviceState

/**
 * Connection screen: remote control and drone states; opens the flight or
 * headset view once a drone is connected.
 */
class MainActivity : Activity() {

    private lateinit var groundSdk: GroundSdk
    private lateinit var rcStatus: TextView
    private lateinit var droneStatus: TextView
    private lateinit var rcDot: View
    private lateinit var droneDot: View
    private lateinit var hint: TextView
    private var rcConnected = false
    private lateinit var flightButton: Button
    private lateinit var headsetButton: Button
    private lateinit var rcButton: Button
    private lateinit var discoSettingsButton: Button
    private lateinit var mediaButton: Button
    private var connectedDroneUid: String? = null
    private lateinit var guard: ConnectionGuard
    private lateinit var recorder: FlightRecorder
    private lateinit var wifiButton: Button
    /** A Disco on Wi-Fi waiting for the pilot's tap, if any. */
    private var wifiDrone: ConnectionGuard.WifiDrone? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        rcStatus = findViewById(R.id.rc_status)
        droneStatus = findViewById(R.id.drone_status)
        rcDot = findViewById(R.id.rc_dot)
        droneDot = findViewById(R.id.drone_dot)
        hint = findViewById(R.id.main_hint)
        flightButton = findViewById(R.id.flight_button)
        flightButton.setOnClickListener {
            connectedDroneUid?.let { startActivity(DroneScreenActivity.intent(this, FlightActivity::class.java, it)) }
        }
        headsetButton = findViewById(R.id.headset_button)
        headsetButton.setOnClickListener {
            connectedDroneUid?.let { startActivity(DroneScreenActivity.intent(this, HeadsetActivity::class.java, it)) }
        }

        rcButton = findViewById(R.id.rc_button)
        rcButton.setOnClickListener {
            connectedDroneUid?.let {
                startActivity(android.content.Intent(this, RcActivity::class.java)
                    .putExtra(DroneScreenActivity.EXTRA_DRONE_UID, it))
            }
        }

        discoSettingsButton = findViewById(R.id.disco_settings_button)
        discoSettingsButton.setOnClickListener {
            connectedDroneUid?.let {
                startActivity(android.content.Intent(this, DiscoSettingsActivity::class.java)
                    .putExtra(DroneScreenActivity.EXTRA_DRONE_UID, it))
            }
        }

        mediaButton = findViewById(R.id.media_button)
        mediaButton.setOnClickListener {
            connectedDroneUid?.let {
                startActivity(android.content.Intent(this, MediaActivity::class.java)
                    .putExtra(DroneScreenActivity.EXTRA_DRONE_UID, it))
            }
        }

        findViewById<Button>(R.id.flights_button).setOnClickListener {
            startActivity(android.content.Intent(this, FlightsActivity::class.java))
        }

        findViewById<Button>(R.id.settings_button).setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }

        wifiButton = findViewById(R.id.wifi_connect)
        wifiButton.setOnClickListener { wifiDrone?.let { guard.allow(it.uid) } }
        guard = ConnectionGuard(this)
        recorder = FlightRecorder(this)
        guard.onWifiDrones = { list ->
            wifiDrone = list.firstOrNull()
            wifiButton.visibility = if (wifiDrone == null) View.GONE else View.VISIBLE
            wifiDrone?.let { wifiButton.text = getString(R.string.wifi_connect, it.name) }
            updateHint()
        }

        groundSdk = ManagedGroundSdk.obtainSession(this)
        groundSdk.getRemoteControlList({ true }) { list ->
            val rcs = list.orEmpty()
            rcStatus.text = describe(rcs.map { Device(it.name, it.model.toString(), it.state) })
            dot(rcDot, rcs.map { it.state.connectionState })
            rcConnected = rcs.any { it.state.connectionState == DeviceState.ConnectionState.CONNECTED }
            updateHint()
        }
        groundSdk.getDroneList({ true }) { list ->
            val drones = list.orEmpty()
            droneStatus.text = describe(drones.map { Device(it.name, it.model.toString(), it.state) })
            dot(droneDot, drones.map { it.state.connectionState })
            connectedDroneUid = drones.firstOrNull {
                it.state.connectionState == DeviceState.ConnectionState.CONNECTED
            }?.uid
            flightButton.isEnabled = connectedDroneUid != null
            headsetButton.isEnabled = connectedDroneUid != null
            rcButton.isEnabled = connectedDroneUid != null
            discoSettingsButton.isEnabled = connectedDroneUid != null
            mediaButton.isEnabled = connectedDroneUid != null
            updateHint()
        }
    }

    override fun onDestroy() {
        guard.close()
        recorder.close()
        super.onDestroy()
    }

    /** What to do next, from what is connected. */
    private fun updateHint() {
        val waiting = wifiDrone
        hint.text = when {
            connectedDroneUid != null && rcConnected -> getString(R.string.main_hint_ready)
            connectedDroneUid != null -> getString(R.string.main_hint_ready_wifi)
            rcConnected -> getString(R.string.main_hint_drone)
            waiting != null -> getString(R.string.main_hint_wifi, waiting.name)
            else -> getString(R.string.main_hint)
        }
    }

    /** Green when connected, amber while connecting, grey otherwise. */
    private fun dot(view: View, states: List<DeviceState.ConnectionState>) {
        val color = when {
            DeviceState.ConnectionState.CONNECTED in states -> R.color.ok
            DeviceState.ConnectionState.CONNECTING in states -> R.color.busy
            else -> R.color.off
        }
        view.background.mutate().setTint(getColor(color))
    }

    private data class Device(val name: String, val model: String, val state: DeviceState)

    private fun describe(devices: List<Device>): String =
        if (devices.isEmpty()) {
            getString(R.string.none)
        } else {
            devices.joinToString("\n") { "${it.name} (${it.model}): ${it.state.connectionState}" }
        }
}
