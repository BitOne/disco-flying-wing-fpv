/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 * Portions copyright (c) 2019-Present PARROT DRONES SAS. All rights reserved.
 */
package io.github.bitone.discofpv

import android.util.Log
import com.parrot.drone.groundsdk.GroundSdk
import com.parrot.drone.groundsdk.Ref
import com.parrot.drone.groundsdk.device.DeviceState
import com.parrot.drone.groundsdk.device.RemoteControlListEntry
import com.parrot.drone.groundsdk.device.peripheral.SkyController2Gamepad
import com.parrot.drone.groundsdk.device.peripheral.gamepad.skycontroller2.ButtonEvent
import java.util.EnumSet

/**
 * Takes one SkyController 2 button for the app while a drone screen is
 * shown ([start]/[stop]): grabbed, the controller sends its presses to the
 * app instead of using it for its own action. [BUTTON] has no flight action
 * on the Disco.
 */
class HudButton(private val groundSdk: GroundSdk, private val onPress: () -> Unit) {

    private var rcList: Ref<MutableList<RemoteControlListEntry>>? = null
    private var gamepadRef: Ref<SkyController2Gamepad>? = null
    private var gamepad: SkyController2Gamepad? = null
    private var watchedUid: String? = null

    private val listener = ButtonEvent.Listener { event, state ->
        Log.d(TAG, "button $event $state")
        if (event == BUTTON_EVENT && state == ButtonEvent.State.PRESSED) onPress()
    }

    fun start() {
        rcList = groundSdk.getRemoteControlList({ it.state.connectionState == DeviceState.ConnectionState.CONNECTED }) {
            val uid = it?.firstOrNull()?.uid
            if (uid == watchedUid) return@getRemoteControlList
            release()
            watchedUid = uid
            val rc = uid?.let { u -> groundSdk.getRemoteControl(u) } ?: return@getRemoteControlList
            gamepadRef = rc.getPeripheral(SkyController2Gamepad::class.java) { pad ->
                Log.d(TAG, "gamepad update: grabbed ${pad?.grabbedButtons}, same ${pad === gamepad}")
                if (pad == null || pad === gamepad) return@getPeripheral
                gamepad = pad
                pad.setButtonEventListener(listener)
                pad.grabInputs(EnumSet.of(BUTTON), EnumSet.noneOf(SkyController2Gamepad.Axis::class.java))
            }
        }
    }

    fun stop() {
        release()
        rcList?.close()
        rcList = null
        watchedUid = null
    }

    private fun release() {
        gamepad?.apply {
            grabInputs(EnumSet.noneOf(SkyController2Gamepad.Button::class.java),
                EnumSet.noneOf(SkyController2Gamepad.Axis::class.java))
            setButtonEventListener(null)
        }
        gamepad = null
        gamepadRef?.close()
        gamepadRef = null
    }

    companion object {
        private const val TAG = "HudButton"

        /** The button that cycles the HUD modes. */
        val BUTTON = SkyController2Gamepad.Button.SETTINGS
        private val BUTTON_EVENT = ButtonEvent.SETTINGS
    }
}
