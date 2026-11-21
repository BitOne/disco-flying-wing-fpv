/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.parrot.drone.groundsdk.GroundSdk
import com.parrot.drone.groundsdk.ManagedGroundSdk
import com.parrot.drone.groundsdk.Ref
import com.parrot.drone.groundsdk.device.Drone
import com.parrot.drone.groundsdk.device.instrument.FlyingIndicators
import com.parrot.drone.groundsdk.device.instrument.Gps
import com.parrot.drone.groundsdk.device.peripheral.Geofence
import com.parrot.drone.groundsdk.device.pilotingitf.Activable
import com.parrot.drone.groundsdk.device.pilotingitf.FlightPlanPilotingItf
import com.parrot.drone.groundsdk.device.pilotingitf.ReturnHomePilotingItf
import com.parrot.drone.groundsdk.mavlink.MavlinkFiles
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Flight plans (T9.8), free: waypoints tapped on the map at a height
 * above the take-off point, what to do at the end (return home, land,
 * circle there), the route's length and time, checked against the
 * Disco's flying zone; uploaded as a MAVLink file and started, paused
 * (the Disco circles where it is) and resumed (GroundSdk's
 * FlightPlanPilotingItf); a new start plays it from the beginning. The plan is kept
 * on the phone and can be edited without a Disco.
 */
class FlightPlanActivity : Activity() {

    private var groundSdk: GroundSdk? = null
    private val refs = mutableListOf<Ref<*>>()
    private var layer: TileLayer? = null

    private var plan = FlightPlan()
    private var itf: FlightPlanPilotingItf? = null
    private var geofence: Geofence? = null
    private var landed = true
    private var discoPosition: GeoPoint? = null
    private var homePosition: GeoPoint? = null
    /** Start once the upload is done. */
    private var startAfterUpload = false

    private lateinit var map: PlanMapView
    private lateinit var summary: TextView
    private lateinit var warnings: TextView
    private lateinit var points: LinearLayout
    private lateinit var endRow: Button
    private lateinit var status: TextView
    private lateinit var start: Button
    private lateinit var pause: Button

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        plan = FlightPlan.load(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(24), px(24), px(24))
        }
        fun place(v: View, top: Int, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT) =
            column.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)
                .apply { topMargin = px(top) })
        fun button(label: String, style: Int = R.style.Button_Secondary, onClick: () -> Unit) =
            Button(this, null, 0, style).apply { text = label; setOnClickListener { onClick() } }

        place(TextView(this, null, 0, R.style.Title).apply { text = getString(R.string.plan_title) }, 0)
        place(TextView(this, null, 0, R.style.SettingNote).apply { text = getString(R.string.plan_hint) }, 8)
        map = PlanMapView(this).apply {
            noPosition = getString(R.string.plan_no_position)
            message = getString(R.string.plan_no_background)
            onTap = { p -> addWaypoint(p) }
        }
        place(map, 12, px(340))
        val zoomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, action) in listOf(
            getString(R.string.minus) to { map.zoom = map.zoom - 1 },
            getString(R.string.plus) to { map.zoom = map.zoom + 1 },
            getString(R.string.plan_center_disco) to { (discoPosition ?: homePosition)?.let { map.center = it } },
            getString(R.string.plan_undo) to { setPlan(plan.copy(waypoints = plan.waypoints.dropLast(1))) },
        )) {
            zoomRow.addView(button(label) { action() },
                LinearLayout.LayoutParams(0, px(44), 1f).apply { marginEnd = px(4) })
        }
        place(zoomRow, 8)
        summary = TextView(this, null, 0, R.style.Card).also { place(it, 12) }
        warnings = TextView(this, null, 0, R.style.SettingNote).apply {
            setTextColor(getColor(R.color.accent))
        }.also { place(it, 4) }
        endRow = Button(this, null, 0, R.style.SettingRow).apply {
            setOnClickListener {
                setPlan(plan.copy(end = FlightPlan.End.values()[(plan.end.ordinal + 1) % FlightPlan.End.values().size]))
            }
        }.also { place(it, 8) }
        points = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { place(it, 4) }
        place(button(getString(R.string.plan_clear)) { confirmClear() }, 8, px(44))
        place(TextView(this, null, 0, R.style.Section).apply { text = getString(R.string.plan_section_fly) }, 20)
        status = TextView(this, null, 0, R.style.Card).also { place(it, 4) }
        start = button(getString(R.string.plan_start), R.style.Button_Primary) { confirmStart() }.also { place(it, 10, px(52)) }
        pause = button(getString(R.string.plan_pause)) { itf?.let { if (it.state == Activable.State.ACTIVE) it.deactivate() else it.activate(false) } }
            .also { place(it, 8, px(48)) }
        place(TextView(this, null, 0, R.style.SettingNote).apply { text = getString(R.string.plan_pause_note) }, 6)
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(column) })

        AppSettings.load(this).tileSource()?.let { src ->
            layer = TileLayer(this, src) { map.postInvalidate() }
            map.tiles = layer
        }
        // the plan where it is; else the Disco once it reports (below); else
        // where it was last seen, without a Disco
        map.center = plan.waypoints.firstOrNull()?.point
        if (intent.getStringExtra(DroneScreenActivity.EXTRA_DRONE_UID) == null) {
            map.center = map.center ?: LastFixStore(this).load()?.point
        }
        intent.getStringExtra(DroneScreenActivity.EXTRA_DRONE_UID)?.let { uid ->
            val sdk = ManagedGroundSdk.obtainSession(this)
            groundSdk = sdk
            sdk.getDrone(uid) { finish() }?.let { watch(it) }
        }
        setPlan(plan)
    }

    private fun watch(drone: Drone) {
        refs += drone.getPilotingItf(FlightPlanPilotingItf::class.java) { itf = it; onItf(it) }
        refs += drone.getPeripheral(Geofence::class.java) { geofence = it; render() }
        refs += drone.getInstrument(FlyingIndicators::class.java) {
            landed = it == null || it.state == FlyingIndicators.State.LANDED
            render()
        }
        refs += drone.getInstrument(Gps::class.java) { g ->
            val l = g?.lastKnownLocation()
            discoPosition = if (g?.isFixed == true && l != null) GeoPoint(l.latitude, l.longitude) else null
            map.disco = discoPosition
            if (map.center == null) map.center = discoPosition
        }
        refs += drone.getPilotingItf(ReturnHomePilotingItf::class.java) { r ->
            homePosition = r?.homeLocation?.let { GeoPoint(it.latitude, it.longitude) }
            map.home = homePosition
            if (map.center == null) map.center = homePosition
            render()
        }
    }

    override fun onDestroy() {
        refs.forEach { it.close() }
        layer?.close()
        super.onDestroy()
    }

    private fun setPlan(p: FlightPlan) {
        plan = p
        p.save(this)
        map.plan = p
        render()
    }

    private fun addWaypoint(p: GeoPoint) {
        val alt = plan.waypoints.lastOrNull()?.altitude ?: FlightPlan.DEFAULT_ALTITUDE
        setPlan(plan.copy(waypoints = plan.waypoints + FlightPlan.Waypoint(p, alt)))
    }

    private fun onItf(f: FlightPlanPilotingItf?) {
        if (f != null && startAfterUpload && f.latestUploadState == FlightPlanPilotingItf.UploadState.UPLOADED
            && f.state == Activable.State.IDLE) {
            startAfterUpload = false
            f.activate(true)
        }
        if (f?.latestUploadState == FlightPlanPilotingItf.UploadState.FAILED) startAfterUpload = false
        render()
    }

    private fun render() {
        val home = homePosition ?: discoPosition
        val length = plan.length(home)
        summary.text = if (plan.waypoints.isEmpty()) getString(R.string.plan_empty)
        else getString(R.string.plan_summary, plan.waypoints.size, HudFormat.distance(length),
            FlightTrack.duration((length / FlightEstimator.DEFAULT_CRUISE).toLong()))
        val g = geofence
        val problems = plan.problems(home, g?.maxAltitude()?.value, g?.maxDistance()?.value,
            g?.mode()?.value == Geofence.Mode.CYLINDER)
        warnings.text = problems.joinToString("\n")
        warnings.visibility = if (problems.isEmpty()) View.GONE else View.VISIBLE
        endRow.text = getString(R.string.plan_end, getString(when (plan.end) {
            FlightPlan.End.RETURN_HOME -> R.string.plan_end_home
            FlightPlan.End.LAND -> R.string.plan_end_land
            FlightPlan.End.CIRCLE -> R.string.plan_end_circle
        }))
        points.removeAllViews()
        plan.waypoints.forEachIndexed { i, w ->
            points.addView(Button(this, null, 0, R.style.SettingRow).apply {
                text = String.format(Locale.US, "%d. %.5f %.5f, %d m", i + 1, w.point.latitude, w.point.longitude,
                    w.altitude.roundToInt())
                setOnClickListener { editWaypoint(i) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(4) })
        }

        val f = itf
        val done = f?.let { plan.waypointDone(it.latestMissionItemExecuted) } ?: 0
        map.done = done
        status.text = when {
            groundSdk == null || f == null -> getString(R.string.plan_no_disco)
            f.state == Activable.State.ACTIVE -> getString(R.string.plan_flying, done, plan.waypoints.size)
            f.isPaused -> getString(R.string.plan_paused, done, plan.waypoints.size)
            plan.finished(f.latestMissionItemExecuted) -> getString(when (plan.end) {
                FlightPlan.End.RETURN_HOME -> R.string.plan_done_home
                FlightPlan.End.LAND -> R.string.plan_done_land
                FlightPlan.End.CIRCLE -> R.string.plan_done_circle
            }, plan.waypoints.size)
            f.latestUploadState == FlightPlanPilotingItf.UploadState.UPLOADING -> getString(R.string.plan_uploading)
            f.latestUploadState == FlightPlanPilotingItf.UploadState.FAILED -> getString(R.string.plan_upload_failed)
            f.latestActivationError == FlightPlanPilotingItf.ActivationError.WAYPOINT_BEYOND_GEOFENCE ->
                getString(R.string.plan_beyond_geofence)
            f.latestActivationError == FlightPlanPilotingItf.ActivationError.INCORRECT_FLIGHT_PLAN_FILE ->
                getString(R.string.plan_bad_file)
            else -> {
                val reasons = f.unavailabilityReasons - FlightPlanPilotingItf.UnavailabilityReason.MISSING_FLIGHT_PLAN_FILE
                if (reasons.isEmpty()) getString(R.string.plan_ready)
                else getString(R.string.plan_unavailable, reasons.joinToString(", ") {
                    it.name.replace('_', ' ').lowercase(Locale.US)
                })
            }
        }
        val active = f?.state == Activable.State.ACTIVE
        start.isEnabled = f != null && plan.waypoints.isNotEmpty() && !active && !startAfterUpload
        pause.isEnabled = f != null && (active || f.isPaused)
        pause.text = getString(if (f?.isPaused == true && !active) R.string.plan_resume else R.string.plan_pause)
    }

    private fun editWaypoint(i: Int) {
        val w = plan.waypoints.getOrNull(i) ?: return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(w.altitude.roundToInt().toString())
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.plan_waypoint_title, i + 1))
            .setMessage(R.string.plan_altitude)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                input.text.toString().toDoubleOrNull()?.let { a ->
                    setPlan(plan.copy(waypoints = plan.waypoints.toMutableList().also {
                        it[i] = w.copy(altitude = a.coerceIn(0.0, 500.0))
                    }))
                }
            }
            .setNeutralButton(R.string.plan_remove) { _, _ ->
                setPlan(plan.copy(waypoints = plan.waypoints.filterIndexed { j, _ -> j != i }))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmClear() {
        if (plan.waypoints.isEmpty()) return
        AlertDialog.Builder(this)
            .setMessage(R.string.plan_clear_confirm)
            .setPositiveButton(R.string.plan_clear_yes) { _, _ -> setPlan(plan.copy(waypoints = emptyList())) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmStart() {
        val f = itf ?: return
        val message = buildString {
            append(getString(if (landed) R.string.plan_start_landed else R.string.plan_start_flying))
            val problems = warnings.text
            if (problems.isNotEmpty()) append("\n\n").append(problems)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.plan_start)
            .setMessage(message)
            .setPositiveButton(R.string.plan_start_yes) { _, _ ->
                val file = File(cacheDir, "flightPlan.mavlink")
                MavlinkFiles.generate(file, plan.commands())
                startAfterUpload = true
                f.uploadFlightPlan(file)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
