package com.theveloper.pixelplay.data.youtube

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the connected YouTube Music session cookies and derives the
 * per-request SAPISIDHASH Authorization header that InnerTube endpoints
 * require for account-scoped data (home feed, library, history).
 *
 * Cookies are captured by the in-app WebView sign-in flow via Android's
 * system CookieManager and persisted by [YtMusicPreferences].
 */
@Singleton
class YtMusicAuthManager @Inject constructor(
    private val preferences: YtMusicPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connection = MutableStateFlow(YtConnection.DISCONNECTED)
    val connection: StateFlow<YtConnection> = _connection.asStateFlow()

    init {
        scope.launch {
            runCatching { preferences.connection.collect { _connection.value = it } }
                .onFailure { Log.w(TAG, "Failed to observe YouTube connection", it) }
        }
    }

    fun sapisid(account: YtConnection = connection.value): String? =
        account.cookies[COOKIE_SAPISID_PRIMARY]
            ?: account.cookies["SAPISID"]
            ?: account.cookies["APISID"]

    fun cookieHeaderValue(account: YtConnection = connection.value): String? {
        if (account.cookies.isEmpty()) return null
        return account.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    suspend fun awaitLoadedConnection(): YtConnection {
        val persisted = preferences.currentConnection()
        return connection.first { it == persisted }
    }

    /** Authoritative connection snapshot straight from persistence. */
    suspend fun currentConnection(): YtConnection = preferences.currentConnection()

    /**
     * Builds `SAPISIDHASH <unix_ts>_<sha1(ts SP sapisid SP origin)>` — the
     * same scheme music.youtube.com itself uses.
     */
    fun authorizationHeaderValue(
        origin: String = AUTH_ORIGIN,
        account: YtConnection = connection.value,
    ): String? {
        val sapisid = sapisid(account) ?: return null
        val timestamp = System.currentTimeMillis() / 1000
        val payload = "$timestamp $sapisid $origin"
        val digest = MessageDigest.getInstance("SHA-1").digest(payload.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${timestamp}_$hex"
    }

    suspend fun connect(rawCookieHeader: String, accountName: String) {
        val cookies = parseCookieHeader(rawCookieHeader)
        if (cookies.isEmpty()) return
        val name = accountName.ifBlank { "Google account" }
        _connection.value = YtConnection(
            cookies = cookies,
            accountName = name,
            connectedAtMillis = System.currentTimeMillis(),
        )
        preferences.saveConnection(cookies, name)
    }

    /** Clears YouTube/Google session cookies so a later login can never
     *  auto-capture the previous account's stale session. Cookies for other
     *  WebView logins (Netease, QQ Music) are left untouched. */
    suspend fun signOut() {
        val previous = _connection.value
        _connection.value = YtConnection.DISCONNECTED
        preferences.clearConnection()
        runCatching {
            withContext(Dispatchers.Main) {
                val cm = CookieManager.getInstance()
                val names = previous.cookies.keys + GOOGLE_SESSION_COOKIE_NAMES
                YOUTUBE_COOKIE_URLS.forEach { url ->
                    names.forEach { name ->
                        val secure = when {
                            name.startsWith("__Host-") -> "; Secure"
                            name.startsWith("__Secure-") -> "; Secure"
                            else -> ""
                        }
                        cm.setCookie(url, "$name=; Max-Age=0; Path=/$secure")
                    }
                }
                cm.flush()
                val webStorage = WebStorage.getInstance()
                YOUTUBE_COOKIE_URLS.forEach { url ->
                    runCatching { webStorage.deleteOrigin(url) }
                }
            }
        }
    }

    /**
     * Re-reads a mid-session cookie refresh (YouTube rotates SAPISID over
     * time). Returns true when fresh cookies were persisted.
     */
    suspend fun refreshCookiesFromCookieManager(): Boolean {
        val current = connection.value
        if (!current.isConnected) return false
        return try {
            val cm = CookieManager.getInstance()
            val music = cm.getCookie("https://music.youtube.com").orEmpty()
            val youtube = cm.getCookie("https://www.youtube.com").orEmpty()
            val combined = when {
                // music.youtube.com is a youtube.com subdomain and its header
                // already carries the Google session; concatenating the two
                // jars risks mixing SAPISID values from different accounts.
                music.isNotBlank() -> music
                else -> youtube
            }
            if (combined.isBlank()) return false
            val cookies = parseCookieHeader(combined)
            val hasSapisid = listOf(COOKIE_SAPISID_PRIMARY, "SAPISID", "APISID").any { cookies.containsKey(it) }
            if (!hasSapisid || !cookies.containsKey("LOGIN_INFO")) return false
            if (current.cookies == cookies) return false
            preferences.saveConnection(cookies, current.accountName, current.channelHandle, current.photoUrl)
            true
        } catch (error: Throwable) {
            Log.w(TAG, "Could not refresh YouTube cookies", error)
            false
        }
    }

    private fun parseCookieHeader(raw: String): Map<String, String> =
        raw.split(';')
            .mapNotNull { pair ->
                val index = pair.indexOf('=')
                if (index <= 0) null else {
                    val name = pair.substring(0, index).trim()
                    val value = pair.substring(index + 1).trim()
                    if (name.isBlank() || value.isBlank()) null else name to value
                }
            }
            .toMap()

    companion object {
        private const val TAG = "YtMusicAuthManager"
        const val COOKIE_SAPISID_PRIMARY = "__Secure-3PAPISID"
        const val AUTH_ORIGIN = "https://music.youtube.com"

        /** Origins whose WebView cookies belong to the YouTube session. */
        private val YOUTUBE_COOKIE_URLS = listOf(
            "https://music.youtube.com",
            "https://www.youtube.com",
            "https://youtube.com",
            "https://accounts.google.com",
        )

        /** Session cookies that must go even if the stored map was partial. */
        private val GOOGLE_SESSION_COOKIE_NAMES = setOf(
            "SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID", "APISID",
            "SSID", "HSID", "SID", "__Secure-1PSID", "__Secure-3PSID",
            "LOGIN_INFO", "SIDCC", "__Secure-1PSIDCC", "__Secure-3PSIDCC",
            "PREF", "VISITOR_INFO1_LIVE", "YSC",
        )
    }
}
