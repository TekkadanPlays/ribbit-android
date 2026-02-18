package com.example.cybin.relay

import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import org.json.JSONArray

/**
 * Nostr relay protocol message serialization/deserialization (NIP-01).
 *
 * Wire format messages:
 * - Client → Relay: `["REQ", subId, filter...]`, `["EVENT", event]`, `["CLOSE", subId]`
 * - Relay → Client: `["EVENT", subId, event]`, `["EOSE", subId]`, `["OK", eventId, success, message]`, `["NOTICE", message]`
 */
object NostrProtocol {

    // ── Client → Relay messages ─────────────────────────────────────────

    /** Build a REQ message: `["REQ", subId, filter1, filter2, ...]` */
    fun buildReq(subscriptionId: String, vararg filters: Filter): String {
        val arr = JSONArray()
        arr.put("REQ")
        arr.put(subscriptionId)
        for (filter in filters) {
            arr.put(org.json.JSONObject(filter.toJson()))
        }
        return arr.toString()
    }

    /** Build an EVENT message: `["EVENT", eventJson]` */
    fun buildEvent(event: Event): String {
        val arr = JSONArray()
        arr.put("EVENT")
        arr.put(org.json.JSONObject(event.toJson()))
        return arr.toString()
    }

    /** Build a CLOSE message: `["CLOSE", subId]` */
    fun buildClose(subscriptionId: String): String {
        val arr = JSONArray()
        arr.put("CLOSE")
        arr.put(subscriptionId)
        return arr.toString()
    }

    /** Build an AUTH message: `["AUTH", signedEventJson]` */
    fun buildAuth(event: Event): String {
        val arr = JSONArray()
        arr.put("AUTH")
        arr.put(org.json.JSONObject(event.toJson()))
        return arr.toString()
    }

    // ── Relay → Client message parsing ──────────────────────────────────

    /** Parsed relay message. */
    sealed class RelayMessage {
        /** `["EVENT", subId, eventJson]` */
        data class EventMsg(val subscriptionId: String, val event: Event) : RelayMessage()
        /** `["EOSE", subId]` */
        data class EndOfStoredEvents(val subscriptionId: String) : RelayMessage()
        /** `["OK", eventId, success, message]` */
        data class Ok(val eventId: String, val success: Boolean, val message: String) : RelayMessage()
        /** `["NOTICE", message]` */
        data class Notice(val message: String) : RelayMessage()
        /** `["AUTH", challenge]` */
        data class Auth(val challenge: String) : RelayMessage()
        /** Unrecognized message type. */
        data class Unknown(val raw: String) : RelayMessage()
    }

    /** Parse a relay message from its JSON string. */
    fun parseRelayMessage(json: String): RelayMessage {
        return try {
            val arr = JSONArray(json)
            when (arr.getString(0)) {
                "EVENT" -> {
                    val subId = arr.getString(1)
                    val eventObj = arr.getJSONObject(2)
                    val event = Event.fromJson(eventObj.toString())
                    RelayMessage.EventMsg(subId, event)
                }
                "EOSE" -> RelayMessage.EndOfStoredEvents(arr.getString(1))
                "OK" -> RelayMessage.Ok(
                    eventId = arr.getString(1),
                    success = arr.getBoolean(2),
                    message = if (arr.length() > 3) arr.getString(3) else "",
                )
                "NOTICE" -> RelayMessage.Notice(arr.getString(1))
                "AUTH" -> RelayMessage.Auth(arr.getString(1))
                else -> RelayMessage.Unknown(json)
            }
        } catch (_: Exception) {
            RelayMessage.Unknown(json)
        }
    }
}
