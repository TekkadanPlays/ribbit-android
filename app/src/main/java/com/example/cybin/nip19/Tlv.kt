package com.example.cybin.nip19

import com.example.cybin.core.toHexString

/**
 * TLV (Type-Length-Value) parser for NIP-19 binary payloads.
 *
 * NIP-19 entities like nevent, nprofile, and naddr encode structured data as TLV sequences
 * inside the bech32 payload.
 *
 * Type IDs:
 * - 0 (SPECIAL): event ID or pubkey (32 bytes hex) or "d" tag value (string)
 * - 1 (RELAY): relay URL (UTF-8 string)
 * - 2 (AUTHOR): author pubkey (32 bytes hex)
 * - 3 (KIND): event kind (4-byte big-endian int)
 */
class Tlv(val data: Map<Byte, List<ByteArray>>) {

    fun firstAsHex(type: Byte): String? = data[type]?.firstOrNull()?.toHexString()
    fun firstAsString(type: Byte): String? = data[type]?.firstOrNull()?.decodeToString()
    fun firstAsInt(type: Byte): Int? = data[type]?.firstOrNull()?.toInt32()
    fun asStringList(type: Byte): List<String>? = data[type]?.map { it.decodeToString() }
    fun asHexList(type: Byte): List<String>? = data[type]?.map { it.toHexString() }

    companion object {
        const val SPECIAL: Byte = 0
        const val RELAY: Byte = 1
        const val AUTHOR: Byte = 2
        const val KIND: Byte = 3

        fun parse(data: ByteArray): Tlv {
            val result = mutableMapOf<Byte, MutableList<ByteArray>>()
            var offset = 0
            while (offset + 1 < data.size) {
                val type = data[offset]
                val length = data[offset + 1].toUByte().toInt()
                if (offset + 2 + length > data.size) break
                val value = data.sliceArray(offset + 2 until offset + 2 + length)
                result.getOrPut(type) { mutableListOf() }.add(value)
                offset += 2 + length
            }
            return Tlv(result)
        }
    }
}

/** Convert a 4-byte big-endian array to an Int, or null if not exactly 4 bytes. */
private fun ByteArray.toInt32(): Int? {
    if (size != 4) return null
    return ((this[0].toLong() and 0xFF) shl 24 or
            ((this[1].toLong() and 0xFF) shl 16) or
            ((this[2].toLong() and 0xFF) shl 8) or
            (this[3].toLong() and 0xFF)).toInt()
}
