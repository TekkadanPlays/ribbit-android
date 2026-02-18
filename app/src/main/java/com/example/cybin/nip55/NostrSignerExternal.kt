package com.example.cybin.nip55

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.util.Log
import com.example.cybin.core.Event
import com.example.cybin.core.HexKey
import com.example.cybin.core.TagArray
import com.example.cybin.crypto.EventHasher
import com.example.cybin.signer.NostrSigner

/**
 * NIP-55 external signer (Amber) implementation for Cybin.
 *
 * Communicates with the signer app via Android ContentResolver queries.
 * Each command type maps to a content URI: `content://{packageName}.{command_type}`
 *
 * Query projection format: `[data, toPubKey/eventPubKey, loggedInUser]`
 * Response cursor columns: `"result"` (signature/ciphertext/plaintext) or `"event"` (full signed JSON)
 */
class NostrSignerExternal(
    pubKey: HexKey,
    val packageName: String,
    val contentResolver: ContentResolver,
) : NostrSigner(pubKey) {

    companion object {
        private const val TAG = "NostrSignerExternal"
    }

    override fun isWriteable(): Boolean = true

    override suspend fun sign(
        createdAt: Long,
        kind: Int,
        tags: TagArray,
        content: String,
    ): Event {
        val id = EventHasher.hashId(pubKey, createdAt, kind, tags, content)
        val unsignedEvent = Event(
            id = id,
            pubKey = pubKey,
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            sig = "",
        )

        val uri = Uri.parse("content://$packageName.${CommandType.SIGN_EVENT.code}")
        val result = queryContentResolver(uri, arrayOf(unsignedEvent.toJson(), unsignedEvent.pubKey, pubKey))

        if (result != null) {
            // Amber may return a full signed event JSON or just a signature
            val eventJson = result["event"]
            if (!eventJson.isNullOrBlank() && eventJson.startsWith("{")) {
                val signedEvent = Event.fromJsonOrNull(eventJson)
                if (signedEvent != null) return signedEvent
            }

            val signature = result["result"]
            if (!signature.isNullOrBlank()) {
                return Event(
                    id = id,
                    pubKey = pubKey,
                    createdAt = createdAt,
                    kind = kind,
                    tags = tags,
                    content = content,
                    sig = signature,
                )
            }
        }

        throw IllegalStateException("Amber failed to sign event (kind=$kind)")
    }

    override suspend fun nip04Encrypt(plaintext: String, toPublicKey: HexKey): String {
        if (plaintext.isBlank()) return ""
        val uri = Uri.parse("content://$packageName.${CommandType.NIP04_ENCRYPT.code}")
        val result = queryContentResolver(uri, arrayOf(plaintext, toPublicKey, pubKey))
        return result?.get("result")
            ?: throw IllegalStateException("Amber failed to NIP-04 encrypt")
    }

    override suspend fun nip04Decrypt(ciphertext: String, fromPublicKey: HexKey): String {
        if (ciphertext.isBlank()) throw IllegalArgumentException("Nothing to decrypt")
        val uri = Uri.parse("content://$packageName.${CommandType.NIP04_DECRYPT.code}")
        val result = queryContentResolver(uri, arrayOf(ciphertext, fromPublicKey, pubKey))
        return result?.get("result")
            ?: throw IllegalStateException("Amber failed to NIP-04 decrypt")
    }

    override suspend fun nip44Encrypt(plaintext: String, toPublicKey: HexKey): String {
        if (plaintext.isBlank()) return ""
        val uri = Uri.parse("content://$packageName.${CommandType.NIP44_ENCRYPT.code}")
        val result = queryContentResolver(uri, arrayOf(plaintext, toPublicKey, pubKey))
        return result?.get("result")
            ?: throw IllegalStateException("Amber failed to NIP-44 encrypt")
    }

    override suspend fun nip44Decrypt(ciphertext: String, fromPublicKey: HexKey): String {
        if (ciphertext.isBlank()) throw IllegalArgumentException("Nothing to decrypt")
        val uri = Uri.parse("content://$packageName.${CommandType.NIP44_DECRYPT.code}")
        val result = queryContentResolver(uri, arrayOf(ciphertext, fromPublicKey, pubKey))
        return result?.get("result")
            ?: throw IllegalStateException("Amber failed to NIP-44 decrypt")
    }

    override fun hasForegroundSupport(): Boolean = false

    /**
     * Query the external signer's ContentProvider and extract named columns from the cursor.
     *
     * @return A map of column names to values, or null if the query failed.
     */
    private fun queryContentResolver(uri: Uri, projection: Array<String>): Map<String, String>? {
        return try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    extractColumns(cursor)
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "ContentResolver query failed for $uri: ${e.message}")
            null
        }
    }

    private fun extractColumns(cursor: Cursor): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (i in 0 until cursor.columnCount) {
            val name = cursor.getColumnName(i)
            val value = cursor.getString(i)
            if (name != null && value != null) {
                result[name] = value
            }
        }
        return result
    }
}
