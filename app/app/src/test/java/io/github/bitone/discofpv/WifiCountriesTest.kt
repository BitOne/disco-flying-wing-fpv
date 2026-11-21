/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class WifiCountriesTest {

    @Test
    fun everyCountryOnce() {
        val all = WifiCountries.GROUPS.flatMap { it.codes }
        assertEquals(all.size, all.toSet().size)
        assertTrue(all.all { it.matches(Regex("[A-Z]{2}")) })
        assertEquals(WifiCountries.CODES.size, WifiCountries.sorted(Locale.ENGLISH).size)
    }

    @Test
    fun describesTheBands() {
        assertEquals("France (FR): 5 GHz indoor only 36-48", WifiCountries.describe("FR", Locale.ENGLISH))
        assertEquals("United States (US): 5 GHz outdoor 36-48, 149-161", WifiCountries.describe("US", Locale.ENGLISH))
        assertEquals("Australia (AU): 5 GHz outdoor 149-161", WifiCountries.describe("AU", Locale.ENGLISH))
        assertEquals("Israel (IL): 2.4 GHz only", WifiCountries.describe("IL", Locale.ENGLISH))
        // unknown to the firmware: name and code only
        assertEquals("Belarus (BY)", WifiCountries.describe("BY", Locale.ENGLISH))
    }

    @Test
    fun sortedByName() {
        val names = WifiCountries.sorted(Locale.ENGLISH).map { WifiCountries.name(it, Locale.ENGLISH) }
        assertEquals(names.sortedBy { it.lowercase(Locale.ENGLISH) }, names)
        assertTrue(WifiCountries.LOCKED.all { it in WifiCountries.CODES })
    }
}
