/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.content.Context

/**
 * The last GPS position of the drone, saved as it flies (at most every
 * [SAVE_INTERVAL_MS]) so it survives a lost link, a lost fix, or the app
 * being closed: where to look for the Disco.
 */
class LastFixStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var lastSaveMs = 0L

    fun load(): LastFix? {
        if (!prefs.contains("lat")) return null
        return LastFix(
            GeoPoint(Double.fromBits(prefs.getLong("lat", 0)), Double.fromBits(prefs.getLong("lon", 0))),
            if (prefs.contains("alt")) Double.fromBits(prefs.getLong("alt", 0)) else null,
            prefs.getLong("time", 0),
        )
    }

    fun save(fix: LastFix, force: Boolean = false) {
        if (!force && fix.timeMs - lastSaveMs < SAVE_INTERVAL_MS) return
        lastSaveMs = fix.timeMs
        val editor = prefs.edit()
            .putLong("lat", fix.point.latitude.toBits())
            .putLong("lon", fix.point.longitude.toBits())
            .putLong("time", fix.timeMs)
        if (fix.altitude != null) editor.putLong("alt", fix.altitude.toBits()) else editor.remove("alt")
        editor.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS = "last_fix"
        const val SAVE_INTERVAL_MS = 2000L
    }
}
