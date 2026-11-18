/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 * Portions copyright (c) 2019-Present PARROT DRONES SAS. All rights reserved.
 */
package io.github.bitone.discofpv

import com.parrot.drone.groundsdk.device.Drone
import com.parrot.drone.groundsdk.hmd.GsdkHmdView

/**
 * Side-by-side headset (FPV) view: the live stream rendered once per eye
 * with lens distortion correction (GroundSdk's GsdkHmdView), and the HUD as
 * the HMD overlay, so it appears in both eyes.
 */
class HeadsetActivity : DroneScreenActivity() {

    private var hmdView: GsdkHmdView? = null

    override fun onDroneReady(drone: Drone) {
        setContentView(R.layout.activity_headset)
        val view: GsdkHmdView = findViewById(R.id.hmd)
        hmdView = view
        val hud = layoutInflater.inflate(R.layout.hud, null)
        view.setOverlay(hud)
        withLiveStream { view.setStream(it) }
        HudBinder(groundSdk, drone, hud).bind()
    }

    override fun onDestroy() {
        hmdView?.setStream(null)
        super.onDestroy()
    }
}
