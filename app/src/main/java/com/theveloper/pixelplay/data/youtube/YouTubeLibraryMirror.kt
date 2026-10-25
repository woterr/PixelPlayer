package com.theveloper.pixelplay.data.youtube

import com.theveloper.pixelplay.data.database.AlbumEntity
import com.theveloper.pixelplay.data.database.ArtistEntity
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.database.SongArtistCrossRef
import com.theveloper.pixelplay.data.database.SongEntity
import com.theveloper.pixelplay.data.database.SourceType
import com.theveloper.pixelplay.data.model.Song
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Mirrors YouTube songs into the unified library so they keep resolving
 * after the session that produced them (saved AI playlists reference song
 * ids, and only Room-resolvable ids survive app restarts).
 *
 * This is the same pattern the Jellyfin/Navidrome integrations use:
 * supplementary rows with negative per-source ids, tagged
 * [SourceType.YOUTUBE], rebuilt from the metadata carried inside the
 * `ytmusic://` URI. Nothing is written for fully-local songs.
 */
@Singleton
class YouTubeLibraryMirror @Inject constructor(
    private val musicDao: MusicDao
) {

    /**
     * Upserts the YouTube songs in [songs] (song + artist + album rows).
     * Local songs are left untouched; artist/album stubs collapse into
     * their song's primary ids so library pages never dangle.
     */
    suspend fun mirrorForPersistence(songs: List<Song>) {
        val youtubeSongs = songs.filter { it.youTubeId != null && it.id.toLongOrNull() != null }
        if (youtubeSongs.isEmpty()) return

        val now = System.currentTimeMillis()
        val entities = mutableListOf<SongEntity>()
        val artists = LinkedHashMap<Long, ArtistEntity>()
        val albums = LinkedHashMap<Long, AlbumEntity>()
        val crossRefs = mutableListOf<SongArtistCrossRef>()

        youtubeSongs.forEach { song ->
            val songId = song.id.toLongOrNull() ?: return@forEach
            val primaryArtistName = song.artist.ifBlank { YOUTUBE_FALLBACK_ARTIST }
            val primaryArtistId = song.artistId

            song.artists
                .filter { it.id != 0L }
                .forEachIndexed { index, artistRef ->
                    artists.putIfAbsent(
                        artistRef.id,
                        ArtistEntity(id = artistRef.id, name = artistRef.name, trackCount = 0)
                    )
                    crossRefs.add(SongArtistCrossRef(songId, artistRef.id, index == 0))
                }

            val albumId = song.albumId.takeIf { it != 0L }
                ?: YouTubeRepository.songIdFor(song.youTubeId.orEmpty())
            albums.putIfAbsent(
                albumId,
                AlbumEntity(
                    id = albumId,
                    title = song.album.ifBlank { YOUTUBE_FALLBACK_ALBUM },
                    artistName = primaryArtistName,
                    artistId = primaryArtistId.takeIf { it != 0L } ?: albumId,
                    albumArtUriString = song.albumArtUriString,
                    songCount = 0,
                    dateAdded = now,
                    year = song.year,
                    albumArtist = song.albumArtist
                )
            )

            entities.add(
                SongEntity(
                    id = songId,
                    title = song.title,
                    artistName = song.artist,
                    artistId = primaryArtistId,
                    albumArtist = song.albumArtist,
                    albumName = song.album.ifBlank { YOUTUBE_FALLBACK_ALBUM },
                    albumId = albumId,
                    contentUriString = song.contentUriString,
                    albumArtUriString = song.albumArtUriString,
                    duration = song.duration,
                    genre = song.genre,
                    isFavorite = song.isFavorite,
                    lyrics = song.lyrics,
                    trackNumber = song.trackNumber,
                    discNumber = song.discNumber,
                    filePath = "",
                    parentDirectoryPath = "",
                    dateAdded = now,
                    year = song.year,
                    mimeType = null,
                    bitrate = null,
                    sampleRate = null,
                    sourceType = SourceType.fromContentUri(song.contentUriString)
                )
            )
        }

        if (entities.isEmpty()) return

        runCatching {
            musicDao.insertArtists(artists.values.toList())
            musicDao.insertAlbums(
                albums.values.map { album ->
                    album.copy(songCount = entities.count { it.albumId == album.id })
                }
            )
            musicDao.insertSongs(entities)
            musicDao.insertSongArtistCrossRefs(crossRefs)
        }.onFailure { Timber.w(it, "Failed to mirror YouTube songs into the library") }
    }

    private companion object {
        const val YOUTUBE_FALLBACK_ARTIST = "YouTube Music"
        const val YOUTUBE_FALLBACK_ALBUM = "YouTube Music"
    }
}
