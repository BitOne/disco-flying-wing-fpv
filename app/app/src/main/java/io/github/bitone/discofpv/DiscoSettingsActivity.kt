/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.parrot.drone.groundsdk.GroundSdk
import com.parrot.drone.groundsdk.ManagedGroundSdk
import com.parrot.drone.groundsdk.Ref
import com.parrot.drone.groundsdk.device.DeviceState
import com.parrot.drone.groundsdk.device.Drone
import com.parrot.drone.groundsdk.device.instrument.FlyingIndicators
import com.parrot.drone.groundsdk.device.peripheral.FixedWingFlightTuning
import com.parrot.drone.groundsdk.device.peripheral.Geofence
import com.parrot.drone.groundsdk.device.peripheral.WifiRegulation
import com.parrot.drone.groundsdk.device.pilotingitf.ReturnHomePilotingItf
import kotlin.math.roundToInt

/**
 * The Disco's own settings, kept by the Disco (T9.1, T9.2): the geofence,
 * the return home, the circling, and the Wi-Fi country and outdoor rules
 * that set its radio's channels and transmit power. Each row shows the
 * value the Disco reports; a tap changes it. The Wi-Fi rows only work on
 * the ground: the Disco restarts its Wi-Fi to apply them.
 */
class DiscoSettingsActivity : Activity() {

    private lateinit var groundSdk: GroundSdk
    private val refs = mutableListOf<Ref<*>>()

    private var geofence: Geofence? = null
    private var rth: ReturnHomePilotingItf? = null
    private var tuning: FixedWingFlightTuning? = null
    private var wifi: WifiRegulation? = null
    private var landed = false
    private var connected = true

    private lateinit var geofenceMode: Button
    private lateinit var maxAltitude: Button
    private lateinit var maxDistance: Button
    private lateinit var rthDelay: Button
    private lateinit var rthTarget: Button
    private lateinit var circlingAltitude: Button
    private lateinit var circlingDirection: Button
    private lateinit var country: Button
    private lateinit var outdoor: Button
    private lateinit var wifiNote: TextView
    private lateinit var linkNote: TextView
    private lateinit var firmware: Button
    private var firmwareVersion: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(24), px(24), px(24))
        }
        fun place(v: View, top: Int) {
            column.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(top) })
        }
        fun text(style: Int, value: String, top: Int = 0) =
            TextView(this, null, 0, style).apply { text = value; place(this, top) }
        fun row(onClick: () -> Unit) = Button(this, null, 0, R.style.SettingRow).apply {
            setOnClickListener { onClick() }
            place(this, 6)
        }

        text(R.style.Title, getString(R.string.disco_settings_title))
        linkNote = TextView(this, null, 0, R.style.Card).apply {
            text = getString(R.string.disco_reconnecting)
            visibility = View.GONE
            place(this, 16)
        }
        text(R.style.Section, getString(R.string.section_geofence), 24)
        geofenceMode = row { geofence?.mode()?.let {
            it.value = if (it.value == Geofence.Mode.CYLINDER) Geofence.Mode.ALTITUDE else Geofence.Mode.CYLINDER
        } }
        maxAltitude = row { geofence?.maxAltitude()?.let { s ->
            askNumber(getString(R.string.disco_max_altitude_title), s.value, s.min, s.max) { s.value = it }
        } }
        maxDistance = row { geofence?.maxDistance()?.let { s ->
            askNumber(getString(R.string.disco_max_distance_title), s.value, s.min, s.max) { s.value = it }
        } }
        text(R.style.SettingNote, getString(R.string.disco_geofence_note))

        text(R.style.Section, getString(R.string.section_return_home), 24)
        rthDelay = row { rth?.autoStartOnDisconnectDelay?.let { s ->
            askNumber(getString(R.string.disco_rth_delay_title), s.value.toDouble(), s.min.toDouble(),
                s.max.toDouble()) { s.value = it.roundToInt() }
        } }
        rthTarget = row { rth?.preferredTarget?.let {
            val next = if (it.value == ReturnHomePilotingItf.Target.TAKE_OFF_POSITION)
                ReturnHomePilotingItf.Target.CONTROLLER_POSITION else ReturnHomePilotingItf.Target.TAKE_OFF_POSITION
            if (next in it.availableValues) it.value = next
        } }
        circlingAltitude = row { tuning?.circlingAltitude()?.let { s ->
            askNumber(getString(R.string.disco_circling_altitude_title), s.value.toDouble(), s.min.toDouble(),
                s.max.toDouble()) { s.value = it.roundToInt() }
        } }
        circlingDirection = row { tuning?.circlingDirection()?.let {
            it.value = if (it.value == FixedWingFlightTuning.CirclingDirection.CW)
                FixedWingFlightTuning.CirclingDirection.CCW else FixedWingFlightTuning.CirclingDirection.CW
        } }
        text(R.style.SettingNote, getString(R.string.disco_rth_note))

        text(R.style.Section, getString(R.string.section_wifi_radio), 24)
        country = row { chooseCountry() }
        outdoor = row { wifi?.let { it.setOutdoor(!it.isOutdoor) } }
        wifiNote = text(R.style.SettingNote, "")
        text(R.style.SettingNote, getString(R.string.disco_wifi_power_note), 6)

        text(R.style.Section, getString(R.string.fw_title), 24)
        firmware = row {
            startActivity(android.content.Intent(this, FirmwareActivity::class.java)
                .putExtra(DroneScreenActivity.EXTRA_DRONE_UID, intent.getStringExtra(DroneScreenActivity.EXTRA_DRONE_UID)))
        }

        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(column) })

        groundSdk = ManagedGroundSdk.obtainSession(this)
        val drone = intent.getStringExtra(DroneScreenActivity.EXTRA_DRONE_UID)?.let { groundSdk.getDrone(it) { finish() } }
        if (drone == null) {
            finish()
            return
        }
        watch(drone)
        render()
    }

    private fun watch(drone: Drone) {
        refs += drone.getState { state ->
            connected = state?.connectionState == DeviceState.ConnectionState.CONNECTED
            render()
        }
        refs += drone.getPeripheral(Geofence::class.java) { geofence = it; render() }
        refs += drone.getPilotingItf(ReturnHomePilotingItf::class.java) { rth = it; render() }
        refs += drone.getPeripheral(FixedWingFlightTuning::class.java) { tuning = it; render() }
        refs += drone.getPeripheral(WifiRegulation::class.java) { wifi = it; render() }
        refs += drone.getPeripheral(com.parrot.drone.groundsdk.device.peripheral.SystemInfo::class.java) {
            firmwareVersion = it?.firmwareVersion
            render()
        }
        refs += drone.getInstrument(FlyingIndicators::class.java) {
            landed = it?.state == FlyingIndicators.State.LANDED
            render()
        }
    }

    override fun onDestroy() {
        refs.forEach { it.close() }
        super.onDestroy()
    }

    private fun onOff(on: Boolean) = getString(if (on) R.string.on else R.string.off)

    private fun render() {
        // a Wi-Fi restart or a link loss: the values come back with the Disco
        linkNote.visibility = if (connected) View.GONE else View.VISIBLE
        val g = geofence
        geofenceMode.isEnabled = g != null
        maxAltitude.isEnabled = g != null
        maxDistance.isEnabled = g != null
        geofenceMode.text = getString(R.string.disco_geofence, g?.let { onOff(it.mode().value == Geofence.Mode.CYLINDER) } ?: "?")
        maxAltitude.text = getString(R.string.disco_max_altitude, g?.maxAltitude()?.value?.roundToInt()?.toString() ?: "?")
        maxDistance.text = getString(R.string.disco_max_distance, g?.maxDistance()?.value?.roundToInt()?.toString() ?: "?")

        val r = rth
        rthDelay.isEnabled = r != null
        rthTarget.isEnabled = r != null
        rthDelay.text = getString(R.string.disco_rth_delay, r?.autoStartOnDisconnectDelay?.value?.toString() ?: "?")
        rthTarget.text = getString(R.string.disco_rth_target, when (r?.preferredTarget?.value) {
            null -> "?"
            ReturnHomePilotingItf.Target.CONTROLLER_POSITION -> getString(R.string.disco_target_pilot)
            else -> getString(R.string.disco_target_takeoff)
        })

        val t = tuning
        circlingAltitude.isEnabled = t != null
        circlingDirection.isEnabled = t != null
        circlingAltitude.text = getString(R.string.disco_circling_altitude, t?.circlingAltitude()?.value?.toString() ?: "?")
        circlingDirection.text = getString(R.string.disco_circling_direction, when (t?.circlingDirection()?.value) {
            null -> "?"
            FixedWingFlightTuning.CirclingDirection.CCW -> getString(R.string.disco_ccw)
            else -> getString(R.string.disco_cw)
        })

        firmware.text = getString(R.string.disco_firmware, firmwareVersion ?: "?")

        val w = wifi
        country.isEnabled = w != null && landed
        outdoor.isEnabled = w != null && landed
        country.text = getString(R.string.disco_country, when {
            w == null -> "?"
            w.isAutomatic -> getString(R.string.disco_country_auto, w.country.ifEmpty { "?" })
            else -> WifiCountries.describe(w.country)
        })
        outdoor.text = getString(R.string.disco_outdoor, w?.let { onOff(it.isOutdoor) } ?: "?")
        wifiNote.text = getString(when {
            !connected -> R.string.disco_wifi_waiting
            w == null -> R.string.disco_wifi_unavailable
            !landed -> R.string.disco_wifi_landed_only
            else -> R.string.disco_wifi_restart
        })
    }

    /** A number between [min] and [max], typed by the pilot. */
    private fun askNumber(title: String, value: Double, min: Double, max: Double, set: (Double) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value.roundToInt().toString())
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(getString(R.string.disco_range, min.roundToInt(), max.roundToInt()))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                input.text.toString().toDoubleOrNull()?.let { set(it.coerceIn(min, max)) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Automatic, or a country chosen by the pilot (their regulation). */
    private fun chooseCountry() {
        val w = wifi ?: return
        val codes = WifiCountries.sorted()
        val labels = listOf(getString(R.string.disco_country_auto_choice)) + codes.map { WifiCountries.describe(it) }
        val checked = if (w.isAutomatic) 0 else codes.indexOf(w.country) + 1
        AlertDialog.Builder(this)
            .setTitle(R.string.disco_country_title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                if (which == 0) w.setAutomatic(true) else w.setCountry(codes[which - 1])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
