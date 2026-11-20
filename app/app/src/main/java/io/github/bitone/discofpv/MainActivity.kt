/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 * Portions copyright (c) 2019-Present PARROT DRONES SAS. All rights reserved.
 */
package io.github.bitone.discofpv

import android.app.Activity
import android.os.Bundle
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
    private lateinit var flightButton: Button
    private lateinit var headsetButton: Button
    private lateinit var rcButton: Button
    private var connectedDroneUid: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        rcStatus = findViewById(R.id.rc_status)
        droneStatus = findViewById(R.id.drone_status)
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

        findViewById<Button>(R.id.settings_button).setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }

        groundSdk = ManagedGroundSdk.obtainSession(this)
        groundSdk.getRemoteControlList({ true }) { list ->
            rcStatus.text = describe(list.orEmpty().map { Device(it.name, it.model.toString(), it.state) })
        }
        groundSdk.getDroneList({ true }) { list ->
            val drones = list.orEmpty()
            droneStatus.text = describe(drones.map { Device(it.name, it.model.toString(), it.state) })
            connectedDroneUid = drones.firstOrNull {
                it.state.connectionState == DeviceState.ConnectionState.CONNECTED
            }?.uid
            flightButton.isEnabled = connectedDroneUid != null
            headsetButton.isEnabled = connectedDroneUid != null
            rcButton.isEnabled = connectedDroneUid != null
        }
    }

    private data class Device(val name: String, val model: String, val state: DeviceState)

    private fun describe(devices: List<Device>): String =
        if (devices.isEmpty()) {
            getString(R.string.none)
        } else {
            devices.joinToString("\n") { "${it.name} (${it.model}): ${it.state.connectionState}" }
        }
}
