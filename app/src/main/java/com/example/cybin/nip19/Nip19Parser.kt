package com.example.cybin.nip19

import android.util.Log
import com.example.cybin.core.hexToByteArray
import com.example.cybin.core.toHexString

/**
 * NIP-19 bech32-encoded entity parser.
 *
 * Parses npub, nsec, note, nevent, nprofile, and naddr strings from raw bech32
 * or `nostr:` URI format.
 */
object Nip19Parser {

    private const val TAG = "Nip19Parser"

    private val nip19Regex = Regex(
        "(nostr:)?@?((nsec1|npub1|note1)([qpzry9x8gf2tvdw0s3jn54khce6mua7l]{58})|(nevent1|naddr1|nprofile1)([qpzry9x8gf2tvdw0s3jn54khce6mua7l]+))([\\S]*)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Result of parsing a NIP-19 string.
     *
     * @property entity The parsed entity (NPub, NSec, NNote, NEvent, NProfile, NAddress).
     * @property nip19raw The raw bech32 string (without nostr: prefix).
     * @property additionalChars Any trailing characters after the bech32 string.
     */
    data class ParseReturn(
        val entity: Entity,
        val nip19raw: String,
        val additionalChars: String? = null,
    )

    /**
     * Parse a NIP-19 URI or bech32 string into a [ParseReturn].
     *
     * Accepts formats: `npub1...`, `nostr:npub1...`, `@npub1...`, etc.
     * Returns null if the string doesn't contain a valid NIP-19 entity.
     */
    fun uriToRoute(uri: String?): ParseReturn? {
        if (uri == null) return null
        return try {
            val matcher = nip19Regex.find(uri) ?: return null
            val type = matcher.groups[3]?.value ?: matcher.groups[5]?.value ?: return null
            val key = matcher.groups[4]?.value ?: matcher.groups[6]?.value
            val additionalChars = matcher.groups[7]?.value
            parseComponents(type, key, additionalChars?.ifEmpty { null })
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to parse NIP-19: $uri: ${e.message}")
            null
        }
    }

    /**
     * Find and parse all NIP-19 entities in a content string.
     */
    fun parseAll(content: String): List<Entity> {
        return nip19Regex.findAll(content).mapNotNull { matcher ->
            val type = matcher.groups[3]?.value ?: matcher.groups[5]?.value ?: return@mapNotNull null
            val key = matcher.groups[4]?.value ?: matcher.groups[6]?.value
            val additionalChars = matcher.groups[7]?.value
            parseComponents(type, key, additionalChars?.ifEmpty { null })?.entity
        }.toList()
    }

    private fun parseComponents(type: String, key: String?, additionalChars: String?): ParseReturn? {
        return try {
            val nip19 = type + key
            val bytes = nip19.bechToBytes()
            val entity = when (type.lowercase()) {
                "nsec1" -> NSec.parse(bytes)
                "npub1" -> NPub.parse(bytes)
                "note1" -> NNote.parse(bytes)
                "nprofile1" -> NProfile.parse(bytes)
                "nevent1" -> NEvent.parse(bytes)
                "naddr1" -> NAddress.parse(bytes)
                else -> null
            } ?: return null
            ParseReturn(entity, nip19, additionalChars)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to decode NIP-19 component $key: ${e.message}")
            null
        }
    }
}

// ── Encoding helpers ────────────────────────────────────────────────────────

/** Encode a 32-byte public key to an npub1 bech32 string. */
fun ByteArray.toNpub(): String = Bech32.encodeBytes("npub", this, Bech32.Encoding.Bech32)

/** Encode a hex public key to an npub1 bech32 string. */
fun String.toNpub(): String = hexToByteArray().toNpub()

/** Encode a 32-byte private key to an nsec1 bech32 string. */
fun ByteArray.toNsec(): String = Bech32.encodeBytes("nsec", this, Bech32.Encoding.Bech32)

/** Encode a 32-byte event ID to a note1 bech32 string. */
fun ByteArray.toNote(): String = Bech32.encodeBytes("note", this, Bech32.Encoding.Bech32)
