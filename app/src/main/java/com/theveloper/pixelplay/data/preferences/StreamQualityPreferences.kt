package com.theveloper.pixelplay.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.theveloper.pixelplay.data.quality.StreamingQuality
import com.theveloper.pixelplay.di.AppScope
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One entry of the multi-addon list (LastWave HttpAddonConfig shape). */
@Serializable
data class HttpAddonConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    val url: String = "",
    val name: String = "",
    val enabled: Boolean = true,
) {
    val isEnabled: Boolean
        get() = enabled && url.isNotBlank()
}

/**
 * Immutable read view of the quality/addon settings. Exposed both as a
 * blocking snapshot for hot-path gating ([StreamQualityPreferences.snapshot])
 * and as a suspending read ([StreamQualityPreferences.current]).
 */
data class StreamQualitySettings(
    /** Preferred streaming tier; see [StreamingQuality]. */
    val streamingQuality: Int = StreamingQuality.QUALITY_MAX_HI_RES,
    val preferLosslessStreaming: Boolean = true,
    /** Preferred download tier for offline copies. */
    val downloadQuality: Int = StreamingQuality.QUALITY_MAX_HI_RES,
    /** User configured addon URL (e.g. http://localhost:8787/a/<token>/). */
    val addonUrl: String = "",
    /** When true, the addon is active for search, streaming and downloads. */
    val addonEnabled: Boolean = true,
    /** Configured HTTP addons supporting multiple concurrent servers. */
    val addons: List<HttpAddonConfig> = emptyList(),
) {
    fun toAddonEndpoints(): List<String> = buildList {
        addAll(addons.filter { it.isEnabled }.map { it.url })
        if (addonEnabled && addonUrl.isNotBlank()) add(addonUrl)
    }
}

/** Verified addon status for the settings screen. */
sealed interface AddonStatus {
    data class Ok(val info: String) : AddonStatus
    data class Failed(val reason: String) : AddonStatus
    data object Checking : AddonStatus
    data object Idle : AddonStatus
}

/**
 * DataStore persistence for streaming/download quality and the optional
 * lossless addon backend. Values mirror LastWave's MiscSettings defaults:
 * prefer-lossless streaming is on, quality tier 27 (Hi-Res max) and the addon
 * is enabled whenever a URL is configured.
 */
@Singleton
class StreamQualityPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @AppScope scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val addonSerializer = ListSerializer(HttpAddonConfig.serializer())

    private val dataFlow = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    private fun Preferences.toSnapshot(): StreamQualitySettings = StreamQualitySettings(
        streamingQuality = this[STREAMING_QUALITY_KEY]?.takeIf { it in TIERS } ?: 27,
        preferLosslessStreaming = this[PREFER_LOSSLESS_KEY] ?: true,
        downloadQuality = this[DOWNLOAD_QUALITY_KEY]?.takeIf { it in TIERS } ?: 27,
        addonUrl = this[ADDON_URL_KEY].orEmpty(),
        addonEnabled = this[ADDON_ENABLED_KEY] ?: true,
        addons = this[ADDONS_KEY]
            ?.let { raw -> runCatching { json.decodeFromString(addonSerializer, raw) }.getOrNull() }
            .orEmpty(),
    )

    /** Blocking snapshot of the latest settings, for gating hot paths. */
    @Volatile
    var snapshot: StreamQualitySettings = StreamQualitySettings()
        private set

    init {
        scope.launch {
            dataFlow.collect { prefs -> snapshot = prefs.toSnapshot() }
        }
    }

    suspend fun current(): StreamQualitySettings = dataFlow.map { it.toSnapshot() }.first()

    val streamingQuality: Flow<Int> = dataFlow.map { it.toSnapshot().streamingQuality }

    val preferLosslessStreaming: Flow<Boolean> = dataFlow.map { it.toSnapshot().preferLosslessStreaming }

    val downloadQuality: Flow<Int> = dataFlow.map { it.toSnapshot().downloadQuality }

    val addonUrl: Flow<String> = dataFlow.map { it.toSnapshot().addonUrl }

    val addonEnabled: Flow<Boolean> = dataFlow.map { it.toSnapshot().addonEnabled }

    val addons: Flow<List<HttpAddonConfig>> = dataFlow.map { it.toSnapshot().addons }

    suspend fun setStreamingQuality(quality: Int) {
        require(quality in TIERS)
        dataStore.edit { prefs -> prefs[STREAMING_QUALITY_KEY] = quality }
    }

    suspend fun setPreferLosslessStreaming(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[PREFER_LOSSLESS_KEY] = enabled }
    }

    suspend fun setDownloadQuality(quality: Int) {
        require(quality in TIERS)
        dataStore.edit { prefs -> prefs[DOWNLOAD_QUALITY_KEY] = quality }
    }

    suspend fun setAddonUrl(url: String) {
        dataStore.edit { prefs -> prefs[ADDON_URL_KEY] = url.trim() }
    }

    suspend fun setAddonEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[ADDON_ENABLED_KEY] = enabled }
    }

    suspend fun setAddons(addons: List<HttpAddonConfig>) {
        dataStore.edit { prefs -> prefs[ADDONS_KEY] = json.encodeToString(addonSerializer, addons) }
    }

    private companion object {
        val STREAMING_QUALITY_KEY = intPreferencesKey("streaming_quality")
        val PREFER_LOSSLESS_KEY = booleanPreferencesKey("prefer_lossless_streaming")
        val DOWNLOAD_QUALITY_KEY = intPreferencesKey("download_quality")
        val ADDON_URL_KEY = stringPreferencesKey("lossless_addon_url")
        val ADDON_ENABLED_KEY = booleanPreferencesKey("lossless_addon_enabled")
        val ADDONS_KEY = stringPreferencesKey("lossless_http_addons")
        val TIERS = setOf(
            StreamingQuality.QUALITY_DOLBY_ATMOS,
            StreamingQuality.QUALITY_MAX_HI_RES,
            StreamingQuality.QUALITY_HI_RES_96,
            StreamingQuality.QUALITY_CD_LOSSLESS,
            StreamingQuality.QUALITY_MP3_320,
            StreamingQuality.QUALITY_DATA_SAVER,
            StreamingQuality.QUALITY_YOUTUBE,
        )
    }
}
