package com.example.cybin.core

/**
 * An unsigned Nostr event template, ready to be signed by a [com.example.cybin.signer.NostrSigner].
 *
 * Created via [Event.build] or the [eventTemplate] helper.
 */
class EventTemplate(
    val createdAt: Long,
    val kind: Int,
    val tags: TagArray,
    val content: String,
)

/** Build an [EventTemplate] using the tag DSL. */
inline fun eventTemplate(
    kind: Int,
    content: String,
    createdAt: Long = nowUnixSeconds(),
    initializer: TagArrayBuilder.() -> Unit = {},
): EventTemplate = EventTemplate(createdAt, kind, tagArray(initializer), content)
