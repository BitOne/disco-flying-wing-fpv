/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.graphics.RectF
import android.view.Choreographer
import android.view.View
import android.widget.TextView

/**
 * One HUD (hud.xml or hud_headset.xml under [root]): renders [HudModel]
 * in the current [HudMode], switches modes (showing the new mode's name for
 * a moment), shows the moving map over any mode on demand ([showMap]), and
 * the warnings ([HudModel.alerts], last fix) in every mode.
 */
class Hud(private val root: View, mode: HudMode) {

    private val classic: View = root.findViewById(R.id.hud_classic)
    private val minimal: MinimalHudView = root.findViewById(R.id.hud_minimal)
    private val fighter: FighterHudView = root.findViewById(R.id.hud_fighter)
    private val map: MapHudView = root.findViewById(R.id.hud_map)
    private val center: CenterIndicatorView = root.findViewById(R.id.hud_center)
    private val alerts: TextView = root.findViewById(R.id.hud_alerts)
    private val banner: TextView = root.findViewById(R.id.hud_mode_banner)
    private val hideBanner = Runnable { banner.visibility = View.GONE }
    private var last = HudModel()

    /** Warnings from outside the drone's state, first of all, by key: the
     *  video (VideoWatchdog), refused buttons (HudButton). */
    private val extraAlerts = linkedMapOf<String, String>()

    /** Sets or clears (null) the warning under [key]. */
    fun setAlert(key: String, alert: String?) {
        val changed = if (alert == null) extraAlerts.remove(key) != null else extraAlerts.put(key, alert) != alert
        if (changed) renderAlerts(last)
    }

    fun setVideoAlert(alert: String?) = setAlert("video", alert)

    /** Draws the warnings again: the last fix's age goes on with no telemetry. */
    fun refreshAlerts() = renderAlerts(last)

    var mode: HudMode = mode
        private set

    /** The map is shown over the current mode (the controller's map button). */
    var mapShown: Boolean = false
        private set

    init {
        show()
    }

    fun setMode(newMode: HudMode, announce: Boolean = true) {
        mode = newMode
        show()
        if (announce) announce("HUD ${newMode.name}")
    }

    fun showMap(shown: Boolean) {
        if (shown == mapShown) return
        mapShown = shown
        show()
    }

    /** Map tiles for the moving map (null: none). */
    fun setTiles(tiles: TileLayer?) {
        map.tiles = tiles
    }

    /** Drawing area of the drawn modes (null: the whole view). */
    fun setArea(area: RectF?) {
        minimal.area = area
        fighter.area = area
        map.area = area
        center.area = area
    }

    private fun announce(text: String) {
        banner.text = text
        banner.visibility = View.VISIBLE
        banner.removeCallbacks(hideBanner)
        banner.postDelayed(hideBanner, BANNER_MS)
    }

    private fun show() {
        val m = if (mapShown) HudMode.MAP else mode
        classic.visibility = if (m == HudMode.CLASSIC) View.VISIBLE else View.GONE
        minimal.visibility = if (m == HudMode.MINIMAL) View.VISIBLE else View.GONE
        fighter.visibility = if (m == HudMode.FIGHTER) View.VISIBLE else View.GONE
        map.visibility = if (m == HudMode.MAP) View.VISIBLE else View.GONE
    }

    private var pending: HudModel? = null
    private val frame = Choreographer.FrameCallback {
        pending?.let { m ->
            pending = null
            render(m)
        }
    }

    /**
     * The latest model, drawn on the next display frame: telemetry comes as
     * many small updates (attitude, speeds, position... each its own
     * message), drawing each one would queue frames and delay them all.
     */
    fun submit(m: HudModel) {
        if (pending == null) Choreographer.getInstance().postFrameCallback(frame)
        pending = m
    }

    fun render(m: HudModel) {
        last = m
        minimal.model = m
        fighter.model = m
        map.model = m
        center.model = m
        text(R.id.hud_state, HudFormat.stateLine(m.connected, m.flyingState), warning = !m.connected)
        text(R.id.hud_altitude, HudFormat.altitude(m.altitude))
        text(R.id.hud_air_speed, HudFormat.airSpeed(m.airSpeed))
        text(R.id.hud_ground_speed, HudFormat.groundSpeed(m.groundSpeed))
        text(R.id.hud_battery, HudFormat.battery("BAT", m.battery), HudFormat.isLowBattery(m.battery))
        text(R.id.hud_rc_battery, HudFormat.battery("RC", m.rcBattery), HudFormat.isLowBattery(m.rcBattery))
        text(R.id.hud_link, HudFormat.link(m.rssi), HudFormat.isWeakLink(m.rssi))
        text(R.id.hud_record, HudFormat.record(m.recordingSinceMs, System.currentTimeMillis(), m.storageFree),
            m.recordingSinceMs != null || (m.storageFree ?: Long.MAX_VALUE) < HudFormat.LOW_STORAGE_BYTES)
        text(R.id.hud_gps, HudFormat.gps(m.gpsFixed, m.satellites))
        text(R.id.hud_camera, HudAlerts.camera(m.cameraTilt, m.cameraPan) ?: "")
        renderAlerts(m)
    }

    private fun renderAlerts(m: HudModel) {
        // no video alert without a link: LINK LOST says it all
        val lines = ((if (m.connected) extraAlerts.values.toList() else emptyList()) + m.alerts).toMutableList()
        val fix = m.lastFix
        // where it was, also (above all) with the link lost: a crash, the
        // battery thrown out; in every mode but the map, which draws it
        if (m.position == null && fix != null && !(mapShown || mode == HudMode.MAP)) {
            lines += HudAlerts.lastFix(fix, System.currentTimeMillis())
        }
        val value = lines.joinToString("\n")
        if (alerts.text.toString() != value) alerts.text = value
        alerts.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun text(id: Int, value: String, warning: Boolean = false) {
        root.findViewById<TextView>(id)?.apply {
            if (text != value) text = value
            setTextColor(if (warning) HudCanvasView.WARNING_COLOR else HudCanvasView.HUD_COLOR)
        }
    }

    private companion object {
        const val BANNER_MS = 2000L
    }
}
