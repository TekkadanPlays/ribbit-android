package com.example.cybin.nip19

import com.example.cybin.core.toHexString

/**
 * Base interface for all NIP-19 parsed entities.
 */
sealed interface Entity

/** npub — a public key (32 bytes). */
data class NPub(val hex: String) : Entity {
    companion object {
        fun parse(bytes: ByteArray): NPub? {
            if (bytes.isEmpty()) return null
            return NPub(bytes.toHexString())
        }
    }
}

/** nsec — a private key (32 bytes). */
data class NSec(val hex: String) : Entity {
    companion object {
        fun parse(bytes: ByteArray): NSec? {
            if (bytes.isEmpty()) return null
            return NSec(bytes.toHexString())
        }
    }
}

/** note — a bare event ID (32 bytes). */
data class NNote(val hex: String) : Entity {
    companion object {
        fun parse(bytes: ByteArray): NNote? {
            if (bytes.isEmpty()) return null
            return NNote(bytes.toHexString())
        }
    }
}

/** nevent — an event ID with optional relay hints, author, and kind (TLV-encoded). */
data class NEvent(
    val hex: String,
    val relays: List<String>,
    val author: String?,
    val kind: Int?,
) : Entity {
    companion object {
        fun parse(bytes: ByteArray): NEvent? {
            if (bytes.isEmpty()) return null
            val tlv = Tlv.parse(bytes)
            val hex = tlv.firstAsHex(Tlv.SPECIAL) ?: return null
            if (hex.isBlank()) return null
            return NEvent(
                hex = hex,
                relays = tlv.asStringList(Tlv.RELAY) ?: emptyList(),
                author = tlv.firstAsHex(Tlv.AUTHOR),
                kind = tlv.firstAsInt(Tlv.KIND),
            )
        }
    }
}

/** nprofile — a pubkey with optional relay hints (TLV-encoded). */
data class NProfile(
    val hex: String,
    val relays: List<String>,
) : Entity {
    companion object {
        fun parse(bytes: ByteArray): NProfile? {
            if (bytes.isEmpty()) return null
            val tlv = Tlv.parse(bytes)
            val hex = tlv.firstAsHex(Tlv.SPECIAL) ?: return null
            if (hex.isBlank()) return null
            return NProfile(
                hex = hex,
                relays = tlv.asStringList(Tlv.RELAY) ?: emptyList(),
            )
        }
    }
}

/** naddr — a parameterized replaceable event address (TLV-encoded). */
data class NAddress(
    val dTag: String,
    val relays: List<String>,
    val author: String?,
    val kind: Int?,
) : Entity {
    companion object {
        fun parse(bytes: ByteArray): NAddress? {
            if (bytes.isEmpty()) return null
            val tlv = Tlv.parse(bytes)
            val dTag = tlv.firstAsString(Tlv.SPECIAL) ?: return null
            return NAddress(
                dTag = dTag,
                relays = tlv.asStringList(Tlv.RELAY) ?: emptyList(),
                author = tlv.firstAsHex(Tlv.AUTHOR),
                kind = tlv.firstAsInt(Tlv.KIND),
            )
        }
    }
}
