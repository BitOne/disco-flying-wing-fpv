/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

/**
 * A position typed or pasted by the pilot, in the forms maps apps give:
 * - decimal degrees: `45.1885, 5.7245`, `45.1885 5.7245`, `45,1885; 5,7245`
 * - with hemispheres: `45.1885N 5.7245E`, `N 45.1885 W 1.5`
 * - degrees, minutes, seconds: `45°11'18.6"N 5°43'28.2"E`, or degrees and
 *   decimal minutes: `45°11.31'N 5°43.47'E`
 * - a `geo:45.1885,5.7245` URI or a map link with `@45.1885,5.7245` or
 *   `?q=45.1885,5.7245` / `mlat=...&mlon=...` in it.
 * Latitude first, as everywhere. Null when it isn't a position on Earth.
 */
object Coordinates {

    fun parse(input: String): GeoPoint? {
        val s = input.trim()
        if (s.isEmpty()) return null
        fromLink(s)?.let { return it }
        return if (s.contains('°') || s.contains('\'') || s.contains('"') || Regex("[NSEWnsew]").containsMatchIn(s)) {
            hemispheres(s)
        } else {
            decimal(s)
        }
    }

    private const val NUM = """[-+]?\d+(?:\.\d+)?"""

    private fun valid(lat: Double, lon: Double) =
        if (lat in -90.0..90.0 && lon in -180.0..180.0) GeoPoint(lat, lon) else null

    /** geo: URIs, and the positions in map links (OpenStreetMap, Google Maps). */
    private fun fromLink(s: String): GeoPoint? {
        Regex("""mlat=($NUM).*?mlon=($NUM)""").find(s)?.let { m ->
            return valid(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
        }
        Regex("""#map=\d+/($NUM)/($NUM)""").find(s)?.let { m ->
            return valid(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
        }
        val pair = Regex("""(?:^geo:|@|[?&](?:q|ll|query)=)($NUM),\s*($NUM)""").find(s) ?: return null
        return valid(pair.groupValues[1].toDouble(), pair.groupValues[2].toDouble())
    }

    /** Two signed decimal numbers; a decimal comma is allowed when the separator isn't a bare comma. */
    private fun decimal(s: String): GeoPoint? {
        val parts = when {
            s.contains(';') -> s.split(';')
            Regex("""^\s*$NUM\s*,\s*$NUM\s*$""").matches(s) -> s.split(',')
            else -> s.split(Regex("""\s+"""))
        }.map { it.trim().replace(',', '.') }.filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        return valid(lat, lon)
    }

    private class Half(val numbers: MutableList<Double> = mutableListOf(), var hemi: String = "")

    /**
     * Hemisphere letters or degree marks: the text is cut into its numbers,
     * letters and separators, then into two halves of 1 to 3 numbers
     * (degrees, minutes, seconds). A letter closes its half when the
     * letters come after the numbers (`45.1N`), opens it when they come
     * first (`N 45.1`); without letters, a degree mark or a separator
     * starts the next half.
     */
    private fun hemispheres(s: String): GeoPoint? {
        val token = Regex("""([-+]?\d+(?:\.\d+)?)\s*([°º]?)|([NSEWnsew])|([,;])|['′"″\s]+""")
        val tokens = token.findAll(s).toList()
        // anything else (a word, a stray symbol) and it isn't a position
        if (tokens.sumOf { it.value.length } != s.length) return null
        val letters = tokens.any { it.groups[3] != null }
        val prefix = tokens.firstOrNull { it.groups[1] != null || it.groups[3] != null }?.groups?.get(3) != null
        val halves = mutableListOf<Half>()
        var current: Half? = null
        for (t in tokens) {
            val number = t.groups[1]?.value?.toDouble()
            val letter = t.groups[3]?.value?.uppercase()
            when {
                letter != null && prefix -> current = Half(hemi = letter).also { halves += it }
                letter != null -> {
                    val h = current ?: return null
                    h.hemi = letter
                    current = null
                }
                number != null -> {
                    val degrees = t.groups[2]?.value?.isNotEmpty() == true
                    if (current == null || (!letters && degrees && current.numbers.isNotEmpty())) {
                        current = Half().also { halves += it }
                    }
                    current.numbers += number
                }
                t.groups[4] != null && !prefix -> current = null
            }
        }
        if (halves.size != 2) return null
        val values = halves.map { h ->
            if (h.numbers.size !in 1..3) return null
            if (h.numbers.drop(1).any { it < 0 || it >= 60 }) return null
            val d = h.numbers[0]
            var v = Math.abs(d) + h.numbers.getOrElse(1) { 0.0 } / 60 + h.numbers.getOrElse(2) { 0.0 } / 3600
            if (d < 0) v = -v
            if (h.hemi == "S" || h.hemi == "W") v = -v
            h.hemi to v
        }
        // the hemisphere letters say which is which; without them, latitude first
        val lat = values.firstOrNull { it.first == "N" || it.first == "S" }?.second
            ?: values.firstOrNull { it.first.isEmpty() }?.second ?: return null
        val lon = values.firstOrNull { it.first == "E" || it.first == "W" }?.second
            ?: values.lastOrNull { it.first.isEmpty() }?.second ?: return null
        if (values.count { it.first == "N" || it.first == "S" } > 1) return null
        if (values.count { it.first == "E" || it.first == "W" } > 1) return null
        return valid(lat, lon)
    }

    /** As the settings show it: `45.18850, 5.72450`. */
    fun format(p: GeoPoint) = String.format(java.util.Locale.ROOT, "%.5f, %.5f", p.latitude, p.longitude)
}
