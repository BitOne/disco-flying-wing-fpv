/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * Settings ([AppSettings]): each row shows its value, a tap moves to the
 * next one. Applied the next time a drone screen opens.
 */
class SettingsActivity : Activity() {

    private companion object {
        /** Area downloaded ahead: the Disco's 2 km range... rounded down. */
        const val PREFETCH_RADIUS_M = 1500.0
    }

    private var settings = AppSettings()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = AppSettings.load(this)
        row(R.id.setting_map_button) { it.copy(mapButton = it.mapButton.next()) }
        row(R.id.setting_map_hold) { it.copy(mapHold = !it.mapHold) }
        row(R.id.setting_last_fix) { it.copy(rememberLastFix = !it.rememberLastFix) }
        row(R.id.setting_hud_size) { it.nextHudPercent() }
        row(R.id.setting_map_tiles) { it.copy(mapTiles = it.mapTiles.next()) }
        row(R.id.setting_head_tracking) { it.copy(headTracking = !it.headTracking) }
        row(R.id.setting_recenter_button) { it.copy(recenterButton = it.recenterButton.next()) }
        findViewById<Button>(R.id.setting_tile_url).setOnClickListener { editTileUrl() }
        findViewById<Button>(R.id.setting_download_tiles).setOnClickListener { downloadTiles() }
        findViewById<Button>(R.id.setting_forget_fix).setOnClickListener {
            LastFixStore(this).clear()
            findViewById<TextView>(R.id.setting_last_fix_value).text = getString(R.string.last_fix_none)
        }
        refresh()
    }

    /** The custom XYZ URL ({z}, {x}, {y}), e.g. a satellite layer with your key. */
    private fun editTileUrl() {
        val input = EditText(this).apply {
            id = R.id.setting_tile_url_input
            setText(settings.customTileUrl)
            hint = "https://.../{z}/{x}/{y}.png"
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.custom_tile_url)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                settings = settings.copy(customTileUrl = input.text.toString().trim())
                AppSettings.save(this, settings)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Tiles around the last GPS position, for flying where there is no network. */
    private fun downloadTiles() {
        val status = findViewById<TextView>(R.id.setting_download_status)
        val source = settings.tileSource()
        val fix = LastFixStore(this).load()
        when {
            source == null -> status.setText(R.string.download_no_source)
            !source.prefetch -> status.setText(R.string.download_not_allowed)
            fix == null -> status.setText(R.string.last_fix_none)
            else -> TileLayer(this, source) {}.prefetch(fix.point, PREFETCH_RADIUS_M) { done, total ->
                status.text = getString(R.string.download_progress, done, total)
            }
        }
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
        findViewById<Button>(R.id.setting_map_tiles).text = getString(R.string.setting_map_tiles, s.mapTiles.label)
        findViewById<Button>(R.id.setting_tile_url).text =
            getString(R.string.setting_tile_url, s.customTileUrl.ifEmpty { getString(R.string.none) })
        val fix = LastFixStore(this).load()
        findViewById<TextView>(R.id.setting_last_fix_value).text =
            fix?.let { HudAlerts.lastFix(it, System.currentTimeMillis()) } ?: getString(R.string.last_fix_none)
    }
}
