package com.example.views.viewmodel

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.views.data.Author
import com.example.views.data.Note
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AppState(
    val currentScreen: String = "dashboard",
    val isSearchMode: Boolean = false,
    val selectedAuthor: Author? = null,
    val selectedNote: Note? = null,
    val previousScreen: String? = null,
    val threadSourceScreen: String? = null,
    val threadScrollPosition: Int = 0,
    val threadExpandedComments: Set<String> = emptySet(),
    val threadExpandedControls: String? = null,
    val feedScrollPosition: Int = 0,
    val profileScrollPosition: Int = 0,
    val userProfileScrollPosition: Int = 0,
    val backPressCount: Int = 0,
    val showExitSnackbar: Boolean = false,
    val isExitWindowActive: Boolean = false
)

class AppViewModel : ViewModel() {
    private val _appState = MutableStateFlow(AppState())
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    fun updateCurrentScreen(screen: String) {
        _appState.value = _appState.value.copy(currentScreen = screen)
    }

    fun updateSearchMode(isSearchMode: Boolean) {
        _appState.value = _appState.value.copy(isSearchMode = isSearchMode)
    }

    fun updateSelectedAuthor(author: Author?) {
        _appState.value = _appState.value.copy(selectedAuthor = author)
    }

    fun updateSelectedNote(note: Note?) {
        _appState.value = _appState.value.copy(selectedNote = note)
    }

    fun updatePreviousScreen(screen: String?) {
        _appState.value = _appState.value.copy(previousScreen = screen)
    }

    fun updateThreadSourceScreen(screen: String?) {
        _appState.value = _appState.value.copy(threadSourceScreen = screen)
    }

    fun updateThreadScrollPosition(position: Int) {
        _appState.value = _appState.value.copy(threadScrollPosition = position)
    }

    fun updateThreadExpandedComments(comments: Set<String>) {
        _appState.value = _appState.value.copy(threadExpandedComments = comments)
    }

    fun updateThreadExpandedControls(commentId: String?) {
        _appState.value = _appState.value.copy(threadExpandedControls = commentId)
    }

    fun updateFeedScrollPosition(position: Int) {
        _appState.value = _appState.value.copy(feedScrollPosition = position)
    }

    fun updateProfileScrollPosition(position: Int) {
        _appState.value = _appState.value.copy(profileScrollPosition = position)
    }

    fun updateUserProfileScrollPosition(position: Int) {
        _appState.value = _appState.value.copy(userProfileScrollPosition = position)
    }

    fun updateBackPressCount(count: Int) {
        _appState.value = _appState.value.copy(backPressCount = count)
    }

    fun updateShowExitSnackbar(show: Boolean) {
        _appState.value = _appState.value.copy(showExitSnackbar = show)
    }

    fun updateExitWindowActive(isActive: Boolean) {
        _appState.value = _appState.value.copy(isExitWindowActive = isActive)
    }

    fun resetToDashboard() {
        _appState.value = AppState()
    }

    fun handleAppExit(): Boolean {
        val currentState = _appState.value
        return if (currentState.currentScreen == "dashboard") {
            if (!currentState.isExitWindowActive) {
                // First back press - show snackbar and start 3-second window
                updateBackPressCount(1)
                updateShowExitSnackbar(true)
                updateExitWindowActive(true)
                
                // Start 3-second timeout to reset exit window
                viewModelScope.launch {
                    kotlinx.coroutines.delay(3000) // 3 seconds
                    updateExitWindowActive(false)
                    updateBackPressCount(0)
                    updateShowExitSnackbar(false) // Dismiss snackbar when window expires
                }
                
                false // Don't exit yet
            } else {
                // Second back press within 3-second window - exit
                true
            }
        } else {
            // Not on dashboard - handle normal navigation
            navigateBack()
            false
        }
    }

    fun navigateBack(): String {
        val currentState = _appState.value
        return when {
            currentState.isSearchMode -> {
                updateSearchMode(false)
                "search_mode_handled"
            }
            currentState.currentScreen == "about" && currentState.previousScreen == "settings" -> {
                updateCurrentScreen("settings")
                updatePreviousScreen(null)
                "settings"
            }
            currentState.currentScreen == "appearance" -> {
                updateCurrentScreen("settings")
                "settings"
            }
            currentState.currentScreen == "thread" -> {
                val sourceScreen = currentState.threadSourceScreen ?: "dashboard"
                updateCurrentScreen(sourceScreen)
                updateThreadSourceScreen(null)
                sourceScreen
            }
            currentState.currentScreen == "profile" -> {
                if (currentState.threadSourceScreen == "thread") {
                    updateCurrentScreen("thread")
                    updateThreadSourceScreen(null)
                    "thread"
                } else {
                    updateCurrentScreen("dashboard")
                    "dashboard"
                }
            }
            currentState.currentScreen == "user_profile" -> {
                updateCurrentScreen("dashboard")
                "dashboard"
            }
            currentState.currentScreen == "settings" -> {
                updateCurrentScreen("dashboard")
                "dashboard"
            }
            else -> {
                updateCurrentScreen("dashboard")
                "dashboard"
            }
        }
    }
}
