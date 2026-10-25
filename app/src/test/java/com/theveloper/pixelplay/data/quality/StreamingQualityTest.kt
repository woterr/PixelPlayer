package com.theveloper.pixelplay.data.quality

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class StreamingQualityTest {

    @Test
    fun `tier ids mirror LastWave presets`() {
        assertThat(StreamingQuality.QUALITY_DOLBY_ATMOS).isEqualTo(28)
        assertThat(StreamingQuality.QUALITY_MAX_HI_RES).isEqualTo(27)
        assertThat(StreamingQuality.QUALITY_HI_RES_96).isEqualTo(7)
        assertThat(StreamingQuality.QUALITY_CD_LOSSLESS).isEqualTo(6)
        assertThat(StreamingQuality.QUALITY_MP3_320).isEqualTo(5)
        assertThat(StreamingQuality.QUALITY_DATA_SAVER).isEqualTo(4)
        assertThat(StreamingQuality.QUALITY_YOUTUBE).isEqualTo(-1)
    }

    @Test
    fun `youtube tier is strict and never attempts lossless`() {
        assertThat(StreamingQuality.getQualityAttemptOrder(StreamingQuality.QUALITY_YOUTUBE)).isEmpty()
    }

    @Test
    fun `atmos order only path with atmos then steps down`() {
        val order = StreamingQuality.getQualityAttemptOrder(StreamingQuality.QUALITY_DOLBY_ATMOS)
        assertThat(order).containsExactly(
            StreamingQuality.QUALITY_DOLBY_ATMOS,
            StreamingQuality.QUALITY_MAX_HI_RES,
            StreamingQuality.QUALITY_HI_RES_96,
            StreamingQuality.QUALITY_CD_LOSSLESS,
            StreamingQuality.QUALITY_MP3_320,
        ).inOrder()
    }

    @Test
    fun `hi-res max steps down and never climbs to atmos`() {
        val order = StreamingQuality.getQualityAttemptOrder(StreamingQuality.QUALITY_MAX_HI_RES)
        assertThat(order).containsExactly(
            StreamingQuality.QUALITY_MAX_HI_RES,
            StreamingQuality.QUALITY_HI_RES_96,
            StreamingQuality.QUALITY_CD_LOSSLESS,
            StreamingQuality.QUALITY_MP3_320,
        ).inOrder()
        assertThat(order).doesNotContain(StreamingQuality.QUALITY_DOLBY_ATMOS)
    }

    @Test
    fun `lower tiers try preferred first then higher then lower`() {
        val order = StreamingQuality.getQualityAttemptOrder(StreamingQuality.QUALITY_MP3_320)
        assertThat(order.first()).isEqualTo(StreamingQuality.QUALITY_MP3_320)
        assertThat(order).doesNotContain(StreamingQuality.QUALITY_DATA_SAVER)
        assertThat(order).doesNotContain(StreamingQuality.QUALITY_YOUTUBE)
        assertThat(order.distinct()).hasSize(order.size)
    }

    @Test
    fun `data saver only appears when explicitly chosen`() {
        val explicit = StreamingQuality.getQualityAttemptOrder(StreamingQuality.QUALITY_DATA_SAVER)
        assertThat(explicit.first()).isEqualTo(StreamingQuality.QUALITY_DATA_SAVER)

        val standard = StreamingQuality.getQualityAttemptOrder(StreamingQuality.QUALITY_CD_LOSSLESS)
        assertThat(standard).doesNotContain(StreamingQuality.QUALITY_DATA_SAVER)
    }

    @Test
    fun `short badges match LastWave labels`() {
        assertThat(StreamingQuality.shortBadge(StreamingQuality.QUALITY_DOLBY_ATMOS)).isEqualTo("ATMOS")
        assertThat(StreamingQuality.shortBadge(StreamingQuality.QUALITY_MAX_HI_RES)).isEqualTo("24-BIT / 192k")
        assertThat(StreamingQuality.shortBadge(StreamingQuality.QUALITY_HI_RES_96)).isEqualTo("24-BIT / 96k")
        assertThat(StreamingQuality.shortBadge(StreamingQuality.QUALITY_CD_LOSSLESS)).isEqualTo("16-BIT / 44.1k")
        assertThat(StreamingQuality.shortBadge(StreamingQuality.QUALITY_MP3_320)).isEqualTo("320 kbps")
        assertThat(StreamingQuality.shortBadge(StreamingQuality.QUALITY_DATA_SAVER)).isEqualTo("96 kbps")
        assertThat(StreamingQuality.shortBadge(StreamingQuality.QUALITY_YOUTUBE)).isEqualTo("YOUTUBE")
    }

    @Test
    fun `depth resolves rate above 48kHz as hi-res`() {
        assertThat(resolveDepthForDisplay(null, 96.0)).isEqualTo(24)
        assertThat(resolveDepthForDisplay(null, 192.1)).isEqualTo(32)
        assertThat(resolveDepthForDisplay(null, 44.1)).isNull()
        assertThat(resolveDepthForDisplay(16, 44.1)).isEqualTo(16)
        assertThat(resolveDepthForDisplay(24, 44.1)).isEqualTo(24)
    }

    @Test
    fun `sample rate formatting uses minimal decimals`() {
        assertThat(formatSampleRateKHz(44.1)).isEqualTo("44.1")
        assertThat(formatSampleRateKHz(48.0)).isEqualTo("48")
        assertThat(formatSampleRateKHz(88.2)).isEqualTo("88.2")
        assertThat(formatSampleRateKHz(192000.0)).isEqualTo("192")
    }

    @Test
    fun `detailed badge composes depth and rate`() {
        assertThat(detailedBadge(24, 96.0)).isEqualTo("24-BIT / 96k")
        assertThat(detailedBadge(16, 44.1)).isEqualTo("16-BIT / 44.1k")
        assertThat(detailedBadge(null, 44.1)).isEqualTo("44.1k FLAC")
        assertThat(detailedBadge(null, null, isAtmos = true)).isEqualTo("DOLBY ATMOS")
    }

    @Test
    fun `playing quality info chip labels`() {
        assertThat(PlayingQualityInfo(isLossless = true, bitDepth = 24, sampleRateKHz = 96.0).chipLabel())
            .isEqualTo("24/96kHz")
        assertThat(PlayingQualityInfo(isLossless = true, sampleRateKHz = 44.1).chipLabel())
            .isEqualTo("44.1kHz FLAC")
        assertThat(PlayingQualityInfo(isLossless = false, bitrateKbps = 320).chipLabel())
            .isEqualTo("320 kbps")
        assertThat(PlayingQualityInfo(isSpatial = true, codecLabel = "DOLBY ATMOS").chipLabel())
            .isEqualTo("ATMOS")
    }
}
