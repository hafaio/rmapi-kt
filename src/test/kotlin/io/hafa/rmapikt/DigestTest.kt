package io.hafa.rmapikt

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins the hand-rolled primitives. CRC32C is the one piece of the protocol this library
 * implements from scratch (`java.util.zip.CRC32C` needs Android 34), so it is checked
 * against the RFC 3720 / Castagnoli vectors rather than against itself.
 */
class DigestTest {
    @Test
    fun `crc32c matches rfc 3720 vectors`() {
        assertEquals(0x00000000, crc32c(ByteArray(0)))
        assertEquals(0x8A9136AA.toInt(), crc32c(ByteArray(32)))
        assertEquals(0x62A8AB43.toInt(), crc32c(ByteArray(32) { 0xFF.toByte() }))
        assertEquals(0xE3069283.toInt(), crc32c("123456789".toByteArray()))
    }

    @Test
    fun `crc32c bytes are big endian`() {
        // 0xE3069283 for "123456789"; the x-goog-hash header base64s these four bytes.
        assertContentEquals(
            byteArrayOf(0xE3.toByte(), 0x06, 0x92.toByte(), 0x83.toByte()),
            crc32cBytes("123456789".toByteArray()),
        )
    }

    @Test
    fun `sha256 matches known digests`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(ByteArray(0)),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun `hex round trips`() {
        val bytes = ByteArray(256) { it.toByte() }
        assertContentEquals(bytes, bytes.toHex().hexToBytes())
        assertEquals("00010203fdfeff", (byteArrayOf(0, 1, 2, 3, -3, -2, -1)).toHex())
    }

    @Test
    fun `hex rejects malformed input`() {
        assertFailsWith<IllegalArgumentException> { "abc".hexToBytes() }
        assertFailsWith<IllegalArgumentException> { "zz".hexToBytes() }
        // upper case is not what the protocol writes, so it is not accepted
        assertFailsWith<IllegalArgumentException> { "AB".hexToBytes() }
    }
}
