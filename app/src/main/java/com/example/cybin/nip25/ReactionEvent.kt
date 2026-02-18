package com.example.cybin.nip25

import com.example.cybin.core.Event
import com.example.cybin.core.EventTemplate
import com.example.cybin.core.eventTemplate
import com.example.cybin.core.nowUnixSeconds

/**
 * NIP-25 Reaction event builder (kind 7).
 *
 * Reactions reference the target event via "e" and "p" tags, and optionally a "k" tag
 * for the target event's kind.
 */
object ReactionEvent {
    const val KIND = 7
    const val LIKE = "+"
    const val DISLIKE = "-"

    /**
     * Build a reaction [EventTemplate].
     *
     * @param reaction The reaction content ("+", "-", emoji, or custom emoji shortcode).
     * @param targetEvent The event being reacted to.
     * @param relayHint Optional relay URL hint for the target event.
     * @param createdAt Timestamp (defaults to now).
     */
    fun build(
        reaction: String,
        targetEvent: Event,
        relayHint: String? = null,
        createdAt: Long = nowUnixSeconds(),
    ): EventTemplate = eventTemplate(KIND, reaction, createdAt) {
        // e tag: reference to the event being reacted to
        if (relayHint != null) {
            add(arrayOf("e", targetEvent.id, relayHint))
        } else {
            add(arrayOf("e", targetEvent.id))
        }
        // p tag: author of the event being reacted to
        if (relayHint != null) {
            add(arrayOf("p", targetEvent.pubKey, relayHint))
        } else {
            add(arrayOf("p", targetEvent.pubKey))
        }
        // k tag: kind of the event being reacted to
        add(arrayOf("k", targetEvent.kind.toString()))
    }
}
