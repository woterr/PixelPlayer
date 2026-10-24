package com.theveloper.pixelplay.presentation.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.components.CollapsibleCommonTopBar
import com.theveloper.pixelplay.presentation.viewmodel.YouTubeLoginViewModel

/**
 * In-app Google/YouTube Music sign-in.
 *
 * The WebView is real: Google's own login page writes session cookies into the
 * app-wide CookieManager, which [YouTubeLoginViewModel] then persists. Nothing
 * is scraped from the page and no password is ever read by the app.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeLoginScreen(
    onBackClick: () -> Unit,
    onDone: () -> Unit,
    viewModel: YouTubeLoginViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    var showCookieDialog by remember { mutableStateOf(false) }
    var webViewError by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    val connectedName = uiState.connectedName
        ?: connection.accountName.takeIf { connection.isConnected }

    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val headerHeight = statusBarHeight + 64.dp

    fun captureSession(force: Boolean = false) {
        val state = viewModel.uiState.value
        if (!force && (state.verifying || state.connectedName != null)) return
        val cookies = readYouTubeCookieHeader()
        if (cookies != null && hasYouTubeSessionTokens(cookies)) {
            viewModel.attemptConnect(cookies)
        } else if (force) {
            // Manual "I'm signed in": surface the instruction instead of
            // leaving the tap without feedback.
            viewModel.attemptConnect(cookies)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webView?.stopLoading()
                webView?.destroy()
            }
            webView = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(top = headerHeight)
        ) {
            when {
                connectedName != null -> {
                    YouTubeConnectedContent(
                        accountName = connectedName,
                        onDone = onDone,
                    )
                }
                webViewError != null -> {
                    YouTubeLoginErrorContent(
                        message = webViewError.orEmpty(),
                        onRetry = {
                            webViewError = null
                            webView = null
                        },
                        onPasteCookies = { showCookieDialog = true },
                    )
                }
                else -> {
                    Box(modifier = Modifier.weight(1f)) {
                        YouTubeSignInWebView(
                            onCreated = { webView = it },
                            onFailure = { message ->
                                webViewError = message
                                webView = null
                            },
                            onPageVisited = { url ->
                                if (url?.contains("music.youtube.com") == true) {
                                    captureSession()
                                }
                            },
                        )
                        if (uiState.verifying) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.35f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(24.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(22.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Text(
                                            text = stringResource(R.string.settings_youtube_login_verifying),
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    YouTubeSignInActionBar(
                        onPasteCookies = { showCookieDialog = true },
                        onSignedIn = { captureSession(force = true) },
                    )
                }
            }

            uiState.errorMessage?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::dismissError) {
                            Text(stringResource(R.string.third_party_notices_close))
                        }
                    }
                }
            }
        }

        CollapsibleCommonTopBar(
            title = stringResource(R.string.settings_youtube_login_title),
            subtitle = stringResource(R.string.settings_youtube_login_subtitle),
            collapseFraction = 0f,
            headerHeight = headerHeight,
            onBackClick = onBackClick,
            actions = {
                FilledTonalIconButton(
                    onClick = { showCookieDialog = true },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                ) {
                    Icon(
                        Icons.Rounded.Key,
                        contentDescription = stringResource(R.string.settings_youtube_login_paste_title),
                    )
                }
            },
        )
    }

    if (showCookieDialog) {
        YouTubeCookieDialog(
            onDismiss = { showCookieDialog = false },
            onConnect = { raw ->
                showCookieDialog = false
                viewModel.attemptCookieConnect(raw)
            },
        )
    }
}

@Composable
private fun YouTubeSignInWebView(
    onCreated: (WebView) -> Unit,
    onFailure: (String) -> Unit,
    onPageVisited: (String?) -> Unit,
) {
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            runCatching {
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    // Google rejects the stock WebView UA on accounts.google.com.
                    // Strip the embedded markers while keeping the real engine
                    // version so client hints stay consistent.
                    val defaultUa = settings.userAgentString
                    settings.userAgentString = if (!defaultUa.isNullOrBlank()) {
                        defaultUa.replace("; wv", "")
                            .replace(Regex("Version/\\d+\\.\\d+\\s*"), "")
                    } else {
                        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                    }
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun doUpdateVisitedHistory(
                            view: WebView?,
                            url: String?,
                            isReload: Boolean,
                        ) {
                            super.doUpdateVisitedHistory(view, url, isReload)
                            onPageVisited(url)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            onPageVisited(url)
                        }
                    }
                    loadUrl(YOUTUBE_LOGIN_URL)
                    onCreated(this)
                }
            }.getOrElse { error ->
                onFailure(
                    "Android System WebView is unavailable. Enable a WebView provider to sign in, " +
                        "or connect with pasted cookies."
                )
                android.widget.FrameLayout(context)
            }
        },
    )
}

@Composable
private fun YouTubeSignInActionBar(
    onPasteCookies: () -> Unit,
    onSignedIn: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_youtube_login_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(onClick = onPasteCookies, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_youtube_login_paste_title))
                }
                Button(onClick = onSignedIn, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_youtube_login_signed_in))
                }
            }
        }
    }
}

@Composable
private fun YouTubeConnectedContent(
    accountName: String,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(52.dp),
                )
                Text(
                    text = stringResource(R.string.settings_youtube_login_connected, accountName),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.settings_youtube_connect_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.common_done))
                }
            }
        }
    }
}

@Composable
private fun YouTubeLoginErrorContent(
    message: String,
    onRetry: () -> Unit,
    onPasteCookies: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.PlayCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(52.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.settings_youtube_retry))
            }
            Button(onClick = onPasteCookies) {
                Text(stringResource(R.string.settings_youtube_login_paste_title))
            }
        }
    }
}

@Composable
private fun YouTubeCookieDialog(
    onDismiss: () -> Unit,
    onConnect: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_youtube_login_paste_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                placeholder = { Text(stringResource(R.string.settings_youtube_login_paste_hint)) },
                shape = RoundedCornerShape(16.dp),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConnect(text) },
                enabled = text.isNotBlank(),
            ) {
                Text(stringResource(R.string.settings_youtube_login_paste_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.third_party_notices_close))
            }
        },
    )
}

private fun hasYouTubeSessionTokens(cookies: String?): Boolean {
    if (cookies.isNullOrBlank()) return false
    return listOf("__Secure-3PAPISID=", "SAPISID=", "APISID=").any { cookies.contains(it) }
}

private fun readYouTubeCookieHeader(): String? = try {
    val cookieManager = CookieManager.getInstance()
    cookieManager.flush()
    // Never concatenate domains: a later .google.com set would overwrite the
    // YouTube session cookies when parsed, producing a mixed session. Prefer
    // the header that actually carries SAPISID so sign-in works even when the
    // music.youtube.com jar has not been populated yet.
    val headers = listOf(
        "https://music.youtube.com",
        "https://www.youtube.com",
        "https://youtube.com",
    ).mapNotNull { cookieManager.getCookie(it)?.takeIf(String::isNotBlank) }
    headers.firstOrNull(::hasYouTubeSessionTokens) ?: headers.firstOrNull()
} catch (_: Exception) {
    null
} catch (_: LinkageError) {
    null
}

private const val YOUTUBE_LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=" +
        "https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop" +
        "%26hl%3Den%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F&hl=en"
