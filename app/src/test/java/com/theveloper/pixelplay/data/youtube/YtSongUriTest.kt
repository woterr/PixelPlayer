package com.theveloper.pixelplay.data.youtube

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.net.URLDecoder

class YtSongUriTest {

    @Test
    fun `encode without optional metadata yields bare video uri`() {
        assertThat(YtSongUri.encode("abc123", "", "", null, null))
            .isEqualTo("ytmusic://abc123")
    }

    @Test
    fun `encode carries title artist album duration as query params`() {
        val uri = YtSongUri.encode(
            videoId = "abc123",
            title = "My Song (Live)",
            artist = "A & B",
            album = "Album/2024",
            durationSeconds = 214,
        )

        assertThat(uri).startsWith("ytmusic://abc123?")
        assertThat(uri).doesNotContain(" \"")
        assertThat(YtSongUri.videoId(uri)).isEqualTo("abc123")
    }

    @Test
    fun `encoder output round-trips through qualifier`() {
        val uri = YtSongUri.encode(
            videoId = "abc123",
            title = "My Song (Live)",
            artist = "A & B",
            album = "Album/2024",
            durationSeconds = 214,
        )

        val qualifier = YtSongUri.qualifier(uri)!!

        assertThat(qualifier.videoId).isEqualTo("abc123")
        assertThat(qualifier.title).isEqualTo("My Song (Live)")
        assertThat(qualifier.artist).isEqualTo("A & B")
        assertThat(qualifier.album).isEqualTo("Album/2024")
        assertThat(qualifier.durationSeconds).isEqualTo(214)
    }

    @Test
    fun `qualifier ignores blank title and artist and returns null`() {
        assertThat(YtSongUri.qualifier("ytmusic://abc123?tt=&ta=")).isNull()
    }

    @Test
    fun `bare ytmusic uri has no qualifier`() {
        assertThat(YtSongUri.qualifier("ytmusic://abc123")).isNull()
    }

    @Test
    fun `videoId strips query the way all existing consumers do`() {
        val uri = YtSongUri.encode("idOnly", "T", "A", null, null)
        assertThat(uri.removePrefix("ytmusic://").substringBefore('?')).isEqualTo("idOnly")
        assertThat(YtSongUri.videoId(uri)).isEqualTo("idOnly")
    }

    @Test
    fun `qualifier tolerates non-ytmusic uris`() {
        assertThat(YtSongUri.qualifier("content://media/external?tt=x")).isNull()
    }

    @Test
    fun `duration param is optional and nullable when absent`() {
        val uri = YtSongUri.encode("v", "Title", "Artist", null, null)
        val qualifier = YtSongUri.qualifier(uri)!!
        assertThat(qualifier.album).isNull()
        assertThat(qualifier.durationSeconds).isNull()
    }

    @Test
    fun `encoded values decode back with UTF-8`() {
        val enc = YtSongUri.encode("v", "Décimo", "Çetin İlkay", null, null)
        val query = enc.substringAfter('?')
        val decoded = query.split('&').associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to
                URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        }
        assertThat(decoded["tt"]).isEqualTo("Décimo")
        assertThat(decoded["ta"]).isEqualTo("Çetin İlkay")
    }
}
