package com.theveloper.pixelplay.data.model

import kotlinx.serialization.Serializable

/**
 * Modelo de datos para las letras de las canciones.
 *
 * @param plain Lista de líneas de texto plano (no sincronizadas).
 * @param synced Lista de pares (milisegundos, línea) para letras sincronizadas.
 * @param areFromRemote Indica si las letras se obtuvieron de una fuente remota.
 */
@Serializable
data class Lyrics(
    val plain: List<String>? = null,
    val synced: List<SyncedLine>? = null,
    val areFromRemote: Boolean = false
)

@Serializable
data class SyncedLine(
    val time: Int,
    val line: String,
    val words: List<SyncedWord>? = null, // Null if not a word-by-word synced lyric
    val translation: String? = null, // Translation text paired by identical timestamp
    val romanization: String? = null // Romanization text paired by identical timestamp
)

@Serializable
data class SyncedWord(
    val time: Int,
    val word: String,
    val startsNewWord: Boolean = true,
    /**
     * How long the word is sung, when the provider reports it. Word-sync
     * providers (Apple Music TTML, BetterLyrics, Kugou) carry per-syllable
     * durations which drive the progressive karaoke fill; 0 means "hold the
     * fill until the next word starts", so older LRC-sourced lyrics keep
     * working unchanged.
     */
    val durationMs: Int = 0
)
