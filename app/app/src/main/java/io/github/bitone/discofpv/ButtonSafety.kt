/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import java.util.Locale

/**
 * Which controller buttons the app may take (T8.3): a grabbed button stops
 * doing what the controller's own mapping makes it do, so the app never
 * takes one the mapping uses for a Disco action (return home, take-off or
 * land, record...; alone or in a combination), only free ones and those
 * sending app events (APP_ACTION_*). Plain values (GroundSdk's
 * ButtonsMappableAction and ButtonEvent names), unit-tested.
 */
object ButtonSafety {

    /** One mapping entry: an action and the button events that trigger it together. */
    data class Entry(val action: String, val events: Set<String>)

    /** The Disco actions the mapping puts on [event], none if the app may take it. */
    fun droneActionsOn(mapping: Collection<Entry>, event: String): List<String> =
        mapping.filter { event in it.events && !it.action.startsWith("APP_ACTION") && it.action != "NO_ACTION" }
            .map { it.action }
            .distinct()
            .sorted()

    /** "B: RETURN HOME, NOT TAKEN": the HUD warning for a refused button. */
    fun warning(buttonLabel: String, actions: List<String>): String =
        "$buttonLabel: ${actions.joinToString(", ") { it.replace('_', ' ') }.uppercase(Locale.US)}, NOT TAKEN"
}
