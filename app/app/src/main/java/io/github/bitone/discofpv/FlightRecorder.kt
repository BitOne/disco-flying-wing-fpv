/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import android.content.Context
import android.util.Log
import com.parrot.drone.groundsdk.GroundSdk
import com.parrot.drone.groundsdk.device.DeviceState
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The recorded flights: one CSV file each, in the app's own storage. */
class FlightLogStore(val dir: File) {

    constructor(context: Context) : this(File(context.filesDir, "flights"))

    /** Newest first. */
    fun list(): List<File> =
        dir.listFiles { f -> f.name.endsWith(".csv") }.orEmpty().sortedByDescending { it.name }

    fun read(file: File): List<FlightSample> =
        runCatching { file.readLines().mapNotNull { FlightSample.parse(it) } }.getOrDefault(emptyList())

    fun newFile(startMs: Long): File {
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(startMs))
        return File(dir, "flight-$stamp.csv")
    }
}

/**
 * When to record (pure, unit-tested): from the take-off (any flying state
 * but LANDED) until the Disco has been landed for [LANDED_END_MS]; one
 * sample of the latest state every [SAMPLE_MS] ([tick], from a timer:
 * telemetry comes as separate updates, sampling on them could miss the
 * last one), while connected. A link loss in flight keeps the flight
 * open: it goes on when the Disco is back.
 */
class RecorderCore(private val sink: Sink) {

    interface Sink {
        fun start(timeMs: Long)
        fun sample(s: FlightSample)
        fun end()
    }

    var recording = false
        private set
    private var latest: HudModel? = null
    private var landedSinceMs: Long? = null

    /** A new state of the Disco: starts or ends the flight. */
    fun onModel(m: HudModel, nowMs: Long) {
        latest = m
        val state = m.flyingState
        val airborne = m.connected && state != null && state != "LANDED"
        if (!recording) {
            if (!airborne) return
            recording = true
            landedSinceMs = null
            sink.start(nowMs)
            sink.sample(FlightSample.of(m, nowMs))
            return
        }
        if (!m.connected) return
        if (state == "LANDED") {
            val since = landedSinceMs ?: nowMs.also { landedSinceMs = it }
            if (nowMs - since >= LANDED_END_MS) finish(m, nowMs)
        } else {
            landedSinceMs = null
        }
    }

    /** Every [SAMPLE_MS]: the latest state, while recording and connected. */
    fun tick(nowMs: Long) {
        val m = latest ?: return
        if (!recording || !m.connected) return
        // the landing's end also comes from here when no update arrives
        val since = landedSinceMs
        if (m.flyingState == "LANDED" && since != null && nowMs - since >= LANDED_END_MS) {
            finish(m, nowMs)
            return
        }
        sink.sample(FlightSample.of(m, nowMs))
    }

    private fun finish(m: HudModel, nowMs: Long) {
        sink.sample(FlightSample.of(m, nowMs))
        recording = false
        landedSinceMs = null
        sink.end()
    }

    /** The app closing: what was recorded stays. */
    fun close() {
        if (recording) {
            recording = false
            sink.end()
        }
    }

    companion object {
        const val SAMPLE_MS = 500L
        const val LANDED_END_MS = 3000L
    }
}

/**
 * Records every flight of the connected Disco on the phone (T9.4), whatever
 * screen is in front: its own GroundSdk session and a [HudBinder] on the
 * connected drone, the samples appended to a CSV file and flushed each
 * time (a crash or a battery pulled out keeps what was flown). The main
 * screen owns it, like [ConnectionGuard].
 */
class FlightRecorder(context: Context) {

    private val store = FlightLogStore(context)
    private val appContext = context.applicationContext
    private val sdk = GroundSdk.newSession(appContext, null)
    private var droneSdk: GroundSdk? = null
    private var droneUid: String? = null
    private var writer: FileWriter? = null

    private val core = RecorderCore(object : RecorderCore.Sink {
        override fun start(timeMs: Long) {
            val file = store.newFile(timeMs)
            writer = runCatching { FileWriter(file, true).apply { write(FlightSample.CSV_HEADER + "\n"); flush() } }
                .onFailure { Log.w(TAG, "flight log: ${it.message}") }.getOrNull()
            Log.i(TAG, "flight log: recording ${file.name}")
        }

        override fun sample(s: FlightSample) {
            writer?.let { w -> runCatching { w.write(s.toCsv() + "\n"); w.flush() } }
        }

        override fun end() {
            runCatching { writer?.close() }
            writer = null
            Log.i(TAG, "flight log: done")
        }
    })

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            core.tick(System.currentTimeMillis())
            handler.postDelayed(this, RecorderCore.SAMPLE_MS)
        }
    }

    init {
        handler.postDelayed(ticker, RecorderCore.SAMPLE_MS)
        sdk.resume()
        sdk.getDroneList({ true }) { list ->
            val uid = list.orEmpty().firstOrNull {
                it.state.connectionState == DeviceState.ConnectionState.CONNECTED
            }?.uid
            // a link loss in flight keeps the binder: the drone stays known
            if (uid != null && uid != droneUid) follow(uid)
        }
    }

    private fun follow(uid: String) {
        droneSdk?.close()
        droneUid = uid
        val session = GroundSdk.newSession(appContext, null).also { it.resume() }
        droneSdk = session
        val drone = session.getDrone(uid) ?: return
        HudBinder(session, drone) { core.onModel(it, System.currentTimeMillis()) }.bind()
    }

    fun close() {
        handler.removeCallbacks(ticker)
        core.close()
        droneSdk?.close()
        sdk.close()
    }

    private companion object { const val TAG = "DiscoFpv" }
}
