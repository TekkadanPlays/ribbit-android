package com.example.cybin.signer

import com.example.cybin.core.Event
import com.example.cybin.core.HexKey
import com.example.cybin.core.TagArray
import com.example.cybin.core.toHexString
import com.example.cybin.crypto.EventHasher
import com.example.cybin.crypto.KeyPair
import com.example.cybin.crypto.Nip04
import fr.acinq.secp256k1.Secp256k1
import kotlin.random.Random

/**
 * Internal (nsec-based) Nostr event signer.
 *
 * Signs events locally using the secp256k1 Schnorr signature scheme (BIP-340).
 * Also handles NIP-04 encrypt/decrypt via ECDH shared secret.
 */
class NostrSignerInternal(
    val keyPair: KeyPair,
) : NostrSigner(keyPair.pubKey.toHexString()) {

    override fun isWriteable(): Boolean = keyPair.privKey != null

    override suspend fun sign(
        createdAt: Long,
        kind: Int,
        tags: TagArray,
        content: String,
    ): Event {
        val privKey = keyPair.privKey
            ?: throw IllegalStateException("Cannot sign: no private key (read-only account)")

        val pubKeyHex = keyPair.pubKey.toHexString()
        val id = EventHasher.hashId(pubKeyHex, createdAt, kind, tags, content)
        val idBytes = EventHasher.hashIdBytes(pubKeyHex, createdAt, kind, tags, content)

        // Schnorr sign the event ID hash
        val auxRand = Random.nextBytes(32)
        val sig = Secp256k1.signSchnorr(idBytes, privKey, auxRand)

        return Event(
            id = id,
            pubKey = pubKeyHex,
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            sig = sig.toHexString(),
        )
    }

    override suspend fun nip04Encrypt(plaintext: String, toPublicKey: HexKey): String {
        val privKey = keyPair.privKey
            ?: throw IllegalStateException("Cannot encrypt: no private key")
        return Nip04.encrypt(plaintext, privKey, toPublicKey)
    }

    override suspend fun nip04Decrypt(ciphertext: String, fromPublicKey: HexKey): String {
        val privKey = keyPair.privKey
            ?: throw IllegalStateException("Cannot decrypt: no private key")
        return Nip04.decrypt(ciphertext, privKey, fromPublicKey)
    }

    override suspend fun nip44Encrypt(plaintext: String, toPublicKey: HexKey): String {
        // NIP-44 uses XChaCha20-Poly1305 — for now delegate to NIP-04 as a fallback.
        // Full NIP-44 support requires libsodium or a ChaCha20 implementation.
        // TODO: Implement proper NIP-44 (XChaCha20-Poly1305 + padding)
        return nip04Encrypt(plaintext, toPublicKey)
    }

    override suspend fun nip44Decrypt(ciphertext: String, fromPublicKey: HexKey): String {
        // NIP-44 ciphertext doesn't contain "?iv=" — detect and handle accordingly.
        // TODO: Implement proper NIP-44 decryption
        return nip04Decrypt(ciphertext, fromPublicKey)
    }

    override fun hasForegroundSupport(): Boolean = true
}
