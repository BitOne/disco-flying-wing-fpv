/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 * Portions copyright (c) 2019-Present PARROT DRONES SAS. All rights reserved.
 */
package io.github.bitone.discofpv

import android.view.View
import android.widget.TextView
import com.parrot.drone.groundsdk.device.Drone
import com.parrot.drone.groundsdk.hmd.GsdkHmdView

/**
 * Side-by-side headset (FPV) view: the live stream rendered once per eye
 * with lens distortion correction (GroundSdk's GsdkHmdView), and the HUD as
 * the HMD overlay, so it appears in both eyes. The headset HUD
 * (hud_headset.xml) sits in the bands above and below the video.
 *
 * A tap shows the lens calibration panel (left and right lens offsets,
 * height), applied live and saved for the next sessions.
 */
class HeadsetActivity : DroneScreenActivity() {

    private var hmdView: GsdkHmdView? = null
    private var lens = LensSettings()

    override fun onDroneReady(drone: Drone) {
        setContentView(R.layout.activity_headset)
        val view: GsdkHmdView = findViewById(R.id.hmd)
        hmdView = view
        val hud = layoutInflater.inflate(R.layout.hud_headset, null)
        HeadsetHudSizer.attach(hud)
        view.setOverlay(hud)
        withLiveStream { view.setStream(it) }
        setupHud(hud)
        lens = LensSettings.load(this)
        applyLens()
        bindLensPanel(view)
    }

    private fun applyLens() {
        hmdView?.apply {
            setLeftLensOffset(lens.left)
            setRightLensOffset(lens.right)
            setLensesVerticalOffset(lens.vertical)
        }
        findViewById<TextView>(R.id.lens_left).text = lens.leftText()
        findViewById<TextView>(R.id.lens_right).text = lens.rightText()
        findViewById<TextView>(R.id.lens_vertical).text = lens.verticalText()
    }

    private fun bindLensPanel(hmd: View) {
        val panel = findViewById<View>(R.id.lens_panel)
        hmd.setOnClickListener {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.lens_done).setOnClickListener { panel.visibility = View.GONE }
        fun step(id: Int, change: (LensSettings) -> LensSettings) {
            findViewById<View>(id).setOnClickListener {
                lens = change(lens)
                LensSettings.save(this, lens)
                applyLens()
            }
        }
        step(R.id.lens_left_minus) { it.stepLeft(-1) }
        step(R.id.lens_left_plus) { it.stepLeft(1) }
        step(R.id.lens_right_minus) { it.stepRight(-1) }
        step(R.id.lens_right_plus) { it.stepRight(1) }
        step(R.id.lens_vertical_minus) { it.stepVertical(-1) }
        step(R.id.lens_vertical_plus) { it.stepVertical(1) }
    }

    override fun onDestroy() {
        hmdView?.setStream(null)
        super.onDestroy()
    }
}
