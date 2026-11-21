/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Disco firmware update files (T9.9): only Parrot's own, recognised by
 * their SHA-256 (the files on firmware.parrot.com/Drones/090e/<version>/
 * ALL/disco_update.plf, NOTES.md section 41), and their PLF header must
 * agree on the version. Anything else is refused: a wrong file can leave
 * the Disco unable to fly.
 */
object FirmwareFiles {

    /** SHA-256 of Parrot's Disco update files, by version. */
    val OFFICIAL = mapOf(
        "1.7.1" to "f52f226d04c986b69e4962247d09b95ac634116a5eae7d183523ce9e7f70a056",
        "1.7.0" to "1c06c211db7b3bd3b5a77cf6884cceb786f61a101351524010a479a850912e59",
        "1.4.1" to "adf14b7085e3f208443e87f3d4dcd6bf31b126220e92f0cbf3dded6fcf5ff0bf",
    )

    sealed class Check {
        data class Official(val version: String) : Check()
        object NotPlf : Check()
        object Unknown : Check()
        data class Mismatch(val header: String, val official: String) : Check()
    }

    /** The version in a PLF header ("PLF!", then major, minor, bugfix as little-endian ints at 0x24). */
    fun headerVersion(header: ByteArray): String? {
        if (header.size < 0x34 || header[0] != 'P'.code.toByte() || header[1] != 'L'.code.toByte() ||
            header[2] != 'F'.code.toByte() || header[3] != '!'.code.toByte()) return null
        fun le32(o: Int) = (header[o].toLong() and 0xFF) or ((header[o + 1].toLong() and 0xFF) shl 8) or
            ((header[o + 2].toLong() and 0xFF) shl 16) or ((header[o + 3].toLong() and 0xFF) shl 24)
        return "${le32(0x24)}.${le32(0x28)}.${le32(0x2c)}"
    }

    fun check(header: ByteArray, sha256: String): Check {
        val version = headerVersion(header) ?: return Check.NotPlf
        val official = OFFICIAL.entries.firstOrNull { it.value.equals(sha256, ignoreCase = true) }?.key
            ?: return Check.Unknown
        return if (official == version) Check.Official(version) else Check.Mismatch(version, official)
    }

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun header(file: File): ByteArray = file.inputStream().use { input ->
        val h = ByteArray(0x34)
        var read = 0
        while (read < h.size) {
            val n = input.read(h, read, h.size - read)
            if (n < 0) break
            read += n
        }
        h.copyOf(read)
    }
}
