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
import android.widget.TextView
import com.parrot.drone.groundsdk.GroundSdk
import com.parrot.drone.groundsdk.ManagedGroundSdk
import com.parrot.drone.groundsdk.device.DeviceState
import com.parrot.drone.groundsdk.device.Drone
import com.parrot.drone.groundsdk.device.instrument.Altimeter
import com.parrot.drone.groundsdk.device.instrument.BatteryInfo
import com.parrot.drone.groundsdk.device.instrument.FlyingIndicators
import com.parrot.drone.groundsdk.device.instrument.Gps
import com.parrot.drone.groundsdk.device.instrument.Speedometer
import com.parrot.drone.groundsdk.device.peripheral.StreamServer
import com.parrot.drone.groundsdk.device.peripheral.stream.CameraLive
import com.parrot.drone.groundsdk.stream.GsdkStreamView

/** Full-screen live video from the drone with a 2D HUD. */
class FlightActivity : Activity() {

    private lateinit var groundSdk: GroundSdk
    private lateinit var streamView: GsdkStreamView
    private var drone: Drone? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_flight)
        streamView = findViewById(R.id.stream)

        groundSdk = ManagedGroundSdk.obtainSession(this)
        val uid = intent.getStringExtra(EXTRA_DRONE_UID)
        val drone = uid?.let { groundSdk.getDrone(it) { finish() } }
        if (drone == null) {
            finish()
            return
        }
        this.drone = drone
        observeDrone(drone)
        observeRemoteControlBattery()
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

    override fun onDestroy() {
        streamView.setStream(null)
        super.onDestroy()
    }

    private fun text(id: Int, value: String) {
        findViewById<TextView>(id).text = value
    }

    private fun observeDrone(drone: Drone) {
        // The StreamServer observer fires on every peripheral change: subscribe
        // to the live stream once, and only ask to play when not playing, so
        // the decoder isn't restarted (flushed) on each update.
        // Open the live stream only once streaming is reported enabled:
        // enabling it restarts the streams already open.
        var liveSubscribed = false
        drone.getPeripheral(StreamServer::class.java) { server ->
            server ?: return@getPeripheral
            if (!server.streamingEnabled()) {
                server.enableStreaming(true)
                return@getPeripheral // wait for the update with streaming enabled
            }
            if (liveSubscribed) return@getPeripheral
            liveSubscribed = true
            server.live { live ->
                if (live != null) {
                    streamView.setStream(live)
                    if (live.playState() != CameraLive.PlayState.PLAYING) live.play()
                }
            }
        }
        drone.getInstrument(Altimeter::class.java) {
            text(R.id.hud_altitude, HudFormat.altitude(it?.takeOffRelativeAltitude))
        }
        drone.getInstrument(Speedometer::class.java) {
            text(R.id.hud_ground_speed, HudFormat.groundSpeed(it?.groundSpeed))
            val air = it?.airSpeed
            text(R.id.hud_air_speed, HudFormat.airSpeed(if (air != null && air.isAvailable) air.value else null))
        }
        drone.getInstrument(BatteryInfo::class.java) {
            text(R.id.hud_battery, HudFormat.battery("BAT", it?.batteryLevel))
        }
        drone.getInstrument(Gps::class.java) {
            text(R.id.hud_gps, HudFormat.gps(it?.isFixed, it?.satelliteCount))
        }
        drone.getInstrument(FlyingIndicators::class.java) {
            // When flying, show the detailed flying state (TAKING_OFF, FLYING...).
            val name = if (it?.state == FlyingIndicators.State.FLYING) it.flyingState.name else it?.state?.name
            text(R.id.hud_state, HudFormat.flyingState(name))
        }
    }

    /** Controller battery: the first connected remote control. */
    private fun observeRemoteControlBattery() {
        var watchedUid: String? = null
        groundSdk.getRemoteControlList({ it.state.connectionState == DeviceState.ConnectionState.CONNECTED }) { list ->
            val uid = list?.firstOrNull()?.uid ?: return@getRemoteControlList
            if (uid == watchedUid) return@getRemoteControlList
            watchedUid = uid
            groundSdk.getRemoteControl(uid)?.getInstrument(BatteryInfo::class.java) {
                text(R.id.hud_rc_battery, HudFormat.battery("RC", it?.batteryLevel))
            }
        }
    }

    companion object {
        private const val EXTRA_DRONE_UID = "drone_uid"

        fun intent(context: Context, droneUid: String): Intent =
            Intent(context, FlightActivity::class.java).putExtra(EXTRA_DRONE_UID, droneUid)
    }
}
