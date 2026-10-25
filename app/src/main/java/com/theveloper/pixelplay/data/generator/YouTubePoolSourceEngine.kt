package com.theveloper.pixelplay.data.generator

import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.youtube.InnerTubeClient
import com.theveloper.pixelplay.data.youtube.YouTubeRepository
import com.theveloper.pixelplay.data.youtube.YouTubeTrack
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/**
 * Pulls the candidate pool for [PlaylistSource] from YouTube Music feeds
 *
 * Ported from LastWave's generator pathways minus the Last.fm engine —
 * every source either reads a YouTube Music browse feed (charts, history,
 * home, mixes), a radio graph branch (RDAMVM song radio), or a genre
 * search. It never throws: sources degrade to an empty pool and cascade to
 * their documented fallback so the AI step can still work from the local
 * library.
 */
@Singleton
class YouTubePoolSourceEngine @Inject constructor(
    private val innerTube: InnerTubeClient,
    private val youTubeRepository: YouTubeRepository,
) {

    data class SourceSeed(
        val tagInput: String = "",
        val seedTrackName: String = "",
        val seedArtistName: String = "",
        val seedVideoId: String? = null,
    )

    /**
     * Ordered pool for [source], deduplicated and capped at [limit].
     *
     * [knownLocalKeys] (lowercased "artist|title" pairs) lets NEVER_HEARD
     * drop everything the device library already knows about.
     */
    suspend fun fetchPool(
        source: PlaylistSource,
        seed: SourceSeed = SourceSeed(),
        limit: Int,
        knownLocalKeys: Set<String> = emptySet(),
        onProgress: (String) -> Unit = {},
    ): List<YouTubeTrack> = withContext(Dispatchers.IO) {
        runCatching {
            when (source) {
                PlaylistSource.LOCAL_LIBRARY -> emptyList()
                PlaylistSource.TOP_TRACKS -> topTracks(limit)
                PlaylistSource.RECENT_TRACKS -> recentTracks(limit)
                PlaylistSource.SONG_RADIO -> songRadio(seed, limit)
                PlaylistSource.SIMILAR_ARTISTS -> similarArtistTracks(seed, limit)
                PlaylistSource.TAG_GENRE -> tagTracks(seed.tagInput, limit)
                PlaylistSource.MY_MIX -> myMix(limit)
                PlaylistSource.RECOMMENDATIONS -> recommendations(limit)
                PlaylistSource.NEVER_HEARD -> neverHeard(limit, knownLocalKeys)
                PlaylistSource.MY_LIBRARY -> likedSongs(limit)
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            emptyList()
        }.distinctBy { it.videoId }
            .filter { it.videoId.isNotBlank() && it.title.isNotBlank() }
            .take(limit)
    }

    /**
     * Converts YouTube tracks into the [Song] domain model used every-
     * where downstream, so AI output ids resolve against the same map the
     * local library uses.
     */
    fun toSongs(tracks: List<YouTubeTrack>): List<Song> =
        tracks.map { youTubeRepository.toSong(it) }.distinctBy { it.id }

    /**
     * The Discover shelf: YouTube-driven recommendations for the home
     * screen. The freshest listening-history track branches through song
     * radio for related material, the personalized home feed widens the
     * pool, and public charts pad anonymous sessions — then the blend is
     * shuffled so the shelf reads fresh on every home visit (LastWave's
     * recommendation remix ordering).
     */
    suspend fun discover(limit: Int = 30): List<YouTubeTrack> = withContext(Dispatchers.IO) {
        runCatching {
            val pool = mutableListOf<YouTubeTrack>()
            val historySeed = runCatching { innerTube.fetchHistory(limit = 1) }
                .getOrDefault(emptyList())
                .firstOrNull()
            if (historySeed != null) {
                pool += runCatching { innerTube.fetchRadio(historySeed.videoId, limit / 2 + 10) }
                    .getOrDefault(emptyList())
            }
            pool += innerTube.fetchHomeSongs(limit)
            if (pool.size < limit) {
                innerTube.fetchCharts(limit).forEach { track ->
                    if (pool.none { it.videoId == track.videoId }) pool += track
                }
            }
            pool
                .distinctBy { it.videoId }
                .filter { it.videoId.isNotBlank() && it.title.isNotBlank() }
                .shuffled()
                .take(limit)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            emptyList()
        }
    }

    /**
     * Builds the candidate pool plus the generation bank for [source]:
     * candidates go straight to the LLM prompt, the bank additionally
     * carries the device library so AI-returned ids always resolve.
     * Returns null when the YouTube pool starves — callers fall back to
     * their local-only ranking path unchanged (LastWave's degrade rule).
     */
    suspend fun prepareCandidates(
        source: PlaylistSource,
        seed: SourceSeed = SourceSeed(),
        librarySongs: List<Song>,
        limit: Int = 120,
    ): Pair<List<Song>, List<Song>>? {
        if (source == PlaylistSource.LOCAL_LIBRARY) return null
        val knownLocalKeys = librarySongs.map { song ->
            song.displayArtist.lowercase().trim() + "|" + song.title.lowercase().trim()
        }.toSet()
        val pool = fetchPool(source, seed, limit, knownLocalKeys)
        val poolSongs = toSongs(pool)
        if (poolSongs.isEmpty()) return null
        return poolSongs to (poolSongs + librarySongs).distinctBy { it.id }
    }

    /**
     * Top tracks: public charts, padded with the home feed the way
     * LastWave's accountless path blended home + charts.
     */
    private suspend fun topTracks(limit: Int): List<YouTubeTrack> {
        val charts = innerTube.fetchCharts(limit)
        val pool = charts.toMutableList()
        if (pool.size < limit) {
            val home = innerTube.fetchHomeSongs(limit)
            home.forEach { track ->
                if (pool.none { it.videoId == track.videoId }) pool += track
            }
        }
        return pool
    }

    /** Recent listens: watch history (auth), falling back to home feed. */
    private suspend fun recentTracks(limit: Int): List<YouTubeTrack> {
        val history = innerTube.fetchHistory(limit)
        if (history.isNotEmpty()) return history
        onProgressFallback()
        return innerTube.fetchHomeSongs(limit)
    }

    /**
     * Song radio: `RDAMVM<videoId>` radio from the seed. The videoId is the
     * exact seed the source menu captured; otherwise the seed title/artist
     * resolve through the standard search.
     */
    private suspend fun songRadio(seed: SourceSeed, limit: Int): List<YouTubeTrack> {
        val seedVideoId = seed.seedVideoId?.takeIf { it.isNotBlank() }
            ?: resolveSeedVideoId(seed) ?: return searchFallback(
                listOfNotNull(seed.seedTrackName.ifBlank { null }, seed.seedArtistName.ifBlank { null })
                    .joinToString(" ").ifBlank { null },
                limit,
            )
        val radio = innerTube.fetchRadio(seedVideoId, limit)
        return if (radio.isNotEmpty()) radio else emptyList()
    }

    /**
     * Similar artists: seeds found via "<artist> songs" expand through
     * song radio branches (LastWave's YouTube-only artist pathway).
     */
    private suspend fun similarArtistTracks(seed: SourceSeed, limit: Int): List<YouTubeTrack> =
        supervisorScope {
            val artistQuery = seed.seedArtistName.ifBlank { seed.seedTrackName }
            if (artistQuery.isBlank()) return@supervisorScope emptyList()
            val seeds = innerTube.searchSongs("$artistQuery songs", limit = 3)
                .take(3)
            if (seeds.isEmpty()) return@supervisorScope emptyList()
            val jobs = seeds.take(2).map { seedTrack ->
                async { runCatching { innerTube.fetchRadio(seedTrack.videoId, limit / 2 + 10) }.getOrDefault(emptyList()) }
            }
            val pool = jobs.awaitAll().flatten().toMutableList()
            if (pool.size < limit) {
                val extra = runCatching { innerTube.searchSongs(artistQuery, limit) }.getOrDefault(emptyList())
                extra.filterNot { track -> pool.any { it.videoId == track.videoId } }
                    .let { pool += it }
            }
            pool.distinctBy { it.videoId }
        }

    /** Genre/tag: search, exactly like LastWave's "$tag music" query. */
    private suspend fun tagTracks(tag: String, limit: Int): List<YouTubeTrack> {
        val normalized = tag.trim()
        if (normalized.isBlank()) return emptyList()
        return innerTube.searchSongs("$normalized music", limit)
            .ifEmpty { innerTube.searchSongs(normalized, limit) }
    }

    /** My mix: the first home mixes opened, a few tracks each. */
    private suspend fun myMix(limit: Int): List<YouTubeTrack> {
        val mixes = innerTube.fetchHomeMixes(limit = 2)
        if (mixes.isEmpty()) return emptyList()
        val perMix = (limit / mixes.size).coerceIn(5, 10)
        val pool = mutableListOf<YouTubeTrack>()
        for (mix in mixes) {
            val tracks = runCatching { innerTube.fetchPlaylistSongs(mix.playlistId, perMix) }
                .getOrDefault(emptyList())
            tracks.filterNot { track -> pool.any { it.videoId == track.videoId } }
                .let { pool += it }
        }
        return pool
    }

    /** Recommendations: the personalized home feed itself. */
    private suspend fun recommendations(limit: Int): List<YouTubeTrack> =
        innerTube.fetchHomeSongs(limit)

    /**
     * Never heard: a wide recommendations pool minus anything already in
     * the device library (artist|title pairs) — blend home + mixes and
     * filter here so engine stays library-agnostic.
     */
    private suspend fun neverHeard(limit: Int, knownLocalKeys: Set<String>): List<YouTubeTrack> {
        val pool = buildList {
            addAll(outerBlock@ innerTube.fetchHomeSongs(limit))
            val mixes = runCatching { myMix(limit) }.getOrDefault(emptyList())
            mixes.filterNot { track -> any { it.videoId == track.videoId } }
                .forEach { add(it) }
        }
        if (knownLocalKeys.isEmpty()) return pool
        // Fresh material keeps dropping until the source has nothing left:
        // aggressive removal is what makes this the "never heard" source.
        return pool.filterNot { track ->
            trackToKey(track) in knownLocalKeys
        }
    }

    /** My library: connected liked music, falling back to home feed. */
    private suspend fun likedSongs(limit: Int): List<YouTubeTrack> {
        val liked = runCatching { innerTube.fetchPlaylistSongs("LM", limit) }.getOrDefault(emptyList())
        if (liked.isNotEmpty()) return liked
        return innerTube.fetchHomeSongs(limit)
    }

    private suspend fun searchFallback(query: String?, limit: Int): List<YouTubeTrack> {
        if (query.isNullOrBlank()) return emptyList()
        return runCatching { innerTube.searchSongs(query, limit) }.getOrDefault(emptyList())
    }

    private suspend fun resolveSeedVideoId(seed: SourceSeed): String? {
        val title = seed.seedTrackName.trim()
        if (title.isBlank()) return null
        val artist = seed.seedArtistName.trim().ifBlank { null }
        val query = buildString {
            append(title)
            if (artist != null) append(" $artist")
        }.trim()
        val results = runCatching { innerTube.searchSongs(query, limit = 5) }.getOrDefault(emptyList())
        // Prefer a match that acknowledges the requested song text; else the
        // top hit — the radio seed only shifts the graph, it is not exact.
        val scored = results.map { it to vendorScore(it, title, artist) }
        return (scored.firstOrNull { it.second > 0 } ?: scored.firstOrNull())?.first?.videoId
    }

    /** Cheap string proximity for preferring the exact requested song. */
    private fun vendorScore(track: YouTubeTrack, title: String, artist: String?): Int {
        val trackTitle = track.title.lowercase()
        val trackArtist = track.artist.lowercase()
        var score = 0
        if (trackTitle.contains(title.lowercase())) score += 2
        if (artist != null && trackArtist.contains(artist.lowercase())) score += 2
        return score
    }

    private fun trackToKey(track: YouTubeTrack): String = buildString {
        append(track.artist.lowercase().trim())
        append('|')
        append(track.title.lowercase().trim())
    }

    private fun onProgressFallback() = Unit
}
