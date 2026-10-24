package com.theveloper.pixelplay.data.youtube

import android.util.Log
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A playable YouTube Music track as returned by search / home / radio. */
data class YouTubeTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val durationSeconds: Int? = null,
)

/** Connected YouTube Music account identity, resolved from the account menu. */
data class YoutubeAccountInfo(
    val accountName: String,
    val channelHandle: String? = null,
    val photoUrl: String? = null,
)

/**
 * Minimal, debloated InnerTube (WEB_REMIX) client.
 *
 * Only the read surfaces needed by the PixelPlayer integration are exposed:
 * song search and the personalized home feed. Playback URLs are resolved
 * separately by [YouTubeStreamExtractor] (NewPipe), so none of LastWave's
 * player-client / PoToken machinery is required here.
 *
 * Requests are anonymous by default. When a YouTube Music account is
 * connected the home feed opts into the session cookies plus the
 * SAPISIDHASH `Authorization` header, which is what makes it personal.
 */
@Singleton
class InnerTubeClient @Inject constructor(
    private val http: OkHttpClient,
    private val auth: YtMusicAuthManager,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val configMutex = Mutex()

    @Volatile
    private var webConfig: WebConfig? = null

    @Volatile
    private var lastConfigAttemptMs = 0L

    private val searchCache = object : LinkedHashMap<String, List<YouTubeTrack>>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<YouTubeTrack>>?): Boolean =
            size > 24
    }

    suspend fun searchSongs(query: String, limit: Int = 30): List<YouTubeTrack> = withContext(Dispatchers.IO) {
        val normalized = query.trim()
        if (normalized.isBlank()) return@withContext emptyList()
        synchronized(searchCache) { searchCache[normalized] }?.let { return@withContext it.take(limit) }

        val config = getWebConfig()
        suspend fun runSearch(params: String?): List<YouTubeTrack> {
            val body = buildJsonObject {
                put("context", context("WEB_REMIX", config.clientVersion, config.visitorData, authenticated = false))
                put("query", normalized)
                if (params != null) put("params", params)
            }
            val root = post(
                url = "$MUSIC_API/search?key=${config.apiKey}&prettyPrint=false",
                body = body,
                clientName = "WEB_REMIX",
                clientVersion = config.clientVersion,
                visitorData = config.visitorData,
                callTimeoutMs = 10_000L,
            )
            return parseSongRenderers(root)
        }

        val filtered = runCatching { runSearch(SONG_SEARCH_FILTER) }.getOrDefault(emptyList())
        val result = if (filtered.isNotEmpty()) filtered else runCatching { runSearch(null) }.getOrDefault(emptyList())
        val tracks = result.distinctBy { it.videoId }.filter { it.videoId.isNotBlank() }.take(limit)
        synchronized(searchCache) { searchCache[normalized] = tracks }
        tracks
    }

    /**
     * The personalized YouTube Music home feed. When connected, YouTube
     * answers with the same shelves the official app shows (recent listens,
     * favourites, related songs); anonymously it returns generic mixes.
     */
    suspend fun fetchHomeSongs(limit: Int = 40): List<YouTubeTrack> = withContext(Dispatchers.IO) {
        val config = getWebConfig()
        val connected = auth.connection.value.isConnected
        suspend fun browse(authenticated: Boolean): JsonObject = post(
            url = "$MUSIC_API/browse?key=${config.apiKey}&prettyPrint=false",
            body = buildJsonObject {
                put("context", context("WEB_REMIX", config.clientVersion, config.visitorData, authenticated = authenticated))
                put("browseId", HOME_BROWSE_ID)
            },
            clientName = "WEB_REMIX",
            clientVersion = config.clientVersion,
            visitorData = config.visitorData,
            authenticated = authenticated,
            callTimeoutMs = 12_000L,
        )

        val root = runCatching { browse(connected) }
            .recoverCatching { if (connected) browse(false) else throw it }
            .getOrNull()
            ?: return@withContext emptyList()

        val tracks = parseHomeFeedSongs(root).ifEmpty { parseSongRenderers(root) }
        tracks.distinctBy { it.videoId }.filter { it.videoId.isNotBlank() }.take(limit)
    }

    suspend fun fetchConnectionIdentity(): String? = withContext(Dispatchers.IO) {
        if (!auth.connection.value.isConnected) return@withContext null
        // The account menu is the authoritative probe: unlike the home feed
        // (which silently falls back to anonymous), it returns null when the
        // session cookies are rejected.
        fetchAccountInfo()?.accountName
    }

    /** Display name / handle / avatar for the connected account. */
    suspend fun fetchAccountInfo(): YoutubeAccountInfo? = withContext(Dispatchers.IO) {
        if (!auth.connection.value.isConnected) return@withContext null
        val config = getWebConfig()
        val root = runCatching {
            post(
                url = "$MUSIC_API/account/account_menu?key=${config.apiKey}&prettyPrint=false",
                body = buildJsonObject {
                    put(
                        "context",
                        context("WEB_REMIX", config.clientVersion, config.visitorData, authenticated = true)
                    )
                },
                clientName = "WEB_REMIX",
                clientVersion = config.clientVersion,
                visitorData = config.visitorData,
                authenticated = true,
                callTimeoutMs = 8_000L,
            )
        }.getOrNull() ?: return@withContext null

        val headers = mutableListOf<JsonObject>()
        collectObjects(root, "activeAccountHeaderRenderer", headers)
        val header = headers.firstOrNull() ?: return@withContext null
        val accountName = header.obj("accountName")?.array("runs")?.firstOrNull()
            ?.asObject()?.string("text")?.trim().orEmpty()
        if (accountName.isBlank()) return@withContext null
        YoutubeAccountInfo(
            accountName = accountName,
            channelHandle = header.obj("channelHandle")?.array("runs")?.firstOrNull()
                ?.asObject()?.string("text"),
            photoUrl = header.obj("accountPhoto")?.obj("thumbnails")?.array("thumbnails")
                ?.lastOrNull()?.asObject()?.string("url")
                ?.highResolutionArtwork(),
        )
    }

    // ── Transport ───────────────────────────────────────────────────────────

    private suspend fun getWebConfig(): WebConfig {
        webConfig?.takeIf { it.hasRealVisitorData() }?.let { return it }
        return configMutex.withLock {
            webConfig?.takeIf { it.hasRealVisitorData() }?.let { return@withLock it }
            val now = System.currentTimeMillis()
            if (webConfig == null || now - lastConfigAttemptMs >= CONFIG_RETRY_COOLDOWN_MS) {
                lastConfigAttemptMs = now
                val fetched = runCatching { fetchWebConfig() }.getOrNull()
                if (fetched != null) webConfig = fetched
            }
            webConfig ?: WebConfig(FALLBACK_WEB_KEY, FALLBACK_WEB_VERSION, null)
        }
    }

    private suspend fun fetchWebConfig(): WebConfig = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$MUSIC_ORIGIN/")
            .header("User-Agent", WEB_USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "SOCS=CAESEwgDEgk2ODE4NDk5NzAaAmVuIAEaBgiA_LyaBg; CONSENT=YES+cb.20210328-17-p0.en+FX+999")
            .build()
        val call = http.newCall(request).apply {
            timeout().timeout(6, TimeUnit.SECONDS)
        }
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("YouTube Music config HTTP ${response.code}")
            val html = response.body?.string().orEmpty()
            WebConfig(
                apiKey = findConfig(html, "INNERTUBE_API_KEY") ?: FALLBACK_WEB_KEY,
                clientVersion = findConfig(html, "INNERTUBE_CONTEXT_CLIENT_VERSION") ?: FALLBACK_WEB_VERSION,
                visitorData = findConfig(html, "VISITOR_DATA") ?: findConfig(html, "EOM_VISITOR_DATA"),
            )
        }
    }

    private fun findConfig(html: String, key: String): String? {
        if (html.isBlank()) return null
        return Regex("\\\"$key\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .find(html)?.groupValues?.getOrNull(1)
            ?.replace("\\u003d", "=")
            ?.replace("\\x3d", "=")
            ?.replace("\\/", "/")
    }

    private suspend fun post(
        url: String,
        body: JsonObject,
        clientName: String,
        clientVersion: String,
        visitorData: String? = null,
        authenticated: Boolean = false,
        callTimeoutMs: Long? = null,
    ): JsonObject = withContext(Dispatchers.IO) {
        val account = auth.connection.value
        val useAuth = authenticated && account.isConnected
        val builder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("User-Agent", WEB_USER_AGENT)
            .header("Origin", MUSIC_ORIGIN)
            .header("X-Origin", MUSIC_ORIGIN)
            .header("Referer", "$MUSIC_ORIGIN/")
            .header("X-Goog-Api-Format-Version", "1")
            .header("X-YouTube-Client-Name", CLIENT_IDS[clientName] ?: clientName)
            .header("X-YouTube-Client-Version", clientVersion)
            .header("Accept-Language", acceptLanguage())
            .apply {
                if (!visitorData.isNullOrBlank()) header("X-Goog-Visitor-Id", visitorData)
                if (useAuth) {
                    auth.cookieHeaderValue(account)?.let { header("Cookie", it) }
                    auth.authorizationHeaderValue(account = account)?.let { header("Authorization", it) }
                }
            }
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))

        val call = http.newCall(builder.build()).apply {
            callTimeoutMs?.let { timeout().timeout(it, TimeUnit.MILLISECONDS) }
        }
        call.execute().use { response ->
            if (!response.isSuccessful) {
                if (useAuth && (response.code == 401 || response.code == 403)) {
                    // Session expired mid-flight: fall back to anonymous once.
                    return@withContext post(
                        url = url,
                        body = body,
                        clientName = clientName,
                        clientVersion = clientVersion,
                        visitorData = visitorData,
                        authenticated = false,
                        callTimeoutMs = callTimeoutMs,
                    )
                }
                throw IOException("InnerTube HTTP ${response.code}")
            }
            val text = response.body?.string().orEmpty()
            runCatching { json.parseToJsonElement(text).jsonObject }
                .getOrElse { throw IOException("Invalid InnerTube response", it) }
        }
    }

    private fun context(
        name: String,
        version: String,
        visitorData: String?,
        authenticated: Boolean,
    ): JsonObject = buildJsonObject {
        put("client", buildJsonObject {
            put("clientName", name)
            put("clientVersion", version)
            put("hl", Locale.getDefault().language.ifBlank { "en" })
            put("gl", Locale.getDefault().country.takeIf { it.length == 2 } ?: "US")
            if (!visitorData.isNullOrBlank()) put("visitorData", visitorData)
        })
        if (authenticated) {
            auth.connection.value.cookies["LOGIN_INFO"]?.let {
                put("user", buildJsonObject {
                    put("lockedSafetyMode", false)
                })
            }
        }
    }

    private fun acceptLanguage(): String {
        val locale = Locale.getDefault()
        val language = locale.language.ifBlank { "en" }
        val country = locale.country.takeIf { it.length == 2 } ?: "US"
        return "$language-$country,$language;q=0.9,en;q=0.8"
    }

    // ── Parsing ─────────────────────────────────────────────────────────────

    private fun parseSongRenderers(root: JsonElement): List<YouTubeTrack> {
        val songs = mutableListOf<YouTubeTrack>()

        val cards = mutableListOf<JsonObject>()
        collectObjects(root, "musicCardShelfRenderer", cards)
        songs += cards.mapNotNull(::parseCardShelfSong)

        val rows = mutableListOf<JsonObject>()
        collectObjects(root, "musicResponsiveListItemRenderer", rows)
        songs += rows.mapNotNull(::parseSong)

        val queueRows = mutableListOf<JsonObject>()
        collectObjects(root, "playlistPanelVideoRenderer", queueRows)
        songs += queueRows.mapNotNull(::parsePlaylistPanelSong)

        if (songs.isEmpty()) {
            val videos = mutableListOf<JsonObject>()
            collectObjects(root, "videoRenderer", videos)
            collectObjects(root, "compactVideoRenderer", videos)
            songs += videos.mapNotNull(::parseVideoRenderer)
        }
        return songs.distinctBy { it.videoId }.filter { it.videoId.isNotBlank() }
    }

    private fun parseHomeFeedSongs(root: JsonElement): List<YouTubeTrack> {
        val rows = mutableListOf<JsonObject>()
        collectObjects(root, "musicResponsiveListItemRenderer", rows)
        val songs = rows.mapNotNull(::parseSong).toMutableList()

        // Home shelves also surface songs as two-row cards (single tracks,
        // mixes, "start radio" tiles) and as the hero card shelf. Parsing all
        // three shapes keeps "Your Mix" populated when the feed's first
        // carousel is album artwork rather than a track list.
        val cards = mutableListOf<JsonObject>()
        collectObjects(root, "musicCardShelfRenderer", cards)
        songs += cards.mapNotNull(::parseCardShelfSong)

        val twoRowCards = mutableListOf<JsonObject>()
        collectObjects(root, "musicTwoRowItemRenderer", twoRowCards)
        songs += twoRowCards.mapNotNull(::parseTwoRowSong)

        val queueRows = mutableListOf<JsonObject>()
        collectObjects(root, "playlistPanelVideoRenderer", queueRows)
        songs += queueRows.mapNotNull(::parsePlaylistPanelSong)

        return songs.distinctBy { it.videoId }.filter { it.videoId.isNotBlank() }
    }

    private fun parseSong(renderer: JsonObject): YouTubeTrack? {
        val videoId = directWatchVideoId(renderer)
            ?: findString(renderer, "videoId")
            ?: return null
        val columns = renderer.array("flexColumns")
        val titleRuns = columns?.getOrNull(0)?.asObject()
            ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")
        val title = titleRuns?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?.trim()?.takeIf { it.isNotBlank() }
            ?: columns?.getOrNull(0)?.asObject()
                ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")
                ?.string("simpleText")?.trim()?.takeIf { it.isNotBlank() }
            ?: renderer.obj("title")?.array("runs")
                ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
                ?.trim()?.takeIf { it.isNotBlank() }
            ?: renderer.obj("title")?.string("simpleText")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        val detailRuns = (1 until (columns?.size ?: 0)).flatMap { index ->
            columns?.getOrNull(index)?.asObject()
                ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")
                ?.mapNotNull { it.asObject() }.orEmpty()
        }
        val fixedRuns = renderer.array("fixedColumns")?.flatMap { column ->
            column.asObject()?.obj("musicResponsiveListItemFixedColumnRenderer")?.obj("text")
                ?.array("runs")?.mapNotNull { it.asObject() }.orEmpty()
        }.orEmpty()
        val allDetails = detailRuns + fixedRuns +
            renderer.obj("subtitle")?.array("runs")?.mapNotNull { it.asObject() }.orEmpty()

        val artist = allDetails.firstOrNull { run ->
            run.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")?.startsWith("UC") == true
        }?.string("text") ?: allDetails.mapNotNull { it.string("text") }
            .firstOrNull { it.isLikelyArtist() }
            ?: "Unknown artist"
        val album = allDetails.firstOrNull { run ->
            run.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")?.startsWith("MPRE") == true
        }?.string("text")
        val duration = allDetails.mapNotNull { it.string("text") }
            .firstNotNullOfOrNull(::parseDuration)
            ?: renderer.string("lengthSeconds")?.toIntOrNull()
        return YouTubeTrack(videoId, title, artist, album, extractArtwork(renderer), duration)
    }

    private fun parseCardShelfSong(renderer: JsonObject): YouTubeTrack? {
        val videoId = directWatchVideoId(renderer)
            ?: renderer.obj("title")?.array("runs")?.firstOrNull()?.asObject()
                ?.obj("navigationEndpoint")?.obj("watchEndpoint")?.string("videoId")
            ?: renderer.obj("onTap")?.obj("watchEndpoint")?.string("videoId")
            ?: findString(renderer, "videoId")
            ?: return null
        val title = renderer.obj("title")?.array("runs")
            ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?.trim()?.takeIf { it.isNotBlank() }
            ?: renderer.obj("title")?.string("simpleText")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val details = renderer.obj("subtitle")?.array("runs")?.mapNotNull { it.asObject() }.orEmpty()
        val artist = details.firstOrNull { run ->
            run.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")?.startsWith("UC") == true
        }?.string("text") ?: details.mapNotNull { it.string("text") }
            .firstOrNull { it.isLikelyArtist() } ?: "Unknown artist"
        val album = details.firstOrNull { run ->
            run.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")?.startsWith("MPRE") == true
        }?.string("text")
        val duration = details.mapNotNull { it.string("text") }.firstNotNullOfOrNull(::parseDuration)
        return YouTubeTrack(videoId, title, artist, album, extractArtwork(renderer), duration)
    }

    private fun parseTwoRowSong(renderer: JsonObject): YouTubeTrack? {
        val titleRuns = renderer.obj("title")?.array("runs")
        val videoId = directWatchVideoId(renderer)
            ?: titleRuns?.firstOrNull()?.asObject()
                ?.obj("navigationEndpoint")?.obj("watchEndpoint")?.string("videoId")
            ?: return null
        val title = titleRuns?.joinToString("") { it.asObject()?.string("text").orEmpty() }
            ?.trim()?.takeIf { it.isNotBlank() }
            ?: renderer.obj("title")?.string("simpleText")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val details = renderer.obj("subtitle")?.array("runs")?.mapNotNull { it.asObject() }.orEmpty()
        val artist = details.firstOrNull { run ->
            run.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")?.startsWith("UC") == true
        }?.string("text") ?: details.mapNotNull { it.string("text") }
            .firstOrNull { it.isLikelyArtist() } ?: "Unknown artist"
        val album = details.firstOrNull { run ->
            run.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")?.startsWith("MPRE") == true
        }?.string("text")
        val duration = details.mapNotNull { it.string("text") }.firstNotNullOfOrNull(::parseDuration)
        return YouTubeTrack(videoId, title, artist, album, extractArtwork(renderer), duration)
    }

    private fun parsePlaylistPanelSong(renderer: JsonObject): YouTubeTrack? {
        val videoId = renderer.string("videoId") ?: return null
        val title = renderer.obj("title")?.string("simpleText")
            ?: renderer.obj("title")?.array("runs")
                ?.joinToString("") { it.asObject()?.string("text").orEmpty() }
                ?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val artist = renderer.obj("longBylineText")?.array("runs")
            ?.firstOrNull()?.asObject()?.string("text")
            ?: renderer.obj("shortBylineText")?.array("runs")
                ?.firstOrNull()?.asObject()?.string("text")
            ?: "Unknown artist"
        val duration = renderer.obj("lengthText")?.string("simpleText")?.let(::parseDuration)
            ?: renderer.string("lengthSeconds")?.toIntOrNull()
        return YouTubeTrack(videoId, title, artist, null, extractArtwork(renderer), duration)
    }

    private fun parseVideoRenderer(renderer: JsonObject): YouTubeTrack? {
        val videoId = renderer.string("videoId") ?: return null
        val title = renderer.obj("title")?.array("runs")
            ?.joinToString("") { it.asObject()?.string("text").orEmpty() }?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: renderer.obj("title")?.string("simpleText")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val artist = renderer.obj("ownerText")?.array("runs")?.firstOrNull()?.asObject()?.string("text")
            ?: renderer.obj("shortBylineText")?.array("runs")?.firstOrNull()?.asObject()?.string("text")
            ?: "Unknown artist"
        val duration = renderer.obj("lengthText")?.string("simpleText")?.let(::parseDuration)
            ?: renderer.string("lengthSeconds")?.toIntOrNull()
        return YouTubeTrack(videoId, title, artist, null, extractArtwork(renderer), duration)
    }

    private fun directWatchVideoId(renderer: JsonObject): String? =
        renderer.obj("playlistItemData")?.string("videoId")
            ?: renderer.obj("navigationEndpoint")?.obj("watchEndpoint")?.string("videoId")
            ?: (renderer.obj("overlay") ?: renderer.obj("thumbnailOverlay"))
                ?.obj("musicItemThumbnailOverlayRenderer")
                ?.obj("content")?.obj("musicPlayButtonRenderer")
                ?.obj("playNavigationEndpoint")?.obj("watchEndpoint")?.string("videoId")
            ?: renderer.obj("onTap")?.obj("watchEndpoint")?.string("videoId")
            ?: renderer.array("flexColumns")?.firstOrNull()?.asObject()
                ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")
                ?.array("runs")?.firstOrNull()?.asObject()
                ?.obj("navigationEndpoint")?.obj("watchEndpoint")?.string("videoId")
            ?: renderer.obj("doubleTapCommand")?.obj("watchEndpoint")?.string("videoId")

    private fun extractArtwork(renderer: JsonObject): String? {
        val thumbnails = renderer.obj("thumbnail")?.obj("musicThumbnailRenderer")
            ?.obj("thumbnail")?.array("thumbnails")
            ?: renderer.obj("thumbnail")?.obj("croppedSquareThumbnailRenderer")
                ?.obj("thumbnail")?.array("thumbnails")
            ?: renderer.obj("thumbnail")?.array("thumbnails")
            ?: renderer.obj("thumbnailRenderer")?.obj("musicThumbnailRenderer")
                ?.obj("thumbnail")?.array("thumbnails")
        return thumbnails?.lastOrNull()?.asObject()?.string("url")?.highResolutionArtwork()
    }

    private fun parseDuration(raw: String): Int? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (':' !in text) return text.toIntOrNull()
        val parts = text.split(':').mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> null
        }
    }

    private data class WebConfig(
        val apiKey: String,
        val clientVersion: String,
        val visitorData: String?,
    ) {
        fun hasRealVisitorData(): Boolean = !visitorData.isNullOrBlank()
    }

    private companion object {
        const val MUSIC_ORIGIN = "https://music.youtube.com"
        const val MUSIC_API = "$MUSIC_ORIGIN/youtubei/v1"
        const val HOME_BROWSE_ID = "FEmusic_home"
        const val SONG_SEARCH_FILTER = "EgWKAQIIAWoKEAkQBRAKEAMQBA=="
        const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
        const val FALLBACK_WEB_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
        const val FALLBACK_WEB_VERSION = "1.20260707.12.00"
        const val CONFIG_RETRY_COOLDOWN_MS = 30_000L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val CLIENT_IDS = mapOf("WEB_REMIX" to "67", "WEB" to "1")
    }
}

private fun String.isLikelyArtist(): Boolean {
    val value = trim()
    if (value.isEmpty() || value.length > 80) return false
    if (value.all { it.isDigit() } || value.contains(" views", ignoreCase = true)) return false
    val lower = value.lowercase()
    return lower !in setOf("song", "video", "album", "single", "ep", "playlist")
}

private fun String.highResolutionArtwork(): String {
    var url = this
    if (url.startsWith("//")) url = "https:$url"
    url = url.replace(Regex("=w\\d+-h\\d+"), "=w544-h544")
    url = url.replace(Regex("=s\\d+(-c)?$"), "=s544")
    return url
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonElement.asObject(): JsonObject? = this as? JsonObject
private fun JsonElement.obj(key: String): JsonObject? = (this as? JsonObject)?.obj(key)
private fun JsonElement.array(key: String): JsonArray? = (this as? JsonObject)?.array(key)
private fun JsonElement.string(key: String): String? = (this as? JsonObject)?.string(key)

private fun collectObjects(element: JsonElement, key: String, output: MutableList<JsonObject>) {
    when (element) {
        is JsonObject -> element.forEach { (name, child) ->
            if (name == key && child is JsonObject) output += child
            collectObjects(child, key, output)
        }
        is JsonArray -> element.forEach { collectObjects(it, key, output) }
        else -> Unit
    }
}

private fun findString(element: JsonElement, key: String): String? {
    when (element) {
        is JsonObject -> {
            (element[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
            element.values.forEach { child -> findString(child, key)?.let { return it } }
        }
        is JsonArray -> element.forEach { child -> findString(child, key)?.let { return it } }
        else -> Unit
    }
    return null
}
