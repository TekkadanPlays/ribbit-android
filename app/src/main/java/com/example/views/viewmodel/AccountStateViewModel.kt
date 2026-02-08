package com.example.views.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.views.auth.AmberSignerManager
import com.example.views.auth.AmberState
import com.example.views.data.AccountInfo
import com.example.views.repository.RelayStorageManager
import com.example.views.data.AuthState
import com.example.views.data.UserProfile
import com.example.views.data.GUEST_PROFILE
import com.example.views.data.Note
import com.example.views.repository.ContactListRepository
import com.example.views.repository.ProfileMetadataCache
import com.example.views.repository.ReactionsRepository
import com.example.views.repository.ZapStatePersistence
import com.example.views.repository.TopicsPublishService
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.bech32.bechToBytes
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.nip19Bech32.entities.NSec
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.hints.EventHintBundle
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip25Reactions.ReactionEvent
import com.example.views.relay.RelayConnectionStateMachine
import com.example.views.utils.normalizeAuthorIdForCache
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
    private val relayStorageManager = RelayStorageManager(application)

    // Current active account
    private val _currentAccount = MutableStateFlow<AccountInfo?>(null)
    val currentAccount: StateFlow<AccountInfo?> = _currentAccount.asStateFlow()

    // All saved accounts
    private val _savedAccounts = MutableStateFlow<List<AccountInfo>>(emptyList())
    val savedAccounts: StateFlow<List<AccountInfo>> = _savedAccounts.asStateFlow()

    // Auth state for UI
    private val _authState = MutableStateFlow(AuthState())
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    /** Exposes the Amber signer state so composables can register foreground launchers. */
    val amberState: StateFlow<AmberState> = amberSignerManager.state

    /** True after loadSavedAccounts() and restore/setGuestMode have run. Use to avoid showing sign-in during init. */
    private val _accountsRestored = MutableStateFlow(false)
    val accountsRestored: StateFlow<Boolean> = _accountsRestored.asStateFlow()

    /** In-memory private key bytes for nsec accounts (hexPubkey -> privKey bytes). Used for reactions when not using Amber. */
    private val nsecPrivKeyByHex = mutableMapOf<String, ByteArray>()

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

            // Try to restore last active account; set _accountsRestored only after current account is set
            val currentNpub = prefs.getString(PREF_CURRENT_ACCOUNT, null)
            if (currentNpub != null) {
                val account = _savedAccounts.value.find { it.npub == currentNpub }
                if (account != null) {
                    Log.d("AccountStateViewModel", "🔐 Restoring account: ${account.toShortNpub()}")
                    switchToAccount(account)
                    // _accountsRestored set at end of switchToAccount's launch block
                } else {
                    setGuestMode()
                    _accountsRestored.value = true
                }
            } else {
                setGuestMode()
                _accountsRestored.value = true
            }
        }

        // When current user's kind-0 is loaded, update auth state so header shows profile picture
        ProfileMetadataCache.getInstance().profileUpdated
            .onEach { pubkey ->
                val account = _currentAccount.value ?: return@onEach
                val hex = account.toHexKey() ?: return@onEach
                if (pubkey != hex) return@onEach
                val author = ProfileMetadataCache.getInstance().getAuthor(pubkey) ?: return@onEach
                _authState.value = _authState.value.copy(
                    userProfile = _authState.value.userProfile?.copy(
                        displayName = author.displayName,
                        picture = author.avatarUrl
                    )
                )
                val updatedAccount = account.copy(
                    displayName = author.displayName,
                    picture = author.avatarUrl
                )
                _currentAccount.value = updatedAccount
                val updatedList = _savedAccounts.value.map { if (it.npub == account.npub) updatedAccount else it }
                _savedAccounts.value = updatedList
                saveSavedAccounts(updatedList)
            }
            .launchIn(viewModelScope)

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
        _zappedNoteIds.value = emptySet()
        _zappedAmountByNoteId.value = emptyMap()
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

        restoreZapState(accountInfo.npub)
        ReactionsRepository.loadForAccount(getApplication(), accountInfo.npub)
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

                restoreZapState(updatedAccount.npub)
                ReactionsRepository.loadForAccount(getApplication(), updatedAccount.npub)
                Log.d("AccountStateViewModel", "✅ Switched to account: ${updatedAccount.getDisplayNameOrNpub()}")
            }
            _accountsRestored.value = true
        }
    }

    private fun restoreZapState(accountNpub: String) {
        _zappedNoteIds.value = ZapStatePersistence.loadZappedIds(getApplication(), accountNpub)
        _zappedAmountByNoteId.value = ZapStatePersistence.loadZappedAmounts(getApplication(), accountNpub)
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
     * Login with a raw key string. Supports:
     * - nsec1... (private key) -> full account with signing
     * - npub1... (public key) -> read-only account
     * - 64-char hex (assumed public key) -> read-only account
     *
     * Returns null on success, or an error message string.
     */
    fun loginWithKey(key: String): String? {
        val trimmed = key.trim()
        if (trimmed.isBlank()) return "Key cannot be empty"

        try {
            when {
                trimmed.startsWith("nsec1") -> {
                    val parsed = Nip19Parser.uriToRoute(trimmed)
                    val nsecEntity = parsed?.entity as? NSec
                        ?: return "Invalid nsec key"
                    val hexPubkey = nsecEntity.toPubKeyHex()
                    val privKeyBytes = try {
                        trimmed.bechToBytes()
                    } catch (e: Exception) {
                        Log.e("AccountStateViewModel", "nsec bechToBytes failed", e)
                        return "Invalid nsec key"
                    }
                    nsecPrivKeyByHex[hexPubkey] = privKeyBytes
                    val npub = hexPubkey.hexToByteArray().toNpub()
                    createAndActivateAccount(
                        npub = npub,
                        hexPubkey = hexPubkey,
                        hasPrivateKey = true,
                        isExternalSigner = false
                    )
                    return null
                }
                trimmed.startsWith("npub1") -> {
                    val parsed = Nip19Parser.uriToRoute(trimmed)
                    val npubEntity = parsed?.entity as? NPub
                        ?: return "Invalid npub key"
                    val hexPubkey = npubEntity.hex
                    createAndActivateAccount(
                        npub = trimmed,
                        hexPubkey = hexPubkey,
                        hasPrivateKey = false,
                        isExternalSigner = false
                    )
                    return null
                }
                trimmed.length == 64 && trimmed.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } -> {
                    // Treat 64-char hex as pubkey (read-only)
                    val npub = trimmed.lowercase().hexToByteArray().toNpub()
                    createAndActivateAccount(
                        npub = npub,
                        hexPubkey = trimmed.lowercase(),
                        hasPrivateKey = false,
                        isExternalSigner = false
                    )
                    return null
                }
                else -> return "Unrecognized key format. Use nsec1... or npub1..."
            }
        } catch (e: Exception) {
            Log.e("AccountStateViewModel", "loginWithKey failed: ${e.message}", e)
            return "Login failed: ${e.message}"
        }
    }

    private fun createAndActivateAccount(
        npub: String,
        hexPubkey: String,
        hasPrivateKey: Boolean,
        isExternalSigner: Boolean
    ) {
        viewModelScope.launch {
            val existing = _savedAccounts.value.find { it.npub == npub }
            val accountInfo = existing?.copy(
                lastUsed = System.currentTimeMillis(),
                hasPrivateKey = hasPrivateKey || existing.hasPrivateKey,
                isExternalSigner = isExternalSigner
            ) ?: AccountInfo(
                npub = npub,
                hasPrivateKey = hasPrivateKey,
                isExternalSigner = isExternalSigner,
                isTransient = false,
                displayName = "Nostr User",
                picture = null,
                lastUsed = System.currentTimeMillis()
            )

            val updatedAccounts = _savedAccounts.value
                .filter { it.npub != npub }
                .plus(accountInfo)
            saveSavedAccounts(updatedAccounts)

            prefs.edit()
                .putString(PREF_CURRENT_ACCOUNT, npub)
                .apply()

            _currentAccount.value = accountInfo

            val aboutText = when {
                hasPrivateKey -> "Signed in with private key"
                isExternalSigner -> "Signed in with Amber Signer"
                else -> "Read-only mode"
            }

            val userProfile = UserProfile(
                pubkey = hexPubkey,
                displayName = accountInfo.displayName ?: "Nostr User",
                name = hexPubkey.take(8),
                picture = accountInfo.picture,
                about = aboutText,
                createdAt = System.currentTimeMillis()
            )

            _authState.value = AuthState(
                isAuthenticated = true,
                isGuest = false,
                userProfile = userProfile,
                isLoading = false,
                error = null
            )
            _accountsRestored.value = true

            Log.d("AccountStateViewModel", "Account activated via key login: ${accountInfo.toShortNpub()}")
        }
    }

    /** Flow for one-shot error/success messages from async operations (reactions, follows, etc.) */
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()
    fun clearToast() { _toastMessage.value = null }

    /** Note IDs currently sending a zap (for loading indicator). */
    private val _zapInProgressNoteIds = MutableStateFlow<Set<String>>(emptySet())
    val zapInProgressNoteIds: StateFlow<Set<String>> = _zapInProgressNoteIds.asStateFlow()

    /** Note IDs the current user has zapped (bolt turns yellow). */
    private val _zappedNoteIds = MutableStateFlow<Set<String>>(emptySet())
    val zappedNoteIds: StateFlow<Set<String>> = _zappedNoteIds.asStateFlow()

    /** Amount (sats) the current user zapped per note ID; for showing "You zapped X sats". */
    private val _zappedAmountByNoteId = MutableStateFlow<Map<String, Long>>(emptyMap())
    val zappedAmountByNoteId: StateFlow<Map<String, Long>> = _zappedAmountByNoteId.asStateFlow()

    /**
     * Send a NIP-25 reaction (kind-7) using the external signer (Amber).
     * Returns null on success, or an error message for synchronous failures.
     * Async failures are emitted via [toastMessage].
     */
    fun sendReaction(note: Note, emoji: String): String? {
        val account = _currentAccount.value ?: return "Sign in to react"
        val accountHex = account.toHexKey() ?: return "Invalid account key"
        val signer = when {
            account.isExternalSigner -> (amberSignerManager.state.value as? AmberState.LoggedIn)?.signer
            account.hasPrivateKey -> {
                val privKeyBytes = nsecPrivKeyByHex[accountHex]
                if (privKeyBytes != null) NostrSignerInternal(KeyPair(privKey = privKeyBytes)) else null
            }
            else -> null
        } ?: return when {
            account.isExternalSigner -> "Amber signer not available"
            account.hasPrivateKey -> "Private key not available. Sign in again with nsec."
            else -> "Sign in with nsec or Amber to react"
        }

        val relaySet = relayStorageManager.loadOutboxRelays(accountHex)
            .mapNotNull { RelayUrlNormalizer.normalizeOrNull(it.url) }
            .toSet()
        if (relaySet.isEmpty()) return "No outbox relays configured"

        Log.d("AccountStateViewModel", "sendReaction: emoji=$emoji, noteId=${note.id.take(8)}, relays=${relaySet.size}")

        val targetPubkey = normalizeAuthorIdForCache(note.author.id)
        val targetEvent = Event(
            id = note.id,
            pubKey = targetPubkey,
            createdAt = (note.timestamp / 1000),
            kind = 1,
            tags = emptyArray(),
            content = note.content,
            sig = ""
        )
        val relayHint = note.relayUrl?.let { RelayUrlNormalizer.normalizeOrNull(it) }
        val hintBundle = EventHintBundle(targetEvent, relayHint)
        val template = ReactionEvent.build(emoji, hintBundle)

        Log.d("AccountStateViewModel", "sendReaction: template kind=${template.kind}, tags=${template.tags.size}")

        viewModelScope.launch {
            try {
                Log.d("AccountStateViewModel", "sendReaction: signing...")
                val signed = signer.sign(template)
                Log.d("AccountStateViewModel", "sendReaction: signed! id=${signed.id.take(8)}, kind=${signed.kind}, sig=${signed.sig.take(8)}...")

                // Validate the signed event
                if (signed.sig.isBlank()) {
                    Log.e("AccountStateViewModel", "sendReaction: signed event has empty sig!")
                    _toastMessage.value = "Reaction signing failed (empty signature)"
                    return@launch
                }

                Log.d("AccountStateViewModel", "sendReaction: sending to ${relaySet.size} outbox relays")
                RelayConnectionStateMachine.getInstance().nostrClient.send(signed, relaySet)
                Log.d("AccountStateViewModel", "sendReaction: sent successfully")

                ReactionsRepository.setLastReaction(note.id, emoji)
                ReactionsRepository.persist(getApplication(), account.npub)
                ReactionsRepository.recordEmoji(getApplication(), account.npub, emoji)
            } catch (e: Exception) {
                Log.e("AccountStateViewModel", "sendReaction failed: ${e.message}", e)
                _toastMessage.value = "Reaction failed: ${e.message?.take(60)}"
            }
        }
        return null
    }

    /**
     * Publish a Kind 11 topic (title, content, hashtags). Signs with Amber and sends to outbox relays.
     * Returns null on success, or an error message.
     */
    fun publishTopic(title: String, content: String, hashtags: List<String>): String? {
        val account = _currentAccount.value ?: return "Sign in to publish"
        val amber = amberSignerManager.state.value
        val signer = (amber as? AmberState.LoggedIn)?.signer ?: return "Amber signer not available"
        val accountHex = account.toHexKey() ?: return "Invalid account key"
        val relaySet = relayStorageManager.loadOutboxRelays(accountHex)
            .mapNotNull { RelayUrlNormalizer.normalizeOrNull(it.url) }
            .toSet()
        if (relaySet.isEmpty()) return "No outbox relays configured"
        viewModelScope.launch {
            try {
                val template = TopicsPublishService.buildTopicEventTemplate(title, content, hashtags)
                val signed = signer.sign(template)
                if (signed.sig.isBlank()) {
                    _toastMessage.value = "Topic signing failed"
                    return@launch
                }
                RelayConnectionStateMachine.getInstance().nostrClient.send(signed, relaySet)
                Log.d("AccountStateViewModel", "Topic published: ${signed.id.take(8)}")
            } catch (e: Exception) {
                Log.e("AccountStateViewModel", "publishTopic failed: ${e.message}", e)
                _toastMessage.value = "Topic failed: ${e.message?.take(60)}"
            }
        }
        return null
    }

    /**
     * Publish a Kind 1111 thread reply. Signs with Amber and sends to outbox relays.
     * rootThreadId/rootThreadPubkey = the Kind 11 topic; parentReplyId/parentReplyPubkey = optional parent reply (Kind 1111).
     */
    fun publishThreadReply(
        rootThreadId: String,
        rootThreadPubkey: String,
        parentReplyId: String?,
        parentReplyPubkey: String?,
        content: String
    ): String? {
        val account = _currentAccount.value ?: return "Sign in to reply"
        val amber = amberSignerManager.state.value
        val signer = (amber as? AmberState.LoggedIn)?.signer ?: return "Amber signer not available"
        val accountHex = account.toHexKey() ?: return "Invalid account key"
        val relaySet = relayStorageManager.loadOutboxRelays(accountHex)
            .mapNotNull { RelayUrlNormalizer.normalizeOrNull(it.url) }
            .toSet()
        if (relaySet.isEmpty()) return "No outbox relays configured"
        viewModelScope.launch {
            try {
                val template = TopicsPublishService.buildThreadReplyEventTemplate(
                    rootThreadId, rootThreadPubkey, parentReplyId, parentReplyPubkey, content
                )
                val signed = signer.sign(template)
                if (signed.sig.isBlank()) {
                    _toastMessage.value = "Reply signing failed"
                    return@launch
                }
                RelayConnectionStateMachine.getInstance().nostrClient.send(signed, relaySet)
                Log.d("AccountStateViewModel", "Thread reply published: ${signed.id.take(8)}")
            } catch (e: Exception) {
                Log.e("AccountStateViewModel", "publishThreadReply failed: ${e.message}", e)
                _toastMessage.value = "Reply failed: ${e.message?.take(60)}"
            }
        }
        return null
    }

    /**
     * Follow a user (publish updated kind-3 contact list via Amber).
     * Returns null on success, or an error message.
     */
    fun followUser(targetPubkey: String): String? {
        val account = _currentAccount.value ?: return "Sign in to follow"
        val amber = amberSignerManager.state.value
        val signer = (amber as? AmberState.LoggedIn)?.signer ?: return "Amber signer not available"
        val accountHex = account.toHexKey() ?: return "Invalid account key"

        val outboxRelays = relayStorageManager.loadOutboxRelays(accountHex)
            .mapNotNull { com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer.normalizeOrNull(it.url) }
            .toSet()
        if (outboxRelays.isEmpty()) return "No outbox relays configured"

        val cacheRelayUrls = relayStorageManager.loadCacheRelays(accountHex).map { it.url }

        viewModelScope.launch {
            val error = ContactListRepository.follow(
                myPubkey = accountHex,
                targetPubkey = targetPubkey,
                signer = signer,
                outboxRelays = outboxRelays,
                cacheRelayUrls = cacheRelayUrls
            )
            if (error != null) {
                Log.e("AccountStateViewModel", "followUser failed: $error")
            } else {
                Log.d("AccountStateViewModel", "Followed ${targetPubkey.take(8)}...")
            }
        }
        return null
    }

    /**
     * Unfollow a user (publish updated kind-3 contact list via Amber).
     * Returns null on success, or an error message.
     */
    fun unfollowUser(targetPubkey: String): String? {
        val account = _currentAccount.value ?: return "Sign in to unfollow"
        val amber = amberSignerManager.state.value
        val signer = (amber as? AmberState.LoggedIn)?.signer ?: return "Amber signer not available"
        val accountHex = account.toHexKey() ?: return "Invalid account key"

        val outboxRelays = relayStorageManager.loadOutboxRelays(accountHex)
            .mapNotNull { com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer.normalizeOrNull(it.url) }
            .toSet()
        if (outboxRelays.isEmpty()) return "No outbox relays configured"

        val cacheRelayUrls = relayStorageManager.loadCacheRelays(accountHex).map { it.url }

        viewModelScope.launch {
            val error = ContactListRepository.unfollow(
                myPubkey = accountHex,
                targetPubkey = targetPubkey,
                signer = signer,
                outboxRelays = outboxRelays,
                cacheRelayUrls = cacheRelayUrls
            )
            if (error != null) {
                Log.e("AccountStateViewModel", "unfollowUser failed: $error")
            } else {
                Log.d("AccountStateViewModel", "Unfollowed ${targetPubkey.take(8)}...")
            }
        }
        return null
    }

    /**
     * Send a zap on a note via NWC (NIP-57 + NIP-47).
     * Progress and errors are emitted via [toastMessage].
     */
    fun sendZap(
        note: Note,
        amountSats: Long,
        zapType: com.example.views.repository.ZapType,
        message: String = ""
    ): String? {
        val account = _currentAccount.value ?: return "Sign in to zap"
        val amber = amberSignerManager.state.value
        val signer = (amber as? AmberState.LoggedIn)?.signer ?: return "Amber signer not available"
        val accountHex = account.toHexKey() ?: return "Invalid account key"
        if (!com.example.views.services.NwcPaymentManager.isConfigured(getApplication())) {
            return "Please connect NWC"
        }

        val outboxRelays = relayStorageManager.loadOutboxRelays(accountHex)
            .mapNotNull { com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer.normalizeOrNull(it.url) }
            .toSet()

        val noteId = note.id
        val accountNpub = account.npub
        _zapInProgressNoteIds.value = _zapInProgressNoteIds.value + noteId
        viewModelScope.launch {
            try {
                com.example.views.services.ZapPaymentHandler.zap(
                    context = getApplication(),
                    note = note,
                    amountSats = amountSats,
                    zapType = zapType,
                    message = message,
                    signer = signer,
                    outboxRelayUrls = outboxRelays,
                    onProgress = { progress ->
                        when (progress) {
                            is com.example.views.services.ZapProgress.InProgress -> {
                                Log.d("AccountStateViewModel", "Zap progress: ${progress.step}")
                            }
                            is com.example.views.services.ZapProgress.Success -> {
                                _zappedNoteIds.value = _zappedNoteIds.value + noteId
                                _zappedAmountByNoteId.value = _zappedAmountByNoteId.value + (noteId to amountSats)
                                ZapStatePersistence.saveZappedIds(getApplication(), accountNpub, _zappedNoteIds.value)
                                ZapStatePersistence.saveZappedAmounts(getApplication(), accountNpub, _zappedAmountByNoteId.value)
                                viewModelScope.launch(Dispatchers.Main.immediate) {
                                    _toastMessage.value = "Zap sent!"
                                }
                            }
                            is com.example.views.services.ZapProgress.Failed -> {
                                viewModelScope.launch(Dispatchers.Main.immediate) {
                                    _toastMessage.value = "Zap failed: ${progress.message}"
                                }
                            }
                            is com.example.views.services.ZapProgress.Idle -> {}
                        }
                    }
                )
            } finally {
                _zapInProgressNoteIds.value = _zapInProgressNoteIds.value - noteId
            }
        }
        return null
    }

    /**
     * Logout from specific account
     */
    fun logoutAccount(accountInfo: AccountInfo) {
        viewModelScope.launch {
            Log.d("AccountStateViewModel", "👋 Logging out account: ${accountInfo.toShortNpub()}")

            // Clear all relay data for this account
            val pubkey = accountInfo.toHexKey()
            if (pubkey != null) {
                relayStorageManager.clearUserData(pubkey)
                Log.d("AccountStateViewModel", "🗑️ Cleared relay data for account: ${accountInfo.toShortNpub()}")
            }

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

                    // Clear guest relay data as well when switching to guest mode
                    relayStorageManager.clearUserData("guest")
                    Log.d("AccountStateViewModel", "🗑️ Cleared guest relay data")

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
