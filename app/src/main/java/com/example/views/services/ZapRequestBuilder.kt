package com.example.views.services

import android.util.Log
import com.example.views.repository.ZapType
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip57Zaps.LnZapEvent
import com.vitorpamplona.quartz.nip57Zaps.LnZapRequestEvent
import kotlinx.serialization.json.Json

/**
 * Builds kind-9734 zap request events (NIP-57).
 *
 * Delegates to Quartz's [LnZapRequestEvent.create] for the heavy lifting,
 * translating ribbit's [ZapType] to Quartz's [LnZapEvent.ZapType].
 */
object ZapRequestBuilder {
    private const val TAG = "ZapRequestBuilder"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Convert ribbit ZapType to Quartz ZapType.
     */
    private fun toQuartzType(zapType: ZapType): LnZapEvent.ZapType = when (zapType) {
        ZapType.PUBLIC -> LnZapEvent.ZapType.PUBLIC
        ZapType.PRIVATE -> LnZapEvent.ZapType.PRIVATE
        ZapType.ANONYMOUS -> LnZapEvent.ZapType.ANONYMOUS
        ZapType.NONZAP -> LnZapEvent.ZapType.NONZAP
    }

    /**
     * Build and sign a kind-9734 zap request for a specific note.
     *
     * @param noteEvent  The Quartz event being zapped
     * @param relays     Relay URLs to include in the request
     * @param signer     The user's signer (for public/private zaps)
     * @param message    Optional message to include
     * @param zapType    The type of zap (PUBLIC, PRIVATE, ANONYMOUS)
     * @return The signed zap request event, or null on failure
     */
    suspend fun buildForNote(
        noteEvent: Event,
        relays: Set<NormalizedRelayUrl>,
        signer: NostrSigner,
        message: String = "",
        zapType: ZapType = ZapType.PUBLIC
    ): LnZapRequestEvent? {
        if (zapType == ZapType.NONZAP) {
            Log.d(TAG, "Non-zap type: no zap request event needed")
            return null
        }
        return try {
            val quartzType = toQuartzType(zapType)
            Log.d(TAG, "Building zap request: type=$quartzType, note=${noteEvent.id.take(8)}")
            LnZapRequestEvent.create(
                zappedEvent = noteEvent,
                relays = relays,
                signer = signer,
                pollOption = null,
                message = message,
                zapType = quartzType,
                toUserPubHex = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error building zap request: ${e.message}", e)
            null
        }
    }

    /**
     * Build and sign a kind-9734 zap request for a user (profile zap, not tied to a note).
     *
     * @param userPubkeyHex  The hex pubkey of the user being zapped
     * @param relays         Relay URLs to include in the request
     * @param signer         The user's signer
     * @param message        Optional message to include
     * @param zapType        The type of zap
     * @return The signed zap request event, or null on failure
     */
    suspend fun buildForUser(
        userPubkeyHex: String,
        relays: Set<NormalizedRelayUrl>,
        signer: NostrSigner,
        message: String = "",
        zapType: ZapType = ZapType.PUBLIC
    ): LnZapRequestEvent? {
        if (zapType == ZapType.NONZAP) {
            Log.d(TAG, "Non-zap type: no zap request event needed")
            return null
        }
        return try {
            val quartzType = toQuartzType(zapType)
            Log.d(TAG, "Building user zap request: type=$quartzType, user=${userPubkeyHex.take(8)}")
            LnZapRequestEvent.create(
                userHex = userPubkeyHex,
                relays = relays,
                signer = signer,
                message = message,
                zapType = quartzType
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error building user zap request: ${e.message}", e)
            null
        }
    }

    /**
     * Serialize a zap request event to its JSON string (for the LNUrl invoice callback).
     */
    fun toJson(event: LnZapRequestEvent): String {
        return event.toJson()
    }
}
