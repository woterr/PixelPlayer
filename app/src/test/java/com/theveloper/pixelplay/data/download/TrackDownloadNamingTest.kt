package com.theveloper.pixelplay.data.download

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure-JVM checks for the download manager's naming and identity rules.
 *
 * `makeDownloadKey` must match exactly what [TrackDownloadManager.downloadTrack]
 * and the cancellation path derive, and `candidateFilenames` must cover every
 * container the tier waterfall can produce — otherwise `isTrackDownloaded`
 * lets an existing file be re-downloaded into a "(1)" copy.
 */
class TrackDownloadNamingTest {

    @Test
    fun `download key lower-cases and trims artist and title`() {
        assertThat(TrackDownloadManager.makeDownloadKey("  Song Name  ", " The Artist "))
            .isEqualTo("the artist_song name")
    }

    @Test
    fun `candidate filenames cover every produced container`() {
        val names = TrackDownloadManager.candidateFilenames("Artist", "Title")
        assertThat(names).containsExactly(
            "Artist - Title.flac",
            "Artist - Title.m4a",
            "Artist - Title.opus",
            "Artist - Title.mp3",
            "Artist - Title.webm",
            "Artist - Title.ogg",
        )
    }

    @Test
    fun `sanitize filename replaces characters illegal on the filesystem`() {
        assertThat(TrackDownloadManager.sanitizeFilename("AC/DC: Best?*"))
            .isEqualTo("AC_DC_ Best__")
    }

    @Test
    fun `sanitize filename falls back for blank titles`() {
        assertThat(TrackDownloadManager.sanitizeFilename("   ")).isEqualTo("track")
    }
}
