/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

/**
 * Settings ([AppSettings]): each row shows its value, a tap moves to the
 * next one. Applied the next time a drone screen opens.
 */
class SettingsActivity : Activity() {

    private var settings = AppSettings()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = AppSettings.load(this)
        row(R.id.setting_map_button) { it.copy(mapButton = it.mapButton.next()) }
        row(R.id.setting_map_hold) { it.copy(mapHold = !it.mapHold) }
        row(R.id.setting_last_fix) { it.copy(rememberLastFix = !it.rememberLastFix) }
        row(R.id.setting_hud_size) { it.nextHudPercent() }
        findViewById<Button>(R.id.setting_forget_fix).setOnClickListener {
            LastFixStore(this).clear()
            findViewById<TextView>(R.id.setting_last_fix_value).text = getString(R.string.last_fix_none)
        }
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
        val fix = LastFixStore(this).load()
        findViewById<TextView>(R.id.setting_last_fix_value).text =
            fix?.let { HudAlerts.lastFix(it, System.currentTimeMillis()) } ?: getString(R.string.last_fix_none)
    }
}
