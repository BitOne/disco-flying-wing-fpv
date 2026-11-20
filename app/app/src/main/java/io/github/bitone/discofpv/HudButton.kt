/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 * Portions copyright (c) 2019-Present PARROT DRONES SAS. All rights reserved.
 */
package io.github.bitone.discofpv

import android.util.Log
import com.parrot.drone.groundsdk.GroundSdk
import com.parrot.drone.groundsdk.Ref
import com.parrot.drone.groundsdk.device.DeviceState
import com.parrot.drone.groundsdk.device.Drone
import com.parrot.drone.groundsdk.device.RemoteControlListEntry
import com.parrot.drone.groundsdk.device.peripheral.SkyController2Gamepad
import com.parrot.drone.groundsdk.device.peripheral.gamepad.skycontroller2.ButtonEvent
import com.parrot.drone.groundsdk.device.peripheral.gamepad.skycontroller2.ButtonsMappingEntry
import com.parrot.drone.groundsdk.device.peripheral.gamepad.skycontroller2.MappingEntry
import java.util.EnumSet

/**
 * Takes the app's SkyController 2 buttons while a drone screen is shown
 * ([start]/[stop]): grabbed, the controller sends their presses to the app
 * instead of using them for its own action. [CYCLE_BUTTON] cycles the HUD
 * modes; the map button ([AppSettings.mapButton], none by choice) shows the
 * moving map, [onMap] with true on press and false on release; the
 * recentre button ([AppSettings.recenterButton]) calls [onRecenter].
 *
 * A button the controller's mapping uses for a Disco action (return home,
 * take-off or land... after a remapping) is never taken (ButtonSafety):
 * [onRefused] gets the HUD warnings (empty when none). Nothing is taken
 * until the mapping is known; it is checked again on each update.
 */
class HudButton(
    private val groundSdk: GroundSdk,
    private val mapButton: AppSettings.MapButton,
    private val onCycle: () -> Unit,
    private val onMap: (pressed: Boolean) -> Unit,
    private val recenterButton: AppSettings.MapButton = AppSettings.MapButton.NONE,
    private val onRecenter: () -> Unit = {},
    private val onRefused: (List<String>) -> Unit = {},
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
                if (pad == null) return@getPeripheral
                if (pad !== gamepad) {
                    gamepad = pad
                    pad.setButtonEventListener(listener)
                }
                grabSafely(pad)
            }
        }
    }

    /** Takes the wanted buttons the mapping leaves to the app (see the class comment). */
    private fun grabSafely(pad: SkyController2Gamepad) {
        val mapping = pad.getMapping(Drone.Model.DISCO)
        if (mapping == null) {
            Log.i(TAG, "controller mapping not known yet: no button taken")
            return
        }
        val entries = mapping.filter { it.type == MappingEntry.Type.BUTTONS_MAPPING }.map {
            val e = it.`as`(ButtonsMappingEntry::class.java)
            ButtonSafety.Entry(e.action.name, e.buttonEvents.map { ev -> ev.name }.toSet())
        }
        val wanted = listOfNotNull(
            Triple(CYCLE_BUTTON, CYCLE_EVENT, "SETTINGS"),
            map?.let { Triple(it.first, it.second, mapButton.label) },
            recenter?.let { Triple(it.first, it.second, recenterButton.label) },
        )
        val buttons = EnumSet.noneOf(SkyController2Gamepad.Button::class.java)
        val refused = mutableListOf<String>()
        for ((button, event, label) in wanted) {
            val actions = ButtonSafety.droneActionsOn(entries, event.name)
            if (actions.isEmpty()) {
                buttons.add(button)
            } else {
                Log.w(TAG, "not taking $label: mapped to $actions on the controller")
                refused += ButtonSafety.warning(label.uppercase(), actions)
            }
        }
        onRefused(refused)
        if (buttons != pad.grabbedButtons) {
            pad.grabInputs(buttons, EnumSet.noneOf(SkyController2Gamepad.Axis::class.java))
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
