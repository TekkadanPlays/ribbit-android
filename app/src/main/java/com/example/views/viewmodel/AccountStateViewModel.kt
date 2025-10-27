package com.example.views.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.views.auth.AmberSignerManager
import com.example.views.auth.AmberState
import com.example.views.data.AccountInfo
import com.example.views.data.AuthState
import com.example.views.data.UserProfile
import com.example.views.data.GUEST_PROFILE
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manages account state across the entire app.
 * Handles:
 * - Multiple account storage
 * - Account switching
 * - Login/logout
 * - Current active account
 *
 * Based on Amethyst's AccountStateViewModel architecture.
 */
class AccountStateViewModel(application: Application) : AndroidViewModel(application) {

    private val amberSignerManager = AmberSignerManager(application)
    private val prefs = application.getSharedPreferences("ribbit_accounts", Application.MODE_PRIVATE)

    // Current active account
    private val _currentAccount = MutableStateFlow<AccountInfo?>(null)
    val currentAccount: StateFlow<AccountInfo?> = _currentAccount.asStateFlow()

    // All saved accounts
    private val _savedAccounts = MutableStateFlow<List<AccountInfo>>(emptyList())
    val savedAccounts: StateFlow<List<AccountInfo>> = _savedAccounts.asStateFlow()

    // Auth state for UI
    private val _authState = MutableStateFlow(AuthState())
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        private const val PREF_CURRENT_ACCOUNT = "current_account_npub"
        private const val PREF_ALL_ACCOUNTS = "all_accounts_json"
    }

    init {
        Log.d("AccountStateViewModel", "🔐 Initializing AccountStateViewModel")

        // Load saved accounts
        viewModelScope.launch {
            loadSavedAccounts()

            // Try to restore last active account
            val currentNpub = prefs.getString(PREF_CURRENT_ACCOUNT, null)
            if (currentNpub != null) {
                val account = _savedAccounts.value.find { it.npub == currentNpub }
                if (account != null) {
                    Log.d("AccountStateViewModel", "🔐 Restoring account: ${account.toShortNpub()}")
                    switchToAccount(account)
                } else {
                    // Account not found, start as guest
                    setGuestMode()
                }
            } else {
                // No saved account, start as guest
                setGuestMode()
            }
        }

        // Observe Amber state changes for new logins
        viewModelScope.launch {
            amberSignerManager.state.collect { amberState ->
                Log.d("AccountStateViewModel", "🔐 Amber state changed: $amberState")

                when (amberState) {
                    is AmberState.LoggedIn -> {
                        // New login completed
                        handleNewAmberLogin(amberState.pubKey)
                    }
                    is AmberState.Error -> {
                        _authState.value = _authState.value.copy(
                            error = amberState.message,
                            isLoading = false
                        )
                    }
                    is AmberState.LoggingIn -> {
                        _authState.value = _authState.value.copy(
                            isLoading = true,
                            error = null
                        )
                    }
                    else -> {
                        // NotInstalled or NotLoggedIn - keep current state
                    }
                }
            }
        }
    }

    private suspend fun loadSavedAccounts() = withContext(Dispatchers.IO) {
        val accountsJson = prefs.getString(PREF_ALL_ACCOUNTS, null)
        if (accountsJson != null) {
            try {
                val accounts = json.decodeFromString<List<AccountInfo>>(accountsJson)
                // Keep accounts in the order they were added (don't sort by lastUsed)
                _savedAccounts.value = accounts
                Log.d("AccountStateViewModel", "📚 Loaded ${accounts.size} saved accounts")
            } catch (e: Exception) {
                Log.e("AccountStateViewModel", "❌ Failed to load accounts: ${e.message}")
                _savedAccounts.value = emptyList()
            }
        } else {
            Log.d("AccountStateViewModel", "📚 No saved accounts found")
            _savedAccounts.value = emptyList()
        }
    }

    private suspend fun saveSavedAccounts(accounts: List<AccountInfo>) = withContext(Dispatchers.IO) {
        try {
            val accountsJson = json.encodeToString(accounts)
            prefs.edit()
                .putString(PREF_ALL_ACCOUNTS, accountsJson)
                .apply()
            _savedAccounts.value = accounts
            Log.d("AccountStateViewModel", "💾 Saved ${accounts.size} accounts")
        } catch (e: Exception) {
            Log.e("AccountStateViewModel", "❌ Failed to save accounts: ${e.message}")
        }
    }

    private fun setGuestMode() {
        Log.d("AccountStateViewModel", "👤 Setting guest mode")
        _currentAccount.value = null
        _authState.value = AuthState(
            isAuthenticated = false,
            isGuest = true,
            userProfile = GUEST_PROFILE,
            isLoading = false
        )
    }

    private suspend fun handleNewAmberLogin(hexPubkey: String) {
        Log.d("AccountStateViewModel", "✅ Handling new Amber login: ${hexPubkey.take(16)}...")

        // Convert hex to npub
        val npub = try {
            hexPubkey.hexToByteArray().toNpub()
        } catch (e: Exception) {
            Log.e("AccountStateViewModel", "❌ Failed to convert to npub: ${e.message}")
            return
        }

        // Check if this account already exists
        val existingAccount = _savedAccounts.value.find { it.npub == npub }

        val accountInfo = if (existingAccount != null) {
            // Update last used time
            existingAccount.copy(lastUsed = System.currentTimeMillis())
        } else {
            // Create new account
            AccountInfo(
                npub = npub,
                hasPrivateKey = false,
                isExternalSigner = true,
                isTransient = false,
                displayName = "Nostr User",
                picture = null,
                lastUsed = System.currentTimeMillis()
            )
        }

        // Save to accounts list - maintain order of addition
        val updatedAccounts = _savedAccounts.value
            .filter { it.npub != npub }
            .plus(accountInfo)

        saveSavedAccounts(updatedAccounts)

        // Set as current account
        prefs.edit()
            .putString(PREF_CURRENT_ACCOUNT, npub)
            .apply()

        _currentAccount.value = accountInfo

        // Update auth state
        val userProfile = UserProfile(
            pubkey = hexPubkey,
            displayName = accountInfo.displayName ?: "Nostr User",
            name = hexPubkey.take(8),
            picture = accountInfo.picture,
            about = "Signed in with Amber Signer",
            createdAt = System.currentTimeMillis()
        )

        _authState.value = AuthState(
            isAuthenticated = true,
            isGuest = false,
            userProfile = userProfile,
            isLoading = false,
            error = null
        )

        Log.d("AccountStateViewModel", "✅ Account activated: ${accountInfo.toShortNpub()}")
    }

    /**
     * Switch to a different saved account
     */
    fun switchToAccount(accountInfo: AccountInfo) {
        viewModelScope.launch {
            Log.d("AccountStateViewModel", "🔄 Switching to account: ${accountInfo.toShortNpub()}")

            // Update last used time
            val updatedAccount = accountInfo.copy(lastUsed = System.currentTimeMillis())

            val updatedAccounts = _savedAccounts.value
                .filter { it.npub != accountInfo.npub }
                .plus(updatedAccount)

            saveSavedAccounts(updatedAccounts)

            // Set as current
            prefs.edit()
                .putString(PREF_CURRENT_ACCOUNT, accountInfo.npub)
                .apply()

            _currentAccount.value = updatedAccount

            // Convert npub to hex for user profile
            val hexPubkey = updatedAccount.toHexKey()

            if (hexPubkey != null) {
                val userProfile = UserProfile(
                    pubkey = hexPubkey,
                    displayName = updatedAccount.displayName ?: "Nostr User",
                    name = hexPubkey.take(8),
                    picture = updatedAccount.picture,
                    about = if (updatedAccount.isExternalSigner) "Signed in with Amber Signer" else "Nostr User",
                    createdAt = System.currentTimeMillis()
                )

                _authState.value = AuthState(
                    isAuthenticated = true,
                    isGuest = false,
                    userProfile = userProfile,
                    isLoading = false,
                    error = null
                )

                Log.d("AccountStateViewModel", "✅ Switched to account: ${updatedAccount.getDisplayNameOrNpub()}")
            }
        }
    }

    /**
     * Login with Amber (creates intent)
     */
    fun loginWithAmber(): android.content.Intent {
        return amberSignerManager.createLoginIntent()
    }

    /**
     * Handle Amber login result
     */
    fun handleAmberLoginResult(resultCode: Int, data: android.content.Intent?) {
        Log.d("AccountStateViewModel", "🔐 Handling Amber login result - resultCode: $resultCode")
        amberSignerManager.handleLoginResult(resultCode, data)
    }

    /**
     * Logout from specific account
     */
    fun logoutAccount(accountInfo: AccountInfo) {
        viewModelScope.launch {
            Log.d("AccountStateViewModel", "👋 Logging out account: ${accountInfo.toShortNpub()}")

            // Remove from saved accounts
            val updatedAccounts = _savedAccounts.value.filter { it.npub != accountInfo.npub }
            saveSavedAccounts(updatedAccounts)

            // If this was the current account, switch to another or guest
            if (_currentAccount.value?.npub == accountInfo.npub) {
                if (updatedAccounts.isNotEmpty()) {
                    // Switch to most recently used account
                    switchToAccount(updatedAccounts.first())
                } else {
                    // No more accounts, go to guest mode
                    prefs.edit().remove(PREF_CURRENT_ACCOUNT).apply()
                    setGuestMode()
                }
            }
        }
    }

    /**
     * Logout current account
     */
    fun logoutCurrentAccount() {
        _currentAccount.value?.let { logoutAccount(it) }
    }

    /**
     * Get current account's npub
     */
    fun currentAccountNpub(): String? {
        return _currentAccount.value?.npub
    }

    /**
     * Check if user is authenticated
     */
    fun isAuthenticated(): Boolean {
        return _authState.value.isAuthenticated
    }

    /**
     * Clear any error messages
     */
    fun clearError() {
        _authState.value = _authState.value.copy(error = null)
    }
}

// Extension to convert hex string to byte array
private fun String.hexToByteArray(): ByteArray {
    val len = length
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i + 1], 16)).toByte()
        i += 2
    }
    return data
}
