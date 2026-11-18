/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.view.View
import android.widget.TextView

/**
 * One HUD (hud.xml or hud_headset.xml under [root]): renders [HudModel]
 * in the current [HudMode] and switches modes, showing the new mode's name
 * for a moment.
 */
class Hud(private val root: View, mode: HudMode) {

    private val classic: View = root.findViewById(R.id.hud_classic)
    private val minimal: MinimalHudView = root.findViewById(R.id.hud_minimal)
    private val fighter: FighterHudView = root.findViewById(R.id.hud_fighter)
    private val banner: TextView = root.findViewById(R.id.hud_mode_banner)
    private val hideBanner = Runnable { banner.visibility = View.GONE }

    var mode: HudMode = mode
        private set

    init {
        show(mode)
    }

    fun setMode(newMode: HudMode, announce: Boolean = true) {
        mode = newMode
        show(newMode)
        if (announce) {
            banner.text = "HUD ${newMode.name}"
            banner.visibility = View.VISIBLE
            banner.removeCallbacks(hideBanner)
            banner.postDelayed(hideBanner, BANNER_MS)
        }
    }

    private fun show(m: HudMode) {
        classic.visibility = if (m == HudMode.CLASSIC) View.VISIBLE else View.GONE
        minimal.visibility = if (m == HudMode.MINIMAL) View.VISIBLE else View.GONE
        fighter.visibility = if (m == HudMode.FIGHTER) View.VISIBLE else View.GONE
    }

    fun render(m: HudModel) {
        minimal.model = m
        fighter.model = m
        text(R.id.hud_state, HudFormat.stateLine(m.connected, m.flyingState), warning = !m.connected)
        text(R.id.hud_altitude, HudFormat.altitude(m.altitude))
        text(R.id.hud_air_speed, HudFormat.airSpeed(m.airSpeed))
        text(R.id.hud_ground_speed, HudFormat.groundSpeed(m.groundSpeed))
        text(R.id.hud_battery, HudFormat.battery("BAT", m.battery), HudFormat.isLowBattery(m.battery))
        text(R.id.hud_rc_battery, HudFormat.battery("RC", m.rcBattery), HudFormat.isLowBattery(m.rcBattery))
        text(R.id.hud_gps, HudFormat.gps(m.gpsFixed, m.satellites))
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
