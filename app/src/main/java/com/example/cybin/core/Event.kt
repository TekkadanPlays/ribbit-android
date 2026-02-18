package com.example.cybin.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * A signed Nostr event (NIP-01).
 *
 * This is the fundamental data structure of the Nostr protocol. Every note, reaction,
 * relay list, zap, and profile update is an Event with a specific [kind].
 *
 * Matches the wire format: `{"id":"...","pubkey":"...","created_at":...,"kind":...,"tags":[...],"content":"...","sig":"..."}`
 */
open class Event(
    val id: HexKey,
    val pubKey: HexKey,
    val createdAt: Long,
    val kind: Kind,
    val tags: TagArray,
    val content: String,
    val sig: HexKey,
) {
    /** Serialize this event to its canonical NIP-01 JSON string. */
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("pubkey", pubKey)
        obj.put("created_at", createdAt)
        obj.put("kind", kind)
        obj.put("tags", tagsToJsonArray(tags))
        obj.put("content", content)
        obj.put("sig", sig)
        return obj.toString()
    }

    companion object {
        /** Parse a Nostr event from its JSON string representation. */
        fun fromJson(json: String): Event {
            val obj = JSONObject(json)
            return Event(
                id = obj.getString("id"),
                pubKey = obj.getString("pubkey"),
                createdAt = obj.getLong("created_at"),
                kind = obj.getInt("kind"),
                tags = parseTagArray(obj.getJSONArray("tags")),
                content = obj.getString("content"),
                sig = obj.getString("sig"),
            )
        }

        /** Parse a Nostr event from JSON, returning null on failure. */
        fun fromJsonOrNull(json: String): Event? =
            try { fromJson(json) } catch (_: Exception) { null }

        /**
         * Build an unsigned [EventTemplate] using the tag DSL.
         *
         * Usage:
         * ```
         * val template = Event.build(1, "Hello Nostr!") {
         *     add(arrayOf("t", "introductions"))
         * }
         * val signed = signer.sign(template)
         * ```
         */
        fun build(
            kind: Int,
            content: String = "",
            createdAt: Long = nowUnixSeconds(),
            initializer: TagArrayBuilder.() -> Unit = {},
        ): EventTemplate = eventTemplate(kind, content, createdAt, initializer)

        // ── JSON helpers ────────────────────────────────────────────────

        private fun tagsToJsonArray(tags: TagArray): JSONArray {
            val outer = JSONArray()
            for (tag in tags) {
                val inner = JSONArray()
                for (element in tag) inner.put(element)
                outer.put(inner)
            }
            return outer
        }

        internal fun parseTagArray(arr: JSONArray): TagArray {
            return Array(arr.length()) { i ->
                val inner = arr.getJSONArray(i)
                Array(inner.length()) { j -> inner.getString(j) }
            }
        }
    }
}
