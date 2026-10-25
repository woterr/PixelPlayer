package com.theveloper.pixelplay.data.youtube

import com.theveloper.pixelplay.data.model.ArtistRef
import com.theveloper.pixelplay.data.model.Song
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Bridges the YouTube Music data layer to PixelPlayer's domain model.
 *
 * YouTube tracks are intentionally transient: they are mapped to [Song] on
 * demand and never written to the Room library, so no schema migration is
 * needed for remote catalogue content. The video id travels inside the
 * `ytmusic://<videoId>` content URI, which keeps every existing PixelPlayer
 * surface (queue, history, media session, lyrics sheet) working unchanged.
 */
@Singleton
class YouTubeRepository @Inject constructor(
    private val innerTube: InnerTubeClient,
    private val extractor: YouTubeStreamExtractor,
    private val auth: YtMusicAuthManager,
    preferences: YtMusicPreferences,
) {
    val connection: Flow<YtConnection> = preferences.connection
    val homeMixEnabled: Flow<Boolean> = preferences.homeMixEnabled
    val preferOpus: Flow<Boolean> = preferences.preferOpus

    suspend fun isConnected(): Boolean = auth.currentConnection().isConnected

    suspend fun searchSongs(query: String, limit: Int = 20): List<Song> = withContext(Dispatchers.IO) {
        // Anonymous WEB_REMIX search works without an account, so the
        // catalogue stays browsable before the user connects (playback is
        // resolved locally by NewPipe and needs no session either).
        runCatching { innerTube.searchSongs(query, limit).map(::toSong) }.getOrDefault(emptyList())
    }

    /**
     * Personalized feed used as the data source for the home "Your Mix" card.
     *
     * Only a connected account has real recommendations, so an anonymous or
     * failed fetch returns nothing and the local listening mix stays visible
     * instead of being replaced by editorial charts.
     */
    suspend fun homeMix(limit: Int = 30): List<Song> = withContext(Dispatchers.IO) {
        if (!auth.currentConnection().isConnected) return@withContext emptyList()
        runCatching { innerTube.fetchHomeSongs(limit) }
            .getOrDefault(emptyList())
            .map(::toSong)
            .distinctBy { it.id }
    }

    fun cachedStream(videoId: String): YouTubeAudioStream? = extractor.cached(videoId)

    suspend fun resolveStream(videoId: String): YouTubeAudioStream = withContext(Dispatchers.IO) {
        extractor.resolveAudioStream(
            videoId = videoId,
            preferOpus = runCatching { preferOpus.first() }.getOrDefault(false),
        )
    }

    suspend fun resolveStream(song: Song): YouTubeAudioStream? {
        val videoId = song.youTubeId ?: return null
        return resolveStream(videoId)
    }

    fun invalidateStream(videoId: String) {
        extractor.invalidatePlayerState(videoId)
    }

    fun toSong(track: YouTubeTrack): Song {
        val artistName = track.artist.ifBlank { "YouTube Music" }
        val albumName = track.album?.takeIf { it.isNotBlank() } ?: "YouTube Music"
        val artistId = -(YOUTUBE_ARTIST_ID_OFFSET + stableHash(artistName.lowercase()))
        val albumId = -(YOUTUBE_ALBUM_ID_OFFSET + stableHash(albumName.lowercase()))
        val contentUriString = YtSongUri.encode(
            videoId = track.videoId,
            title = track.title,
            artist = artistName,
            album = albumName,
            durationSeconds = track.durationSeconds,
        )
        return Song(
            id = songIdFor(track.videoId).toString(),
            title = track.title,
            artist = artistName,
            artistId = artistId,
            artists = listOf(ArtistRef(id = artistId, name = artistName, isPrimary = true)),
            album = albumName,
            albumId = albumId,
            albumArtist = artistName,
            path = "",
            contentUriString = contentUriString,
            albumArtUriString = track.artworkUrl,
            duration = (track.durationSeconds ?: 0).coerceAtLeast(0) * 1_000L,
            genre = null,
            lyrics = null,
            isFavorite = false,
            trackNumber = 0,
            discNumber = null,
            year = 0,
            dateAdded = 0L,
            dateModified = 0L,
            mimeType = null,
            bitrate = null,
            sampleRate = null,
            youtubeVideoId = track.videoId,
        )
    }

    companion object {
        /** Distinct id band so YouTube ids never collide with local or cloud ids. */
        private const val YOUTUBE_SONG_ID_OFFSET = 15_000_000_000_000L
        private const val YOUTUBE_ALBUM_ID_OFFSET = 16_000_000_000_000L
        private const val YOUTUBE_ARTIST_ID_OFFSET = 17_000_000_000_000L

        fun songIdFor(videoId: String): Long =
            -(YOUTUBE_SONG_ID_OFFSET + stableHash(videoId))

        private fun stableHash(value: String): Long =
            value.hashCode().toLong().absoluteValue
    }
}
