package com.theveloper.pixelplay.data.generator

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure-JVM checks for the generator's source plumbing.
 *
 * The storage values must stay byte-stable with LastWave's [GenerateMode]
 * ids (anything persisted about a playlist's origin uses them), and the
 * playlist-id extraction must accept every form the source menu lets a
 * user paste.
 */
class PlaylistSourceTest {

    @Test
    fun `storage values match LastWave mode ids`() {
        assertThat(PlaylistSource.TOP_TRACKS.storageValue).isEqualTo("top")
        assertThat(PlaylistSource.RECENT_TRACKS.storageValue).isEqualTo("recent")
        assertThat(PlaylistSource.SONG_RADIO.storageValue).isEqualTo("similar-tracks")
        assertThat(PlaylistSource.SIMILAR_ARTISTS.storageValue).isEqualTo("similar-artists")
        assertThat(PlaylistSource.TAG_GENRE.storageValue).isEqualTo("tag")
        assertThat(PlaylistSource.MY_MIX.storageValue).isEqualTo("mix")
        assertThat(PlaylistSource.RECOMMENDATIONS.storageValue).isEqualTo("recommendations")
        assertThat(PlaylistSource.NEVER_HEARD.storageValue).isEqualTo("never-heard")
        assertThat(PlaylistSource.MY_LIBRARY.storageValue).isEqualTo("library")
        assertThat(PlaylistSource.LOCAL_LIBRARY.storageValue).isEqualTo("local")
    }

    @Test
    fun `storage values round trip through fromStorageValue`() {
        PlaylistSource.entries.forEach { source ->
            assertThat(PlaylistSource.fromStorageValue(source.storageValue)).isEqualTo(source)
        }
    }

    @Test
    fun `unknown storage values resolve to null`() {
        assertThat(PlaylistSource.fromStorageValue("mood")).isNull()
        assertThat(PlaylistSource.fromStorageValue("")).isNull()
    }

    @Test
    fun `source seed defaults are empty`() {
        val seed = YouTubePoolSourceEngine.SourceSeed()
        assertThat(seed.tagInput).isEmpty()
        assertThat(seed.seedTrackName).isEmpty()
        assertThat(seed.seedArtistName).isEmpty()
        assertThat(seed.seedVideoId).isNull()
    }
}
