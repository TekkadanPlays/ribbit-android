package com.example.cybin.signer

import com.example.cybin.core.Event
import com.example.cybin.core.EventTemplate
import com.example.cybin.core.HexKey
import com.example.cybin.core.TagArray

/**
 * Abstract signer for Nostr events.
 *
 * Implementations:
 * - [NostrSignerInternal]: Signs with a local nsec private key (secp256k1 Schnorr).
 * - External signers (NIP-55 Amber): Sign via Android IPC content resolver.
 */
abstract class NostrSigner(
    val pubKey: HexKey,
) {
    /** Whether this signer can produce signatures (false for read-only npub accounts). */
    abstract fun isWriteable(): Boolean

    /** Sign an [EventTemplate], producing a fully signed [Event]. */
    suspend fun sign(ev: EventTemplate): Event = sign(ev.createdAt, ev.kind, ev.tags, ev.content)

    /** Sign raw event fields, producing a fully signed [Event]. */
    abstract suspend fun sign(
        createdAt: Long,
        kind: Int,
        tags: TagArray,
        content: String,
    ): Event

    /** NIP-04 encrypt plaintext to a recipient public key. */
    abstract suspend fun nip04Encrypt(
        plaintext: String,
        toPublicKey: HexKey,
    ): String

    /** NIP-04 decrypt ciphertext from a sender public key. */
    abstract suspend fun nip04Decrypt(
        ciphertext: String,
        fromPublicKey: HexKey,
    ): String

    /** NIP-44 encrypt plaintext to a recipient public key. */
    abstract suspend fun nip44Encrypt(
        plaintext: String,
        toPublicKey: HexKey,
    ): String

    /** NIP-44 decrypt ciphertext from a sender public key. */
    abstract suspend fun nip44Decrypt(
        ciphertext: String,
        fromPublicKey: HexKey,
    ): String

    /** Whether this signer supports foreground signing (e.g. Amber activity launch). */
    abstract fun hasForegroundSupport(): Boolean

    /** Decrypt content using NIP-04 or NIP-44 based on the ciphertext format. */
    suspend fun decrypt(
        encryptedContent: String,
        fromPublicKey: HexKey,
    ): String {
        if (encryptedContent.isBlank()) throw IllegalArgumentException("Nothing to decrypt")
        // NIP-04 ciphertext contains "?iv=" separator
        return if (encryptedContent.contains("?iv=")) {
            nip04Decrypt(encryptedContent, fromPublicKey)
        } else {
            nip44Decrypt(encryptedContent, fromPublicKey)
        }
    }
}
