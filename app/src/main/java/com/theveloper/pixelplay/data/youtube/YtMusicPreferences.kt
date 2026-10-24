package com.theveloper.pixelplay.data.youtube

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Connected YouTube Music account (session cookies + display identity). */
@Serializable
data class YtConnection(
    val cookies: Map<String, String> = emptyMap(),
    val accountName: String = "",
    val channelHandle: String? = null,
    val photoUrl: String? = null,
    val connectedAtMillis: Long = 0L,
) {
    val isConnected: Boolean
        get() = cookies.isNotEmpty() && accountName.isNotBlank()

    companion object {
        val DISCONNECTED = YtConnection()
    }
}

/**
 * DataStore persistence for the YouTube Music integration.
 *
 * The session cookies live in the app's existing shared preference store,
 * so they ride along with the normal backup/clear flows. No credential is
 * ever sent anywhere except back to YouTube's own endpoints.
 */
@Singleton
class YtMusicPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cookieSerializer = MapSerializer(String.serializer(), String.serializer())

    private val preferences: Flow<Preferences> = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    val connection: Flow<YtConnection> = preferences.map { prefs ->
        val cookies = prefs[COOKIES_KEY]
            ?.let { raw -> runCatching { json.decodeFromString(cookieSerializer, raw) }.getOrNull() }
            .orEmpty()
        val name = prefs[ACCOUNT_NAME_KEY].orEmpty()
        if (cookies.isEmpty()) {
            YtConnection.DISCONNECTED
        } else {
            YtConnection(
                cookies = cookies,
                accountName = name.ifBlank { "Google account" },
                channelHandle = prefs[CHANNEL_HANDLE_KEY],
                photoUrl = prefs[PHOTO_URL_KEY],
                connectedAtMillis = prefs[CONNECTED_AT_KEY]?.toLongOrNull() ?: 0L,
            )
        }
    }

    suspend fun currentConnection(): YtConnection = connection.first()

    suspend fun saveConnection(
        cookies: Map<String, String>,
        accountName: String,
        channelHandle: String? = null,
        photoUrl: String? = null,
    ) {
        dataStore.edit { prefs ->
            prefs[COOKIES_KEY] = json.encodeToString(cookieSerializer, cookies)
            prefs[ACCOUNT_NAME_KEY] = accountName.ifBlank { "Google account" }
            if (channelHandle.isNullOrBlank()) prefs.remove(CHANNEL_HANDLE_KEY) else prefs[CHANNEL_HANDLE_KEY] = channelHandle
            if (photoUrl.isNullOrBlank()) prefs.remove(PHOTO_URL_KEY) else prefs[PHOTO_URL_KEY] = photoUrl
            prefs[CONNECTED_AT_KEY] = System.currentTimeMillis().toString()
        }
    }

    suspend fun clearConnection() {
        dataStore.edit { prefs ->
            prefs.remove(COOKIES_KEY)
            prefs.remove(ACCOUNT_NAME_KEY)
            prefs.remove(CHANNEL_HANDLE_KEY)
            prefs.remove(PHOTO_URL_KEY)
            prefs.remove(CONNECTED_AT_KEY)
        }
    }

    /** Whether the home "Your Mix" should be fed by YouTube Music. */
    val homeMixEnabled: Flow<Boolean> = preferences.map { it[HOME_MIX_ENABLED_KEY] ?: true }

    suspend fun setHomeMixEnabled(enabled: Boolean) {
        dataStore.edit { it[HOME_MIX_ENABLED_KEY] = enabled }
    }

    /** Whether playback should prefer Opus/WebM over AAC/M4A when both exist. */
    val preferOpus: Flow<Boolean> = preferences.map { it[PREFER_OPUS_KEY] ?: false }

    suspend fun setPreferOpus(enabled: Boolean) {
        dataStore.edit { it[PREFER_OPUS_KEY] = enabled }
    }

    private companion object {
        val COOKIES_KEY = stringPreferencesKey("ytmusic_cookies")
        val ACCOUNT_NAME_KEY = stringPreferencesKey("ytmusic_account_name")
        val CHANNEL_HANDLE_KEY = stringPreferencesKey("ytmusic_channel_handle")
        val PHOTO_URL_KEY = stringPreferencesKey("ytmusic_photo_url")
        val CONNECTED_AT_KEY = stringPreferencesKey("ytmusic_connected_at")
        val HOME_MIX_ENABLED_KEY = booleanPreferencesKey("ytmusic_home_mix_enabled")
        val PREFER_OPUS_KEY = booleanPreferencesKey("ytmusic_prefer_opus")
    }
}
