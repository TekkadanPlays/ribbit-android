package com.example.cybin.core

/**
 * DSL builder for constructing Nostr tag arrays.
 *
 * Usage:
 * ```
 * val tags = tagArray {
 *     add(arrayOf("e", eventId))
 *     add(arrayOf("p", pubkey))
 *     addUnique(arrayOf("d", ""))
 * }
 * ```
 */
class TagArrayBuilder {
    private val tagList = mutableMapOf<String, MutableList<Tag>>()

    fun remove(tagName: String): TagArrayBuilder {
        tagList.remove(tagName)
        return this
    }

    fun remove(tagName: String, tagValue: String): TagArrayBuilder {
        tagList[tagName]?.removeAll { it.valueOrNull() == tagValue }
        if (tagList[tagName]?.isEmpty() == true) {
            tagList.remove(tagName)
        }
        return this
    }

    fun add(tag: Array<String>): TagArrayBuilder {
        if (tag.isEmpty() || tag[0].isEmpty()) return this
        tagList.getOrPut(tag[0], ::mutableListOf).add(tag)
        return this
    }

    fun addUnique(tag: Array<String>): TagArrayBuilder {
        if (tag.isEmpty() || tag[0].isEmpty()) return this
        tagList[tag[0]] = mutableListOf(tag)
        return this
    }

    fun addAll(tags: List<Array<String>>): TagArrayBuilder {
        tags.forEach(::add)
        return this
    }

    fun addAll(tags: Array<Array<String>>): TagArrayBuilder {
        tags.forEach(::add)
        return this
    }

    fun toTypedArray(): TagArray = tagList.flatMap { it.value }.toTypedArray()

    fun build(): TagArray = toTypedArray()
}

/** Build a [TagArray] using the DSL builder. */
inline fun tagArray(initializer: TagArrayBuilder.() -> Unit = {}): TagArray =
    TagArrayBuilder().apply(initializer).build()

/** Create a builder pre-populated with existing tags, then apply mutations. */
fun TagArray.builder(updater: TagArrayBuilder.() -> Unit = {}): TagArray =
    TagArrayBuilder().addAll(this).apply(updater).build()
