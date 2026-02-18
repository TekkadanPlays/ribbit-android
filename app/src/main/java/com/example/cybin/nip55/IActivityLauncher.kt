package com.example.cybin.nip55

import android.content.Intent

/**
 * Interface for NIP-55 foreground activity launching.
 *
 * When the external signer (Amber) needs user interaction (e.g. permission approval),
 * it launches a foreground activity. This interface manages the launcher registration
 * and response handling.
 */
interface IActivityLauncher {
    fun registerForegroundLauncher(launcher: ((Intent) -> Unit))
    fun unregisterForegroundLauncher(launcher: ((Intent) -> Unit))
    fun newResponse(data: Intent)
    fun hasForegroundActivity(): Boolean
}
