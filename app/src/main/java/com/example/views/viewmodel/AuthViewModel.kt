package com.example.views.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.views.auth.AmberSignerManager
import com.example.views.auth.AmberState
import com.example.views.data.AuthState
import com.example.views.data.UserProfile
import com.example.views.data.GUEST_PROFILE
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val amberSignerManager = AmberSignerManager(application)

    private val _authState = MutableStateFlow(AuthState())
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        // Start with guest mode
        _authState.value = AuthState(
            isAuthenticated = false,
            isGuest = true,
            userProfile = GUEST_PROFILE,
            isLoading = false
        )

        Log.d("AuthViewModel", "🔐 Initialized with guest mode")

        // Observe Amber state changes
        viewModelScope.launch {
            amberSignerManager.state.collect { amberState ->
                Log.d("AuthViewModel", "🔐 Amber state changed: $amberState")

                val newState = when (amberState) {
                    is AmberState.NotInstalled -> {
                        AuthState(
                            isAuthenticated = false,
                            isGuest = true,
                            userProfile = GUEST_PROFILE,
                            error = "Amber Signer not installed"
                        )
                    }
                    is AmberState.NotLoggedIn -> {
                        AuthState(
                            isAuthenticated = false,
                            isGuest = true,
                            userProfile = GUEST_PROFILE,
                            error = null
                        )
                    }
                    is AmberState.LoggingIn -> {
                        _authState.value.copy(
                            isLoading = true,
                            error = null
                        )
                    }
                    is AmberState.LoggedIn -> {
                        Log.d("AuthViewModel", "✅ User logged in with pubkey: ${amberState.pubKey.take(16)}...")

                        val userProfile = UserProfile(
                            pubkey = amberState.pubKey,
                            displayName = "Nostr User",
                            name = amberState.pubKey.take(8),
                            about = "Signed in with Amber Signer",
                            createdAt = System.currentTimeMillis()
                        )

                        AuthState(
                            isAuthenticated = true,
                            isGuest = false,
                            userProfile = userProfile,
                            isLoading = false,
                            error = null
                        )
                    }
                    is AmberState.Error -> {
                        AuthState(
                            isAuthenticated = false,
                            isGuest = true,
                            userProfile = GUEST_PROFILE,
                            isLoading = false,
                            error = amberState.message
                        )
                    }
                }

                _authState.value = newState
                Log.d("AuthViewModel", "🔐 Auth state updated - isAuthenticated: ${newState.isAuthenticated}, isGuest: ${newState.isGuest}")
            }
        }
    }

    fun loginWithAmber(): android.content.Intent {
        return amberSignerManager.createLoginIntent()
    }

    fun handleLoginResult(resultCode: Int, data: android.content.Intent?) {
        Log.d("AuthViewModel", "🔐 Handling login result - resultCode: $resultCode")
        amberSignerManager.handleLoginResult(resultCode, data)
    }

    fun logout() {
        amberSignerManager.logout()
        _authState.value = AuthState(
            isAuthenticated = false,
            isGuest = true,
            userProfile = GUEST_PROFILE,
            isLoading = false,
            error = null
        )
    }

    fun clearError() {
        _authState.value = _authState.value.copy(error = null)
    }

    fun getCurrentSigner() = amberSignerManager.getCurrentSigner()
    fun getCurrentPubKey() = amberSignerManager.getCurrentPubKey()
}
