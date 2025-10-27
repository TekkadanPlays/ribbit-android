package com.example.views.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.views.data.UserRelay
import com.example.views.data.RelayConnectionStatus
import com.example.views.repository.RelayRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class RelayManagementUiState(
    val relays: List<UserRelay> = emptyList(),
    val connectionStatus: Map<String, RelayConnectionStatus> = emptyMap(),
    val showAddRelayDialog: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class RelayManagementViewModel(
    private val relayRepository: RelayRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(RelayManagementUiState())
    val uiState: StateFlow<RelayManagementUiState> = _uiState.asStateFlow()
    
    init {
        // Collect relay updates from repository
        viewModelScope.launch {
            relayRepository.relays.collect { relays ->
                _uiState.value = _uiState.value.copy(relays = relays)
            }
        }
        
        // Collect connection status updates from repository
        viewModelScope.launch {
            relayRepository.connectionStatus.collect { connectionStatus ->
                _uiState.value = _uiState.value.copy(connectionStatus = connectionStatus)
            }
        }
    }
    
    fun showAddRelayDialog() {
        _uiState.value = _uiState.value.copy(showAddRelayDialog = true)
    }
    
    fun hideAddRelayDialog() {
        _uiState.value = _uiState.value.copy(showAddRelayDialog = false, errorMessage = null)
    }
    
    fun addRelay(url: String, read: Boolean, write: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            
            relayRepository.addRelay(url, read, write)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        showAddRelayDialog = false
                    )
                }
                .onFailure { exception ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = exception.message ?: "Failed to add relay"
                    )
                }
        }
    }
    
    fun removeRelay(url: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            relayRepository.removeRelay(url)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
                .onFailure { exception ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = exception.message ?: "Failed to remove relay"
                    )
                }
        }
    }
    
    fun refreshRelayInfo(url: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            relayRepository.refreshRelayInfo(url)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
                .onFailure { exception ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = exception.message ?: "Failed to refresh relay info"
                    )
                }
        }
    }
    
    fun testRelayConnection(url: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            relayRepository.testRelayConnection(url)
                .onSuccess { isConnected ->
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    // Connection status will be updated via the repository's StateFlow
                }
                .onFailure { exception ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = exception.message ?: "Failed to test connection"
                    )
                }
        }
    }
    
    fun updateRelaySettings(url: String, read: Boolean, write: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            relayRepository.updateRelaySettings(url, read, write)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
                .onFailure { exception ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = exception.message ?: "Failed to update relay settings"
                    )
                }
        }
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}
