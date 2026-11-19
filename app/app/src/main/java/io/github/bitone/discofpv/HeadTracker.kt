/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 * Portions copyright (c) 2019-Present PARROT DRONES SAS. All rights reserved.
 */
package io.github.bitone.discofpv

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.parrot.drone.groundsdk.Ref
import com.parrot.drone.groundsdk.device.Drone
import com.parrot.drone.groundsdk.device.peripheral.Gimbal
import kotlin.math.abs

/**
 * Head tracking: the phone's orientation in the headset (game rotation
 * vector: gyroscope and accelerometer, no compass, so no magnetic jumps)
 * points the Disco's camera, which is digital: a window moving in the
 * fisheye picture. The head's pose when tracking starts, or at [recenter],
 * is the centred view (the camera along the nose). Sends at most
 * [RATE_HZ] orientations, only when they change by [STEP_DEG].
 */
class HeadTracker(context: Context, private val drone: Drone) : SensorEventListener {

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val rotation = FloatArray(9)
    private var reference: Pair<Double, Double>? = null
    private var gimbalRef: Ref<Gimbal>? = null
    private var gimbal: Gimbal? = null
    private var lastSent: Pair<Double, Double>? = null
    private var lastSentMs = 0L

    /** False on phones without the sensor. */
    val available: Boolean get() = sensor != null

    fun start() {
        val s = sensor ?: return
        gimbalRef = drone.getPeripheral(Gimbal::class.java) { gimbal = it }
        reference = null
        sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensors.unregisterListener(this)
        gimbalRef?.close()
        gimbalRef = null
        gimbal = null
    }

    /** The current head pose becomes the centred view; the camera centres. */
    fun recenter() {
        reference = null
        send(Pair(0.0, 0.0), force = true)
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        val look = HeadMath.look(rotation)
        val ref = reference ?: look.also { reference = it }
        send(HeadMath.cameraFor(look, ref, limit()))
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    /** The camera's range, from the drone (both axes the same on the Disco). */
    private fun limit(): Double {
        val g = gimbal ?: return DEFAULT_LIMIT
        if (Gimbal.Axis.PITCH !in g.supportedAxes) return DEFAULT_LIMIT
        return g.getAttitudeBounds(Gimbal.Axis.PITCH).upper.coerceAtLeast(1.0)
    }

    private fun send(camera: Pair<Double, Double>, force: Boolean = false) {
        val g = gimbal ?: return
        val now = SystemClock.elapsedRealtime()
        val last = lastSent
        if (!force) {
            if (now - lastSentMs < 1000 / RATE_HZ) return
            if (last != null && abs(last.first - camera.first) < STEP_DEG &&
                abs(last.second - camera.second) < STEP_DEG
            ) {
                return
            }
        }
        val yaw = if (Gimbal.Axis.YAW in g.supportedAxes) camera.first else null
        g.control(Gimbal.ControlMode.POSITION, yaw, camera.second, null)
        lastSent = camera
        lastSentMs = now
    }

    private companion object {
        const val RATE_HZ = 10
        const val STEP_DEG = 1.0
        const val DEFAULT_LIMIT = 80.0
    }
}
