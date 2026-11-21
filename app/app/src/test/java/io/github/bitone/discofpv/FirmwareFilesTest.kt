/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirmwareFilesTest {

    /** The first bytes of disco_update.plf 1.7.1. */
    private val header171 = byteArrayOf(
        0x50, 0x4c, 0x46, 0x21, 0x0d, 0, 0, 0, 0x38, 0, 0, 0, 0x14, 0, 0, 0,
        0x02, 0, 0, 0, 0, 0, 0, 0, 0x06, 0, 0, 0, 0x78, 0, 0, 0,
        0x68, 0x71, 0xf3.toByte(), 0xfa.toByte(), 0x01, 0, 0, 0, 0x07, 0, 0, 0, 0x01, 0, 0, 0,
        0, 0, 0, 0,
    )

    @Test
    fun headerVersion() {
        assertEquals("1.7.1", FirmwareFiles.headerVersion(header171))
        assertNull(FirmwareFiles.headerVersion(ByteArray(0x34)))
        assertNull(FirmwareFiles.headerVersion(header171.copyOf(20)))
    }

    @Test
    fun onlyParrotsFiles() {
        val sha171 = FirmwareFiles.OFFICIAL.getValue("1.7.1")
        assertEquals(FirmwareFiles.Check.Official("1.7.1"), FirmwareFiles.check(header171, sha171.uppercase()))
        assertEquals(FirmwareFiles.Check.Unknown, FirmwareFiles.check(header171, "00".repeat(32)))
        assertEquals(FirmwareFiles.Check.NotPlf, FirmwareFiles.check(ByteArray(0x34), sha171))
        assertEquals(FirmwareFiles.Check.Mismatch("1.7.1", "1.7.0"),
            FirmwareFiles.check(header171, FirmwareFiles.OFFICIAL.getValue("1.7.0")))
    }

    @Test
    fun sha256() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            FirmwareFiles.sha256("abc".byteInputStream()))
    }
}
