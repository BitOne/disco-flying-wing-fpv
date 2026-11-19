/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.content.Context

/**
 * User settings (the settings screen), independent of the views:
 * - [mapButton]: the controller button that brings the moving map into
 *   view, [mapHold] while held (else each press toggles it);
 * - [rememberLastFix]: keep the last GPS position across sessions, to find
 *   the Disco after a loss;
 * - [headsetHudPercent]: size of the HUD area in the headset, in percent of
 *   the video: only the middle of the picture is seen through the lenses.
 */
data class AppSettings(
    val mapButton: MapButton = MapButton.B,
    val mapHold: Boolean = false,
    val rememberLastFix: Boolean = true,
    val headsetHudPercent: Int = DEFAULT_HUD_PERCENT,
) {
    /** Controller buttons free for the app (not flight actions). */
    enum class MapButton(val label: String) {
        NONE("none"),
        B("B"),
        A("A"),
        REAR_LEFT("rear left"),
        REAR_RIGHT("rear right");

        fun next(): MapButton = entries[(ordinal + 1) % entries.size]
    }

    fun nextHudPercent(): AppSettings =
        copy(headsetHudPercent = if (headsetHudPercent >= MAX_HUD_PERCENT) MIN_HUD_PERCENT else headsetHudPercent + 10)

    companion object {
        const val DEFAULT_HUD_PERCENT = 60
        const val MIN_HUD_PERCENT = 40
        const val MAX_HUD_PERCENT = 100
        private const val PREFS = "settings"

        fun load(context: Context): AppSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return AppSettings(
                mapButton = MapButton.entries.firstOrNull { it.name == p.getString("map_button", null) } ?: MapButton.B,
                mapHold = p.getBoolean("map_hold", false),
                rememberLastFix = p.getBoolean("remember_last_fix", true),
                headsetHudPercent = p.getInt("headset_hud_percent", DEFAULT_HUD_PERCENT)
                    .coerceIn(MIN_HUD_PERCENT, MAX_HUD_PERCENT),
            )
        }

        fun save(context: Context, s: AppSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("map_button", s.mapButton.name)
                .putBoolean("map_hold", s.mapHold)
                .putBoolean("remember_last_fix", s.rememberLastFix)
                .putInt("headset_hud_percent", s.headsetHudPercent)
                .apply()
        }
    }
}
