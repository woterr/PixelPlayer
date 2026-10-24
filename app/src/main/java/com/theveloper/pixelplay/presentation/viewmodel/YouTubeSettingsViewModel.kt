package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.youtube.InnerTubeClient
import com.theveloper.pixelplay.data.youtube.YtConnection
import com.theveloper.pixelplay.data.youtube.YtMusicAuthManager
import com.theveloper.pixelplay.data.youtube.YtMusicPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class YouTubeSessionStatus(
    val checking: Boolean = false,
    val message: String? = null,
)

/**
 * State for the YouTube Music settings category: connection badge, playback
 * format preference and the home-mix switch.
 */
@HiltViewModel
class YouTubeSettingsViewModel @Inject constructor(
    private val authManager: YtMusicAuthManager,
    private val preferences: YtMusicPreferences,
    private val innerTube: InnerTubeClient,
) : ViewModel() {

    val connection: StateFlow<YtConnection> = authManager.connection

    val homeMixEnabled: StateFlow<Boolean> = preferences.homeMixEnabled.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        true,
    )

    val preferOpus: StateFlow<Boolean> = preferences.preferOpus.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        false,
    )

    private val _sessionStatus = MutableStateFlow(YouTubeSessionStatus())
    val sessionStatus: StateFlow<YouTubeSessionStatus> = _sessionStatus.asStateFlow()

    private val _switchAccountRequested = MutableStateFlow(false)
    val switchAccountRequested: StateFlow<Boolean> = _switchAccountRequested.asStateFlow()

    fun setHomeMixEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setHomeMixEnabled(enabled) }
    }

    fun setPreferOpus(enabled: Boolean) {
        viewModelScope.launch { preferences.setPreferOpus(enabled) }
    }

    fun refreshSession() {
        if (_sessionStatus.value.checking) return
        _sessionStatus.value = YouTubeSessionStatus(checking = true)
        viewModelScope.launch {
            authManager.refreshCookiesFromCookieManager()
            val identity = runCatching { innerTube.fetchConnectionIdentity() }.getOrNull()
            _sessionStatus.update {
                it.copy(
                    checking = false,
                    message = if (identity != null) {
                        "Session active — $identity"
                    } else {
                        "Session expired. Please reconnect your account."
                    },
                )
            }
        }
    }

    fun clearStatusMessage() {
        _sessionStatus.update { it.copy(message = null) }
    }

    fun signOut() {
        viewModelScope.launch {
            authManager.signOut()
            _sessionStatus.value = YouTubeSessionStatus()
        }
    }

    /**
     * Clears the current YouTube/Google session *before* the login screen is
     * shown, so its WebView cannot auto-capture the account being replaced.
     */
    fun switchAccount() {
        if (_switchAccountRequested.value) return
        viewModelScope.launch {
            authManager.signOut()
            _sessionStatus.value = YouTubeSessionStatus()
            _switchAccountRequested.value = true
        }
    }

    fun consumeSwitchAccountRequest() {
        _switchAccountRequested.value = false
    }
}
