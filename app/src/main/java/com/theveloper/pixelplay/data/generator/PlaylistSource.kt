package com.theveloper.pixelplay.data.generator

import androidx.annotation.StringRes
import com.theveloper.pixelplay.R

/**
 * Candidates sources for AI playlist generation.
 *
 * Faithful to LastWave's [GenerateMode] ids so playlist metadata keeps the
 * same storage values, with every source fed by YouTube Music (this app has
 * no Last.fm engine): the pool a source returns is always a YouTube track
 * list the Gemini pipeline then curates.
 */
enum class PlaylistSource(
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
    val storageValue: String,
) {
    TOP_TRACKS(R.string.gen_mode_top, R.string.gen_mode_top_desc, "top"),
    RECENT_TRACKS(R.string.gen_mode_recent, R.string.gen_mode_recent_desc, "recent"),
    SONG_RADIO(R.string.gen_mode_song_radio, R.string.gen_mode_song_radio_desc, "similar-tracks"),
    SIMILAR_ARTISTS(R.string.gen_mode_sim_artists, R.string.gen_mode_sim_artists_desc, "similar-artists"),
    TAG_GENRE(R.string.gen_mode_tag, R.string.gen_mode_tag_desc, "tag"),
    MY_MIX(R.string.gen_mode_mix, R.string.gen_mode_mix_desc, "mix"),
    RECOMMENDATIONS(R.string.gen_mode_reco, R.string.gen_mode_reco_desc, "recommendations"),
    NEVER_HEARD(R.string.gen_mode_never, R.string.gen_mode_never_desc, "never-heard"),
    MY_LIBRARY(R.string.gen_mode_library, R.string.gen_mode_library_desc, "library"),

    /** No YouTube pool — the classic device-library generation path. */
    LOCAL_LIBRARY(R.string.gen_source_local, R.string.gen_source_local_desc, "local");

    companion object {
        fun fromStorageValue(value: String): PlaylistSource? =
            entries.firstOrNull { it.storageValue == value }
    }
}
