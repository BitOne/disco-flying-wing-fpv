/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 * Portions copyright (c) 2019-Present PARROT DRONES SAS. All rights reserved.
 */
package io.github.bitone.discofpv

import android.view.View
import com.parrot.drone.groundsdk.device.Drone
import com.parrot.drone.groundsdk.stream.GsdkStreamView

/** Full-screen live video from the drone with a 2D HUD. */
class FlightActivity : DroneScreenActivity() {

    private var streamView: GsdkStreamView? = null

    override fun onDroneReady(drone: Drone) {
        setContentView(R.layout.activity_flight)
        val view: GsdkStreamView = findViewById(R.id.stream)
        streamView = view
        withLiveStream {
            view.setStream(it)
            // GsdkStreamView keeps its last frame without a stream: hide it,
            // a frozen picture must not pass for live video
            view.visibility = if (it == null) View.INVISIBLE else View.VISIBLE
        }
        setupHud(findViewById(R.id.hud))
        // on the phone, a tap also cycles the HUD modes
        view.setOnClickListener { cycleHud() }
    }

    override fun onDestroy() {
        streamView?.setStream(null)
        super.onDestroy()
    }
}
