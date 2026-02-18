package com.example.cybin.nip19

/**
 * Bech32 encoding/decoding (BIP-173 / BIP-350).
 *
 * Used by NIP-19 for npub, nsec, note, nevent, nprofile, naddr bech32 strings.
 * Based on the ACINQ reference implementation (Apache 2.0).
 */
object Bech32 {
    const val ALPHABET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    private const val GEN0 = 0x3b6a57b2
    private const val GEN1 = 0x26508e6d
    private const val GEN2 = 0x1ea119fa
    private const val GEN3 = 0x3d4233dd
    private const val GEN4 = 0x2a1462b3

    enum class Encoding(val constant: Int) {
        Bech32(1),
        Bech32m(0x2bc830a3),
    }

    // char -> 5-bit value lookup
    private val charMap = IntArray(128) { -1 }.also { map ->
        ALPHABET.forEachIndexed { i, c ->
            map[c.code] = i
            map[c.uppercaseChar().code] = i
        }
    }

    private fun expand(hrp: String): IntArray {
        val result = IntArray(hrp.length * 2 + 1)
        hrp.forEachIndexed { i, c ->
            result[i] = c.code shr 5
            result[hrp.length + 1 + i] = c.code and 31
        }
        // result[hrp.length] = 0 (already zero-initialized)
        return result
    }

    private fun polymod(values: IntArray, values2: IntArray): Int {
        var chk = 1
        fun step(v: Int) {
            val b = chk shr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            if (b and 1 != 0) chk = chk xor GEN0
            if (b and 2 != 0) chk = chk xor GEN1
            if (b and 4 != 0) chk = chk xor GEN2
            if (b and 8 != 0) chk = chk xor GEN3
            if (b and 16 != 0) chk = chk xor GEN4
        }
        values.forEach { step(it) }
        values2.forEach { step(it) }
        return chk
    }

    /** Encode bytes to a bech32 string with the given human-readable prefix. */
    fun encodeBytes(hrp: String, data: ByteArray, encoding: Encoding): String {
        val int5s = eightToFive(data)
        val withChecksum = addChecksum(hrp, int5s, encoding)
        return hrp + "1" + String(CharArray(withChecksum.size) { ALPHABET[withChecksum[it]] })
    }

    /** Decode a bech32 string to (hrp, data bytes, encoding). */
    fun decodeBytes(bech32: String): Triple<String, ByteArray, Encoding> {
        val (hrp, int5s, enc) = decode(bech32)
        return Triple(hrp, fiveToEight(int5s), enc)
    }

    private fun decode(bech32: String): Triple<String, IntArray, Encoding> {
        val filtered = bech32.filter { it.code in 33..126 }
        var separatorPos = -1
        filtered.forEachIndexed { i, c ->
            if (c == '1') separatorPos = i
        }
        require(separatorPos > 0) { "Missing separator in bech32 string" }

        val hrp = filtered.substring(0, separatorPos).lowercase()
        require(hrp.length in 1..83) { "HRP must be 1-83 characters" }

        val dataStr = filtered.substring(separatorPos + 1)
        val data = IntArray(dataStr.length) {
            val v = charMap.getOrElse(dataStr[it].code) { -1 }
            require(v >= 0) { "Invalid bech32 character: ${dataStr[it]}" }
            v
        }

        val encoding = when (polymod(expand(hrp), data)) {
            Encoding.Bech32.constant -> Encoding.Bech32
            Encoding.Bech32m.constant -> Encoding.Bech32m
            else -> throw IllegalArgumentException("Invalid checksum for $bech32")
        }

        return Triple(hrp, data.copyOfRange(0, data.size - 6), encoding)
    }

    private fun addChecksum(hrp: String, data: IntArray, encoding: Encoding): IntArray {
        val values = expand(hrp) + data + IntArray(6)
        val poly = polymod(expand(hrp), data + IntArray(6)) xor encoding.constant
        val result = IntArray(data.size + 6)
        data.copyInto(result)
        for (i in 0 until 6) {
            result[data.size + i] = (poly shr (5 * (5 - i))) and 31
        }
        return result
    }

    private fun eightToFive(input: ByteArray): IntArray {
        val output = mutableListOf<Int>()
        var buffer = 0L
        var count = 0
        input.forEach { b ->
            buffer = (buffer shl 8) or (b.toLong() and 0xff)
            count += 8
            while (count >= 5) {
                output.add(((buffer shr (count - 5)) and 31).toInt())
                count -= 5
            }
        }
        if (count > 0) output.add(((buffer shl (5 - count)) and 31).toInt())
        return output.toIntArray()
    }

    private fun fiveToEight(input: IntArray): ByteArray {
        val output = mutableListOf<Byte>()
        var buffer = 0L
        var count = 0
        input.forEach { b ->
            buffer = (buffer shl 5) or (b.toLong() and 31)
            count += 5
            while (count >= 8) {
                output.add(((buffer shr (count - 8)) and 0xff).toByte())
                count -= 8
            }
        }
        return output.toByteArray()
    }
}

/** Decode a bech32 string to raw bytes, optionally verifying the HRP. */
fun String.bechToBytes(hrp: String? = null): ByteArray {
    val (decodedHrp, bytes, _) = Bech32.decodeBytes(this)
    if (hrp != null) {
        require(decodedHrp == hrp) { "Expected HRP '$hrp' but got '$decodedHrp'" }
    }
    return bytes
}
