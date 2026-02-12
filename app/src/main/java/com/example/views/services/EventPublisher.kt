package com.example.views.services

import android.content.Context
import android.util.Log
import com.example.views.relay.RelayConnectionStateMachine
import com.example.views.utils.ClientTagManager
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer

/**
 * Result of an event publish attempt.
 */
sealed class PublishResult {
    data class Success(val eventId: String) : PublishResult()
    data class Error(val message: String) : PublishResult()
}

/**
 * Centralized event publisher for all Nostr event kinds.
 *
 * Extracts the common pattern shared by every publish method:
 *   1. Build an EventTemplate (caller provides kind + content + tags)
 *   2. Optionally inject NIP-89 client tag
 *   3. Sign with the provided NostrSigner
 *   4. Send to the provided relay URLs
 *
 * Usage:
 * ```
 * val result = EventPublisher.publish(
 *     context = appContext,
 *     signer = signer,
 *     relayUrls = relaySet,
 *     kind = 1311,
 *     content = "hello chat",
 *     tags = { add(arrayOf("a", activityAddress)) }
 * )
 * ```
 */
object EventPublisher {

    private const val TAG = "EventPublisher"

    /**
     * Build, sign, and send a Nostr event.
     *
     * @param context Application context (for ClientTagManager preference).
     * @param signer The NostrSigner to sign the event with.
     * @param relayUrls Raw relay URLs to publish to. Will be normalized; empty set after normalization is an error.
     * @param kind The Nostr event kind (1, 7, 11, 1011, 1111, 1311, 30073, etc.).
     * @param content The event content string.
     * @param tags Lambda to add custom tags to the event template builder.
     * @return [PublishResult.Success] with the event ID, or [PublishResult.Error] with a message.
     */
    suspend fun publish(
        context: Context,
        signer: NostrSigner,
        relayUrls: Set<String>,
        kind: Int,
        content: String,
        tags: (TagArrayBuilder<Event>.() -> Unit)? = null
    ): PublishResult {
        val normalized = normalizeRelays(relayUrls)
        if (normalized.isEmpty()) {
            return PublishResult.Error("No valid relays selected")
        }

        return try {
            // Build event template
            val template = Event.build(kind, content) {
                tags?.invoke(this)
                if (ClientTagManager.isEnabled(context)) add(ClientTagManager.CLIENT_TAG)
            }

            // Sign
            val signed = signer.sign(template)
            if (signed.sig.isBlank()) {
                return PublishResult.Error("Signing failed (empty signature)")
            }

            // Send
            RelayConnectionStateMachine.getInstance().nostrClient.send(signed, normalized)
            Log.d(TAG, "Kind-$kind published: ${signed.id.take(8)} → ${normalized.size} relays")

            PublishResult.Success(signed.id)
        } catch (e: Exception) {
            Log.e(TAG, "Kind-$kind publish failed: ${e.message}", e)
            PublishResult.Error(e.message?.take(80) ?: "Unknown error")
        }
    }

    /**
     * Overload accepting a pre-built EventTemplate (for complex events like reactions
     * that use library builders). Still injects client tag and handles sign+send.
     */
    suspend fun publish(
        context: Context,
        signer: NostrSigner,
        relayUrls: Set<String>,
        template: EventTemplate<Event>
    ): PublishResult {
        val normalized = normalizeRelays(relayUrls)
        if (normalized.isEmpty()) {
            return PublishResult.Error("No valid relays selected")
        }

        return try {
            // Inject client tag if enabled
            val finalTemplate = if (ClientTagManager.isEnabled(context)) {
                EventTemplate<Event>(template.createdAt, template.kind, template.tags + arrayOf(ClientTagManager.CLIENT_TAG), template.content)
            } else template

            val signed: Event = signer.sign(finalTemplate)
            if (signed.sig.isBlank()) {
                return PublishResult.Error("Signing failed (empty signature)")
            }

            RelayConnectionStateMachine.getInstance().nostrClient.send(signed, normalized)
            Log.d(TAG, "Kind-${template.kind} published: ${signed.id.take(8)} → ${normalized.size} relays")

            PublishResult.Success(signed.id)
        } catch (e: Exception) {
            Log.e(TAG, "Kind-${template.kind} publish failed: ${e.message}", e)
            PublishResult.Error(e.message?.take(80) ?: "Unknown error")
        }
    }

    /** Normalize raw relay URL strings to NormalizedRelayUrl set. */
    private fun normalizeRelays(urls: Set<String>): Set<NormalizedRelayUrl> =
        urls.mapNotNull { RelayUrlNormalizer.normalizeOrNull(it) }.toSet()
}
