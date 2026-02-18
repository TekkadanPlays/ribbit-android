package com.example.cybin.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * A Nostr relay subscription filter (NIP-01).
 *
 * Sent as part of a REQ message to request events matching the given criteria.
 * All fields are optional; only non-null fields are included in the JSON output.
 */
class Filter(
    val ids: List<String>? = null,
    val authors: List<String>? = null,
    val kinds: List<Int>? = null,
    val tags: Map<String, List<String>>? = null,
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null,
    val search: String? = null,
) {
    /** Serialize this filter to its NIP-01 JSON representation. */
    fun toJson(): String {
        val obj = JSONObject()
        ids?.let { obj.put("ids", JSONArray(it)) }
        authors?.let { obj.put("authors", JSONArray(it)) }
        kinds?.let { obj.put("kinds", JSONArray(it)) }
        tags?.forEach { (key, values) ->
            obj.put("#$key", JSONArray(values))
        }
        since?.let { obj.put("since", it) }
        until?.let { obj.put("until", it) }
        limit?.let { obj.put("limit", it) }
        search?.let { obj.put("search", it) }
        return obj.toString()
    }

    fun copy(
        ids: List<String>? = this.ids,
        authors: List<String>? = this.authors,
        kinds: List<Int>? = this.kinds,
        tags: Map<String, List<String>>? = this.tags,
        since: Long? = this.since,
        until: Long? = this.until,
        limit: Int? = this.limit,
        search: String? = this.search,
    ) = Filter(ids, authors, kinds, tags, since, until, limit, search)

    /** Returns true if this filter contains any non-null and non-empty criteria. */
    fun isFilledFilter() =
        (ids != null && ids.isNotEmpty()) ||
            (authors != null && authors.isNotEmpty()) ||
            (kinds != null && kinds.isNotEmpty()) ||
            (tags != null && tags.isNotEmpty() && tags.values.all { it.isNotEmpty() }) ||
            (since != null) ||
            (until != null) ||
            (limit != null) ||
            (search != null && search.isNotEmpty())

    companion object {
        /** Parse a filter from its JSON representation. */
        fun fromJson(json: String): Filter {
            val obj = JSONObject(json)
            val tagMap = mutableMapOf<String, List<String>>()
            for (key in obj.keys()) {
                if (key.startsWith("#")) {
                    val tagName = key.removePrefix("#")
                    val arr = obj.getJSONArray(key)
                    tagMap[tagName] = List(arr.length()) { arr.getString(it) }
                }
            }
            return Filter(
                ids = obj.optJSONArray("ids")?.let { arr -> List(arr.length()) { arr.getString(it) } },
                authors = obj.optJSONArray("authors")?.let { arr -> List(arr.length()) { arr.getString(it) } },
                kinds = obj.optJSONArray("kinds")?.let { arr -> List(arr.length()) { arr.getInt(it) } },
                tags = tagMap.ifEmpty { null },
                since = if (obj.has("since")) obj.getLong("since") else null,
                until = if (obj.has("until")) obj.getLong("until") else null,
                limit = if (obj.has("limit")) obj.getInt("limit") else null,
                search = if (obj.has("search")) obj.getString("search") else null,
            )
        }
    }
}
