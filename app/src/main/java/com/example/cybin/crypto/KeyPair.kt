package com.example.cybin.crypto

import com.example.cybin.core.toHexString
import fr.acinq.secp256k1.Secp256k1
import kotlin.random.Random

/**
 * A secp256k1 key pair for Nostr event signing.
 *
 * @param privKey 32-byte private key (null for read-only accounts).
 * @param pubKey 32-byte x-only public key. Derived from privKey if not provided.
 */
class KeyPair(
    privKey: ByteArray? = null,
    pubKey: ByteArray? = null,
) {
    val privKey: ByteArray?
    val pubKey: ByteArray

    init {
        if (privKey != null) {
            this.privKey = privKey
            this.pubKey = pubKey ?: derivePublicKey(privKey)
        } else if (pubKey != null) {
            check(pubKey.size == 32) { "Public key must be 32 bytes (x-only)" }
            this.privKey = null
            this.pubKey = pubKey
        } else {
            // Generate random key pair
            this.privKey = Random.nextBytes(32)
            this.pubKey = derivePublicKey(this.privKey)
        }
    }

    override fun toString(): String =
        "KeyPair(privKey=${privKey?.toHexString()?.take(8)}..., pubKey=${pubKey.toHexString().take(8)}...)"

    companion object {
        /** Derive the 32-byte x-only public key from a 32-byte private key. */
        fun derivePublicKey(privKey: ByteArray): ByteArray {
            // secp256k1-kmp returns 33-byte compressed pubkey; strip the prefix byte
            val compressed = Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(privKey))
            return compressed.copyOfRange(1, 33)
        }
    }
}
