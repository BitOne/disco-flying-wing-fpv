/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.content.Context

/**
 * HUD styles, cycled with a controller button (or a tap on the flight
 * view) and remembered across sessions.
 */
enum class HudMode {
    /** Text readouts: flight data and systems. */
    CLASSIC,

    /** Battery and an arrow pointing home, nothing else. */
    MINIMAL,

    /** Jet fighter style: artificial horizon, speed and altitude tapes. */
    FIGHTER,

    /** Moving map: trail, home, last fix. */
    MAP;

    fun next(): HudMode = entries[(ordinal + 1) % entries.size]

    companion object {
        private const val PREFS = "hud"
        private const val KEY_MODE = "mode"

        fun parse(name: String?): HudMode = entries.firstOrNull { it.name == name } ?: CLASSIC

        fun load(context: Context): HudMode =
            parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, null))

        fun save(context: Context, mode: HudMode) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode.name).apply()
        }
    }
}
