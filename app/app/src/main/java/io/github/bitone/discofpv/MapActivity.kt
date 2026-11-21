/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The map screen: the moving map's background ([AppSettings.mapTiles], a
 * custom URL, an offline Mapsforge file), a preview of it that can be
 * dragged and zoomed, centred on the Disco's last position, this phone or
 * typed coordinates ([Coordinates]), and the download of the area around
 * the preview's centre for flying without network.
 */
class MapActivity : Activity() {

    private companion object {
        /** Area downloaded ahead: the Disco's 2 km range... rounded down. */
        const val PREFETCH_RADIUS_M = 1500.0
        const val PICK_MAP_FILE = 1
        const val LOCATION_PERMISSION = 2
        /** A phone position older than this is looked for again. */
        const val PHONE_FIX_MAX_AGE_MS = 2 * 60_000L
        const val PHONE_FIX_TIMEOUT_MS = 30_000L
        const val PREFS = "map_screen"
    }

    /** What the preview is centred on, for its caption. */
    private enum class Centre { DISCO, PHONE, COORDINATES, MOVED }

    private var settings = AppSettings()
    private var layer: TileLayer? = null
    private var layerSource: TileSource? = null
    private var centre = Centre.DISCO
    /** The download in progress, if any. */
    private var prefetch: TileLayer.Prefetch? = null
    private val downloading get() = prefetch != null

    private lateinit var preview: MapPreviewView
    private lateinit var caption: TextView
    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)
        settings = AppSettings.load(this)
        preview = findViewById(R.id.map_preview)
        caption = findViewById(R.id.map_center)
        status = findViewById(R.id.setting_download_status)
        preview.radiusMetres = PREFETCH_RADIUS_M
        preview.onMoved = { p ->
            centre = Centre.MOVED
            saveCentre(p)
            showCaption()
        }
        findViewById<Button>(R.id.setting_map_tiles).setOnClickListener { chooseBackground() }
        findViewById<Button>(R.id.setting_tile_url).setOnClickListener { editTileUrl() }
        findViewById<Button>(R.id.setting_map_file).setOnClickListener { pickMapFile() }
        findViewById<Button>(R.id.map_zoom_in).setOnClickListener { zoomBy(1) }
        findViewById<Button>(R.id.map_zoom_out).setOnClickListener { zoomBy(-1) }
        findViewById<Button>(R.id.map_center_disco).setOnClickListener { centreOnDisco(quiet = false) }
        findViewById<Button>(R.id.map_center_phone).setOnClickListener { centreOnPhone() }
        findViewById<Button>(R.id.map_center_coordinates).setOnClickListener { askCoordinates() }
        findViewById<Button>(R.id.map_download).setOnClickListener { if (downloading) stopDownload() else download() }
        restoreCentre()
        refresh()
    }

    override fun onDestroy() {
        // leaving the screen ends the download (the tiles fetched so far stay)
        prefetch?.cancel()
        layer?.close()
        super.onDestroy()
    }

    // --- the background ---

    private fun chooseBackground() {
        val choices = AppSettings.MapTiles.entries
        AlertDialog.Builder(this)
            .setTitle(R.string.map_section_background)
            .setSingleChoiceItems(choices.map { it.label }.toTypedArray(), settings.mapTiles.ordinal) { d, i ->
                d.dismiss()
                val chosen = choices[i]
                settings = settings.copy(mapTiles = chosen)
                AppSettings.save(this, settings)
                refresh()
                // what that background needs first
                when {
                    chosen == AppSettings.MapTiles.CUSTOM && settings.customTileUrl.isEmpty() -> editTileUrl()
                    chosen == AppSettings.MapTiles.OFFLINE && settings.offlineMapUri.isEmpty() -> pickMapFile()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    /** A Mapsforge .map file, read in place (it can be several hundred MB). */
    private fun pickMapFile() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(pick, PICK_MAP_FILE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != PICK_MAP_FILE || resultCode != RESULT_OK || uri == null) return
        // keep the right to read it after a restart
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        settings = settings.copy(offlineMapUri = uri.toString(), mapTiles = AppSettings.MapTiles.OFFLINE)
        AppSettings.save(this, settings)
        refresh()
    }

    private fun fileName(uri: String): String = try {
        contentResolver.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri
    } catch (e: Exception) {
        getString(R.string.map_file_unreadable)
    }

    private fun refresh() {
        val s = settings
        findViewById<Button>(R.id.setting_map_tiles).text = getString(R.string.setting_map_tiles, s.mapTiles.label)
        findViewById<Button>(R.id.setting_tile_url).apply {
            visibility = if (s.mapTiles == AppSettings.MapTiles.CUSTOM) View.VISIBLE else View.GONE
            // its key stays off the screen (screenshots, screen sharing); the edit dialog has it
            text = getString(R.string.setting_tile_url, s.customTileUrl.ifEmpty { null }?.let { TileMath.redact(it) } ?: getString(R.string.none))
        }
        findViewById<Button>(R.id.setting_map_file).apply {
            visibility = if (s.mapTiles == AppSettings.MapTiles.OFFLINE) View.VISIBLE else View.GONE
            text = getString(R.string.setting_map_file,
                s.offlineMapUri.ifEmpty { null }?.let { fileName(it) } ?: getString(R.string.none))
        }
        findViewById<TextView>(R.id.map_source_note).setText(when (s.mapTiles) {
            AppSettings.MapTiles.NONE -> R.string.map_note_none
            AppSettings.MapTiles.OSM -> R.string.map_note_osm
            AppSettings.MapTiles.IGN_PHOTO -> R.string.map_note_ign
            AppSettings.MapTiles.CUSTOM -> R.string.map_note_custom
            AppSettings.MapTiles.OFFLINE -> R.string.map_file_hint
            AppSettings.MapTiles.SATELLITE -> R.string.map_note_satellite
        })
        // a new source: a new tile layer for the preview
        val source = s.tileSource()
        if (source != layerSource) {
            // a download was for the previous background
            prefetch?.cancel()
            layer?.close()
            layerSource = source
            layer = source?.let { TileLayer(this, it) { preview.postInvalidate() } }
            preview.tiles = layer
            preview.zoom = preview.zoom
        }
        preview.message = previewMessage()
        showDownloadButton()
        showCaption()
    }

    /** What the empty preview says: what it lacks. */
    private fun previewMessage(): String =
        getString(if (layerSource == null) R.string.map_no_background else R.string.map_no_center)

    // --- the preview's centre ---

    private fun restoreCentre() {
        preview.zoom = prefs.getInt("zoom", MapPreviewView.DEFAULT_ZOOM)
        centre = runCatching { Centre.valueOf(prefs.getString("centre", "") ?: "") }.getOrDefault(Centre.DISCO)
        if (centre == Centre.DISCO && centreOnDisco(quiet = true)) return
        if (prefs.contains("lat")) {
            preview.center = GeoPoint(Double.fromBits(prefs.getLong("lat", 0)), Double.fromBits(prefs.getLong("lon", 0)))
        } else {
            centreOnDisco(quiet = true)
        }
    }

    private fun saveCentre(p: GeoPoint) {
        prefs.edit()
            .putLong("lat", p.latitude.toBits())
            .putLong("lon", p.longitude.toBits())
            .putString("centre", centre.name)
            .putInt("zoom", preview.zoom)
            .apply()
    }

    private fun centreOn(p: GeoPoint, how: Centre) {
        centre = how
        preview.center = p
        saveCentre(p)
        showCaption()
    }

    private fun showCaption() {
        // a finished download's line is about the previous centre
        if (!downloading) status.text = ""
        val p = preview.center
        caption.text = when {
            p == null -> ""
            centre == Centre.DISCO -> getString(R.string.map_center_at_disco, Coordinates.format(p))
            centre == Centre.PHONE -> getString(R.string.map_center_at_phone, Coordinates.format(p))
            else -> getString(R.string.map_center_at, Coordinates.format(p))
        }
    }

    private fun zoomBy(step: Int) {
        preview.zoom = preview.zoom + step
        preview.center?.let { saveCentre(it) }
    }

    /** The Disco's last GPS position (saved as it flies); false if there is none. */
    private fun centreOnDisco(quiet: Boolean): Boolean {
        val fix = LastFixStore(this).load()
        if (fix == null) {
            if (!quiet) status.setText(R.string.last_fix_none)
            return false
        }
        centreOn(fix.point, Centre.DISCO)
        return true
    }

    /** Latitude and longitude typed or pasted (see [Coordinates]); the last ones are offered again. */
    private fun askCoordinates() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            id = R.id.setting_coordinates_input
            setText(prefs.getString("coordinates", ""))
            hint = getString(R.string.coordinates_hint)
            isSingleLine = true
        }
        val help = TextView(this, null, 0, R.style.SettingNote).apply {
            setPadding(0, pad / 2, 0, 0)
            setText(R.string.coordinates_help)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
            addView(help)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.coordinates_title)
            .setView(box)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            // stays open on a typo, saying what it expects
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text.toString()
                val point = Coordinates.parse(text)
                if (point == null) {
                    help.setText(R.string.coordinates_invalid)
                    help.setTextColor(getColor(R.color.accent))
                } else {
                    prefs.edit().putString("coordinates", text.trim()).apply()
                    dialog.dismiss()
                    centreOn(point, Centre.COORDINATES)
                }
            }
        }
        dialog.show()
    }

    /** This phone's own position: at the flying field, before the Disco is on. */
    private fun centreOnPhone() {
        val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (perms.none { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(perms, LOCATION_PERMISSION)
            return
        }
        locatePhone()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != LOCATION_PERMISSION) return
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) locatePhone()
        else caption.setText(R.string.download_no_permission)
    }

    /**
     * Asks every enabled source at once (fused, network, GPS) and takes the
     * first answer: a GPS alone can need minutes for a first fix after the
     * permission is granted, where the network answers in a second. What
     * happens shows under the preview, where the eyes are.
     */
    @Suppress("MissingPermission", "DEPRECATION")
    private fun locatePhone() {
        val lm = getSystemService(LocationManager::class.java)
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }
        val providers = candidates.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            caption.setText(R.string.download_no_location)
            return
        }
        // a recent position is enough for a 1.5 km area
        val recent = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < PHONE_FIX_MAX_AGE_MS }
            .maxByOrNull { it.time }
        if (recent != null) {
            centreOn(GeoPoint(recent.latitude, recent.longitude), Centre.PHONE)
            return
        }
        caption.setText(R.string.download_locating)
        if (layerSource != null) preview.message = getString(R.string.download_locating)
        val main = Handler(Looper.getMainLooper())
        val cancels = mutableListOf<() -> Unit>()
        var waiting = providers.size
        var answered = false
        fun finish(loc: Location?) {
            answered = true
            main.removeCallbacksAndMessages(null)
            cancels.forEach { runCatching { it() } }
            if (isDestroyed) return
            preview.message = previewMessage()
            if (loc == null) caption.setText(R.string.download_no_location)
            else centreOn(GeoPoint(loc.latitude, loc.longitude), Centre.PHONE)
        }
        fun answer(loc: Location?) {
            if (answered) return
            if (loc != null) finish(loc)
            else if (--waiting == 0) finish(null)
        }
        for (provider in providers) {
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    val cancel = android.os.CancellationSignal()
                    cancels += { cancel.cancel() }
                    lm.getCurrentLocation(provider, cancel, mainExecutor) { answer(it) }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) = answer(location)
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                        override fun onProviderEnabled(p: String) {}
                        override fun onProviderDisabled(p: String) {}
                    }
                    cancels += { lm.removeUpdates(listener) }
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            } catch (e: Exception) {
                // e.g. GPS refused with the approximate permission only (before Android 12)
                answer(null)
            }
        }
        if (!answered) main.postDelayed({ if (!answered) finish(null) }, PHONE_FIX_TIMEOUT_MS)
    }

    // --- the download ---

    /** The circle's tiles, for flying where there is no network. */
    private fun download() {
        val source = settings.tileSource()
        val point = preview.center
        when {
            source == null -> status.setText(R.string.download_no_source)
            source.mapFile != null -> status.setText(R.string.download_offline)
            !source.prefetch -> status.setText(R.string.download_not_allowed)
            point == null -> status.setText(R.string.map_no_center)
            else -> {
                val where = Coordinates.format(point)
                status.text = getString(R.string.download_progress, 0, 0, where)
                // the progress is under the button: bring it into view
                findViewById<android.widget.ScrollView>(R.id.map_scroll).let { sv -> sv.post { sv.fullScroll(View.FOCUS_DOWN) } }
                val job = (layer ?: return).prefetch(point, PREFETCH_RADIUS_M) { done, total, ended ->
                    if (isDestroyed) return@prefetch
                    status.text = when {
                        !ended -> getString(R.string.download_progress, done, total, where)
                        done < total -> getString(R.string.download_stopped, done, total, where)
                        else -> getString(R.string.download_done, total, where)
                    }
                    if (ended) {
                        prefetch = null
                        showDownloadButton()
                        preview.invalidate()
                    }
                }
                prefetch = job
                showDownloadButton()
            }
        }
    }

    /** Stops after the tile being fetched; the ones already there stay. */
    private fun stopDownload() {
        prefetch?.cancel()
        findViewById<Button>(R.id.map_download).isEnabled = false
    }

    /** "Download this area", or "Stop the download" while one runs. */
    private fun showDownloadButton() {
        val source = settings.tileSource()
        findViewById<Button>(R.id.map_download).apply {
            setText(if (downloading) R.string.map_download_stop else R.string.map_download)
            isEnabled = downloading || (source != null && source.mapFile == null && source.prefetch)
        }
    }
}
