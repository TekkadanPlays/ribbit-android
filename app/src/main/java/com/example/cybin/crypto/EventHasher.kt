package com.example.cybin.crypto

import com.example.cybin.core.HexKey
import com.example.cybin.core.TagArray
import com.example.cybin.core.toHexString
import org.json.JSONArray
import java.security.MessageDigest

/**
 * Computes NIP-01 event IDs.
 *
 * The event ID is the SHA-256 hash of the canonical JSON serialization:
 * `[0, pubkey, created_at, kind, tags, content]`
 */
object EventHasher {

    /**
     * Compute the event ID (hex) for the given event fields.
     */
    fun hashId(
        pubKey: HexKey,
        createdAt: Long,
        kind: Int,
        tags: TagArray,
        content: String,
    ): String = hashIdBytes(pubKey, createdAt, kind, tags, content).toHexString()

    /**
     * Compute the event ID as raw bytes for the given event fields.
     */
    fun hashIdBytes(
        pubKey: HexKey,
        createdAt: Long,
        kind: Int,
        tags: TagArray,
        content: String,
    ): ByteArray {
        val json = makeJsonForId(pubKey, createdAt, kind, tags, content)
        return sha256(json.toByteArray(Charsets.UTF_8))
    }

    /**
     * Build the canonical JSON array for event ID hashing: [0, pubkey, created_at, kind, tags, content]
     */
    internal fun makeJsonForId(
        pubKey: HexKey,
        createdAt: Long,
        kind: Int,
        tags: TagArray,
        content: String,
    ): String {
        val arr = JSONArray()
        arr.put(0)
        arr.put(pubKey)
        arr.put(createdAt)
        arr.put(kind)
        val tagsArr = JSONArray()
        for (tag in tags) {
            val inner = JSONArray()
            for (element in tag) inner.put(element)
            tagsArr.put(inner)
        }
        arr.put(tagsArr)
        arr.put(content)
        return arr.toString()
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
