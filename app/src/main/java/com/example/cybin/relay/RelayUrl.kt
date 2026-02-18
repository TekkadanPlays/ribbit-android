package com.example.cybin.relay

/**
 * A normalized relay URL. Ensures consistent comparison and storage of relay addresses.
 *
 * Normalization rules:
 * - Lowercased scheme and host
 * - Trailing slash removed
 * - Default ports stripped (443 for wss, 80 for ws)
 */
@JvmInline
value class NormalizedRelayUrl(val url: String) {
    override fun toString(): String = url
}

/**
 * Relay URL normalization utilities.
 */
object RelayUrlNormalizer {

    /** Normalize a relay URL, or return null if it's invalid. */
    fun normalizeOrNull(url: String): NormalizedRelayUrl? {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return null

        val normalized = trimmed
            .lowercase()
            .removeSuffix("/")
            .let { u ->
                when {
                    u.startsWith("wss://") || u.startsWith("ws://") -> u
                    u.startsWith("https://") -> u.replaceFirst("https://", "wss://")
                    u.startsWith("http://") -> u.replaceFirst("http://", "ws://")
                    else -> "wss://$u"
                }
            }
            .removeSuffix(":443")
            .removeSuffix(":80")

        // Basic validation: must have a host after the scheme
        val host = normalized.removePrefix("wss://").removePrefix("ws://")
        if (host.isBlank() || !host.contains('.')) return null

        return NormalizedRelayUrl(normalized)
    }

    /** Normalize a relay URL, throwing if invalid. */
    fun normalize(url: String): NormalizedRelayUrl =
        normalizeOrNull(url) ?: throw IllegalArgumentException("Invalid relay URL: $url")
}
