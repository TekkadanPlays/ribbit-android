package com.example.views.auth

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip55AndroidSigner.client.ExternalSignerLogin
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import com.vitorpamplona.quartz.nip55AndroidSigner.client.isExternalSignerInstalled
import com.vitorpamplona.quartz.nip55AndroidSigner.client.getExternalSignersInstalled
import com.vitorpamplona.quartz.nip55AndroidSigner.api.permission.Permission
import com.vitorpamplona.quartz.nip55AndroidSigner.api.CommandType
import android.net.Uri
import android.database.Cursor
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class AmberState {
    object NotInstalled : AmberState()
    object NotLoggedIn : AmberState()
    object LoggingIn : AmberState()
    data class LoggedIn(val pubKey: HexKey, val signer: NostrSigner) : AmberState()
    data class Error(val message: String) : AmberState()
}

class AmberSignerManager(private val context: Context) {

    private val _state = MutableStateFlow<AmberState>(AmberState.NotInstalled)
    val state: StateFlow<AmberState> = _state.asStateFlow()

    private var currentSigner: NostrSignerExternal? = null
    private var activityContext: Context? = null

    // SharedPreferences for persisting auth state
    private val prefs: SharedPreferences = context.getSharedPreferences("amber_auth", Context.MODE_PRIVATE)

    companion object {
        const val AMBER_PACKAGE_NAME = "com.greenart7c3.nostrsigner"

        // SharedPreferences keys
        private const val PREF_IS_LOGGED_IN = "is_logged_in"
        private const val PREF_USER_PUBKEY = "user_pubkey"
        private const val PREF_PACKAGE_NAME = "package_name"

        val DEFAULT_PERMISSIONS = listOf<Permission>(
            Permission(
                type = CommandType.SIGN_EVENT,
                kind = 1 // Text notes
            ),
            Permission(
                type = CommandType.SIGN_EVENT,
                kind = 22242 // NIP-42 auth events
            ),
            Permission(
                type = CommandType.NIP04_ENCRYPT
            ),
            Permission(
                type = CommandType.NIP04_DECRYPT
            ),
            Permission(
                type = CommandType.NIP44_ENCRYPT
            ),
            Permission(
                type = CommandType.NIP44_DECRYPT
            )
        )
    }

    init {
        // Store activity context if the provided context is an Activity
        if (context is android.app.Activity) {
            activityContext = context
        }
        checkAmberInstallation()
    }

    /**
     * Set the current activity context for signing operations
     * Call this from MainActivity when it becomes active
     */
    fun setActivityContext(activity: android.app.Activity) {
        activityContext = activity
        Log.d("AmberSignerManager", "🔐 Activity context set for signing")
    }

    /**
     * Clear the activity context when activity is destroyed
     */
    fun clearActivityContext() {
        activityContext = null
        Log.d("AmberSignerManager", "🔐 Activity context cleared")
    }

    private fun checkAmberInstallation() {
        val installedSigners = getExternalSignersInstalled(context)
        Log.d("AmberSignerManager", "🔍 Checking Amber installation. Found ${installedSigners.size} signers: $installedSigners")

        if (installedSigners.isEmpty()) {
            Log.d("AmberSignerManager", "❌ No external signers installed")
            _state.value = AmberState.NotInstalled
            return
        }

        Log.d("AmberSignerManager", "✅ Amber or compatible signer is installed")

        // Check if we have saved auth state
        val isLoggedIn = prefs.getBoolean(PREF_IS_LOGGED_IN, false)
        val savedPubkey = prefs.getString(PREF_USER_PUBKEY, null)
        val savedPackageName = prefs.getString(PREF_PACKAGE_NAME, AMBER_PACKAGE_NAME)

        if (isLoggedIn && !savedPubkey.isNullOrEmpty()) {
            Log.d("AmberSignerManager", "🔐 Restoring saved Amber session: ${savedPubkey.take(16)}...")

            try {
                // Convert npub to hex if needed
                val hexPubkey = if (savedPubkey.startsWith("npub")) {
                    try {
                        val nip19 = com.vitorpamplona.quartz.nip19Bech32.Nip19Parser.uriToRoute(savedPubkey)
                        (nip19?.entity as? com.vitorpamplona.quartz.nip19Bech32.entities.NPub)?.hex ?: savedPubkey
                    } catch (e: Exception) {
                        Log.w("AmberSignerManager", "Failed to convert npub to hex: ${e.message}")
                        savedPubkey
                    }
                } else {
                    savedPubkey
                }

                // Recreate the signer with saved credentials
                val signer = NostrSignerExternal(
                    pubKey = hexPubkey,
                    packageName = savedPackageName ?: AMBER_PACKAGE_NAME,
                    contentResolver = context.contentResolver
                )

                currentSigner = signer
                _state.value = AmberState.LoggedIn(hexPubkey, signer)
                Log.d("AmberSignerManager", "✅ Successfully restored Amber session")
            } catch (e: Exception) {
                Log.w("AmberSignerManager", "❌ Failed to restore Amber session: ${e.message}")
                // Clear invalid saved state
                clearSavedAuthState()
                _state.value = AmberState.NotLoggedIn
            }
        } else {
            Log.d("AmberSignerManager", "🔐 No saved Amber session found")
            _state.value = AmberState.NotLoggedIn
        }
    }

    fun getInstalledSigners() = getExternalSignersInstalled(context)

    /**
     * Save authentication state to persistent storage
     */
    private fun saveAuthState(pubkey: String, packageName: String) {
        prefs.edit()
            .putBoolean(PREF_IS_LOGGED_IN, true)
            .putString(PREF_USER_PUBKEY, pubkey)
            .putString(PREF_PACKAGE_NAME, packageName)
            .apply()
        Log.d("AmberSignerManager", "💾 Saved Amber auth state: ${pubkey.take(16)}...")
    }

    /**
     * Clear saved authentication state
     */
    private fun clearSavedAuthState() {
        prefs.edit()
            .putBoolean(PREF_IS_LOGGED_IN, false)
            .remove(PREF_USER_PUBKEY)
            .remove(PREF_PACKAGE_NAME)
            .apply()
        Log.d("AmberSignerManager", "🗑️ Cleared saved Amber auth state")
    }

    fun createLoginIntent(): Intent {
        _state.value = AmberState.LoggingIn
        return ExternalSignerLogin.createIntent(
            permissions = DEFAULT_PERMISSIONS,
            packageName = AMBER_PACKAGE_NAME
        )
    }

    fun handleLoginResult(resultCode: Int, data: Intent?) {
        try {
            if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                // Parse the actual Amber response according to NIP-55
                val pubkey = data.getStringExtra("result")
                val packageName = data.getStringExtra("package") ?: AMBER_PACKAGE_NAME

                if (pubkey != null) {
                    Log.d("AmberSignerManager", "✅ Amber login successful: ${pubkey.take(16)}...")

                    // Convert npub to hex if needed
                    val hexPubkey = if (pubkey.startsWith("npub")) {
                        try {
                            val nip19 = com.vitorpamplona.quartz.nip19Bech32.Nip19Parser.uriToRoute(pubkey)
                            val hex = (nip19?.entity as? com.vitorpamplona.quartz.nip19Bech32.entities.NPub)?.hex
                            Log.d("AmberSignerManager", "🔄 Converted npub to hex: ${hex?.take(16)}...")
                            hex ?: pubkey
                        } catch (e: Exception) {
                            Log.w("AmberSignerManager", "Failed to convert npub to hex: ${e.message}")
                            pubkey
                        }
                    } else {
                        pubkey
                    }

                    // Create external signer with hex pubkey
                    val signer = NostrSignerExternal(
                        pubKey = hexPubkey,
                        packageName = packageName,
                        contentResolver = context.contentResolver
                    )

                    currentSigner = signer
                    _state.value = AmberState.LoggedIn(hexPubkey, signer)

                    // Save auth state for future app starts (save original npub/hex)
                    saveAuthState(hexPubkey, packageName)
                } else {
                    Log.e("AmberSignerManager", "❌ No pubkey in Amber response")
                    _state.value = AmberState.Error("No pubkey received from Amber")
                }
            } else {
                Log.w("AmberSignerManager", "❌ Amber login cancelled or failed")
                _state.value = AmberState.Error("Login cancelled or failed")
            }
        } catch (e: Exception) {
            Log.e("AmberSignerManager", "❌ Login error: ${e.message}", e)
            _state.value = AmberState.Error("Login error: ${e.message}")
        }
    }

    fun logout() {
        currentSigner = null
        _state.value = AmberState.NotLoggedIn
        // Clear saved auth state
        clearSavedAuthState()
        Log.d("AmberSignerManager", "👋 Logged out from Amber")
    }

    fun getCurrentPubKey(): HexKey? {
        return when (val currentState = _state.value) {
            is AmberState.LoggedIn -> currentState.pubKey
            else -> null
        }
    }

    fun getCurrentSigner(): NostrSigner? {
        return when (val currentState = _state.value) {
            is AmberState.LoggedIn -> {
                Log.d("AmberSignerManager", "🔐 Using logged-in signer")
                currentState.signer
            }
            is AmberState.NotLoggedIn -> {
                // Create on-demand signer - Amber will handle permissions when needed
                Log.d("AmberSignerManager", "🔐 Creating on-demand Amber signer")

                // Use activity context if available, otherwise fall back to app context
                val signerContext = activityContext ?: context
                Log.d("AmberSignerManager", "🔐 Activity context available: ${activityContext != null}")
                Log.d("AmberSignerManager", "🔐 Using context for signer: ${signerContext.javaClass.simpleName}")

                try {
                    // Check if we have Activity context available
                    if (activityContext != null) {
                        Log.d("AmberSignerManager", "🎯 Creating signer with Activity context")
                        NostrSignerExternal(
                            pubKey = "", // Will be determined by Amber when signing
                            packageName = AMBER_PACKAGE_NAME,
                            contentResolver = activityContext!!.contentResolver
                        )
                    } else {
                        Log.w("AmberSignerManager", "⚠️ No Activity context - signer may not work for signing")
                        NostrSignerExternal(
                            pubKey = "", // Will be determined by Amber when signing
                            packageName = AMBER_PACKAGE_NAME,
                            contentResolver = context.contentResolver
                        )
                    }
                } catch (e: Exception) {
                    Log.w("AmberSignerManager", "Failed to create on-demand signer: ${e.message}")
                    null
                }
            }
            else -> {
                Log.w("AmberSignerManager", "⚠️ Amber not available - state: $currentState")
                null
            }
        }
    }
}
