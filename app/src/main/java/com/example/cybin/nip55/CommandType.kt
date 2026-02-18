package com.example.cybin.nip55

/**
 * NIP-55 command types for Android signer IPC.
 */
enum class CommandType(val code: String) {
    SIGN_EVENT("sign_event"),
    NIP04_ENCRYPT("nip04_encrypt"),
    NIP04_DECRYPT("nip04_decrypt"),
    NIP44_ENCRYPT("nip44_encrypt"),
    NIP44_DECRYPT("nip44_decrypt"),
    GET_PUBLIC_KEY("get_public_key"),
    ;

    companion object {
        fun parse(code: String): CommandType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * A permission request for the NIP-55 login intent.
 *
 * @param type The command type this permission grants.
 * @param kind Optional event kind restriction (only for SIGN_EVENT).
 */
data class Permission(
    val type: CommandType,
    val kind: Int? = null,
) {
    fun toJson(): String = buildString {
        append("{\"type\":\"${type.code}\"")
        if (kind != null) append(",\"kind\":$kind")
        append("}")
    }
}
