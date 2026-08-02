package io.hafa.rmapikt

import java.security.MessageDigest

private const val HEX_DIGITS = "0123456789abcdef"

/** Castagnoli polynomial, bit-reversed, as used by `x-goog-hash: crc32c=`. */
private const val CRC32C_POLYNOMIAL = 0x82F63B78.toInt()

private const val BITS_PER_BYTE = 8
private const val BYTE_MASK = 0xFF
private const val NIBBLE_BITS = 4
private const val TABLE_SIZE = 256

/**
 * Precomputed Castagnoli table.
 *
 * `java.util.zip.CRC32C` would do this, but it needs Android 34; this keeps the
 * library's Android floor at 21.
 */
private val CRC32C_TABLE: IntArray = IntArray(TABLE_SIZE) { index ->
    var value = index
    repeat(BITS_PER_BYTE) {
        value = if (value and 1 != 0) (value ushr 1) xor CRC32C_POLYNOMIAL else value ushr 1
    }
    value
}

/** Lower-case hex encoding; `java.util.HexFormat` is JDK 17 only and absent on Android. */
internal fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and BYTE_MASK
        out.append(HEX_DIGITS[value ushr NIBBLE_BITS])
        out.append(HEX_DIGITS[value and 0x0F])
    }
    return out.toString()
}

/** Decodes lower-case hex. Throws [IllegalArgumentException] on odd length or non-hex input. */
internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string had odd length $length" }
    val out = ByteArray(length / 2)
    for (index in out.indices) {
        val high = HEX_DIGITS.indexOf(this[index * 2])
        val low = HEX_DIGITS.indexOf(this[index * 2 + 1])
        require(high >= 0 && low >= 0) { "'$this' was not lower-case hex" }
        out[index] = ((high shl NIBBLE_BITS) or low).toByte()
    }
    return out
}

/** SHA-256 of [bytes], the address of every blob in the cloud. */
internal fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

/** SHA-256 of [bytes] as a lower-case hex string. */
internal fun sha256Hex(bytes: ByteArray): String = sha256(bytes).toHex()

/** CRC-32C (Castagnoli) checksum, sent as a big-endian base64 upload integrity header. */
internal fun crc32c(bytes: ByteArray): Int {
    var crc = -1
    for (byte in bytes) {
        val index = (crc xor byte.toInt()) and BYTE_MASK
        crc = (crc ushr BITS_PER_BYTE) xor CRC32C_TABLE[index]
    }
    return crc.inv()
}

/** [crc32c] as the four big-endian bytes the `x-goog-hash` header carries. */
internal fun crc32cBytes(bytes: ByteArray): ByteArray {
    val crc = crc32c(bytes)
    return byteArrayOf(
        (crc ushr 24).toByte(),
        (crc ushr 16).toByte(),
        (crc ushr BITS_PER_BYTE).toByte(),
        crc.toByte(),
    )
}
