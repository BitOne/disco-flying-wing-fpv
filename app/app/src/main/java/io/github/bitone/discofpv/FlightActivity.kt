/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 * Portions copyright (c) 2019-Present PARROT DRONES SAS. All rights reserved.
 */
package io.github.bitone.discofpv

import com.parrot.drone.groundsdk.device.Drone
import com.parrot.drone.groundsdk.stream.GsdkStreamView

/** Full-screen live video from the drone with a 2D HUD. */
class FlightActivity : DroneScreenActivity() {

    private var streamView: GsdkStreamView? = null

    override fun onDroneReady(drone: Drone) {
        setContentView(R.layout.activity_flight)
        val view: GsdkStreamView = findViewById(R.id.stream)
        streamView = view
        withLiveStream { view.setStream(it) }
        HudBinder(groundSdk, drone, findViewById(R.id.hud)).bind()
    }

    override fun onDestroy() {
        streamView?.setStream(null)
        super.onDestroy()
    }
}
