package com.example.cybin.nip47

import com.example.cybin.core.Event
import com.example.cybin.core.HexKey
import com.example.cybin.core.nowUnixSeconds
import com.example.cybin.signer.NostrSigner
import org.json.JSONObject

/**
 * NIP-47 Nostr Wallet Connect event builders and response parsers.
 */

// ── Kind-23194: Pay Invoice Request ─────────────────────────────────────────

object LnZapPaymentRequestEvent {
    const val KIND = 23194

    /**
     * Create a signed kind-23194 pay_invoice request.
     *
     * @param lnInvoice The bolt11 Lightning invoice to pay.
     * @param walletServicePubkey The wallet service's hex public key.
     * @param signer The NWC app signer (derived from the nostr+walletconnect secret).
     * @param createdAt Timestamp (defaults to now).
     * @return A fully signed Event of kind 23194.
     */
    suspend fun create(
        lnInvoice: String,
        walletServicePubkey: String,
        signer: NostrSigner,
        createdAt: Long = nowUnixSeconds(),
    ): Event {
        val requestJson = JSONObject().apply {
            put("method", "pay_invoice")
            put("params", JSONObject().apply {
                put("invoice", lnInvoice)
            })
        }.toString()

        val tags = arrayOf(
            arrayOf("p", walletServicePubkey),
            arrayOf("alt", "Zap payment request"),
        )

        val encrypted = signer.nip04Encrypt(requestJson, walletServicePubkey)
        return signer.sign(createdAt, KIND, tags, encrypted)
    }
}

// ── Kind-23195: Pay Invoice Response ────────────────────────────────────────

object LnZapPaymentResponseEvent {
    const val KIND = 23195

    /** Extract the request event ID from the "e" tag. */
    fun requestId(event: Event): String? =
        event.tags.firstOrNull { it.size > 1 && it[0] == "e" }?.get(1)

    /** Extract the request author pubkey from the "p" tag. */
    fun requestAuthor(event: Event): String? =
        event.tags.firstOrNull { it.size > 1 && it[0] == "p" }?.get(1)

    /**
     * Decrypt and parse a kind-23195 response event.
     *
     * @param event The kind-23195 event.
     * @param signer The NWC app signer (for decryption).
     * @return A parsed [NwcResponse].
     */
    suspend fun decrypt(event: Event, signer: NostrSigner): NwcResponse {
        val talkingWith = if (event.pubKey == signer.pubKey) {
            requestAuthor(event) ?: event.pubKey
        } else {
            event.pubKey
        }
        val json = signer.decrypt(event.content, talkingWith)
        return NwcResponse.parse(json)
    }
}

// ── Response types ──────────────────────────────────────────────────────────

sealed class NwcResponse(val resultType: String) {
    companion object {
        fun parse(json: String): NwcResponse {
            val obj = JSONObject(json)
            val resultType = obj.optString("result_type", "")
            val error = obj.optJSONObject("error")

            return if (error != null) {
                val code = error.optString("code", "OTHER")
                val message = error.optString("message", "")
                PayInvoiceErrorResponse(
                    error = PayInvoiceErrorResponse.ErrorParams(
                        code = try { PayInvoiceErrorResponse.ErrorType.valueOf(code) }
                               catch (_: Exception) { PayInvoiceErrorResponse.ErrorType.OTHER },
                        message = message,
                    )
                )
            } else {
                val result = obj.optJSONObject("result")
                val preimage = result?.let { if (it.has("preimage")) it.getString("preimage") else null }
                PayInvoiceSuccessResponse(
                    result = PayInvoiceSuccessResponse.ResultParams(preimage = preimage)
                )
            }
        }
    }
}

class PayInvoiceSuccessResponse(
    val result: ResultParams? = null,
) : NwcResponse("pay_invoice") {
    class ResultParams(val preimage: String? = null)
}

class PayInvoiceErrorResponse(
    val error: ErrorParams? = null,
) : NwcResponse("pay_invoice") {
    class ErrorParams(
        val code: ErrorType? = null,
        val message: String? = null,
    )

    enum class ErrorType {
        RATE_LIMITED,
        NOT_IMPLEMENTED,
        INSUFFICIENT_BALANCE,
        PAYMENT_FAILED,
        QUOTA_EXCEEDED,
        RESTRICTED,
        UNAUTHORIZED,
        INTERNAL,
        OTHER,
    }
}
