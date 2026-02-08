package com.example.views.repository

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate

/**
 * Builds Kind 11 (topic) and Kind 1111 (thread reply) event templates
 * for signing via Amber and publishing to relays.
 * Tag structure matches RelayTools / NIP-22 so relays that support kind-11/1111 accept them.
 */
object TopicsPublishService {

    /**
     * Build a Kind 11 topic event template. Tags: "title", "t" (hashtags). Caller signs and sends.
     */
    fun buildTopicEventTemplate(
        title: String,
        content: String,
        hashtags: List<String>
    ): EventTemplate<Event> {
        return Event.build(11, content) {
            add(arrayOf("title", title))
            hashtags.forEach { tag ->
                add(arrayOf("t", tag.removePrefix("#").trim()))
            }
        }
    }

    /**
     * Build a Kind 1111 thread reply event template. Root: E, K, P; parent: e, k, p. Caller signs and sends.
     */
    fun buildThreadReplyEventTemplate(
        rootThreadId: String,
        rootThreadPubkey: String,
        parentReplyId: String?,
        parentReplyPubkey: String?,
        content: String
    ): EventTemplate<Event> {
        return Event.build(1111, content) {
            add(arrayOf("E", rootThreadId, "", rootThreadPubkey))
            add(arrayOf("K", "11"))
            add(arrayOf("P", rootThreadPubkey, ""))
            if (parentReplyId != null && parentReplyPubkey != null) {
                add(arrayOf("e", parentReplyId, "", parentReplyPubkey))
                add(arrayOf("k", "1111"))
                add(arrayOf("p", parentReplyPubkey, ""))
            } else {
                add(arrayOf("e", rootThreadId, "", rootThreadPubkey))
                add(arrayOf("k", "11"))
                add(arrayOf("p", rootThreadPubkey, ""))
            }
        }
    }
}
