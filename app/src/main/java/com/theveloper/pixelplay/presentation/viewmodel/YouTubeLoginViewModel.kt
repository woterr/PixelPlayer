package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.youtube.InnerTubeClient
import com.theveloper.pixelplay.data.youtube.YoutubeAccountInfo
import com.theveloper.pixelplay.data.youtube.YtConnection
import com.theveloper.pixelplay.data.youtube.YtMusicAuthManager
import com.theveloper.pixelplay.data.youtube.YtMusicPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class YouTubeLoginUiState(
    val verifying: Boolean = false,
    val connectedName: String? = null,
    val errorMessage: String? = null,
)

/**
 * Drives the in-app YouTube Music sign-in flow. The WebView writes Google's
 * session cookies into the system CookieManager; this holder persists them
 * (via [YtMusicAuthManager]) and resolves the account identity for the badge.
 */
@HiltViewModel
class YouTubeLoginViewModel @Inject constructor(
    private val authManager: YtMusicAuthManager,
    private val innerTube: InnerTubeClient,
    private val preferences: YtMusicPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(YouTubeLoginUiState())
    val uiState: StateFlow<YouTubeLoginUiState> = _uiState.asStateFlow()

    val connection: StateFlow<YtConnection> = authManager.connection

    fun attemptConnect(rawCookieHeader: String?) {
        if (_uiState.value.verifying) return
        val raw = rawCookieHeader.orEmpty()
        if (raw.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = "Sign-in incomplete — finish signing in, then tap \"I'm signed in\".")
            }
            return
        }
        val normalized = try {
            com.theveloper.pixelplay.data.youtube.CookieNormalizer.normalize(raw)
        } catch (error: Exception) {
            _uiState.update {
                it.copy(errorMessage = error.message ?: "Invalid cookies")
            }
            return
        }
        attemptConnectNormalized(normalized)
    }

    /** Connects cookies pasted as a header, cookies.txt or JSON export. */
    fun attemptCookieConnect(rawText: String) {
        if (_uiState.value.verifying) return
        val normalized = try {
            com.theveloper.pixelplay.data.youtube.CookieNormalizer.normalize(rawText)
        } catch (error: Exception) {
            _uiState.update {
                it.copy(errorMessage = error.message ?: "Invalid cookies")
            }
            return
        }
        attemptConnectNormalized(normalized)
    }

    private fun attemptConnectNormalized(cookieHeader: String) {
        if (_uiState.value.verifying) return
        _uiState.update { it.copy(verifying = true, errorMessage = null) }
        viewModelScope.launch {
            val previous = authManager.connection.value
            try {
                authManager.connect(cookieHeader, "")
                if (!authManager.connection.value.isConnected) {
                    throw IllegalStateException(
                        "Those cookies don't contain a YouTube session. Sign in first, then try again."
                    )
                }
                val info = resolveAccountInfo()
                val displayName = info?.accountName?.takeIf { it.isNotBlank() } ?: "Google account"
                preferences.saveConnection(
                    cookies = authManager.currentConnection().cookies,
                    accountName = displayName,
                    channelHandle = info?.channelHandle,
                    photoUrl = info?.photoUrl,
                )
                _uiState.update { it.copy(verifying = false, connectedName = displayName) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                runCatching {
                    if (previous.isConnected) {
                        preferences.saveConnection(
                            cookies = previous.cookies,
                            accountName = previous.accountName,
                            channelHandle = previous.channelHandle,
                            photoUrl = previous.photoUrl,
                        )
                    } else {
                        authManager.signOut()
                    }
                }
                _uiState.update {
                    it.copy(
                        verifying = false,
                        errorMessage = "Couldn't finish connecting: ${error.localizedMessage ?: "unknown error"}",
                    )
                }
            }
        }
    }

    /**
     * The account menu sometimes needs a couple of seconds after the OAuth
     * redirect chain settles. Retries are best-effort: a failed identity
     * lookup never rejects a session whose cookies are valid.
     */
    private suspend fun resolveAccountInfo(): YoutubeAccountInfo? {
        repeat(3) { attempt ->
            if (attempt > 0) kotlinx.coroutines.delay(1_200L * attempt)
            runCatching { android.webkit.CookieManager.getInstance().flush() }
            authManager.refreshCookiesFromCookieManager()
            val info = runCatching { innerTube.fetchAccountInfo() }.getOrNull()
            if (info != null) return info
        }
        return null
    }

    fun dismissError() = _uiState.update { it.copy(errorMessage = null) }
}
