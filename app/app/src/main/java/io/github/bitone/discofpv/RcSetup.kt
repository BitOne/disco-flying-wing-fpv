/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Setting up a hobby radio on the Disco's receiver (T8.8, RcActivity): the
 * calibration steps in order, what the screen says. Plain values
 * (RcReceiver's action and channel type names), unit-tested.
 */
object RcSetup {

    /** One calibration step: [action] null for the neutral position. */
    data class Step(val action: String?, val type: String?, val instruction: String, val required: Boolean)

    val STEPS = listOf(
        Step(null, null, "Neutral: sticks centred, throttle at rest (down), switches off", true),
        Step("ROLL", "SIGNED_AXIS", "Roll: move the roll stick fully left and right", true),
        Step("PITCH", "SIGNED_AXIS", "Pitch: move the pitch stick fully forward and back", true),
        // a plane radio's throttle rests at one end
        Step("GAZ", "UNSIGNED_AXIS", "Throttle: move the throttle fully up and back down", true),
        Step("TAKE_CONTROL", "BISTABLE_BUTTON",
            "Take control: flip the switch that gives the radio control, on and off", true),
        Step("RETURN_HOME", "BISTABLE_BUTTON", "Return home (optional): flip its switch on and off", false),
        Step("TAKEOFF_LAND", "MONOSTABLE_BUTTON", "Take-off / land (optional): press its button", false),
    )

    /** The first step not done, -1 when all are (optional ones included). */
    fun current(neutral: Boolean, calibrated: Set<String>): Int =
        STEPS.indexOfFirst { if (it.action == null) !neutral else it.action !in calibrated }

    /** Whether the radio can be enabled: every required step done. */
    fun ready(neutral: Boolean, calibrated: Set<String>, required: Set<String>): Boolean =
        neutral && calibrated.containsAll(required)

    fun status(connected: Boolean, protocol: String, enabled: Boolean): String = when {
        !connected -> "No receiver on the Disco"
        enabled -> "Receiver $protocol: the radio can fly the Disco (with its take-control switch on)"
        else -> "Receiver $protocol connected, radio off"
    }

    /** "ROLL  -40 [-----|     ]": a channel as text, value -100..100. */
    fun bar(id: Int, action: String?, value: Int, width: Int = 10): String {
        val v = value.coerceIn(-100, 100)
        val n = (kotlin.math.abs(v) * width / 100.0).roundToInt()
        val left = if (v < 0) " ".repeat(width - n) + "-".repeat(n) else " ".repeat(width)
        val right = if (v > 0) "+".repeat(n) + " ".repeat(width - n) else " ".repeat(width)
        val name = (action ?: "ch $id").replace('_', ' ').lowercase(Locale.US)
        return String.format(Locale.US, "%2d %-14s %4d [%s|%s]", id, name, value, left, right)
    }
}
