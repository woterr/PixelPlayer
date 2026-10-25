package com.theveloper.pixelplay.data.youtube

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The `ytmusic://` content URI is the only value that travels from a queued
 * [Song][com.theveloper.pixelplay.data.model.Song] into ExoPlayer's
 * [androidx.media3.exoplayer.upstream.DataSpec], so the lossless resolver needs
 * the verified title/artist/album/duration inside the URI itself.
 *
 * `ytmusic://<videoId>` keeps parsing everywhere (every consumer strips text
 * after `?`), and the metadata rides along as URL-encoded query parameters:
 * `ytmusic://<videoId>?tt=<title>&ta=<artist>&tal=<album>&td=<seconds>`
 */
object YtSongUri {
    /** Video id exactly as `removePrefix("ytmusic://").substringBefore('?')`. */
    fun encode(videoId: String, title: String, artist: String, album: String?, durationSeconds: Int?): String {
        val segments = buildList {
            if (title.isNotBlank()) add("tt=${encodeURIComponent(title)}")
            if (artist.isNotBlank()) add("ta=${encodeURIComponent(artist)}")
            if (!album.isNullOrBlank()) add("tal=${encodeURIComponent(album)}")
            if (durationSeconds != null && durationSeconds > 0) add("td=$durationSeconds")
        }
        return if (segments.isEmpty()) "ytmusic://$videoId" else "ytmusic://$videoId?${segments.joinToString("&")}"
    }

    fun videoId(contentUriString: String): String =
        contentUriString.removePrefix("ytmusic://").substringBefore('?')

    /** Metadata qualifier carried on the URI, or null when absent/unparseable. */
    fun qualifier(contentUriString: String): LosslessTrackQualifier? {
        val query = contentUriString.substringAfter('?', "").takeIf { contentUriString.startsWith("ytmusic://") } ?: return null
        if (query.isBlank()) return null
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var durationSeconds: Int? = null
        for (pair in query.split('&')) {
            val key = pair.substringBefore('=')
            val value = URLDecoder.decode(pair.substringAfter('=', ""), Charsets.UTF_8)
            when (key) {
                "tt" -> title = value.takeIf { it.isNotBlank() } ?: title
                "ta" -> artist = value.takeIf { it.isNotBlank() } ?: artist
                "tal" -> album = value.takeIf { it.isNotBlank() } ?: album
                "td" -> durationSeconds = value.trim().toIntOrNull()
            }
        }
        if (title.isNullOrBlank() && artist.isNullOrBlank()) return null
        return LosslessTrackQualifier(
            videoId = videoId(contentUriString),
            title = title.orEmpty(),
            artist = artist.orEmpty(),
            album = album,
            durationSeconds = durationSeconds,
        )
    }

    private fun encodeURIComponent(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}

/** Track identity passed to the lossless addon resolver from a queued stream. */
data class LosslessTrackQualifier(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationSeconds: Int?,
)
