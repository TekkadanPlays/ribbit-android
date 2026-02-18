package com.example.cybin.crypto

import com.example.cybin.core.HexKey
import com.example.cybin.core.hexToByteArray
import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

/**
 * NIP-04 encryption/decryption using ECDH shared secret + AES-256-CBC.
 *
 * Wire format: `<base64(ciphertext)>?iv=<base64(iv)>`
 */
object Nip04 {

    /**
     * Encrypt plaintext to a recipient using ECDH + AES-256-CBC.
     *
     * @param plaintext The message to encrypt.
     * @param privKey The sender's 32-byte private key.
     * @param toPublicKey The recipient's hex public key.
     * @return NIP-04 formatted string: `<base64(ciphertext)>?iv=<base64(iv)>`
     */
    fun encrypt(plaintext: String, privKey: ByteArray, toPublicKey: HexKey): String {
        val sharedSecret = computeSharedSecret(privKey, toPublicKey)
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val encB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        return "$encB64?iv=$ivB64"
    }

    /**
     * Decrypt NIP-04 ciphertext from a sender.
     *
     * @param ciphertext NIP-04 formatted string: `<base64(ciphertext)>?iv=<base64(iv)>`
     * @param privKey The recipient's 32-byte private key.
     * @param fromPublicKey The sender's hex public key.
     * @return The decrypted plaintext.
     */
    fun decrypt(ciphertext: String, privKey: ByteArray, fromPublicKey: HexKey): String {
        val parts = ciphertext.split("?iv=")
        require(parts.size == 2) { "Invalid NIP-04 ciphertext format" }
        val encrypted = Base64.decode(parts[0], Base64.DEFAULT)
        val iv = Base64.decode(parts[1], Base64.DEFAULT)
        val sharedSecret = computeSharedSecret(privKey, fromPublicKey)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    /** Check if a string looks like NIP-04 ciphertext (contains `?iv=`). */
    fun isNip04(content: String): Boolean = content.contains("?iv=")

    /**
     * Compute the ECDH shared secret between a private key and a public key.
     * Returns the 32-byte x-coordinate of the shared point (used as AES key).
     */
    private fun computeSharedSecret(privKey: ByteArray, pubKeyHex: HexKey): ByteArray {
        // secp256k1-kmp needs 33-byte compressed pubkey (02 prefix + 32 bytes x-coord)
        val pubKeyBytes = byteArrayOf(0x02) + pubKeyHex.hexToByteArray()
        val shared = Secp256k1.ecdh(privKey, pubKeyBytes)
        // ECDH returns 32 bytes (x-coordinate of shared point)
        return shared
    }
}
