package com.theveloper.pixelplay.data.quality

import android.util.Log
import com.theveloper.pixelplay.data.addon.AddonClient
import com.theveloper.pixelplay.data.addon.AddonStream
import com.theveloper.pixelplay.data.addon.AddonTrack
import com.theveloper.pixelplay.data.preferences.StreamQualityPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class LosslessAudioStream(
    val url: String,
    val mimeType: String = "application/dash+xml",
    /** 0 means unknown; never assume 16 for an unmeasured depth. */
    val bitDepth: Int = 0,
    val samplingRate: Double = 44.1,
    val formatId: Int = 6,
    val bitrateKbps: Int? = null,
    val trackId: Long = 0,
    val durationSeconds: Int = 0,
    val audioCodecOverride: String? = null,
)

/** One enabled addon endpoint. */
data class AddonEndpoint(
    val baseUrl: String,
)

private data class TidalCandidateItem(
    val id: Long,
    val title: String,
    val duration: Int = 0,
    val performerName: String = "",
    val albumArtistName: String = "",
    val albumTitle: String = "",
    val performers: String = "",
    val isAtmos: Boolean = false,
    val isSpatial: Boolean = false,
    val rawAddonId: String = "",
    /** Addon search flag (HI_RES_LOSSLESS vs LOSSLESS). Upstream answers a
     *  hi_res /stream with HTTP 200 + 16-bit on CD-only masters instead of
     *  an error, so without this the resolver stops at the first
     *  downgraded success and a 24-bit master later in the list is never
     *  tried. */
    val audioQuality: String = "",
    val bitDepth: Int? = null,
    val directStreamUrl: String? = null,
) {
    fun isHiResFlagged(): Boolean =
        (bitDepth ?: 0) > 16 ||
            audioQuality.contains("HI_RES", ignoreCase = true) ||
            audioQuality.contains("HI-RES", ignoreCase = true) ||
            audioQuality.contains("24-BIT", ignoreCase = true) ||
            audioQuality.contains("24BIT", ignoreCase = true) ||
            audioQuality.contains("24/")
}

/**
 * Resolves lossless/hi-res audio through user-configured Addon servers,
 * ported from LastWave's Addon-based `LosslessMusicApi` path. Every resolve
 * races the enabled addons, verifies the searched candidates against the
 * requested title/artist/album, cascades down the quality waterfall and
 * returns a direct play URL (progressive or an inline DASH manifest).
 */
/** Parsed identity of a track title (LastWave TitleParts shape). */
data class TitleParts(
    val words: List<String>,
    val core: String,
    val versions: Set<String>,
    val context: Set<String>,
)

@Singleton
class LosslessMusicApi @Inject constructor(
    okHttpClient: OkHttpClient,
    private val preferences: StreamQualityPreferences,
) {
    private val client = okHttpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var consecutiveFailures = 0
    @Volatile
    private var failureCooldownUntilMs = 0L

    val isConfigured: Boolean
        get() {
            if (System.currentTimeMillis() < failureCooldownUntilMs) return false
            val snap = preferences.snapshot
            return snap.addons.any { it.isEnabled } || (snap.addonEnabled && snap.addonUrl.isNotBlank())
        }

    /**
     * True only while a recent backend failure is backing off. Unlike
     * [isConfigured], this is safe to skip on: the backend just failed, so
     * attempting would only burn the resolve timeout before falling back.
     */
    val isCoolingDown: Boolean
        get() = System.currentTimeMillis() < failureCooldownUntilMs

    /**
     * Consecutive backend misses before the chain is treated as down and
     * every resolve falls through to YouTube directly.
     */
    private companion object {
        const val FAILURE_COOLDOWN_THRESHOLD = 2

        /** How long a backed-off backend stays out of the resolve path. */
        const val FAILURE_COOLDOWN_MS = 30_000L

        const val TAG = "LosslessMusicApi"

        // Language markers found in YouTube/Tidal titles and album names.
        val LANGUAGE_TOKENS = setOf(
            "telugu", "tamil", "hindi", "kannada", "malayalam", "punjabi",
            "marathi", "gujarati", "bengali", "bhojpuri", "odia", "oriya",
            "assamese", "urdu", "sanskrit", "english", "spanish", "french",
            "german", "italian", "portuguese", "japanese", "korean", "chinese",
            "arabic", "turkish",
        )

        // Bug #2 ("same song, different language audio"): duration only
        // vets when the caller supplies it.
        const val MAX_DURATION_DIFFERENCE_SECONDS = 5

        val MANIFEST_CODECS = Regex("""codecs="([^"]+)"""")
        val MANIFEST_SAMPLE_RATE = Regex("""audioSamplingRate="(\d+)"""", RegexOption.IGNORE_CASE)
        // Tidal rendition id carries ground truth: id="FLAC_HIRES,48000,24".
        val MANIFEST_RENDITION = Regex("""Representation[^>]*id="[^"]*,(\d+),(\d+)"""", RegexOption.IGNORE_CASE)

        val VERSION_WORDS = setOf(
            "remix", "remixes", "rmx", "refix", "flip", "bootleg", "mashup", "medley",
            "live", "concert", "unplugged", "acoustic", "instrumental", "karaoke",
            "vocals", "vocal", "acapella", "acappella", "backing", "stems", "stem",
            "cover", "demo", "reprise", "remake", "rework", "extended", "edit",
            "sped", "slowed", "reverb", "nightcore", "lofi", "orchestral", "symphonic",
            "part", "pt", "chapter", "atmos", "dolby", "spatial",
        )

        val NEUTRAL_SEGMENTS = setOf(
            "albumversion", "originalversion", "originalmix", "singleversion",
            "radioversion", "radioedit", "stereoversion", "monoversion",
            "studioversion", "fullversion", "standardversion", "explicitversion",
            "deluxeversion", "originaltrack", "audio", "officialaudio", "officialvideo",
            "musicvideo", "lyricvideo", "lyrics", "lyric", "visualizer", "4k", "hd",
        )

        val NOISE_WORDS = setOf(
            "official", "video", "audio", "lyrics", "lyric", "lyrical", "visualizer",
            "song", "songs", "full", "music", "the", "and", "from", "feat", "ft",
            "featuring", "with", "new", "latest", "free", "download", "remaster",
            "remastered", "explicit", "clean", "bonus", "track", "deluxe", "original",
            "album", "single", "hd", "hq", "4k", "mp3", "ost", "soundtrack", "mv",
            "version", "versions", "mix", "mixes",
        )

        val RECORD_LABELS = setOf(
            "tseries", "t series", "zeemusiccompany", "zeemusic", "zee music", "zee music company",
            "sonymusicindia", "sonymusic", "sony music", "sony music india",
            "yrf", "yashrajfilms", "tips", "tipsmusic", "tips official",
            "venus", "speedrecords", "speed records", "saregama", "geetmp3", "geet mp3",
            "whitehillmusic", "white hill music", "vyrloriginals", "vyrl", "desimusicfactory",
            "eros", "erosnow", "adityamusic", "aditya music", "laharimusic", "lahari music",
            "t-series", "sony", "universal", "warner",
        )

        val TRAILING_NOISE = setOf(
            "song", "songs", "video", "audio", "lyrics", "lyric", "lyrical",
            "official", "full", "hd", "hq", "4k", "mp3", "ost", "soundtrack", "mv", "track",
        )

        val JOINING_WORDS = setOf("and")
        val ARTIST_SEPARATORS =
            Regex("""\s*(?:[,&/;·|]|\band\b|\bx\b|\bvs\.?\b|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b)\s*""", RegexOption.IGNORE_CASE)

        val DIACRITICS = Regex("\\p{M}+")
        val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
        val MULTI_SPACE = Regex("\\s+")
        val TOPIC_CHANNEL_SUFFIX = Regex("""(?i)\s*[-–—]\s*topic\s*$|\s+topic\s*$""")
        val PIPE_NOISE = Regex("""\s*\|.*$""")
        val SOUNDTRACK_SUFFIX = Regex(
            """(?i)\s*[\[(]\s*(?:from\s+(?:the\s+)?(?:original\s+)?(?:motion\s+picture|movie|film|soundtrack)|soundtrack|ost)\s*(?:["“][^"”\r\n]+["”]|[^\])]+)?\s*[\])]\s*$|\s*[-–—|]\s*(?:from\s+(?:the\s+)?(?:original\s+)?(?:motion\s+picture|movie|film|soundtrack)|soundtrack|ost)\s*.*$""",
        )
        val FEATURING_CLAUSE = Regex("""(?i)(?:\s*[\[(])?\s*(feat\.?|ft\.?|featuring|with)\s+.*$""")
        val BRACKETED_DISPLAY_NOISE = Regex(
            """(?i)[\[(]\s*(?:explicit|clean|(?:official\s+)?(?:music\s+)?(?:audio|video|lyrics?|lyric\s+video|visualizer|hd|4k|mv|full\s+song|full\s+audio|prod\.?\s*(?:by\s*)?[^\])]+)|remaster(?:ed)?(?:\s*\d{2,4})?|\d{2,4}\s*remaster(?:ed)?|deluxe(?:\s*edition)?|bonus(?:\s*track)?|special\s*edition|anniversary(?:\s*edition)?|radio\s*edit|single\s*version|album\s*version|with\s+[^\])]+|from\s+[^\])]+)\s*[\])]""",
        )
        val TRAILING_DISPLAY_NOISE = Regex(
            """(?i)\s*[-–—|]\s*(?:official\s+)?(?:music\s+)?(?:audio|video|lyrics?|visualizer|mv|full\s+song|coke\s*studio(?:\s*season\s*\d+)?|a\s*colors\s*show|tiny\s*desk|live\s*session|unplugged|acoustic\s*version)\s*$""",
        )
        val VERSION_NOISE_REGEX = Regex(
            """(?i)\s*[\[(]\s*(?:remaster(?:ed)?(?:\s*\d{2,4})?|\d{2,4}\s*remaster(?:ed)?|deluxe(?:\s*edition)?|bonus(?:\s*track)?|radio\s*edit|single\s*version|album\s*version|anniversary(?:\s*edition)?|special\s*edition|original\s*mix|extended\s*mix|club\s*mix|acoustic|live(?:\s*at[^\])]*)?|edit|version|mono|stereo|clean|explicit|re-?recorded|pt\.?\s*\d+|part\s*\d+|from\s+[^\])]+)\s*[\])]|\s*[-–—|]\s*(?:remaster(?:ed)?(?:\s*\d{2,4})?|\d{2,4}\s*remaster(?:ed)?|deluxe(?:\s*edition)?|bonus(?:\s*track)?|radio\s*edit|single\s*version|album\s*version|anniversary(?:\s*edition)?|live(?:\s*at.*)?|acoustic|re-?recorded|mono|stereo|coke\s*studio.*|a\s*colors\s*show)\s*$""",
        )
        val ATMOS_VERSION_MARKER = Regex(
            """(?i)[\[(]\s*(?:dolby\s+atmos|dolby|atmos|spatial\s+audio|spatial|360(?:\s*reality\s*audio)?|sony\s+360|mpeg-?\s*h)\s*[\])]|\s*[-–—]\s*(?:dolby\s+atmos|dolby|atmos|spatial\s+audio|spatial)\s*$""",
        )

        val ARTIST_NOISE_WORDS = setOf("the", "and", "feat", "ft", "featuring", "with", "x", "topic")
        val PERFORMING_ROLE_WORDS = setOf(
            "mainartist", "featuredartist", "performer", "vocal", "vocals", "vocalist", "singer",
        )
        val IDENTITY_VARIANT_PATTERNS = listOf(
            "live" to Regex("\\blive\\b"),
            "acoustic" to Regex("\\bacoustic\\b"),
            "karaoke" to Regex("\\bkaraoke\\b"),
            "instrumental" to Regex("\\binstrumental\\b"),
            "tribute" to Regex("\\btribute\\b"),
            "cover" to Regex("\\bcover\\b"),
            "remix" to Regex("\\bremix(?:ed)?\\b"),
            "mashup" to Regex("""\bmash[ -]?up\b|\b[a-z0-9]+\s+x\s+[a-z0-9]+\b"""),
            "demo" to Regex("\\bdemo\\b"),
            "slowed" to Regex("\\bslowed\\b"),
            "reverb" to Regex("\\breverb\\b"),
            "sped-up" to Regex("\\b" + Regex.escape("sped up") + "\\b"),
            "nightcore" to Regex("\\bnightcore\\b"),
            "radio-edit" to Regex("\\b" + Regex.escape("radio edit") + "\\b"),
            "extended" to Regex("\\bextended(?: version| mix)?\\b"),
            "atmos" to Regex("\\bdolby\\b|\\batmos\\b|\\bspatial\\b|360\\s*reality|sony\\s*360"),
        )
    }

    /**
     * True when DASH manifest XML carries E-AC-3 / Dolby Atmos (or JOC).
     */
    fun isAtmosManifest(mpdXml: String): Boolean {
        if (mpdXml.isBlank()) return false
        val lower = mpdXml.lowercase()
        return lower.contains("ec-3") || lower.contains("eac3") || lower.contains("ec3") ||
            lower.contains("atmos") || lower.contains("joc")
    }

    fun isSpatialManifest(mpdXml: String): Boolean {
        if (mpdXml.isBlank()) return false
        val lower = mpdXml.lowercase()
        return lower.contains("mha1") || lower.contains("mhm1") || lower.contains("mpeg-h") ||
            lower.contains("360ra") || lower.contains("sony_360") || lower.contains("spatial")
    }

    /** First `codecs=` value inside a base64 DASH data URL, or null when unreadable. */
    fun manifestCodecOf(dataUrl: String): String? {
        val b64 = dataUrl.substringAfter("base64,", "").trim()
        if (b64.isEmpty()) return null
        return runCatching {
            val xml = String(
                android.util.Base64.decode(b64, android.util.Base64.DEFAULT),
                Charsets.UTF_8,
            ).lowercase()
            MANIFEST_CODECS.find(xml)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** Extract audioSamplingRate from base64 DASH data URL or XML. */
    fun manifestSampleRateOf(dataUrl: String): Int? {
        val b64 = dataUrl.substringAfter("base64,", "")
        val xml = if (b64.isNotEmpty() && dataUrl.startsWith("data:application/dash+xml")) {
            runCatching {
                String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()
        } else if (dataUrl.trimStart().startsWith("<")) {
            dataUrl
        } else {
            null
        }
        if (xml.isNullOrBlank()) return null
        return MANIFEST_SAMPLE_RATE.find(xml)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
    }

    /** Depth from the rendition id (`FLAC_HIRES,48000,24` -> 24). Null when unreadable. */
    fun manifestBitDepthOf(dataUrl: String): Int? {
        val b64 = dataUrl.substringAfter("base64,", "")
        val xml = if (b64.isNotEmpty() && dataUrl.startsWith("data:application/dash+xml")) {
            runCatching {
                String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()
        } else if (dataUrl.trimStart().startsWith("<")) {
            dataUrl
        } else {
            null
        }
        if (xml.isNullOrBlank()) return null
        return MANIFEST_RENDITION.find(xml)?.groupValues?.getOrNull(2)?.toIntOrNull()?.takeIf { it in 8..32 }
    }

    /** True for E-AC-3 spatial codec labels. Pure; safe to unit-test on JVM. */
    fun isAtmosCodec(codec: String?): Boolean {
        val c = codec?.trim()?.lowercase().orEmpty()
        return c.startsWith("ec-3") || c.startsWith("eac3") || c.startsWith("ac-3")
    }

    /** True when the stream bytes are E-AC-3 spatial. Fail-open (false) when unreadable. */
    fun isAtmosStreamUrl(url: String): Boolean {
        if (url.startsWith("data:application/dash+xml")) {
            return isAtmosCodec(manifestCodecOf(url))
        }
        val lower = url.lowercase()
        return lower.contains("atmos") || lower.contains("eac3") || lower.contains("ec-3")
    }

    /** True when the stream points to a known prank or decoy CDN stream. */
    fun isDecoyStream(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        return lower.contains("pranks-cdn") || lower.contains("definatelynagato")
    }

    /**
     * Pull an MPD URI or inline XML/base64 out of the many JSON shapes the
     * backend uses for `/trackManifests/?atmos=true`.
     */
    fun extractAtmosManifestRef(json: JSONObject): AtmosManifestRef? {
        fun fromObject(obj: JSONObject?): AtmosManifestRef? {
            if (obj == null) return null
            sequenceOf("uri", "url", "manifestUrl", "mpdUrl").forEach { key ->
                val value = obj.optString(key)?.takeIf { it.isNotBlank() } ?: return@forEach
                if (value.startsWith("http", ignoreCase = true)) {
                    return AtmosManifestRef(mpdUri = value)
                }
            }
            val manifest = obj.optString("manifest")
            if (manifest.isNotBlank()) {
                val trimmed = manifest.trimStart()
                return when {
                    trimmed.startsWith("<") -> AtmosManifestRef(mpdXml = manifest)
                    trimmed.startsWith("http", ignoreCase = true) -> AtmosManifestRef(mpdUri = manifest)
                    else -> AtmosManifestRef(mpdBase64 = manifest)
                }
            }
            return null
        }

        fun walk(obj: JSONObject?, depth: Int): AtmosManifestRef? {
            if (obj == null || depth > 6) return null
            fromObject(obj)?.let { return it }
            obj.optJSONObject("attributes")?.let { walk(it, depth + 1) }?.let { return it }
            obj.optJSONObject("data")?.let { walk(it, depth + 1) }?.let { return it }
            return null
        }
        return walk(json, 0)
    }

    fun parseSpatialFlags(item: JSONObject): Pair<Boolean, Boolean> {
        var atmos = false
        var spatial = false
        fun consider(raw: String?) {
            val m = raw?.uppercase().orEmpty()
            if (m.isBlank()) return
            if (m.contains("DOLBY") || m.contains("ATMOS")) atmos = true
            if (m.contains("360") || m.contains("SONY") || (m.contains("SPATIAL") && !m.contains("ATMOS"))) {
                spatial = true
            }
        }
        val modes = item.optJSONArray("audioModes")
        val modeCount = modes?.length() ?: 0
        for (i in 0 until modeCount) consider(modes?.optString(i))
        val tags = item.optJSONObject("mediaMetadata")?.optJSONArray("tags")
            ?: item.optJSONArray("mediaMetadataTags")
        val tagCount = tags?.length() ?: 0
        for (i in 0 until tagCount) consider(tags?.optString(i))
        consider(item.optString("audioQuality"))
        return atmos to spatial
    }

    /**
     * Records a backend miss. One transient 404/503 must not black out the
     * whole chain; once misses repeat, back off for [FAILURE_COOLDOWN_MS].
     */
    private fun noteBackendFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= FAILURE_COOLDOWN_THRESHOLD) {
            failureCooldownUntilMs = System.currentTimeMillis() + FAILURE_COOLDOWN_MS
        }
    }

    fun invalidateCredentialsCache() {
        consecutiveFailures = 0
        failureCooldownUntilMs = 0L
    }

    /** All enabled addon endpoints, multi-addons first, then the single URL. */
    suspend fun getEnabledAddonEndpoints(): List<AddonEndpoint> = withContext(Dispatchers.IO) {
        preferences.current().toAddonEndpoints().map { AddonEndpoint(baseUrl = AddonClient.normalizeBase(it)) }
    }

    suspend fun resolveStream(
        title: String,
        artist: String,
        expectedDurationSeconds: Int? = null,
        expectedAlbum: String? = null,
        preferredQuality: Int = StreamingQuality.QUALITY_MAX_HI_RES,
        excludedUrls: Set<String> = emptySet(),
        isDownload: Boolean = false,
    ): LosslessAudioStream? = withContext(Dispatchers.IO) {
        if (preferredQuality == StreamingQuality.QUALITY_YOUTUBE || title.isBlank() || artist.isBlank()) {
            return@withContext null
        }

        val allEndpoints = getEnabledAddonEndpoints()
        if (allEndpoints.isEmpty()) {
            return@withContext null
        }

        if (allEndpoints.size == 1) {
            Log.i(TAG, "resolveStream starting for '$title' by '$artist' via addon (preferredQuality=$preferredQuality, isDownload=$isDownload)")
            val resolved = runCatching {
                resolveFromEndpoint(
                    title = title,
                    artist = artist,
                    expectedDurationSeconds = expectedDurationSeconds,
                    expectedAlbum = expectedAlbum,
                    preferredQuality = preferredQuality,
                    endpointBase = allEndpoints.first().baseUrl,
                    excludedUrls = excludedUrls,
                    isDownload = isDownload,
                )
            }.getOrNull()
            if (resolved != null) return@withContext resolved

            // Atmos fallback: if Dolby Atmos was selected but not found on the track,
            // fall back to stereo hi-res rather than failing and cooling down the backend.
            if (preferredQuality == StreamingQuality.QUALITY_DOLBY_ATMOS) {
                return@withContext resolveStream(
                    title = title,
                    artist = artist,
                    expectedDurationSeconds = expectedDurationSeconds,
                    expectedAlbum = expectedAlbum,
                    preferredQuality = StreamingQuality.QUALITY_MAX_HI_RES,
                    excludedUrls = excludedUrls,
                    isDownload = isDownload,
                )
            }

            noteBackendFailure()
            return@withContext null
        }

        Log.i(TAG, "resolveStream racing for '$title' by '$artist' across ${allEndpoints.size} addons concurrently (preferredQuality=$preferredQuality, isDownload=$isDownload)")
        val deferreds = allEndpoints.map { endpoint ->
            async {
                runCatching {
                    resolveFromEndpoint(
                        title = title,
                        artist = artist,
                        expectedDurationSeconds = expectedDurationSeconds,
                        expectedAlbum = expectedAlbum,
                        preferredQuality = preferredQuality,
                        endpointBase = endpoint.baseUrl,
                        excludedUrls = excludedUrls,
                        isDownload = isDownload,
                    )
                }.getOrNull()
            }
        }

        val candidates = deferreds.awaitAll().filterNotNull()
        if (candidates.isEmpty()) {
            if (preferredQuality == StreamingQuality.QUALITY_DOLBY_ATMOS) {
                return@withContext resolveStream(
                    title = title,
                    artist = artist,
                    expectedDurationSeconds = expectedDurationSeconds,
                    expectedAlbum = expectedAlbum,
                    preferredQuality = StreamingQuality.QUALITY_MAX_HI_RES,
                    excludedUrls = excludedUrls,
                    isDownload = isDownload,
                )
            }

            noteBackendFailure()
            return@withContext null
        }

        candidates.maxByOrNull { stream ->
            val tierScore = when (stream.formatId) {
                StreamingQuality.QUALITY_DOLBY_ATMOS -> 1000
                StreamingQuality.QUALITY_MAX_HI_RES -> 800
                StreamingQuality.QUALITY_HI_RES_96 -> 700
                StreamingQuality.QUALITY_CD_LOSSLESS -> 500
                StreamingQuality.QUALITY_MP3_320 -> 300
                StreamingQuality.QUALITY_DATA_SAVER -> 100
                else -> 50
            }
            tierScore + (stream.bitDepth * 5) + stream.samplingRate.toInt()
        }
    }

    private suspend fun resolveFromEndpoint(
        title: String,
        artist: String,
        expectedDurationSeconds: Int?,
        expectedAlbum: String?,
        preferredQuality: Int,
        endpointBase: String,
        excludedUrls: Set<String>,
        isDownload: Boolean = false,
    ): LosslessAudioStream? {
        val addonClient = AddonClient(endpointBase, client)
        val cleanArtist = cleanForSearch(artist).ifBlank { artist }
        val cleanTitle = cleanForSearch(title).ifBlank { title }
        val parsedTarget = parseTitle(title, cleanArtist, expectedAlbum)
        val searchableTitle = (parsedTarget.words + parsedTarget.versions).joinToString(" ").ifBlank { cleanTitle }

        val artistList = cleanArtist.split(ARTIST_SEPARATORS)
            .map { cleanForSearch(it) }
            .filter { it.isNotBlank() }
        val primaryArtist = artistList.firstOrNull().orEmpty()
        val secondaryArtist = artistList.getOrNull(1).orEmpty()

        val queries = listOfNotNull(
            if (primaryArtist.isNotBlank()) "$searchableTitle $primaryArtist".trim() else null,
            if (secondaryArtist.isNotBlank()) "$searchableTitle $secondaryArtist".trim() else null,
            searchableTitle.trim(),
            if (cleanArtist.isNotBlank() && cleanArtist != primaryArtist) "$searchableTitle $cleanArtist".trim() else null,
            if (parsedTarget.core.length >= 4) foldTransliteration(searchableTitle).trim() else null,
        ).filter { it.isNotBlank() }.distinct()

        val isAtmosPreferred = preferredQuality == StreamingQuality.QUALITY_DOLBY_ATMOS
        val qualityParam = when (preferredQuality) {
            StreamingQuality.QUALITY_DOLBY_ATMOS -> "lossless"
            StreamingQuality.QUALITY_MAX_HI_RES, StreamingQuality.QUALITY_HI_RES_96 -> "hi_res"
            StreamingQuality.QUALITY_CD_LOSSLESS -> "lossless"
            StreamingQuality.QUALITY_MP3_320 -> "high"
            StreamingQuality.QUALITY_DATA_SAVER -> "low"
            else -> "lossless"
        }

        var candidates: List<TidalCandidateItem> = emptyList()
        for (query in queries) {
            currentCoroutineContext().ensureActive()
            val searchResult = addonClient.search(query, qualityParam, isAtmosPreferred)
            val tracks = searchResult.getOrNull() ?: continue
            if (tracks.isEmpty()) continue

            val verified = tracks.asSequence()
                .map { track ->
                    TidalCandidateItem(
                        id = track.id.toLongOrNull() ?: track.id.hashCode().toLong(),
                        title = track.title,
                        duration = track.duration.toInt(),
                        performerName = track.artist,
                        albumArtistName = track.artist,
                        albumTitle = track.album,
                        performers = track.artist,
                        isAtmos = track.isDolbyAtmos || track.audioModes.any {
                            it.contains("DOLBY", ignoreCase = true) || it.contains("ATMOS", ignoreCase = true)
                        },
                        isSpatial = track.audioModes.any { it.contains("360", ignoreCase = true) || it.contains("SPATIAL", ignoreCase = true) },
                        rawAddonId = track.id,
                        audioQuality = track.audioQuality,
                        bitDepth = track.bitDepth,
                        directStreamUrl = track.directStreamUrl,
                    )
                }
                .mapNotNull { item ->
                    verifiedMatchScore(
                        item = item,
                        title = title,
                        artist = artist,
                        expectedDurationSeconds = expectedDurationSeconds,
                        expectedAlbum = expectedAlbum,
                    )?.let { score ->
                        var finalScore = score
                        if (isAtmosPreferred && isAtmosCandidate(item)) finalScore += 200
                        item to finalScore
                    }
                }
                .sortedWith(compareByDescending { it.second })
                .map { it.first }
                .distinctBy { it.rawAddonId.ifBlank { it.id.toString() } }
                .toList()

            if (verified.isNotEmpty()) {
                val gated = gateAmbiguousLanguage(verified, title, expectedAlbum)
                if (gated.isNotEmpty()) {
                    candidates = gated
                    break
                }
            }
        }

        if (candidates.isEmpty()) {
            Log.w(TAG, "resolveFromEndpoint: No matching candidate found for '$title' by '$artist'")
            return null
        }

        // Hi-res preference: hi-res-flagged masters first (stable — score
        // order kept within each group). A CD-only master otherwise scores
        // identically to the 24-bit master and backend order wins the coin
        // flip, parking playback at 16-bit forever.
        val wantsHiRes = qualityParam == "hi_res" && !isAtmosPreferred
        val ordered = if (isAtmosPreferred) {
            candidates.sortedWith(compareByDescending<TidalCandidateItem> { isAtmosCandidate(it) })
        } else if (wantsHiRes) {
            candidates.sortedWith(compareByDescending<TidalCandidateItem> { it.isHiResFlagged() })
        } else {
            candidates
        }

        // Tier waterfall between addon stream tiers.
        val qualitiesToTry = when {
            isAtmosPreferred -> listOf("atmos", "hi_res", "lossless", "high")
            qualityParam == "hi_res" -> listOf("hi_res", "lossless", "high")
            qualityParam == "lossless" -> listOf("lossless", "hi_res", "high")
            qualityParam == "high" -> listOf("high", "lossless", "hi_res")
            qualityParam == "low" -> listOf("low", "high", "lossless", "hi_res")
            else -> listOf(qualityParam, "lossless", "high")
        }
        for (q in qualitiesToTry) {
            val wantAtmos = q == "atmos"
            val targetCandidates = if (wantAtmos) {
                val atmosMatches = ordered.filter { isAtmosCandidate(it) }
                if (atmosMatches.isNotEmpty()) atmosMatches else ordered
            } else {
                val stereoMatches = ordered.filter { !isAtmosCandidate(it) }
                if (stereoMatches.isNotEmpty()) stereoMatches else ordered
            }

            val tierBudget = if (wantAtmos) 3 else if (wantsHiRes && q == "hi_res") 4 else 2
            for (candidate in targetCandidates.take(tierBudget)) {
                currentCoroutineContext().ensureActive()
                val trackId = candidate.rawAddonId.ifBlank { candidate.id.toString() }
                val streamResult = addonClient.stream(trackId, q, wantAtmos, isDownload = isDownload)
                val stream: AddonStream? = streamResult.getOrNull()

                val rawUrl = stream?.dataUrl?.takeIf { it.isNotBlank() }
                    ?: stream?.manifestXml?.takeIf { it.isNotBlank() }?.let { xml ->
                        val b64 = android.util.Base64.encodeToString(xml.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                        "data:application/dash+xml;base64,$b64"
                    }
                    ?: stream?.url?.takeIf { it.isNotBlank() }
                    ?: candidate.directStreamUrl?.takeIf { it.isNotBlank() }
                    ?: continue

                if (rawUrl in excludedUrls) continue
                if (isDecoyStream(rawUrl) || (stream?.url?.let(::isDecoyStream) == true)) {
                    Log.w(TAG, "resolveFromEndpoint: candidate $trackId returned decoy prank stream ($rawUrl); skipping")
                    continue
                }
                if (!wantAtmos && isAtmosStreamUrl(rawUrl)) continue

                // Atmos is a property of the STREAM (audioMode flag or spatial
                // URL), never of the request: a stereo fallback for an Atmos
                // preference must be labeled (and badged) as what it is.
                val isStreamAtmos = stream?.isDolbyAtmos == true ||
                    (stream?.audioMode?.contains("ATMOS", ignoreCase = true) == true) ||
                    isAtmosCodec(stream?.codec) ||
                    isAtmosStreamUrl(rawUrl)

                // Atmos isolation: a hi-res/CD/320 request must never come home
                // as Dolby (the URL check above misses manifests whose
                // spatial-ness is only in the audioMode flag). Atmos plays
                // only when explicitly selected (quality 28).
                if (!isAtmosPreferred && isStreamAtmos) {
                    Log.i(TAG, "resolveFromEndpoint: skipping spatial stream for track $trackId (stereo tier requested)")
                    continue
                }
                val manifestSampleRate = manifestSampleRateOf(rawUrl)
                val manifestDepth = manifestBitDepthOf(rawUrl)
                val streamSampleRate = stream?.sampleRate ?: 0.0
                val rawSampleRate = if (streamSampleRate > 1000.0) {
                    streamSampleRate
                } else if (streamSampleRate > 0.0) {
                    streamSampleRate * 1000.0
                } else {
                    44100.0
                }
                val effectiveSampleRate = manifestSampleRate?.toDouble() ?: rawSampleRate
                val streamCodec = stream?.codec.orEmpty().lowercase()
                val isLossyCodec = streamCodec.contains("mp4a") || streamCodec.contains("aac") ||
                    streamCodec.contains("mp3") || streamCodec.contains("opus")
                val isLossyStream = isLossyCodec || stream?.quality.equals("high", ignoreCase = true) ||
                    stream?.quality.equals("low", ignoreCase = true) || q == "high" || q == "low"

                val isHiResFlagged = !isLossyStream && (candidate.isHiResFlagged() ||
                    stream?.quality?.contains("HI_RES", ignoreCase = true) == true ||
                    stream?.quality?.contains("HI-RES", ignoreCase = true) == true ||
                    stream?.audioQuality?.contains("HI_RES", ignoreCase = true) == true ||
                    stream?.audioQuality?.contains("HI-RES", ignoreCase = true) == true ||
                    stream?.quality?.contains("24-BIT", ignoreCase = true) == true ||
                    stream?.quality?.contains("24BIT", ignoreCase = true) == true ||
                    stream?.quality?.contains("24/") == true ||
                    stream?.bitDepth == 24 ||
                    (manifestDepth ?: 0) >= 24 ||
                    (wantsHiRes && q == "hi_res") ||
                    effectiveSampleRate > 48000.0)
                // Depth precedence: manifest rendition id (ground truth for
                // these bytes) > addon numbers > hi-res inference. 0 stays
                // unknown — never fabricate 16 for an unmeasured depth.
                val reportedDepth = if (isLossyStream) {
                    null
                } else {
                    manifestDepth
                        ?: stream?.bitDepth?.takeIf { it > 0 }
                        ?: candidate.bitDepth?.takeIf { it > 0 }
                }
                val effectiveBitDepth = when {
                    isLossyStream -> null
                    (reportedDepth ?: 0) > 0 -> reportedDepth
                    effectiveSampleRate > 192000.0 -> 32
                    isHiResFlagged -> 24
                    else -> null
                }
                val formatId = when {
                    isStreamAtmos -> StreamingQuality.QUALITY_DOLBY_ATMOS
                    // 96k / Low / Data Saver tier:
                    q == "low" || stream?.quality.equals("low", ignoreCase = true) ||
                        stream?.audioQuality.equals("low", ignoreCase = true) ||
                        (isLossyCodec && (stream?.bitrate ?: 0) in 1..160_000) -> StreamingQuality.QUALITY_DATA_SAVER
                    // 320k / High tier:
                    q == "high" || stream?.quality.equals("high", ignoreCase = true) ||
                        stream?.audioQuality.equals("high", ignoreCase = true) ||
                        (isLossyCodec && (stream?.bitrate ?: 0) > 160_000) -> StreamingQuality.QUALITY_MP3_320
                    (effectiveBitDepth ?: 0) > 16 || effectiveSampleRate > 48000.0 || isHiResFlagged -> {
                        if (effectiveSampleRate > 96000.0) StreamingQuality.QUALITY_MAX_HI_RES else StreamingQuality.QUALITY_HI_RES_96
                    }
                    streamCodec.equals("flac", ignoreCase = true) || effectiveBitDepth == 16 -> StreamingQuality.QUALITY_CD_LOSSLESS
                    else -> StreamingQuality.QUALITY_CD_LOSSLESS
                }

                // A hi_res request answered with ≤16-bit/≤48kHz is a silent
                // downgrade (CD-only master), not a hi-res hit: keep
                // scanning candidates instead of parking playback at 16-bit
                // while a 24-bit master sits later in the list.
                val isHiResTierHit = formatId == StreamingQuality.QUALITY_MAX_HI_RES || formatId == StreamingQuality.QUALITY_HI_RES_96
                val isHiResAttempt = q == "hi_res"
                if (isHiResAttempt && !isStreamAtmos && !isHiResTierHit) {
                    Log.i(TAG, "resolveFromEndpoint: candidate $trackId answered hi_res with ${effectiveBitDepth ?: 0}-bit/${effectiveSampleRate}Hz; trying next candidate")
                    continue
                }

                Log.i(TAG, "resolveFromEndpoint: Acquired stream for track $trackId: formatId=$formatId, bitDepth=${effectiveBitDepth ?: "unknown"}, sampleRate=${effectiveSampleRate}Hz, codec=$streamCodec")
                consecutiveFailures = 0
                failureCooldownUntilMs = 0L

                val lowerUrlPath = rawUrl.substringBefore('?').lowercase()
                val isExplicitDash = rawUrl.startsWith("data:application/dash+xml") ||
                    lowerUrlPath.endsWith(".mpd") || lowerUrlPath.contains("/mpd")
                val streamMimeType = when {
                    isExplicitDash -> "application/dash+xml"
                    lowerUrlPath.endsWith(".m3u8") || lowerUrlPath.contains(".m3u8") -> "application/x-mpegURL"
                    // Atmos arrives as a direct progressive E-AC-3 MP4.
                    isStreamAtmos -> "audio/mp4"
                    lowerUrlPath.endsWith(".flac") -> "audio/flac"
                    lowerUrlPath.endsWith(".mp4") || lowerUrlPath.endsWith(".m4a") -> "audio/mp4"
                    lowerUrlPath.endsWith(".mp3") -> "audio/mpeg"
                    lowerUrlPath.endsWith(".aac") -> "audio/aac"
                    lowerUrlPath.endsWith(".ogg") || lowerUrlPath.endsWith(".opus") -> "audio/ogg"
                    streamCodec.contains("mp3") -> "audio/mpeg"
                    streamCodec.contains("aac") -> "audio/mp4"
                    streamCodec.contains("mp4a") -> "audio/mp4"
                    streamCodec.contains("opus") -> "audio/ogg"
                    streamCodec.contains("flac") -> "audio/flac"
                    formatId == StreamingQuality.QUALITY_MP3_320 -> "audio/mpeg"
                    formatId == StreamingQuality.QUALITY_DATA_SAVER -> "audio/mp4"
                    else -> "audio/flac"
                }

                val resolvedBitrateKbps = stream?.bitrate?.let { if (it > 10_000) it / 1000 else it }
                    ?: when (formatId) {
                        StreamingQuality.QUALITY_MP3_320 -> 320
                        StreamingQuality.QUALITY_DATA_SAVER -> 96
                        else -> null
                    }

                return LosslessAudioStream(
                    url = rawUrl,
                    mimeType = streamMimeType,
                    bitDepth = if (isLossyStream) 0 else (effectiveBitDepth ?: (if (isHiResFlagged || effectiveSampleRate > 48000.0) 24 else 0)),
                    samplingRate = effectiveSampleRate / 1000.0,
                    formatId = formatId,
                    bitrateKbps = resolvedBitrateKbps,
                    trackId = candidate.id,
                    durationSeconds = candidate.duration,
                    audioCodecOverride = when {
                        isStreamAtmos -> "DOLBY ATMOS"
                        formatId == StreamingQuality.QUALITY_MP3_320 -> "320 kbps"
                        formatId == StreamingQuality.QUALITY_DATA_SAVER -> "96 kbps"
                        streamCodec.equals("mp3", ignoreCase = true) -> "320 kbps"
                        else -> null
                    },
                )
            }
        }
        return null
    }

    private fun verifiedMatchScore(
        item: TidalCandidateItem,
        title: String,
        artist: String,
        expectedDurationSeconds: Int?,
        expectedAlbum: String?,
    ): Int? {
        val matchArtist = cleanForSearch(artist).ifBlank { artist }
        val wanted = parseTitle(title, matchArtist, expectedAlbum)
        val got = parseTitle(item.title, item.performerName, item.albumTitle)
        if (wanted.core.isBlank() || got.core.isBlank()) return null

        // 1. Version agreement: asking for original must never land on remix,
        //    live, acoustic, karaoke, etc. — and vice versa.
        if (wanted.versions != got.versions) {
            Log.d(TAG, "reject candidate id=${item.id}: version mismatch wanted=${wanted.versions} got=${got.versions} for '$title'")
            return null
        }

        // Severe mismatches (karaoke, instrumental, tribute, cover)
        val targetVariants = identityVariants(title, matchArtist)
        val candidateVariants = identityVariants(item.title, matchArtist)
        val severeMismatch = (!targetVariants.contains("instrumental") && candidateVariants.contains("instrumental")) ||
            (!targetVariants.contains("karaoke") && candidateVariants.contains("karaoke")) ||
            (!targetVariants.contains("tribute") && candidateVariants.contains("tribute")) ||
            (!targetVariants.contains("cover") && candidateVariants.contains("cover"))
        if (severeMismatch) {
            Log.d(TAG, "reject candidate id=${item.id}: severe variant mismatch target=$targetVariants candidate=$candidateVariants for '$title'")
            return null
        }

        // 2. Title core matching
        val isExactCore = wanted.core == got.core
        val isNormalizedCore = wanted.core.replace(" ", "") == got.core.replace(" ", "")
        val isPhoneticCore = foldTransliteration(wanted.core) == foldTransliteration(got.core)
        val coreDistance = levenshtein(wanted.core, got.core)
        val minLen = minOf(wanted.core.length, got.core.length)
        val isFuzzyCore = minLen >= 7 && coreDistance <= (if (minLen >= 14) 2 else 1)

        if (!isExactCore && !isNormalizedCore && !isPhoneticCore && !isFuzzyCore) return null

        // 3. Artist verification & exact-master duration tie-break
        val hasArtistMatch = isVerifiedArtistMatch(
            targetArtist = matchArtist,
            performer = item.performerName,
            albumArtist = item.albumArtistName,
            performersText = item.performers,
        )

        val durationDifference = if (expectedDurationSeconds != null && expectedDurationSeconds > 0 && item.duration > 0) {
            kotlin.math.abs(item.duration - expectedDurationSeconds)
        } else {
            null
        }

        val isLabel = isRecordLabel(matchArtist)
        val hasAlbumCtx = hasAlbumOrContextOverlap(wanted, got, expectedAlbum, item.albumTitle)

        // When credits disagree (e.g. composer vs singer in regional music, or
        // channel name like T-Series): allow only when the title is an exact
        // master match within 2s AND (it is a label OR album/context matches).
        val creditDisagreeOverride = !hasArtistMatch && (isExactCore || isNormalizedCore || isPhoneticCore) &&
            (isLabel || hasAlbumCtx) &&
            durationDifference != null && durationDifference <= 2

        if (!hasArtistMatch && !creditDisagreeOverride) {
            Log.d(TAG, "reject candidate id=${item.id}: artist mismatch target='$matchArtist' performer='${item.performerName}' for '$title'")
            return null
        }

        // Bug #2: same title + same artist in another language.
        val expectedLanguages = extractLanguages("$title ${expectedAlbum.orEmpty()}")
        val candidateLanguages = extractLanguages("${item.title} ${item.albumTitle}")
        if (expectedLanguages.isNotEmpty() && candidateLanguages.isNotEmpty() &&
            expectedLanguages.intersect(candidateLanguages).isEmpty()
        ) {
            Log.d(TAG, "reject candidate id=${item.id} title='${item.title}' album='${item.albumTitle}': language mismatch expected=$expectedLanguages candidate=$candidateLanguages for '$title'")
            return null
        }

        // 4. Duration ceiling
        val maxDurationDifference = when {
            hasArtistMatch && isExactCore -> 45
            hasArtistMatch -> 35
            else -> 2 // Credit disagree override strictly capped at 2 seconds
        }
        if (durationDifference != null && durationDifference > maxDurationDifference) {
            Log.d(TAG, "reject candidate id=${item.id}: duration ${item.duration}s vs expected ${expectedDurationSeconds}s (Δ${durationDifference}s > ${maxDurationDifference}s) for '$title'")
            return null
        }

        // 5. Scoring
        var score = 1_000 - coreDistance * 30
        if (isExactCore) score += 400
        else if (isNormalizedCore) score += 300
        else if (isPhoneticCore) score += 200

        if (hasArtistMatch) {
            score += 300
        } else {
            // Master tie-break penalty so any credited candidate beats it
            score -= 300
        }

        // Context alignment (shared movie/album/soundtrack name)
        if (wanted.context.isNotEmpty() && got.context.isNotEmpty() && wanted.context.any { it in got.context }) {
            score += 200
        }

        expectedAlbum?.takeIf(String::isNotBlank)?.let { album ->
            val normExpected = normalizeTitle(album, "")
            val normCandidate = normalizeTitle(item.albumTitle, "")
            if (normExpected.isNotBlank() && normCandidate.isNotBlank()) {
                when {
                    normExpected == normCandidate -> score += 500
                    normCandidate.contains(normExpected) || normExpected.contains(normCandidate) -> score += 300
                    else -> {
                        val expTokens = normExpected.split(' ').filter { it.length > 1 }.toSet()
                        val candTokens = normCandidate.split(' ').filter { it.length > 1 }.toSet()
                        val expNumbers = Regex("""\b\d+\b""").findAll(normExpected).map { it.value }.toSet()
                        val candNumbers = Regex("""\b\d+\b""").findAll(normCandidate).map { it.value }.toSet()
                        val numbersClash = expNumbers.isNotEmpty() && candNumbers.isNotEmpty() && expNumbers != candNumbers
                        val overlap = expTokens.intersect(candTokens).size
                        if (!numbersClash && expTokens.isNotEmpty() && overlap >= minOf(2, expTokens.size) && overlap * 2 >= expTokens.size) {
                            score += 150
                        } else {
                            score -= 100
                            Log.d(TAG, "album mismatch penalty id=${item.id}: expected='$album' candidate='${item.albumTitle}' for '$title'")
                        }
                    }
                }
            }
        }
        durationDifference?.let { score += (maxDurationDifference - it) * 5 }
        return score
    }

    private fun extractLanguages(raw: String): Set<String> {
        if (raw.isBlank()) return emptySet()
        return normalizeText(raw).split(' ').toSet().intersect(LANGUAGE_TOKENS)
    }

    private fun hasAlbumOrContextOverlap(
        wanted: TitleParts,
        got: TitleParts,
        expectedAlbum: String?,
        candidateAlbum: String?,
    ): Boolean {
        if (wanted.context.isNotEmpty() && got.context.isNotEmpty() && wanted.context.any { it in got.context }) {
            return true
        }
        if (wanted.context.isNotEmpty() && !candidateAlbum.isNullOrBlank()) {
            val normCand = normalizeText(candidateAlbum).split(' ').filter { it.length > 2 }.toSet()
            if (wanted.context.any { it in normCand }) return true
        }
        if (got.context.isNotEmpty() && !expectedAlbum.isNullOrBlank()) {
            val normExp = normalizeText(expectedAlbum).split(' ').filter { it.length > 2 }.toSet()
            if (got.context.any { it in normExp }) return true
        }
        if (!expectedAlbum.isNullOrBlank() && !candidateAlbum.isNullOrBlank()) {
            val normExp = normalizeText(expectedAlbum)
            val normCand = normalizeText(candidateAlbum)
            if (normExp.isNotBlank() && normCand.isNotBlank()) {
                if (normExp == normCand || normExp.contains(normCand) || normCand.contains(normExp)) return true
                val expTokens = normExp.split(' ').filter { it.length > 2 }.toSet()
                val candTokens = normCand.split(' ').filter { it.length > 2 }.toSet()
                if (expTokens.isNotEmpty() && candTokens.isNotEmpty() && expTokens.any { it in candTokens }) return true
            }
        }
        return false
    }

    /**
     * When multiple language releases exist and the request specifies an
     * expected language, filter candidates to match that language.
     * Never drops all candidates to empty.
     */
    private fun gateAmbiguousLanguage(
        verified: List<TidalCandidateItem>,
        title: String,
        expectedAlbum: String?,
    ): List<TidalCandidateItem> {
        if (verified.size < 2) return verified
        val expectedLanguages = extractLanguages("$title ${expectedAlbum.orEmpty()}")
        if (expectedLanguages.isNotEmpty()) {
            val matching = verified.filter {
                val candidateLangs = extractLanguages("${it.title} ${it.albumTitle}")
                candidateLangs.isEmpty() || candidateLangs.intersect(expectedLanguages).isNotEmpty()
            }
            if (matching.isNotEmpty()) return matching
        }
        return verified
    }

    private fun cleanForSearch(raw: String): String = raw
        .replace(TOPIC_CHANNEL_SUFFIX, "")
        .replace(PIPE_NOISE, "")
        .replace(SOUNDTRACK_SUFFIX, "")
        .replace(FEATURING_CLAUSE, " ")
        .replace(ATMOS_VERSION_MARKER, " ")
        .replace(BRACKETED_DISPLAY_NOISE, " ")
        .replace(TRAILING_DISPLAY_NOISE, " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun normalizeText(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .lowercase(Locale.ROOT)
        .replace("$", "s")
        .replace(NON_ALPHANUMERIC, " ")
        .replace(MULTI_SPACE, " ")
        .trim()

    private fun isRecordLabel(artist: String): Boolean {
        val norm = normalizeText(artist).replace(" ", "")
        return RECORD_LABELS.any { norm == it.replace(" ", "") || (it.length >= 4 && norm.contains(it.replace(" ", ""))) }
    }

    private fun normalizeTitle(raw: String, artist: String): String {
        var cleaned = cleanForSearch(raw)
        if (artist.isNotBlank()) {
            val cleanArt = cleanForSearch(artist).ifBlank { artist }
            cleaned = cleaned.replaceFirst(
                Regex("""^\s*${Regex.escape(cleanArt)}\s*[-–—:]\s*""", RegexOption.IGNORE_CASE),
                "",
            )
            cleaned = cleaned.replace(
                Regex("""(?i)\s*[-–—:]\s*${Regex.escape(cleanArt)}\s*$"""),
                "",
            )
        }
        return normalizeText(cleaned)
    }

    fun extractCoreTitle(title: String): String = title
        .replace(SOUNDTRACK_SUFFIX, " ")
        .replace(VERSION_NOISE_REGEX, " ")
        .replace(ATMOS_VERSION_MARKER, " ")
        .replace(BRACKETED_DISPLAY_NOISE, " ")
        .replace(TRAILING_DISPLAY_NOISE, " ")
        .replace(FEATURING_CLAUSE, " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    fun parseTitle(raw: String, artist: String = "", album: String? = null): TitleParts {
        val versions = sortedSetOf<String>()
        val context = mutableSetOf<String>()
        var text = raw.lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace("$", "s")

        // 1. Bracketed asides, innermost first: "(From "Satyamev Jayate")", "[Official Audio]"
        val bracketRegex = Regex("""[(\[]([^()\[\]]*)[)\]]""")
        repeat(3) {
            if (!bracketRegex.containsMatchIn(text)) return@repeat
            text = bracketRegex.replace(text) { match ->
                classifySegment(match.groupValues[1], versions, context)
                " "
            }
        }
        // Unbalanced bracket
        text.indexOfFirst { it == '(' || it == '[' }.takeIf { it >= 0 }?.let { open ->
            classifySegment(text.substring(open), versions, context)
            text = text.substring(0, open)
        }

        // 2. Dash-, colon-, and pipe-separated tails
        val dashRegex = Regex("""\s*[-–—:|]+\s*""")
        repeat(3) {
            val dash = dashRegex.find(text) ?: return@repeat
            val head = text.substring(0, dash.range.first).trim()
            val tail = text.substring(dash.range.last + 1).trim()
            if (head.isBlank() || tail.isBlank()) return@repeat

            if (isArtistOrAlbumName(head, artist, album)) {
                classifySegment(head, versions, context)
                text = tail
            } else if (isArtistOrAlbumName(tail, artist, album)) {
                classifySegment(tail, versions, context)
                text = head
            } else if (isSegmentMetadata(tail)) {
                classifySegment(tail, versions, context)
                text = head
            } else if (isSegmentMetadata(head)) {
                classifySegment(head, versions, context)
                text = tail
            } else {
                classifySegment(head, versions, context)
                text = tail
            }
        }

        // 3. Featuring clause
        text = text.replace(Regex("""\b(feat|ft|featuring|with)\b.*""", RegexOption.IGNORE_CASE), " ")

        var words = text.split(Regex("""[\s.·/]+"""))
            .map { it.replace(NON_ALPHANUMERIC, "") }
            .filter { it.isNotEmpty() && it !in JOINING_WORDS }

        while (words.size > 1 && words.last() in TRAILING_NOISE) {
            words = words.dropLast(1)
        }

        return TitleParts(
            words = words,
            core = words.joinToString(""),
            versions = versions,
            context = context,
        )
    }

    private fun classifySegment(
        segment: String,
        versions: MutableSet<String>,
        context: MutableSet<String>,
    ) {
        val words = segment.split(Regex("""[\s.·/]+"""))
            .map { it.replace(NON_ALPHANUMERIC, "") }
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return
        if (words.joinToString("") in NEUTRAL_SEGMENTS) return
        val marks = words.filter { it in VERSION_WORDS }
        if (marks.isNotEmpty()) {
            versions += marks
            return
        }
        context += words.filter { it.length > 2 && it !in NOISE_WORDS }
    }

    private fun isSegmentMetadata(segment: String): Boolean {
        val words = segment.split(Regex("""[\s.·/]+"""))
            .map { it.replace(NON_ALPHANUMERIC, "") }
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        if (words.joinToString("") in NEUTRAL_SEGMENTS) return true
        if (words.any { it in VERSION_WORDS }) return true
        return words.all { it in NOISE_WORDS || it in NEUTRAL_SEGMENTS || it in VERSION_WORDS }
    }

    private fun isArtistOrAlbumName(text: String, artist: String, album: String?): Boolean {
        if (text.isBlank()) return false
        val words = text.split(Regex("""[\s.·/]+""")).map { it.replace(NON_ALPHANUMERIC, "") }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return false

        val artistWords = artist.lowercase(Locale.ROOT).split(Regex("""[\s.·/]+"""))
            .map { it.replace(NON_ALPHANUMERIC, "") }
            .filter { it.isNotEmpty() }
            .toSet()
        if (words.all { it in artistWords } || (artistWords.isNotEmpty() && artistWords.all { it in words })) return true

        for (part in text.split(ARTIST_SEPARATORS)) {
            val partWords = part.split(Regex("""[\s.·/]+""")).map { it.replace(NON_ALPHANUMERIC, "") }.filter { it.isNotEmpty() }
            if (partWords.isNotEmpty() && artistWords.isNotEmpty() &&
                (partWords.all { it in artistWords } || artistWords.all { it in partWords })
            ) {
                return true
            }
        }

        if (!album.isNullOrBlank()) {
            val albumWords = album.lowercase(Locale.ROOT).split(Regex("""[\s.·/]+"""))
                .map { it.replace(NON_ALPHANUMERIC, "") }
                .filter { it.isNotEmpty() }
                .toSet()
            if (words.all { it in albumWords } || (albumWords.isNotEmpty() && albumWords.all { it in words })) return true
        }

        return false
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private fun identityVariants(raw: String, artist: String): Set<String> {
        val withoutArtistPrefix = if (artist.isBlank()) raw else raw.replaceFirst(
            Regex("""^\s*${Regex.escape(artist)}\s*[-–—:]\s*""", RegexOption.IGNORE_CASE),
            "",
        )
        val normalized = normalizeText(withoutArtistPrefix)
        return IDENTITY_VARIANT_PATTERNS.mapNotNullTo(linkedSetOf()) { (name, pattern) ->
            name.takeIf { pattern.containsMatchIn(normalized) }
        }
    }

    /** True for an Atmos/Spatial mix by backend flag OR by title label. */
    private fun isAtmosCandidate(item: TidalCandidateItem): Boolean =
        item.isAtmos || item.isSpatial ||
            identityVariants(item.title, item.performerName).contains("atmos")

    private fun foldTransliteration(str: String): String = str
        .replace("aa", "a")
        .replace("ee", "i")
        .replace("oo", "u")
        .replace("th", "t")
        .replace("dh", "d")
        .replace("bh", "b")
        .replace("kh", "k")
        .replace("gh", "g")
        .replace("sh", "s")
        .replace("zh", "z")
        .replace("ph", "f")
        .replace("v", "w")
        .replace("ll", "l")
        .replace("tt", "t")
        .replace("dd", "d")
        .replace("pp", "p")
        .replace("mm", "m")
        .replace("nn", "n")
        .replace("ss", "s")
        .replace("rr", "r")
        .replace("cc", "c")
        .replace("yy", "y")

    private fun cleanArtistIdentity(raw: String): String {
        val normalized = normalizeText(raw)
        // Collapse single-letter initials: "a r rahman" -> "ar rahman"
        val collapsed = normalized.replace(Regex("""\b([a-z])\s+(?=[a-z]\b)"""), "$1")
        return collapsed
            .split(' ')
            .filter { it !in ARTIST_NOISE_WORDS }
            .joinToString(" ")
            .trim()
    }

    internal fun artistNames(value: String): Set<List<String>> {
        val lowered = value.lowercase(Locale.ROOT).replace("$", "s")
        val collapsed = lowered
            .replace(Regex("""\b([a-z])\s*\.\s*(?=[a-z]\b)"""), "$1")
            .replace(Regex("""\b([a-z])\s+(?=[a-z]\b)"""), "$1")
        return collapsed
            .split(ARTIST_SEPARATORS)
            .map { name ->
                name.split(Regex("""[\s.·/]+"""))
                    .map { it.replace(NON_ALPHANUMERIC, "") }
                    .filter { it.length > 1 && it !in ARTIST_NOISE_WORDS }
            }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun runOfWords(outer: List<String>, inner: List<String>): Boolean {
        if (inner.isEmpty() || inner.size > outer.size) return false
        if (inner.size == 1 && outer.size > 1) return false
        return (0..outer.size - inner.size).any { at ->
            outer.subList(at, at + inner.size) == inner
        }
    }

    private fun isVerifiedArtistMatch(
        targetArtist: String,
        performer: String,
        albumArtist: String,
        performersText: String?,
    ): Boolean {
        val target = cleanArtistIdentity(targetArtist)
        if (target.isBlank()) return false
        val primaryIdentities = listOf(performer, albumArtist)
            .map(::cleanArtistIdentity)
            .filter(String::isNotBlank)
        if (primaryIdentities.any { it == target }) return true

        val targetArtists = artistNames(targetArtist)
        val candidateArtists = (listOf(performer, albumArtist) + listOfNotNull(performersText))
            .filter(String::isNotBlank)
            .flatMap { artistNames(it) }
            .toSet()

        if (targetArtists.isEmpty() || candidateArtists.isEmpty()) return false

        for (ta in targetArtists) {
            for (ca in candidateArtists) {
                if (ta == ca) return true
                if (runOfWords(ta, ca) || runOfWords(ca, ta)) return true
            }
        }

        val performingCredits = performersText.orEmpty()
            .split(Regex("""\s+-\s+"""))
            .map(::cleanArtistIdentity)
            .filter { credit -> PERFORMING_ROLE_WORDS.any { role -> role in credit.split(' ') } }
        val performingTokens = (primaryIdentities + performingCredits)
            .flatMap { it.split(' ') }
            .toSet()
        val targetTokens = target.split(' ').filter { it.length > 1 }.toSet()
        return targetTokens.isNotEmpty() && targetTokens.all(performingTokens::contains)
    }
}

/** URI or inline MPD extracted from an addon manifest payload. */
data class AtmosManifestRef(
    val mpdUri: String? = null,
    val mpdXml: String? = null,
    val mpdBase64: String? = null,
)
