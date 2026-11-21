/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import java.util.Locale

/**
 * The Wi-Fi countries the Disco knows (firmware 1.7.1,
 * `usr/share/netmon/country_regul.yaml`, NOTES.md section 41). The country
 * sets the regulatory domain of the Disco's Broadcom radio: its channels
 * and its transmit power limits. All allow 2.4 GHz channels 1 to 11; they
 * differ on 5 GHz.
 */
object WifiCountries {

    data class Group(val codes: List<String>, val outdoor5: String, val indoor5: String)

    val GROUPS = listOf(
        Group(("AD,AT,BE,BG,CH,CY,CZ,DE,DK,DZ,EE,ES,FI,FR,GB,GF,GP,GR,HR,HU,IE,IT,JP,LB,LT,LU,LV,MT," +
            "NL,NO,PL,PT,RO,SE,SI,SK,TR,UA").split(','), "", "36-48"),
        Group("AE,AR,AU,EC,ID,NZ,QA,TW".split(','), "149-161", ""),
        Group("BR,MX,US".split(','), "36-48, 149-161", ""),
        Group(listOf("PR"), "149-161", ""),
        Group("CL,CN,KZ,RU,VN".split(','), "149-161", "36-48"),
        Group(listOf("KR"), "149-161", "36-44"),
        Group(listOf("CA"), "149-161", "36-48"),
        Group(listOf("IL"), "", ""),
        Group(listOf("HK"), "149-161", "36-48"),
        Group("KW,SG".split(','), "36-48", ""),
        Group("IN,MY,PH,SA,TH,VE".split(','), "36-48, 149-161", ""),
        Group(listOf("ZA"), "", ""),
        Group("CO,CR".split(','), "", "36-48"),
    )

    /** Factory countries whose Discos refuse a manual change (autolock). */
    val LOCKED = setOf("CA", "IL", "US")

    val CODES: List<String> = GROUPS.flatMap { it.codes }.distinct()

    fun group(code: String): Group? = GROUPS.firstOrNull { code in it.codes }

    fun name(code: String, locale: Locale = Locale.getDefault()): String =
        Locale("", code).getDisplayCountry(locale).ifEmpty { code }

    /** "France (FR): 5 GHz indoor only 36-48". */
    fun describe(code: String, locale: Locale = Locale.getDefault()): String {
        val g = group(code)
        val band = when {
            g == null -> ""
            g.outdoor5.isNotEmpty() -> "5 GHz outdoor ${g.outdoor5}"
            g.indoor5.isNotEmpty() -> "5 GHz indoor only ${g.indoor5}"
            else -> "2.4 GHz only"
        }
        return if (band.isEmpty()) "${name(code, locale)} ($code)" else "${name(code, locale)} ($code): $band"
    }

    /** Countries sorted by name, as offered in the settings. */
    fun sorted(locale: Locale = Locale.getDefault()): List<String> =
        CODES.sortedBy { name(it, locale).lowercase(locale) }
}
