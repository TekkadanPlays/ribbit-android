package com.example.cybin.nip57

import com.example.cybin.core.Event
import com.example.cybin.core.HexKey
import com.example.cybin.core.nowUnixSeconds
import com.example.cybin.crypto.KeyPair
import com.example.cybin.signer.NostrSigner
import com.example.cybin.signer.NostrSignerInternal

/**
 * NIP-57 Zap request event builder (kind 9734).
 */
object LnZapRequestEvent {
    const val KIND = 9734

    /**
     * Zap type enum matching Quartz's LnZapEvent.ZapType.
     */
    enum class ZapType {
        PUBLIC,
        PRIVATE,
        ANONYMOUS,
        NONZAP,
    }

    /**
     * Build and sign a kind-9734 zap request for a specific event.
     *
     * @param zappedEvent The event being zapped.
     * @param relays Relay URLs to include in the request.
     * @param signer The user's signer.
     * @param message Optional zap message.
     * @param zapType PUBLIC, PRIVATE, or ANONYMOUS.
     * @param toUserPubHex Override recipient pubkey (defaults to event author).
     * @param createdAt Timestamp.
     */
    suspend fun create(
        zappedEvent: Event,
        relays: Set<String>,
        signer: NostrSigner,
        pollOption: Int? = null,
        message: String = "",
        zapType: ZapType = ZapType.PUBLIC,
        toUserPubHex: String? = null,
        createdAt: Long = nowUnixSeconds(),
    ): Event {
        var tags = mutableListOf(
            arrayOf("e", zappedEvent.id),
            arrayOf("p", toUserPubHex ?: zappedEvent.pubKey),
            arrayOf("relays") + relays.toList(),
            arrayOf("alt", "Zap request"),
        )

        if (pollOption != null && pollOption >= 0) {
            tags.add(arrayOf("poll_option", pollOption.toString()))
        }

        return when (zapType) {
            ZapType.PUBLIC -> signer.sign(createdAt, KIND, tags.toTypedArray(), message)
            ZapType.ANONYMOUS -> {
                tags.add(arrayOf("anon"))
                NostrSignerInternal(KeyPair()).sign(createdAt, KIND, tags.toTypedArray(), message)
            }
            ZapType.PRIVATE -> {
                tags.add(arrayOf("anon", ""))
                signer.sign(createdAt, KIND, tags.toTypedArray(), message)
            }
            ZapType.NONZAP -> throw IllegalArgumentException("Invalid zap type: NONZAP")
        }
    }

    /**
     * Build and sign a kind-9734 zap request for a user (profile zap, not tied to a note).
     *
     * @param userHex The hex pubkey of the user being zapped.
     * @param relays Relay URLs to include.
     * @param signer The user's signer.
     * @param message Optional zap message.
     * @param zapType PUBLIC, PRIVATE, or ANONYMOUS.
     * @param createdAt Timestamp.
     */
    suspend fun create(
        userHex: String,
        relays: Set<String>,
        signer: NostrSigner,
        message: String = "",
        zapType: ZapType = ZapType.PUBLIC,
        createdAt: Long = nowUnixSeconds(),
    ): Event {
        var tags = arrayOf(
            arrayOf("p", userHex),
            arrayOf("relays") + relays.toList(),
        )

        return when (zapType) {
            ZapType.PUBLIC -> signer.sign(createdAt, KIND, tags, message)
            ZapType.ANONYMOUS -> {
                tags += arrayOf(arrayOf("anon", ""))
                NostrSignerInternal(KeyPair()).sign(createdAt, KIND, tags, message)
            }
            ZapType.PRIVATE -> {
                tags += arrayOf(arrayOf("anon", ""))
                signer.sign(createdAt, KIND, tags, message)
            }
            ZapType.NONZAP -> throw IllegalArgumentException("Invalid zap type: NONZAP")
        }
    }
}
