package com.example.cybin.core

import kotlin.random.Random

/**
 * Utility functions for the Cybin Nostr library.
 */
object CybinUtils {
    private val HEX_CHARS = "0123456789abcdef"
    private val ALPHANUMERIC = "abcdefghijklmnopqrstuvwxyz0123456789"

    /** Generate a random alphanumeric string of the given length. */
    fun randomChars(length: Int): String =
        buildString(length) {
            repeat(length) { append(ALPHANUMERIC[Random.nextInt(ALPHANUMERIC.length)]) }
        }

    /** Generate a random hex string of the given byte length (output is 2x chars). */
    fun randomHex(byteLength: Int): String =
        buildString(byteLength * 2) {
            repeat(byteLength * 2) { append(HEX_CHARS[Random.nextInt(16)]) }
        }
}
