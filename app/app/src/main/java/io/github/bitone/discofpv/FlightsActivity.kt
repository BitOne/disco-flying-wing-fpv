/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The flights recorded on this phone ([FlightRecorder], T9.4): the total
 * flight time, and for each flight its date, duration, height, distance
 * and lowest battery; exported as GPX (maps, Airdata-like sites), KML
 * (Google Earth, in 3D) or CSV (every sample) where the pilot chooses,
 * through Android's file picker. Nothing leaves the phone otherwise.
 */
class FlightsActivity : Activity() {

    private lateinit var store: FlightLogStore
    private lateinit var column: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView

    /** The last export's outcome, shown after the summary until the next one. */
    private var notice: String? = null

    /** The export waiting for the file picker's answer. */
    private var pendingExport: Pair<File, String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = FlightLogStore(this)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(24), px(24), px(24))
        }
        fun place(v: View, top: Int) = column.addView(v, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(top) })
        place(TextView(this, null, 0, R.style.Title).apply { text = getString(R.string.flights_title) }, 0)
        summary = TextView(this, null, 0, R.style.SettingNote)
        place(summary, 12)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        place(list, 8)
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(column) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        list.removeAllViews()
        val flights = store.list().map { it to FlightTrack.stats(store.read(it)) }.filter { it.second != null }
        val total = flights.sumOf { it.second!!.durationS }
        val line = if (flights.isEmpty()) getString(R.string.flights_none)
        else resources.getQuantityString(R.plurals.flights_summary, flights.size, flights.size,
            FlightTrack.duration(total))
        summary.text = notice?.let { "$line\n$it" } ?: line
        val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        for ((file, stats) in flights) {
            val s = stats!!
            list.addView(TextView(this, null, 0, R.style.Section).apply {
                text = format.format(Date(s.startMs))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(20) })
            list.addView(TextView(this, null, 0, R.style.Card).apply {
                text = describe(s)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(4) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((label, kind) in listOf("GPX" to "gpx", "KML" to "kml", "CSV" to "csv")) {
                row.addView(Button(this, null, 0, R.style.Button_Secondary).apply {
                    text = getString(R.string.flights_export, label)
                    contentDescription = "${file.name} $label"
                    setOnClickListener { export(file, kind) }
                }, LinearLayout.LayoutParams(0, px(44), 1f).apply { marginEnd = px(6) })
            }
            row.addView(Button(this, null, 0, R.style.Button_Secondary).apply {
                text = getString(R.string.flights_delete)
                contentDescription = "${file.name} delete"
                setOnClickListener { confirmDelete(file) }
            }, LinearLayout.LayoutParams(0, px(44), 1f))
            list.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6) })
        }
    }

    private fun describe(s: FlightStats): String {
        val parts = mutableListOf(FlightTrack.duration(s.durationS))
        s.maxAltitude?.let { parts += getString(R.string.flights_max_altitude, it.roundToInt()) }
        s.maxDistance?.let { parts += getString(R.string.flights_max_distance, HudFormat.distance(it)) }
        if (s.distance > 0) parts += getString(R.string.flights_flown, HudFormat.distance(s.distance))
        s.minBattery?.let { parts += getString(R.string.flights_battery, it) }
        return parts.joinToString("\n")
    }

    private fun export(file: File, kind: String) {
        pendingExport = file to kind
        val mime = when (kind) {
            "gpx" -> "application/gpx+xml"
            "kml" -> "application/vnd.google-earth.kml+xml"
            else -> "text/csv"
        }
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, file.nameWithoutExtension + "." + kind)
        }, REQUEST_EXPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val (file, kind) = pendingExport ?: return
        pendingExport = null
        val uri = data?.data
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK || uri == null) return
        val samples = store.read(file)
        val name = file.nameWithoutExtension
        val text = when (kind) {
            "gpx" -> FlightTrack.gpx(name, samples)
            "kml" -> FlightTrack.kml(name, samples)
            else -> FlightTrack.csv(samples)
        }
        val ok = runCatching {
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
        }.isSuccess
        // onResume follows and shows it
        notice = getString(if (ok) R.string.flights_exported else R.string.flights_export_failed,
            kind.uppercase(Locale.ROOT))
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setMessage(R.string.flights_delete_confirm)
            .setPositiveButton(R.string.flights_delete_yes) { _, _ ->
                file.delete()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private companion object { const val REQUEST_EXPORT = 1 }
}
