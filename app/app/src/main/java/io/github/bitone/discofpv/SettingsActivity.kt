/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

/**
 * Settings ([AppSettings]): each row shows its value, a tap moves to the
 * next one. The map's background and offline download have their own
 * screen ([MapActivity]). Applied the next time a drone screen opens.
 */
class SettingsActivity : Activity() {

    private var settings = AppSettings()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        row(R.id.setting_map_button) { it.copy(mapButton = it.mapButton.next()) }
        row(R.id.setting_map_hold) { it.copy(mapHold = !it.mapHold) }
        row(R.id.setting_last_fix) { it.copy(rememberLastFix = !it.rememberLastFix) }
        row(R.id.setting_hud_size) { it.nextHudPercent() }
        row(R.id.setting_head_tracking) { it.copy(headTracking = !it.headTracking) }
        row(R.id.setting_recenter_button) { it.copy(recenterButton = it.recenterButton.next()) }
        findViewById<Button>(R.id.setting_map).setOnClickListener {
            startActivity(Intent(this, MapActivity::class.java))
        }
        findViewById<Button>(R.id.setting_forget_fix).setOnClickListener {
            LastFixStore(this).clear()
            findViewById<TextView>(R.id.setting_last_fix_value).text = getString(R.string.last_fix_none)
        }
        findViewById<Button>(R.id.setting_forget_wifi).setOnClickListener {
            TrustedDrones(this).clear()
            showTrusted()
        }
    }

    /** How many Discos connect by themselves over Wi-Fi ([ConnectionGuard]). */
    private fun showTrusted() {
        findViewById<Button>(R.id.setting_forget_wifi).apply {
            val n = TrustedDrones(this@SettingsActivity).all().size
            text = getString(R.string.forget_wifi_discos, n)
            isEnabled = n > 0
        }
    }

    override fun onResume() {
        super.onResume()
        showTrusted()
        // back from the map screen: its choice shows here
        settings = AppSettings.load(this)
        refresh()
    }

    private fun row(id: Int, change: (AppSettings) -> AppSettings) {
        findViewById<Button>(id).setOnClickListener {
            settings = change(settings)
            AppSettings.save(this, settings)
            refresh()
        }
    }

    private fun refresh() {
        val s = settings
        findViewById<Button>(R.id.setting_map_button).text = getString(R.string.setting_map_button, s.mapButton.label)
        findViewById<Button>(R.id.setting_map_hold).text =
            getString(R.string.setting_map_hold, getString(if (s.mapHold) R.string.map_hold else R.string.map_toggle))
        findViewById<Button>(R.id.setting_last_fix).text =
            getString(R.string.setting_last_fix, getString(if (s.rememberLastFix) R.string.on else R.string.off))
        findViewById<Button>(R.id.setting_hud_size).text = getString(R.string.setting_hud_size, s.headsetHudPercent)
        findViewById<Button>(R.id.setting_head_tracking).text =
            getString(R.string.setting_head_tracking, getString(if (s.headTracking) R.string.on else R.string.off))
        findViewById<Button>(R.id.setting_recenter_button).text =
            getString(R.string.setting_recenter_button, s.recenterButton.label)
        findViewById<Button>(R.id.setting_map).text = getString(R.string.setting_map, s.mapTiles.label)
        val fix = LastFixStore(this).load()
        findViewById<TextView>(R.id.setting_last_fix_value).text =
            fix?.let { HudAlerts.lastFix(it, System.currentTimeMillis()) } ?: getString(R.string.last_fix_none)
    }
}
