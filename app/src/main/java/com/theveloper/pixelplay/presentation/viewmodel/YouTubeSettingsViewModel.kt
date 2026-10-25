package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.addon.AddonClient
import com.theveloper.pixelplay.data.preferences.AddonStatus
import com.theveloper.pixelplay.data.preferences.StreamQualityPreferences
import com.theveloper.pixelplay.data.preferences.StreamQualitySettings
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
import kotlinx.coroutines.flow.combine
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
    private val streamQualityPreferences: StreamQualityPreferences,
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

    /** Streaming quality + lossless addon state, for the settings section. */
    val streamQualitySettings: StateFlow<StreamQualitySettings> = combine(
        streamQualityPreferences.streamingQuality,
        streamQualityPreferences.preferLosslessStreaming,
        streamQualityPreferences.downloadQuality,
        streamQualityPreferences.addonUrl,
        streamQualityPreferences.addonEnabled,
        streamQualityPreferences.addons,
    ) { values ->
        StreamQualitySettings(
            streamingQuality = values[0] as Int,
            preferLosslessStreaming = values[1] as Boolean,
            downloadQuality = values[2] as Int,
            addonUrl = values[3] as String,
            addonEnabled = values[4] as Boolean,
            addons = values[5] as List<com.theveloper.pixelplay.data.preferences.HttpAddonConfig>,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        StreamQualitySettings(),
    )

    private val _addonStatus = MutableStateFlow<AddonStatus>(AddonStatus.Idle)
    val addonStatus: StateFlow<AddonStatus> = _addonStatus.asStateFlow()

    private val _sessionStatus = MutableStateFlow(YouTubeSessionStatus())
    val sessionStatus: StateFlow<YouTubeSessionStatus> = _sessionStatus.asStateFlow()

    private val _switchAccountRequested = MutableStateFlow(false)
    val switchAccountRequested: StateFlow<Boolean> = _switchAccountRequested.asStateFlow()

    fun setStreamingQuality(quality: Int) {
        viewModelScope.launch { streamQualityPreferences.setStreamingQuality(quality) }
    }

    fun setDownloadQuality(quality: Int) {
        viewModelScope.launch { streamQualityPreferences.setDownloadQuality(quality) }
    }

    fun setPreferLosslessStreaming(enabled: Boolean) {
        viewModelScope.launch { streamQualityPreferences.setPreferLosslessStreaming(enabled) }
    }

    fun setAddonUrl(url: String) {
        viewModelScope.launch { streamQualityPreferences.setAddonUrl(url) }
    }

    fun setAddonEnabled(enabled: Boolean) {
        viewModelScope.launch { streamQualityPreferences.setAddonEnabled(enabled) }
    }

    /**
     * Probes the addon the way playback will: manifest first, search fallback.
     * Runs against the given URL (the dialog's draft), not just the saved one.
     */
    fun checkAddonHealth(url: String) {
        if (_addonStatus.value == AddonStatus.Checking) return
        _addonStatus.value = AddonStatus.Checking
        viewModelScope.launch {
            val status = runCatching { AddonClient(url).health() }
                .getOrElse { AddonStatus.Failed(it.message ?: "Connection failed") }
            _addonStatus.value = when (status) {
                is AddonStatus.Ok -> status
                is AddonStatus.Failed -> status
                else -> AddonStatus.Failed("Unexpected status")
            }
        }
    }

    fun resetAddonStatus() {
        _addonStatus.value = AddonStatus.Idle
    }

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
