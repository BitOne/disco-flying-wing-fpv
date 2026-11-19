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
 * Takes the app's SkyController 2 buttons while a drone screen is shown
 * ([start]/[stop]): grabbed, the controller sends their presses to the app
 * instead of using them for its own action. [CYCLE_BUTTON] cycles the HUD
 * modes; the map button ([AppSettings.mapButton], none by choice) shows the
 * moving map, [onMap] with true on press and false on release; the
 * recentre button ([AppSettings.recenterButton]) calls [onRecenter].
 */
class HudButton(
    private val groundSdk: GroundSdk,
    private val mapButton: AppSettings.MapButton,
    private val onCycle: () -> Unit,
    private val onMap: (pressed: Boolean) -> Unit,
    private val recenterButton: AppSettings.MapButton = AppSettings.MapButton.NONE,
    private val onRecenter: () -> Unit = {},
) {

    private var rcList: Ref<MutableList<RemoteControlListEntry>>? = null
    private var gamepadRef: Ref<SkyController2Gamepad>? = null
    private var gamepad: SkyController2Gamepad? = null
    private var watchedUid: String? = null
    private val map: Pair<SkyController2Gamepad.Button, ButtonEvent>? = buttonOf(mapButton)
    private val recenter: Pair<SkyController2Gamepad.Button, ButtonEvent>? =
        buttonOf(recenterButton).takeIf { recenterButton != mapButton }

    private val listener = ButtonEvent.Listener { event, state ->
        Log.d(TAG, "button $event $state")
        when {
            event == CYCLE_EVENT && state == ButtonEvent.State.PRESSED -> onCycle()
            event == map?.second -> onMap(state == ButtonEvent.State.PRESSED)
            event == recenter?.second && state == ButtonEvent.State.PRESSED -> onRecenter()
        }
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
                val buttons = EnumSet.of(CYCLE_BUTTON)
                map?.let { m -> buttons.add(m.first) }
                recenter?.let { r -> buttons.add(r.first) }
                pad.grabInputs(buttons, EnumSet.noneOf(SkyController2Gamepad.Axis::class.java))
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
        val CYCLE_BUTTON = SkyController2Gamepad.Button.SETTINGS
        private val CYCLE_EVENT = ButtonEvent.SETTINGS

        fun buttonOf(b: AppSettings.MapButton): Pair<SkyController2Gamepad.Button, ButtonEvent>? = when (b) {
            AppSettings.MapButton.NONE -> null
            AppSettings.MapButton.B -> SkyController2Gamepad.Button.BUTTON_B to ButtonEvent.BUTTON_B
            AppSettings.MapButton.A -> SkyController2Gamepad.Button.BUTTON_A to ButtonEvent.BUTTON_A
            AppSettings.MapButton.REAR_LEFT ->
                SkyController2Gamepad.Button.REAR_LEFT_BUTTON to ButtonEvent.REAR_LEFT_BUTTON
            AppSettings.MapButton.REAR_RIGHT ->
                SkyController2Gamepad.Button.REAR_RIGHT_BUTTON to ButtonEvent.REAR_RIGHT_BUTTON
        }
    }
}
