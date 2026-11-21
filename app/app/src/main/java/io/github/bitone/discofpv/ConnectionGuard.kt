/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.content.Context
import com.parrot.drone.groundsdk.GroundSdk
import com.parrot.drone.groundsdk.device.DeviceConnector
import com.parrot.drone.groundsdk.device.DeviceState
import com.parrot.drone.groundsdk.device.DroneListEntry
import com.parrot.drone.groundsdk.facility.AutoConnection

/** The Discos the pilot allowed over Wi-Fi, by uid (their serial number). */
class TrustedDrones(context: Context) {
    private val prefs = context.getSharedPreferences("trusted_drones", Context.MODE_PRIVATE)

    fun all(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty()
    fun contains(uid: String) = uid in all()
    fun add(uid: String) = prefs.edit().putStringSet(KEY, all() + uid).apply()
    fun clear() = prefs.edit().remove(KEY).apply()

    private companion object { const val KEY = "uids" }
}

/**
 * Who the app connects to.
 *
 * - A SkyController plugged in (USB): GroundSdk's auto-connection, which
 *   connects it and the Disco it flies, and drops any other drone.
 * - No controller: a Disco seen on the phone's Wi-Fi (its own DISCO-xxxxxx
 *   network, for short range flying or with an RC transmitter) connects by
 *   itself only if the pilot allowed it once ([allow], a tap on the main
 *   screen); the others are listed through [onWifiDrones], never connected.
 *
 * GroundSdk's auto-connection alone connected any drone announced on any
 * Wi-Fi the phone joins (a public hotspot, a rogue access point), showing
 * its video and telemetry; it now runs only while a controller is there.
 *
 * Its own session, resumed from creation to [close]: it keeps working
 * (a Wi-Fi Disco back in range is reconnected) while the flight screens
 * are in front. The main screen owns it.
 */
class ConnectionGuard(context: Context) {

    /** A Disco on Wi-Fi the pilot hasn't allowed yet. */
    data class WifiDrone(val uid: String, val name: String)

    /** The Wi-Fi Discos waiting for the pilot's tap (empty with a controller). */
    var onWifiDrones: ((List<WifiDrone>) -> Unit)? = null
        set(value) { field = value; value?.invoke(waiting) }

    private val trusted = TrustedDrones(context)
    private val sdk = GroundSdk.newSession(context.applicationContext, null)
    private var rcVisible = false
    private var drones: List<DroneListEntry> = emptyList()
    private var waiting: List<WifiDrone> = emptyList()

    init {
        sdk.resume()
        sdk.getRemoteControlList({ true }) { list ->
            rcVisible = list.orEmpty().any { it.state.connectors.isNotEmpty() }
            update()
        }
        sdk.getDroneList({ true }) { list ->
            drones = list.orEmpty()
            update()
        }
    }

    /** The pilot's tap: connect this Disco now, and by itself from now on. */
    fun allow(uid: String) {
        trusted.add(uid)
        update()
    }

    fun close() {
        onWifiDrones = null
        sdk.close()
    }

    private fun update() {
        val auto = sdk.getFacility(AutoConnection::class.java)
        if (rcVisible) {
            if (auto != null && auto.status != AutoConnection.Status.STARTED) auto.start()
        } else {
            if (auto != null && auto.status == AutoConnection.Status.STARTED) auto.stop()
            // a drone on Wi-Fi the pilot didn't allow never stays connected
            drones.filter { onWifi(it) && !trusted.contains(it.uid) && active(it) }
                .forEach { sdk.getDrone(it.uid)?.disconnect() }
            // the allowed one connects, and again when back in range
            if (drones.none { active(it) }) {
                drones.firstOrNull { onWifi(it) && trusted.contains(it.uid) && it.state.canBeConnected() }
                    ?.let { sdk.connectDrone(it.uid) }
            }
        }
        waiting = if (rcVisible) emptyList() else drones
            .filter { onWifi(it) && !trusted.contains(it.uid) }
            .map { WifiDrone(it.uid, it.name) }
        onWifiDrones?.invoke(waiting)
    }

    private fun active(d: DroneListEntry) = d.state.connectionState == DeviceState.ConnectionState.CONNECTED ||
        d.state.connectionState == DeviceState.ConnectionState.CONNECTING

    /** Seen directly on the phone's Wi-Fi (not through a controller). */
    private fun onWifi(d: DroneListEntry) = d.state.connectors.any {
        it.type == DeviceConnector.Type.LOCAL && it.technology == DeviceConnector.Technology.WIFI
    }
}
