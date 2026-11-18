/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 * Portions copyright (c) 2019-Present PARROT DRONES SAS. All rights reserved.
 */
package io.github.bitone.discofpv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
                    onLive(stream)
                    if (stream.playState() != CameraLive.PlayState.PLAYING) stream.play()
                }
            }
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
        const val EXTRA_DRONE_UID = "drone_uid"

        fun <T : DroneScreenActivity> intent(context: Context, screen: Class<T>, droneUid: String): Intent =
            Intent(context, screen).putExtra(EXTRA_DRONE_UID, droneUid)
    }
}
