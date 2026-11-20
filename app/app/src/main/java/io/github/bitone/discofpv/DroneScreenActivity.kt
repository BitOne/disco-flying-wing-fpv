/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 * Portions copyright (c) 2019-Present PARROT DRONES SAS. All rights reserved.
 */
package io.github.bitone.discofpv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import com.parrot.drone.groundsdk.GroundSdk
import com.parrot.drone.groundsdk.ManagedGroundSdk
import com.parrot.drone.groundsdk.Ref
import com.parrot.drone.groundsdk.device.DeviceState
import com.parrot.drone.groundsdk.device.Drone
import com.parrot.drone.groundsdk.device.peripheral.StreamServer
import com.parrot.drone.groundsdk.device.peripheral.stream.CameraLive

/**
 * Base for full-screen drone screens (flight view, headset view): finds the
 * drone from the intent, keeps the screen on, hides system bars, and
 * provides the drone's live stream.
 */
abstract class DroneScreenActivity : Activity() {

    protected lateinit var groundSdk: GroundSdk
    protected lateinit var drone: Drone

    /** Called once the drone is found; set up views and observers here. */
    protected abstract fun onDroneReady(drone: Drone)

    /** The HUD, once [setupHud] has run. */
    protected var hud: Hud? = null
        private set
    private var hudButton: HudButton? = null
    private var tileLayer: TileLayer? = null

    /** Frozen picture detection (T8.2): fed by the view's frame listener. */
    private val videoWatchdog = VideoWatchdog()
    private var liveStream: CameraLive? = null

    /**
     * Restarts the live stream as a new screen would: releases it (the view
     * drops its sink and renderer) and opens it again. A stop and play of
     * the same stream is not enough after the camera's timestamps went back
     * (pdraw keeps stalling).
     */
    private var restartLive: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastVideoState = VideoWatchdog.State.NO_STREAM
    private var ticks = 0
    private val watchdogTick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val frames = videoFrameCount()
            // a new renderer counts from 0 again
            if (frames >= 0) videoWatchdog.onFrames(now, if (frames >= lastFrameCount) frames - lastFrameCount else frames)
            lastFrameCount = frames.coerceAtLeast(0)
            val state = videoWatchdog.state(now)
            if (state != lastVideoState) {
                Log.i(TAG, "video $lastVideoState -> $state")
                lastVideoState = state
            }
            hud?.setVideoAlert(videoWatchdog.alert(now))
            if (++ticks % 4 == 0) hud?.refreshAlerts()
            if (videoWatchdog.shouldRestart(now)) {
                Log.w(TAG, "no video for a while: restarting the stream (${videoWatchdog.restarts})")
                restartLive?.invoke()
            }
            main.postDelayed(this, WATCHDOG_TICK_MS)
        }
    }

    /**
     * Distinct frames the stream view has rendered so far (GsdkStreamView or
     * GsdkHmdView getFrameCount, SDK patch 0004), -1 without a stream: still
     * while the same frame is shown again, a frozen picture.
     */
    protected open fun videoFrameCount(): Int = -1
    private var lastFrameCount = 0

    /** The user's settings, read when the screen opens. */
    protected val settings: AppSettings by lazy { AppSettings.load(this) }

    /**
     * Sets up the HUD under [root] (hud.xml or hud_headset.xml) in the saved
     * mode, fed from the drone; while this screen is in front, the
     * controller's HUD button cycles the modes and its map button (see the
     * settings) shows the moving map, while held or until pressed again.
     */
    protected fun setupHud(root: View) {
        val h = Hud(root, HudMode.load(this))
        hud = h
        settings.tileSource()?.let { src ->
            val layer = TileLayer(this, src) { root.findViewById<View>(R.id.hud_map)?.invalidate() }
            tileLayer = layer
            h.setTiles(layer)
        }
        val store = if (settings.rememberLastFix) LastFixStore(this) else null
        HudBinder(groundSdk, drone, store) { h.submit(it) }.bind()
        hudButton = HudButton(
            groundSdk, settings.mapButton, onCycle = { cycleHud() },
            onMap = { pressed ->
                if (settings.mapHold) h.showMap(pressed) else if (pressed) h.showMap(!h.mapShown)
            },
            recenterButton = settings.recenterButton,
            onRecenter = { recenterView() },
            onRefused = { h.setAlert("buttons", it.joinToString("\n").ifEmpty { null }) },
        )
    }

    /** The recentre button: the camera back along the nose (see HeadsetActivity). */
    protected open fun recenterView() {
        drone.getPeripheral(com.parrot.drone.groundsdk.device.peripheral.Gimbal::class.java)
            ?.control(com.parrot.drone.groundsdk.device.peripheral.Gimbal.ControlMode.POSITION, 0.0, 0.0, null)
    }

    /** Next HUD mode, saved for the next sessions. */
    protected fun cycleHud() {
        val h = hud ?: return
        val next = h.mode.next()
        h.setMode(next)
        HudMode.save(this, next)
    }

    override fun onResume() {
        super.onResume()
        hudButton?.start()
        main.post(watchdogTick)
    }

    override fun onPause() {
        hudButton?.stop()
        main.removeCallbacks(watchdogTick)
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        groundSdk = ManagedGroundSdk.obtainSession(this)
        val found = intent.getStringExtra(EXTRA_DRONE_UID)?.let { groundSdk.getDrone(it) { finish() } }
        if (found == null) {
            finish()
            return
        }
        drone = found
        onDroneReady(found)
    }

    override fun onDestroy() {
        tileLayer?.close()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    /**
     * Calls [onLive] with the drone's live stream, playing, and with null
     * while the drone is away. The stream is opened once streaming is
     * reported enabled (enabling it restarts the streams already open),
     * subscribed once per connection, and only asked to play when not
     * playing, so the decoder isn't restarted on each peripheral update.
     * When the link to the drone drops, the stream is released and the view
     * cleared (no frozen frame passing for live video); it is opened again
     * when the drone is back.
     */
    protected fun withLiveStream(onLive: (CameraLive?) -> Unit) {
        var live: Ref<CameraLive>? = null
        var server: StreamServer? = null
        var connected = false
        fun release() {
            if (live == null) return
            live?.close()
            live = null
            liveStream = null
            videoWatchdog.onStreamClosed()
            onLive(null)
        }
        fun open() {
            val s = server ?: return
            if (!connected || live != null) return
            if (!s.streamingEnabled()) {
                s.enableStreaming(true) // opened on the update with streaming enabled
                return
            }
            live = s.live { stream ->
                if (stream != null) {
                    val first = liveStream == null
                    liveStream = stream
                    onLive(stream)
                    val stopped = stream.playState() != CameraLive.PlayState.PLAYING
                    if (stopped) stream.play()
                    // a new subscription or session: time for its first frame
                    if (first || stopped) videoWatchdog.onStreamOpened(SystemClock.elapsedRealtime())
                }
            }
        }
        restartLive = {
            release()
            main.postDelayed({ open() }, RESTART_GAP_MS)
        }
        drone.getState {
            connected = it?.connectionState == DeviceState.ConnectionState.CONNECTED
            if (connected) open() else release()
        }
        drone.getPeripheral(StreamServer::class.java) {
            server = it
            if (it == null) release() else open()
        }
    }

    companion object {
        private const val TAG = "DroneScreen"
        private const val WATCHDOG_TICK_MS = 250L
        private const val RESTART_GAP_MS = 500L
        const val EXTRA_DRONE_UID = "drone_uid"

        fun <T : DroneScreenActivity> intent(context: Context, screen: Class<T>, droneUid: String): Intent =
            Intent(context, screen).putExtra(EXTRA_DRONE_UID, droneUid)
    }
}
