/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 * Portions copyright (c) 2019-Present PARROT DRONES SAS. All rights reserved.
 */
package io.github.bitone.discofpv

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.parrot.drone.groundsdk.GroundSdk
import com.parrot.drone.groundsdk.ManagedGroundSdk
import com.parrot.drone.groundsdk.device.DeviceState

/** Lists the remote control and drone known to GroundSdk with their states. */
class MainActivity : Activity() {

    private lateinit var groundSdk: GroundSdk
    private lateinit var rcStatus: TextView
    private lateinit var droneStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        rcStatus = findViewById(R.id.rc_status)
        droneStatus = findViewById(R.id.drone_status)

        groundSdk = ManagedGroundSdk.obtainSession(this)
        groundSdk.getRemoteControlList({ true }) { list ->
            rcStatus.text = describe(list.orEmpty().map { Device(it.name, it.model.toString(), it.state) })
        }
        groundSdk.getDroneList({ true }) { list ->
            droneStatus.text = describe(list.orEmpty().map { Device(it.name, it.model.toString(), it.state) })
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
