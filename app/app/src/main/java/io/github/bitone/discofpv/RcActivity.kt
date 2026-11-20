/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.parrot.drone.groundsdk.GroundSdk
import com.parrot.drone.groundsdk.ManagedGroundSdk
import com.parrot.drone.groundsdk.Ref
import com.parrot.drone.groundsdk.device.peripheral.RcReceiver

/**
 * A hobby radio on the Disco's receiver (T8.8, NOTES.md section 31): the
 * receiver's state, the channels as bars, the calibration steps (neutral,
 * then each action's channel by moving it: RcSetup), the inversions, and
 * turning the radio on, which the Disco only accepts on the ground once
 * calibrated. GroundSdk's RcReceiver peripheral (SDK patch 0005).
 */
class RcActivity : Activity() {

    private lateinit var groundSdk: GroundSdk
    private var rcRef: Ref<RcReceiver>? = null
    private var rc: RcReceiver? = null

    private lateinit var status: TextView
    private lateinit var enable: Button
    private lateinit var monitor: Button
    private lateinit var channels: TextView
    private lateinit var step: TextView
    private lateinit var startStep: Button
    private lateinit var abort: Button
    private lateinit var reset: Button
    private lateinit var inversions: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun text(size: Float = 16f, mono: Boolean = false) = TextView(this).apply {
            textSize = size
            if (mono) typeface = Typeface.MONOSPACE
            column.addView(this)
        }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
            column.addView(this)
        }
        text(24f).text = getString(R.string.rc_title)
        status = text()
        enable = button("") { rc?.let { it.setEnabled(!it.isEnabled) } }
        text(14f).text = getString(R.string.rc_enable_note)
        text(20f).text = getString(R.string.rc_calibration)
        step = text()
        startStep = button(getString(R.string.rc_start_step)) { startCurrentStep() }
        abort = button(getString(R.string.rc_abort)) { rc?.abortCalibration() }
        reset = button(getString(R.string.rc_reset)) { rc?.resetCalibration() }
        text(20f).text = getString(R.string.rc_channels)
        monitor = button("") { rc?.let { it.setMonitoring(!it.isMonitoring) } }
        channels = text(13f, mono = true)
        inversions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(inversions)
        setContentView(ScrollView(this).apply { addView(column) })

        groundSdk = ManagedGroundSdk.obtainSession(this)
        val drone = intent.getStringExtra(DroneScreenActivity.EXTRA_DRONE_UID)?.let { groundSdk.getDrone(it) { finish() } }
        if (drone == null) {
            finish()
            return
        }
        rcRef = drone.getPeripheral(RcReceiver::class.java) { r ->
            rc = r
            render(r)
        }
    }

    override fun onStop() {
        // the channels are only needed on this screen
        rc?.takeIf { it.isMonitoring }?.setMonitoring(false)
        super.onStop()
    }

    override fun onDestroy() {
        rcRef?.close()
        super.onDestroy()
    }

    private fun calibrated(r: RcReceiver) = r.calibratedActions.map { it.name }.toSet()

    private fun startCurrentStep() {
        val r = rc ?: return
        val i = RcSetup.current(r.isNeutralCalibrated, calibrated(r))
        val s = RcSetup.STEPS.getOrNull(i) ?: return
        if (s.action == null) {
            r.startNeutralCalibration()
        } else {
            r.startCalibration(RcReceiver.Action.valueOf(s.action), RcReceiver.ChannelType.valueOf(s.type!!))
        }
    }

    private fun render(r: RcReceiver?) {
        if (r == null) {
            status.text = getString(R.string.rc_unsupported)
            listOf(enable, monitor, startStep, abort, reset).forEach { it.isEnabled = false }
            return
        }
        status.text = RcSetup.status(r.isConnected, r.protocol, r.isEnabled)
        val calibrated = calibrated(r)
        val ready = RcSetup.ready(r.isNeutralCalibrated, calibrated, r.requiredActions.map { it.name }.toSet())
        enable.text = getString(if (r.isEnabled) R.string.rc_turn_off else R.string.rc_turn_on)
        enable.isEnabled = r.isConnected && (r.isEnabled || ready)
        monitor.text = getString(if (r.isMonitoring) R.string.rc_monitor_off else R.string.rc_monitor_on)
        monitor.isEnabled = r.isConnected
        val busy = r.calibration != RcReceiver.Calibration.NONE
        val i = RcSetup.current(r.isNeutralCalibrated, calibrated)
        step.text = when {
            busy && r.calibration == RcReceiver.Calibration.NEUTRAL -> getString(R.string.rc_step_neutral_busy)
            busy -> getString(R.string.rc_step_busy, RcSetup.STEPS.firstOrNull { it.action == r.calibratingAction?.name }
                ?.instruction ?: r.calibratingAction?.name ?: "")
            i < 0 -> getString(R.string.rc_steps_done)
            else -> getString(R.string.rc_step, i + 1, RcSetup.STEPS.size, RcSetup.STEPS[i].instruction)
        }
        startStep.isEnabled = r.isConnected && !busy && !r.isEnabled && i >= 0
        abort.isEnabled = busy
        reset.isEnabled = r.isConnected && !busy && !r.isEnabled
        channels.text = r.channels.joinToString("\n") { RcSetup.bar(it.id, it.action?.name, it.value) }
        channels.visibility = if (r.isMonitoring) View.VISIBLE else View.GONE
        // inversion of each calibrated axis or switch: check its direction on the bars
        inversions.removeAllViews()
        for ((action, info) in r.actions) {
            if (info.calibratedType == null) continue
            inversions.addView(Button(this).apply {
                isAllCaps = false
                text = getString(R.string.rc_inverted, action.name.replace('_', ' ').lowercase(),
                    getString(if (info.isInverted) R.string.on else R.string.off))
                isEnabled = !r.isEnabled
                setOnClickListener { rc?.setInverted(action, !info.isInverted) }
            })
        }
    }
}
