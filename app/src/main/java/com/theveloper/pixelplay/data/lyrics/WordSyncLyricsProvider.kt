package com.theveloper.pixelplay.data.lyrics

import android.util.Base64
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.SyncedLine
import com.theveloper.pixelplay.data.model.SyncedWord
import com.theveloper.pixelplay.utils.LyricsUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.InflaterInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Word-synchronised lyrics sourced the way LastWave does it.
 *
 * Provider chain (first hit wins):
 *  1. BetterLyrics (`lyrics-api.boidu.dev`) – Apple Music TTML, no key.
 *  2. Apple Music through the Lyrically aggregator, matched via iTunes.
 *  3. Kugou KRC – decrypted locally, syllable timings.
 *
 * Every provider returns per-word `time` **and** `durationMs`, which is what
 * enables PixelPlayer's progressive karaoke fill. Line-synced LRC remains the
 * fallback handled by the existing LyricsRepository pipeline.
 */
@Singleton
class WordSyncLyricsProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private val client = okHttpClient.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun fetch(
        title: String,
        artist: String,
        album: String? = null,
        durationMs: Long = 0L,
        videoId: String? = null,
    ): Lyrics? = withContext(Dispatchers.IO) {
        if (title.isBlank() && videoId.isNullOrBlank()) return@withContext null
        val durationSeconds = durationMs.takeIf { it > 0 }?.div(1_000L)?.toInt()
        val cleanArtistName = artist.takeIf { it.isNotBlank() }

        // Race every source, the way LastWave does. Sequential fallbacks made
        // a slow first provider delay the whole lookup, and a provider missing
        // from the chain meant line-only LRCLIB lyrics even though a word-synced
        // copy existed elsewhere. The first word-timed hit wins; line-timed
        // results stay only as a last resort.
        withTimeoutOrNull(RACE_TIMEOUT_MS) {
            coroutineScope {
                val requests = mutableListOf<Deferred<Lyrics?>>()
                fun race(provider: suspend () -> Lyrics?) {
                    requests += async { runCatching { provider() }.getOrNull() }
                }

                // Exact video id: the cut that is actually playing.
                race { fetchSimpMusic(videoId, durationSeconds) }
                // Apple syllable TTML by recording (Bini -> lrc.red by ISRC).
                race { fetchBini(title, cleanArtistName, album, durationSeconds) }
                race { fetchUnison(title, cleanArtistName, album, durationSeconds) }
                race { fetchBetterLyrics(title, artist, album, durationSeconds) }
                race { fetchAppleMusic(title, artist, durationSeconds) }
                race { fetchKugou(title, artist, durationSeconds) }

                var lineFallback: Lyrics? = null
                try {
                    while (requests.isNotEmpty()) {
                        val (finished, result) = select<Pair<Deferred<Lyrics?>, Lyrics?>> {
                            requests.forEach { request ->
                                request.onAwait { value -> request to value }
                            }
                        }
                        requests.remove(finished)
                        if (result == null) continue
                        if (result.hasWordTiming()) return@coroutineScope result
                        if (lineFallback == null) lineFallback = result
                    }
                } finally {
                    requests.forEach { it.cancel() }
                }
                lineFallback
            }
        }
    }

    private fun Lyrics.hasWordTiming(): Boolean =
        synced.orEmpty().any { !it.words.isNullOrEmpty() }

    // ---------------------------------------------------------------------
    // SimpMusic (exact video id, rich enhanced LRC)
    // ---------------------------------------------------------------------

    private suspend fun fetchSimpMusic(
        videoId: String?,
        durationSeconds: Int?,
    ): Lyrics? {
        val id = videoId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val body = requestBody(
            "https://api-lyrics.simpmusic.org/v1/$id",
            accept = "application/json",
        ) ?: return null
        val response = runCatching { json.decodeFromString<SimpResponse>(body) }.getOrNull()
            ?: return null
        if (!response.success) return null
        val expected = durationSeconds?.takeIf { it > 0 }
        val track = response.data.orEmpty()
            .filter { track ->
                expected == null || (track.duration ?: 0) <= 0 ||
                    abs((track.duration ?: 0) - expected) <= SIMP_DURATION_TOLERANCE_SECONDS
            }
            .minByOrNull { track ->
                if (expected == null) 0 else abs((track.duration ?: 0) - expected)
            } ?: return null
        val raw = track.richSyncLyrics?.takeIf { it.isNotBlank() }
            ?: track.syncedLyrics?.takeIf { it.isNotBlank() }
            ?: return null
        return parseDocument(raw)?.takeIf { it.isNotEmpty() }?.let(::lyricsFrom)
    }

    // ---------------------------------------------------------------------
    // Bini / lrc.red (Apple syllable TTML)
    // ---------------------------------------------------------------------

    private suspend fun fetchBini(
        title: String,
        artist: String?,
        album: String?,
        durationSeconds: Int?,
    ): Lyrics? {
        val hit = searchBini(title, artist, album, durationSeconds) ?: return null
        val document = hit.lyricsUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { requestBody(it, accept = "application/xml, text/xml, */*") }
        if (!document.isNullOrBlank()) {
            parseDocument(document)
                ?.takeIf { lines -> lines.any { !it.words.isNullOrEmpty() } }
                ?.let { return lyricsFrom(it) }
        }
        val isrc = hit.isrc?.takeIf { it.isNotBlank() } ?: return null
        return fetchLrcRed(isrc)
    }

    private suspend fun searchBini(
        title: String,
        artist: String?,
        album: String?,
        durationSeconds: Int?,
    ): BiniHit? {
        val queries = buildList {
            if (!artist.isNullOrBlank()) {
                val shaped = buildList {
                    add("track" to title.trim())
                    add("artist" to artist.trim())
                    album?.takeIf { it.isNotBlank() }?.let { add("album" to it.trim()) }
                    durationSeconds?.takeIf { it > 0 }?.let { add("duration" to it.toString()) }
                }
                add(shaped)
                add(listOf("q" to "$artist - $title".trim()))
                add(listOf("q" to title.trim()))
            } else {
                add(listOf("q" to title.trim()))
            }
        }

        for (params in queries) {
            val url = "https://lyrics-api.binimum.org/".toHttpUrlOrNull()
                ?.newBuilder()
                ?.apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }
                ?.build()
                ?.toString() ?: continue
            val body = requestBody(url, accept = "application/json") ?: continue
            val hits = runCatching { json.decodeFromString<BiniSearchResponse>(body).results }
                .getOrNull()
                .orEmpty()
            selectBiniHit(hits, title, artist, durationSeconds)?.let { return it }
        }
        return null
    }

    private fun selectBiniHit(
        hits: List<BiniHit>,
        title: String,
        artist: String?,
        durationSeconds: Int?,
    ): BiniHit? {
        val floor = if (artist.isNullOrBlank()) 1 else MIN_BINI_SCORE
        return hits
            .filter { sameVersion(title, it.trackName.orEmpty()) }
            .map { hit -> hit to biniScore(hit, title, artist, durationSeconds) }
            .filter { (_, score) -> score >= floor }
            .sortedWith(
                compareByDescending<Pair<BiniHit, Int>> { (_, score) -> score }
                    .thenByDescending { (hit, _) -> hit.timingType.equals("word", ignoreCase = true) }
                    .thenBy { (hit, _) -> biniDurationDelta(hit, durationSeconds) },
            )
            .firstOrNull()
            ?.first
    }

    private fun biniScore(
        hit: BiniHit,
        title: String,
        artist: String?,
        durationSeconds: Int?,
    ): Int {
        val candidateTitle = cleanTitle(hit.trackName.orEmpty())
        val requestedTitle = cleanTitle(title)
        var score = when {
            candidateTitle.equals(requestedTitle, ignoreCase = true) -> 3
            titlesMatch(candidateTitle, requestedTitle) -> 1
            else -> return 0
        }
        if (!artist.isNullOrBlank() && artistMatches(hit.artistName.orEmpty(), artist)) {
            score += 2
        }
        val expected = durationSeconds?.takeIf { it > 0 }
        val candidate = hit.duration?.takeIf { it > 0 }
        if (expected != null && candidate != null) {
            score += when (abs(candidate - expected)) {
                in 0..3 -> 3
                in 4..10 -> 1
                else -> 0
            }
        }
        return score
    }

    private fun biniDurationDelta(hit: BiniHit, durationSeconds: Int?): Long {
        if (durationSeconds == null || durationSeconds <= 0 || (hit.duration ?: 0) <= 0) return 0L
        return abs((hit.duration ?: 0) - durationSeconds).toLong()
    }

    private suspend fun fetchLrcRed(isrc: String): Lyrics? {
        val cleanIsrc = isrc.trim().uppercase()
        if (cleanIsrc.length != 12) return null
        val ttml = requestBody(
            "https://lrc.red/s/$cleanIsrc.ttml",
            accept = "application/xml, text/xml, */*",
        )
        if (!ttml.isNullOrBlank()) {
            parseDocument(ttml)
                ?.takeIf { lines -> lines.any { !it.words.isNullOrEmpty() } }
                ?.let { return lyricsFrom(it) }
        }
        val lrc = requestBody("https://lrc.red/s/$cleanIsrc.lrc")
        return lrc
            ?.takeIf { it.isNotBlank() }
            ?.let(::parseDocument)
            ?.takeIf { it.isNotEmpty() }
            ?.let(::lyricsFrom)
    }

    // ---------------------------------------------------------------------
    // Unison (community database)
    // ---------------------------------------------------------------------

    private suspend fun fetchUnison(
        title: String,
        artist: String?,
        album: String?,
        durationSeconds: Int?,
    ): Lyrics? {
        if (artist.isNullOrBlank()) return null
        val url = "https://unison.boidu.dev/lyrics".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("song", title.trim())
            ?.addQueryParameter("artist", artist.trim())
            ?.apply {
                if (!album.isNullOrBlank()) addQueryParameter("album", album.trim())
                if (durationSeconds != null && durationSeconds > 0) {
                    addQueryParameter("duration", durationSeconds.toString())
                }
            }
            ?.build()
            ?.toString() ?: return null
        val body = requestBody(url, accept = "application/json") ?: return null
        val response = runCatching { json.decodeFromString<UnisonResponse>(body) }.getOrNull()
            ?: return null
        if (response.success != true) return null
        val entry = response.data ?: return null
        if (entry.syncType.equals("plain", ignoreCase = true)) return null
        val raw = entry.lyrics?.takeIf { it.isNotBlank() } ?: return null
        return parseDocument(raw)?.takeIf { it.isNotEmpty() }?.let(::lyricsFrom)
    }

    // ---------------------------------------------------------------------
    // BetterLyrics
    // ---------------------------------------------------------------------

    private suspend fun fetchBetterLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?,
    ): Lyrics? {
        val endpoints = listOf(
            "https://lyrics-api.boidu.dev/getLyrics",
            "https://lyrics-api.boidu.dev/ttml/getLyrics",
            "https://lyrics-api.boidu.dev/qq/getLyrics",
        )
        val queries = buildList {
            add(cleanTitle(title) to cleanArtist(artist))
            val cleanedTitle = cleanTitle(title)
            val cleanedArtist = cleanArtist(artist)
            if (cleanedTitle != title || cleanedArtist != artist) {
                add(cleanedTitle to cleanedArtist)
            }
        }
        for ((queryTitle, queryArtist) in queries) {
            for (endpoint in endpoints) {
                val url = endpoint.toHttpUrlOrNull()?.newBuilder()
                    ?.addQueryParameter("s", queryTitle)
                    ?.addQueryParameter("a", queryArtist)
                    ?.apply {
                        if (durationSeconds != null && durationSeconds > 0) {
                            addQueryParameter("d", durationSeconds.toString())
                        }
                        if (!album.isNullOrBlank()) addQueryParameter("al", album.trim())
                    }
                    ?.build() ?: continue
                val body = requestBody(url.toString(), accept = "application/json, text/xml, */*")
                    ?: continue
                parseDocument(body)?.takeIf { it.isNotEmpty() }?.let { return lyricsFrom(it) }
            }
        }
        return null
    }

    private fun parseDocument(raw: String): List<SyncedLine>? {
        val payload = unwrapPayload(raw) ?: return null
        if (payload.isBlank()) return null
        if ("<tt" in payload.lowercase() || "http://www.w3.org/ns/ttml" in payload) {
            TtmlLyricsParser.parse(payload).takeIf { it.isNotEmpty() }?.let { return it }
        }
        val parsed = runCatching { LyricsUtils.parseLyrics(payload).synced }.getOrNull()
        return parsed?.takeIf { it.isNotEmpty() }
    }

    private fun unwrapPayload(raw: String): String? {
        val trimmed = raw.replace("\uFEFF", "").trim()
        if (trimmed.isBlank()) return null
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return trimmed
        return try {
            extractContent(json.parseToJsonElement(trimmed))?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            trimmed
        }
    }

    private fun extractContent(element: JsonElement): String? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> if (element.isString) {
            val text = element.content.trim()
            if (text.isEmpty()) {
                null
            } else if (text.startsWith("{") || text.startsWith("[")) {
                val nested = runCatching { json.parseToJsonElement(text) }.getOrNull()
                nested?.let { extractContent(it) } ?: text
            } else {
                text
            }
        } else {
            null
        }
        is JsonArray -> element.mapNotNull(::extractContent)
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
        is JsonObject -> {
            if (element["isError"]?.toString() == "true" || element["ok"]?.toString() == "false") return null
            val keys = listOf(
                "ttml", "ttmlContent", "lyrics", "lrc", "content", "text",
                "plainLyrics", "syncedLyrics", "line", "lines", "lyric",
                "data", "result", "response",
            )
            keys.asSequence().mapNotNull { key -> element[key]?.let(::extractContent) }.firstOrNull()
        }
    }

    // ---------------------------------------------------------------------
    // Apple Music (iTunes match + Lyrically aggregator)
    // ---------------------------------------------------------------------

    private suspend fun fetchAppleMusic(
        title: String,
        artist: String,
        durationSeconds: Int?,
    ): Lyrics? {
        val expectedMs = durationSeconds?.takeIf { it > 0 }?.times(1_000L)
        val trackId = resolveITunesTrackId(title, artist, expectedMs) ?: run {
            val cleanedTitle = cleanTitle(title)
            val cleanedArtist = cleanArtist(artist)
            if (cleanedTitle != title || cleanedArtist != artist) {
                resolveITunesTrackId(cleanedTitle, cleanedArtist, expectedMs)
            } else {
                null
            }
        } ?: return null

        fetchAppleTtml(trackId)?.takeIf { it.isNotEmpty() }?.let { return lyricsFrom(it) }

        val envelope = fetchAppleEnvelope(trackId) ?: return null
        mapPaxLines(envelope.lyrics)?.takeIf { it.isNotEmpty() }?.let { return lyricsFrom(it) }
        val lrc = envelope.lrc?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            LyricsUtils.parseLyrics(lrc).takeIf { !it.synced.isNullOrEmpty() }
        }.getOrNull()?.copy(areFromRemote = true)
    }

    private suspend fun resolveITunesTrackId(
        title: String,
        artist: String,
        expectedMs: Long?,
    ): Long? {
        if (title.isBlank()) return null
        val term = if (artist.isNotBlank()) "$title $artist" else title
        val url = "https://itunes.apple.com/search?term=${URLEncoder.encode(term, "UTF-8")}" +
            "&media=music&entity=song&limit=5"
        val body = requestBody(url, accept = "application/json") ?: return null
        val results = runCatching {
            json.decodeFromString<ITunesSearchResponse>(body).results
        }.getOrNull() ?: return null

        return results
            .filter { it.trackId > 0L }
            .filter { sameVersion(title, it.trackName.orEmpty()) }
            .mapNotNull { song ->
                val score = matchScore(song, title, artist, expectedMs)
                if (score < MIN_MATCH_SCORE) null else song to score
            }
            .maxByOrNull { it.second }
            ?.first
            ?.trackId
    }

    private fun matchScore(
        song: ITunesSong,
        title: String,
        artist: String,
        expectedMs: Long?,
    ): Int {
        val candidateTitle = song.trackName.orEmpty()
        val candidateArtist = song.artistName.orEmpty()
        var score = 0
        val cleanCandidateTitle = cleanTitle(candidateTitle)
        val cleanRequestedTitle = cleanTitle(title)
        score += when {
            cleanCandidateTitle.equals(cleanRequestedTitle, ignoreCase = true) -> 3
            titlesMatch(cleanCandidateTitle, cleanRequestedTitle) -> 1
            else -> return 0
        }
        score += if (
            candidateArtist.isNotBlank() &&
            artist.isNotBlank() &&
            artistMatches(candidateArtist, artist)
        ) 2 else 0
        val candidateMs = song.trackTimeMillis
        if (expectedMs != null && expectedMs > 0 && candidateMs != null && candidateMs > 0) {
            score += when (abs(candidateMs - expectedMs)) {
                in 0..3_000L -> 3
                in 3_001L..DURATION_TOLERANCE_MS -> 1
                else -> 0
            }
        }
        return score
    }

    private suspend fun fetchAppleTtml(trackId: Long): List<SyncedLine>? {
        val body = requestBody(
            "https://lyrics.paxsenix.org/apple-music/lyrics?id=$trackId&ttml=true",
            accept = "application/json, text/xml, */*",
        ) ?: return null
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        val ttml = extractTtml(trimmed) ?: trimmed
        if ("<tt" !in ttml.lowercase() && "http://www.w3.org/ns/ttml" !in ttml) return null
        return TtmlLyricsParser.parse(ttml).takeIf { it.isNotEmpty() }
    }

    private fun extractTtml(raw: String): String? {
        if (!raw.startsWith("{") && !raw.startsWith("[")) return raw
        return runCatching { findTtmlString(json.parseToJsonElement(raw)) }.getOrNull()
    }

    private fun findTtmlString(element: JsonElement): String? = when (element) {
        is JsonPrimitive -> if (element.isString) {
            element.content.takeIf { "<tt" in it.lowercase() }
        } else {
            null
        }
        is JsonArray -> element.firstNotNullOfOrNull(::findTtmlString)
        is JsonObject -> {
            val keys = listOf("ttml", "ttmlContent", "lyrics", "lrc", "content", "text", "data", "result")
            keys.firstNotNullOfOrNull { key -> element[key]?.let(::findTtmlString) }
                ?: element.values.firstNotNullOfOrNull(::findTtmlString)
        }
        else -> null
    }

    private suspend fun fetchAppleEnvelope(trackId: Long): PaxEnvelope? {
        val body = requestBody(
            "https://lyrics.paxsenix.org/apple-music/lyrics?id=$trackId&v=2",
            accept = "application/json",
        ) ?: return null
        return runCatching { json.decodeFromString<PaxEnvelope>(body) }.getOrNull()
    }

    private fun mapPaxLines(lines: List<PaxLine>): List<SyncedLine>? {
        val mapped = lines.mapNotNull { line ->
            val words = line.text.filter { it.text.isNotBlank() }
            if (words.isEmpty()) return@mapNotNull null
            val tokens = mutableListOf<String>()
            val synced = mutableListOf<SyncedWord>()
            words.forEach { word ->
                val text = word.text.trim()
                if (text.isEmpty()) return@forEach
                val continues = word.part && synced.isNotEmpty()
                if (continues) {
                    // PixelPlayer lays words out as separate items, so a
                    // syllable that continues the previous word is folded
                    // back in instead of rendering "Hel lo".
                    val previous = synced.last()
                    synced[synced.lastIndex] = previous.copy(
                        word = previous.word + text,
                        durationMs = (previous.durationMs + word.duration.toInt()).coerceAtLeast(0),
                    )
                    tokens[tokens.lastIndex] = tokens.last() + text
                    return@forEach
                }
                tokens += text
                val duration = word.duration.takeIf { it > 0 } ?: (word.endtime - word.timestamp)
                synced += SyncedWord(
                    time = word.timestamp.coerceAtLeast(0L).toInt(),
                    word = text,
                    startsNewWord = true,
                    durationMs = duration.coerceAtLeast(0L).toInt(),
                )
            }
            if (synced.isEmpty()) return@mapNotNull null
            SyncedLine(
                time = (line.timestamp.takeIf { it > 0L } ?: synced.first().time.toLong())
                    .coerceAtLeast(0L)
                    .toInt(),
                line = tokens.joinToString(" "),
                words = clampWordEdges(synced),
            )
        }.sortedBy { it.time }
        return mapped.takeIf { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------------
    // Kugou KRC
    // ---------------------------------------------------------------------

    private suspend fun fetchKugou(
        title: String,
        artist: String,
        durationSeconds: Int?,
    ): Lyrics? {
        val cleanedTitle = cleanTitle(title)
        val cleanedArtist = cleanArtist(artist)
        val searchTitle = cleanedTitle
        val searchUrl = "https://lyrics.kugou.com/search".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("ver", "1")
            ?.addQueryParameter("man", "yes")
            ?.addQueryParameter("client", "pc")
            ?.addQueryParameter("keyword", "$cleanedArtist - $searchTitle")
            ?.addQueryParameter("duration", if (durationSeconds != null && durationSeconds > 0) "${durationSeconds * 1000}" else "")
            ?.addQueryParameter("hash", "")
            ?.build() ?: return null

        val searchBody = requestBody(searchUrl.toString()) ?: return null
        val search = runCatching { json.decodeFromString<KugouSearchResponse>(searchBody) }.getOrNull()
            ?: return null
        if (search.candidates.isEmpty()) return null

        val matched = search.candidates.filter { candidate ->
            sameVersion(title, candidate.song) &&
                artistMatches(candidate.singer, cleanedArtist) &&
                (titlesMatch(cleanTitle(candidate.song), cleanedTitle) ||
                    titlesMatch(cleanTitle(candidate.song), searchTitle))
        }
        if (matched.isEmpty()) return null

        fun durationDelta(candidate: KugouCandidate): Long {
            if (durationSeconds == null || durationSeconds <= 0 || candidate.duration <= 0) return 0L
            return abs(candidate.duration - durationSeconds * 1_000L)
        }

        val candidate = matched.firstOrNull { durationDelta(it) <= 8_000L }
            ?: matched.firstOrNull { durationDelta(it) <= 30_000L }
            ?: matched.first()
        val downloadUrl = "https://lyrics.kugou.com/download".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("ver", "1")
            ?.addQueryParameter("client", "pc")
            ?.addQueryParameter("id", candidate.id)
            ?.addQueryParameter("accesskey", candidate.accesskey)
            ?.addQueryParameter("fmt", "krc")
            ?.addQueryParameter("charset", "utf8")
            ?.build() ?: return null
        val downloadBody = requestBody(downloadUrl.toString()) ?: return null
        val download = runCatching { json.decodeFromString<KugouDownloadResponse>(downloadBody) }.getOrNull()
            ?: return null
        val rawBase64 = download.content ?: return null
        val krc = decryptKrc(rawBase64) ?: return null
        return parseKrc(krc)?.takeIf { it.isNotEmpty() }?.let(::lyricsFrom)
    }

    private fun decryptKrc(base64Content: String): String? = try {
        val encrypted = Base64.decode(base64Content, Base64.DEFAULT)
        if (encrypted.size <= 4) {
            null
        } else {
            val key = byteArrayOf(
                0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
                0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69,
            )
            val xor = ByteArray(encrypted.size - 4)
            for (i in 4 until encrypted.size) {
                xor[i - 4] = (encrypted[i].toInt() xor key[(i - 4) % key.size].toInt()).toByte()
            }
            InflaterInputStream(ByteArrayInputStream(xor)).use { inflater ->
                ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(1_024)
                    var read: Int
                    while (inflater.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                    output.toString("UTF-8")
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    internal fun parseKrc(krcText: String): List<SyncedLine>? {
        val lines = mutableListOf<SyncedLine>()
        var globalOffsetMs = 0L
        var isFirstRawLine = true
        for (rawLine in krcText.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || !trimmed.startsWith("[")) continue
            val offsetMatch = krcOffsetRegex.matchEntire(trimmed)
            if (offsetMatch != null) {
                globalOffsetMs = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
                continue
            }
            val match = krcLineRegex.matchEntire(trimmed) ?: continue
            val lineStartMs = ((match.groupValues[1].toLongOrNull() ?: continue) + globalOffsetMs)
                .coerceAtLeast(0L)
            val lineDurationMs = match.groupValues[2].toLongOrNull() ?: 0L
            val syllablesContent = match.groupValues[3]

            val rawSyllables = mutableListOf<RawSyllable>()
            val textBuilder = StringBuilder()
            for (syllable in krcSyllableRegex.findAll(syllablesContent)) {
                val offsetMs = syllable.groupValues[1].toLongOrNull() ?: 0L
                val durationMs = syllable.groupValues[2].toLongOrNull() ?: 0L
                val text = syllable.groupValues[3]
                textBuilder.append(text)
                if (text.isBlank()) continue
                rawSyllables += RawSyllable(
                    timeMs = (lineStartMs + offsetMs).coerceAtLeast(0L),
                    durationMs = durationMs.coerceAtLeast(0L),
                    text = text,
                )
            }
            val fullText = textBuilder.toString().trim()
            if (fullText.isEmpty() || rawSyllables.isEmpty()) continue
            if (isCreditLine(fullText, isFirstRawLine)) continue
            isFirstRawLine = false

            // KRC stores raw syllables, often splitting Latin words ("Hel" +
            // "lo"). FlowRow renders every word as a separate chip, so fold
            // connected syllables back into display words while keeping CJK
            // characters separate (their timing is already per character).
            val lineWords = mergeKrcWords(rawSyllables, fullText)
            if (lineWords.isEmpty()) continue
            lines += SyncedLine(
                time = minOf(lineStartMs, lineWords.first().time.toLong()).toInt(),
                line = fullText,
                words = clampWordEdges(lineWords, lineDurationMs, lineStartMs),
            )
        }
        return lines.sortedBy { it.time }.takeIf { it.isNotEmpty() }
    }

    private fun mergeKrcWords(
        syllables: List<RawSyllable>,
        fullText: String,
    ): List<SyncedWord> {
        val keepSyllablesSeparate = fullText.any(::isWideScript)
        val words = mutableListOf<SyncedWord>()
        var previousHadTrailingSpace = true
        syllables.forEach { syllable ->
            val text = syllable.text
            val display = text.trim()
            if (display.isEmpty()) {
                previousHadTrailingSpace = true
                return@forEach
            }
            val startsWithSpace = text.firstOrNull()?.isWhitespace() == true
            val canMerge = !keepSyllablesSeparate &&
                !startsWithSpace &&
                !previousHadTrailingSpace &&
                words.isNotEmpty()
            if (canMerge) {
                val previous = words.last()
                words[words.lastIndex] = previous.copy(
                    word = previous.word + display,
                    durationMs = (previous.durationMs + syllable.durationMs)
                        .coerceAtMost(Int.MAX_VALUE.toLong())
                        .toInt(),
                )
            } else {
                words += SyncedWord(
                    time = syllable.timeMs.toInt(),
                    word = display,
                    startsNewWord = true,
                    durationMs = syllable.durationMs
                        .coerceAtMost(Int.MAX_VALUE.toLong())
                        .toInt(),
                )
            }
            previousHadTrailingSpace = text.lastOrNull()?.isWhitespace() == true
        }
        return words
    }

    private fun isWideScript(char: Char): Boolean =
        char in '\u3040'..'\u30FF' || // Hiragana / Katakana
            char in '\u3400'..'\u9FFF' || // CJK
            char in '\uAC00'..'\uD7AF' || // Hangul
            char in '\uF900'..'\uFAFF' // CJK compatibility ideographs

    private data class RawSyllable(
        val timeMs: Long,
        val durationMs: Long,
        val text: String,
    )

    private fun isCreditLine(text: String, isFirstLine: Boolean): Boolean {
        if (creditLineRegex.containsMatchIn(text)) return true
        if (isFirstLine && (text.contains(" - ") || text.contains(" – ") || text.contains(" — "))) {
            return true
        }
        return false
    }

    // ---------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------

    private fun lyricsFrom(lines: List<SyncedLine>): Lyrics =
        Lyrics(synced = clampLineEdges(lines), areFromRemote = true)

    private fun clampWordEdges(
        words: List<SyncedWord>,
        lineDurationMs: Long = 0L,
        lineStartMs: Long = 0L,
    ): List<SyncedWord> {
        if (words.isEmpty()) return words
        val lineEnd = if (lineDurationMs > 0L) lineStartMs + lineDurationMs else Long.MAX_VALUE
        return words.mapIndexed { index, word ->
            val nextStart = words.getOrNull(index + 1)?.time?.toLong() ?: lineEnd
            val end = word.time.toLong() + word.durationMs
            val clampedEnd = end.coerceAtMost(nextStart).coerceAtMost(lineEnd)
            val duration = (clampedEnd - word.time).coerceAtLeast(0L).toInt()
            if (duration != word.durationMs) word.copy(durationMs = duration) else word
        }
    }

    private fun clampLineEdges(lines: List<SyncedLine>): List<SyncedLine> {
        if (lines.size < 2) return lines
        return lines.mapIndexed { index, line ->
            val nextStart = lines.getOrNull(index + 1)?.time?.toLong() ?: return@mapIndexed line
            val words = line.words ?: return@mapIndexed line
            val clamped = words.mapIndexed { wordIndex, word ->
                val wordEnd = word.time.toLong() + word.durationMs
                val nextWordStart = words.getOrNull(wordIndex + 1)?.time?.toLong() ?: nextStart
                val end = wordEnd.coerceAtMost(nextWordStart).coerceAtMost(nextStart)
                val duration = (end - word.time).coerceAtLeast(0L).toInt()
                if (duration != word.durationMs) word.copy(durationMs = duration) else word
            }
            if (clamped == words) line else line.copy(words = clamped)
        }
    }

    private fun requestBody(url: String, accept: String = "*/*"): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", accept)
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()?.takeIf { it.isNotBlank() }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val USER_AGENT = "PixelPlayer/1.0 (Android)"
        private const val MIN_MATCH_SCORE = 5
        private const val MIN_BINI_SCORE = 5
        private const val DURATION_TOLERANCE_MS = 12_000L
        private const val SIMP_DURATION_TOLERANCE_SECONDS = 10
        private const val RACE_TIMEOUT_MS = 10_000L

        private val krcOffsetRegex = Regex("""^\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
        private val krcLineRegex = Regex("""^\[(\d+),(\d+)](.*)$""")
        private val krcSyllableRegex = Regex("""<(\d+),(\d+),\d+>([^<]*)""")
        private val creditLineRegex = Regex(
            """(?i)^\s*(?:lyrics\s*by|written\s*by|composed\s*by|produced\s*by|arranged\s*by|recorded\s*(?:at|by)|mixed\s*by|remixed\s*by|mixing\s*(?:assistant|engineer)?|mastered\s*by|mastering|drums|guitar|bass|keyboards|strings|vocals?|作\s*词|作\s*曲|编\s*曲|制\s*作(?:人)?|演\s*唱|录\s*音|混\s*音|母\s*带|吉\s*他|贝\s*斯|鼓)\s*[:：]"""
        )
        private val VERSION_TAG_REGEX = Regex(
            """(?i)\b(remix|mix|mashup|bootleg|edit|extended|radio|club|vip|dub|live|acoustic|unplugged|sped\s*up|slowed|nightcore|instrumental|karaoke|cover|version)\b"""
        )

        /** Packaging/credit noise that never distinguishes recordings. */
        fun cleanTitle(raw: String): String {
            var out = raw
            out = out.replace(
                Regex("""(?i)\s*[\(\[](?:feat\.?|ft\.?|featuring|with)[^\)\]]*[\)\]]"""),
                "",
            )
            out = out.replace(Regex("""(?i)\s+(?:feat\.?|ft\.?|featuring)\s+.*$"""), "")
            out = out.replace(
                Regex("""(?i)\s*[\(\[]\s*(?:official\s*)?(?:music\s*)?(?:video|audio|visualizer|lyrics?\s*video|lyrics?|m/?v|hd|hq|4k|full\s*song)\s*[\)\]]"""),
                "",
            )
            out = out.replace(Regex("""\s+"""), " ").trim()
            return out.trim('-', '–', '—', ' ').ifBlank { raw.trim() }
        }

        fun cleanArtist(raw: String): String = raw
            .replace(Regex("""(?i)\s*[\(\[](?:feat\.?|ft\.?)[^\)\]]*[\)\]]"""), "")
            .replace(Regex("""(?i)\s*(?:feat\.?|ft\.?)\s+.*$"""), "")
            .trim()

        fun sameVersion(requestTitle: String, candidateTitle: String): Boolean =
            versionTags(requestTitle) == versionTags(candidateTitle)

        private fun versionTags(title: String): Set<String> =
            VERSION_TAG_REGEX.findAll(cleanTitle(title))
                .map { it.groupValues[1].lowercase().replace(Regex("""\s+"""), " ") }
                .toSet()

        fun titlesMatch(candidateTitle: String, requestTitle: String): Boolean {
            val candidates = listOf(candidateTitle, stripLeadingArtistPrefix(candidateTitle))
            return candidates.any { candidate -> strictTitleMatch(candidate, requestTitle) }
        }

        private fun strictTitleMatch(a: String, b: String): Boolean {
            val ca = a.trim()
            val cb = b.trim()
            if (ca.isBlank() || cb.isBlank()) return false
            if (ca.equals(cb, ignoreCase = true)) return true
            if (ca.contains(cb, ignoreCase = true) || cb.contains(ca, ignoreCase = true)) {
                val ratio = minOf(ca.length, cb.length).toDouble() / maxOf(ca.length, cb.length)
                if (ratio >= 0.7) return true
            }
            return isSimilar(ca, cb)
        }

        fun artistMatches(candidateArtist: String, requestArtist: String): Boolean {
            val ca = cleanArtist(candidateArtist)
            val ra = cleanArtist(requestArtist)
            if (ca.isBlank() || ra.isBlank()) return false
            if (ca.equals(ra, ignoreCase = true)) return true
            if (ca.contains(ra, ignoreCase = true) || ra.contains(ca, ignoreCase = true)) {
                if (minOf(ca.length, ra.length) < 4) return isSimilar(ca, ra)
                val ratio = minOf(ca.length, ra.length).toDouble() / maxOf(ca.length, ra.length)
                if (ratio >= 0.6) return true
            }
            return isSimilar(ca, ra)
        }

        fun stripLeadingArtistPrefix(raw: String): String {
            val stripped = raw.replace(Regex("""^\s*.+?\s*[-–—:]\s+(?=\S)"""), "").trim()
            return if (stripped.length >= 2) stripped else raw.trim()
        }

        fun isSimilar(s1: String, s2: String): Boolean {
            val a = s1.trim().lowercase()
            val b = s2.trim().lowercase()
            if (a == b) return true
            if (a.isEmpty() || b.isEmpty()) return false
            val aTokens = a.split(Regex("""\s+""")).toSet()
            val bTokens = b.split(Regex("""\s+""")).toSet()
            val intersection = aTokens.intersect(bTokens).size
            val union = aTokens.union(bTokens).size
            return if (union > 0) (intersection.toDouble() / union) >= 0.5 else false
        }
    }
}

@Serializable
private data class SimpResponse(
    val success: Boolean = false,
    val data: List<SimpTrack> = emptyList(),
)

@Serializable
private data class SimpTrack(
    val duration: Int? = null,
    val richSyncLyrics: String? = null,
    val syncedLyrics: String? = null,
    val plainLyrics: String? = null,
)

@Serializable
private data class BiniSearchResponse(
    val total: Int? = null,
    val source: String? = null,
    val results: List<BiniHit> = emptyList(),
)

@Serializable
private data class BiniHit(
    @SerialName("track_name") val trackName: String? = null,
    @SerialName("artist_name") val artistName: String? = null,
    @SerialName("album_name") val albumName: String? = null,
    val duration: Int? = null,
    val isrc: String? = null,
    @SerialName("timing_type") val timingType: String? = null,
    val lyricsUrl: String? = null,
)

@Serializable
private data class UnisonResponse(
    val success: Boolean? = null,
    val data: UnisonEntry? = null,
)

@Serializable
private data class UnisonEntry(
    val lyrics: String? = null,
    /** `ttml` or `lrc`. */
    val format: String? = null,
    /** `wordsync`, `linesync` or `plain`. */
    val syncType: String? = null,
)

@Serializable
private data class ITunesSearchResponse(
    val results: List<ITunesSong> = emptyList(),
)

@Serializable
private data class ITunesSong(
    val trackId: Long = 0L,
    val trackName: String? = null,
    val artistName: String? = null,
    val trackTimeMillis: Long? = null,
)

@Serializable
private data class PaxEnvelope(
    val lyrics: List<PaxLine> = emptyList(),
    val lrc: String? = null,
    val plain: String? = null,
)

@Serializable
private data class PaxLine(
    val timestamp: Long = 0L,
    val endtime: Long = 0L,
    val duration: Long = 0L,
    val text: List<PaxWord> = emptyList(),
    val background: Boolean = false,
)

@Serializable
private data class PaxWord(
    val text: String = "",
    val timestamp: Long = 0L,
    val endtime: Long = 0L,
    val duration: Long = 0L,
    val part: Boolean = false,
)

@Serializable
private data class KugouSearchResponse(
    val status: Int = 0,
    val errcode: Int = 0,
    val error: String = "",
    val candidates: List<KugouCandidate> = emptyList(),
)

@Serializable
private data class KugouCandidate(
    val id: String,
    val accesskey: String,
    val song: String = "",
    val singer: String = "",
    val duration: Long = 0L,
    @SerialName("score") val score: Int = 0,
)

@Serializable
private data class KugouDownloadResponse(
    val status: Int = 0,
    val errcode: Int = 0,
    val error: String = "",
    val content: String? = null,
    val fmt: String = "krc",
)
