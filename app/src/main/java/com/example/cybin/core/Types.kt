package com.example.cybin.core

/** Hex-encoded string (event IDs, public keys, signatures). */
typealias HexKey = String

/** Nostr event kind (NIP-01). */
typealias Kind = Int

/** Tag array: Array of string arrays, e.g. [["e", "abc..."], ["p", "def..."]]. */
typealias TagArray = Array<Array<String>>

/** Convenience alias for a single tag entry. */
typealias Tag = Array<String>

/** Get the tag name (first element) or null. */
fun Tag.nameOrNull(): String? = getOrNull(0)

/** Get the tag value (second element) or null. */
fun Tag.valueOrNull(): String? = getOrNull(1)

/** Decode a hex string to a ByteArray. */
fun String.hexToByteArray(): ByteArray {
    check(length % 2 == 0) { "Hex string must have even length" }
    return ByteArray(length / 2) { i ->
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        check(hi >= 0 && lo >= 0) { "Invalid hex character at index ${i * 2}" }
        ((hi shl 4) or lo).toByte()
    }
}

/** Encode a ByteArray to a lowercase hex string. */
fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

/** Current Unix timestamp in seconds. */
fun nowUnixSeconds(): Long = System.currentTimeMillis() / 1000
