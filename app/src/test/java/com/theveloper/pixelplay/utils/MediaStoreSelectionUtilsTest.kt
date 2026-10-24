package com.theveloper.pixelplay.utils

import android.provider.MediaStore
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MediaStoreSelectionUtilsTest {

    @Test
    fun `buildLocalAudioSelection does not depend on is music flag`() {
        val (selection, selectionArgs) = buildLocalAudioSelection(10_000)

        assertFalse(selection.contains(MediaStore.Audio.Media.IS_MUSIC))
        assertTrue(selection.contains(MediaStore.Audio.Media.DURATION))
        assertTrue(selection.contains(MediaStore.Audio.Media.TITLE))
        assertArrayEquals(
            arrayOf("10000", "audio/midi", "audio/x-midi", "audio/sp-midi", "audio/x-mid", "%.mid", "%.midi"),
            selectionArgs
        )
    }

    @Test
    fun `buildLocalAudioSelection clamps negative durations`() {
        val (_, selectionArgs) = buildLocalAudioSelection(-250)

        assertTrue(selectionArgs.isNotEmpty())
        assertArrayEquals(arrayOf("0"), selectionArgs.take(1).toTypedArray())
    }

    @Test
    fun `buildLocalAudioSelection includes midi duration bypass`() {
        val (selection, _) = buildLocalAudioSelection(10_000)

        assertTrue(selection.contains(MediaStore.Audio.Media.MIME_TYPE))
        assertTrue(selection.contains("audio_media._data") || selection.contains(MediaStore.Audio.Media.DATA))
        assertTrue(selection.contains("LIKE"))
    }

    @Test
    fun `buildLocalAudioSelection keeps rows with unknown duration`() {
        val (selection, _) = buildLocalAudioSelection(10_000)

        // Files whose MediaStore duration is 0/NULL (e.g. ALAC/M4A on some Samsung
        // devices) must still be discoverable; the real length is resolved during sync.
        assertTrue(selection.contains("COALESCE(${MediaStore.Audio.Media.DURATION}, 0) <= 0"))
    }

    @Test
    fun `buildLocalAudioSelection keeps unknown duration when the filter is disabled`() {
        val (selection, selectionArgs) = buildLocalAudioSelection(0)

        assertTrue(selection.contains("COALESCE(${MediaStore.Audio.Media.DURATION}, 0) <= 0"))
        assertArrayEquals(arrayOf("0"), selectionArgs.take(1).toTypedArray())
    }
}
