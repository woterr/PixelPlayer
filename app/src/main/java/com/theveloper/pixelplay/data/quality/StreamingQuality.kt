package com.theveloper.pixelplay.data.quality

import kotlin.math.roundToInt

/**
 * Streaming quality tiers and the attempt order used when resolving audio.
 *
 * Ported from LastWave's `LosslessMusicApi` quality presets: the numeric ids,
 * the fallback waterfall between tiers and the descriptive labels are kept
 * identical so a LastWave-configured addon behaves the same way here.
 */
object StreamingQuality {
    const val QUALITY_DOLBY_ATMOS = 28 // Dolby Atmos Spatial Audio
    const val QUALITY_MAX_HI_RES = 27  // Up to 24-bit / 192 kHz
    const val QUALITY_HI_RES_96 = 7    // Up to 24-bit / 96 kHz
    const val QUALITY_CD_LOSSLESS = 6  // 16-bit / 44.1 kHz FLAC
    const val QUALITY_MP3_320 = 5      // 320 kbps MP3 / AAC High
    const val QUALITY_DATA_SAVER = 4   // 96 kbps HE-AAC Data Saver
    const val QUALITY_YOUTUBE = -1     // YouTube Music standard stream

    /** All tiers in descending fidelity order, YouTube last. */
    val ALL_TIERS = listOf(
        QUALITY_DOLBY_ATMOS,
        QUALITY_MAX_HI_RES,
        QUALITY_HI_RES_96,
        QUALITY_CD_LOSSLESS,
        QUALITY_MP3_320,
        QUALITY_DATA_SAVER,
        QUALITY_YOUTUBE,
    )

    /** Compact badge shown next to each tier in the settings dialog. */
    fun shortBadge(qualityId: Int): String = when (qualityId) {
        QUALITY_DOLBY_ATMOS -> "ATMOS"
        QUALITY_MAX_HI_RES -> "24-BIT / 192k"
        QUALITY_HI_RES_96 -> "24-BIT / 96k"
        QUALITY_CD_LOSSLESS -> "16-BIT / 44.1k"
        QUALITY_MP3_320 -> "320 kbps"
        QUALITY_DATA_SAVER -> "96 kbps"
        QUALITY_YOUTUBE -> "YOUTUBE"
        else -> "AUDIO"
    }

    /**
     * Tier fallback waterfall between addon search tiers. Rules mirror the
     * streaming rules:
     * - YouTube: strict, never attempts lossless (empty list = straight to YouTube).
     * - Atmos (28): atmos -> hi-res -> CD -> 320. Only path containing Atmos.
     * - Hi-Res (27/7): steps down only, never up to Atmos.
     * - Lower tiers: preferred first, then higher above, then lower below.
     * Data Saver is excluded from fallback (YouTube beats 96k) unless explicitly chosen.
     */
    fun getQualityAttemptOrder(preferred: Int): List<Int> {
        if (preferred == QUALITY_YOUTUBE) return emptyList()
        if (preferred == QUALITY_DOLBY_ATMOS) {
            return listOf(
                QUALITY_DOLBY_ATMOS,
                QUALITY_MAX_HI_RES,
                QUALITY_HI_RES_96,
                QUALITY_CD_LOSSLESS,
                QUALITY_MP3_320,
            )
        }
        if (preferred == QUALITY_MAX_HI_RES) {
            return listOf(
                QUALITY_MAX_HI_RES,
                QUALITY_HI_RES_96,
                QUALITY_CD_LOSSLESS,
                QUALITY_MP3_320,
            )
        }
        if (preferred == QUALITY_HI_RES_96) {
            return listOf(
                QUALITY_HI_RES_96,
                QUALITY_CD_LOSSLESS,
                QUALITY_MP3_320,
            )
        }
        val tiersAscending = listOf(
            QUALITY_DATA_SAVER,
            QUALITY_MP3_320,
            QUALITY_CD_LOSSLESS,
            QUALITY_HI_RES_96,
            QUALITY_MAX_HI_RES,
        )
        val index = tiersAscending.indexOf(preferred)
        if (index == -1) {
            return listOf(QUALITY_MAX_HI_RES, QUALITY_HI_RES_96, QUALITY_CD_LOSSLESS, QUALITY_MP3_320)
        }
        val preferredQuality = tiersAscending[index]
        val above = tiersAscending.subList(index + 1, tiersAscending.size)
        val below = tiersAscending.subList(0, index).reversed()
        return (listOf(preferredQuality) + above + below)
            .distinct()
            .filter { it != QUALITY_DATA_SAVER || preferred == QUALITY_DATA_SAVER }
    }

    /** True when the tier is one that seeks an addon lossless/lossy stream. */
    fun wantsAddon(qualityId: Int): Boolean = qualityId != QUALITY_YOUTUBE

    /** True when the tier would express a "*" spatial mix exclusively. */
    fun isAtmosTier(qualityId: Int): Boolean = qualityId == QUALITY_DOLBY_ATMOS
}

/**
 * Describes the stream that ended up playing, for the small quality chip that
 * surfaces near the player. Ported from LastWave's QualityBadge helpers.
 */
data class PlayingQualityInfo(
    val isLossless: Boolean = false,
    val isSpatial: Boolean = false,
    val bitDepth: Int? = null,
    val sampleRateKHz: Double? = null,
    val bitrateKbps: Int? = null,
    val codecLabel: String? = null,
) {
    fun badgeLabel(): String {
        if (isSpatial) return spatialIndicatorLabel(codecLabel)
        return detailedBadge(bitDepth, sampleRateKHz, false)
    }

    /** Compact chip label like `24/96kHz`, `44.1kHz FLAC`, `320 kbps` or `ATMOS`. */
    fun chipLabel(): String {
        if (isSpatial) return spatialIndicatorLabel(codecLabel)
        val rate = sampleRateKHz ?: 0.0
        return when {
            isLossless && (bitDepth ?: resolveDepthForDisplay(bitDepth, rate) ?: 0) > 0 && rate > 0.0 ->
                "$bitDepth/${formatSampleRateKHz(rate)}kHz"
            isLossless && rate > 0.0 -> "${formatSampleRateKHz(rate)}kHz FLAC"
            isLossless && (bitDepth ?: 0) > 0 -> "$bitDepth-BIT FLAC"
            isLossless -> "LOSSLESS"
            bitrateKbps != null -> "$bitrateKbps kbps"
            codecLabel != null -> codecLabel.uppercase()
            else -> "AUDIO"
        }
    }
}

/** Compact chip next to the title when the playing stream is spatial. */
fun spatialIndicatorLabel(codec: String?): String {
    val c = codec?.uppercase().orEmpty()
    if (c.contains("ATMOS")) return "ATMOS"
    if (c.contains("SPATIAL") || c.contains("360")) return "SPATIAL"
    return codec?.uppercase().orEmpty()
}

fun isSpatialAudioCodec(codec: String?): Boolean =
    codec?.uppercase().orEmpty().let { it.contains("ATMOS") || it.contains("SPATIAL") || it.contains("360") }

/** Format sample rate in kHz with minimal decimal places (44.1, 48, 88.2, 96, 176.4, 192). */
fun formatSampleRateKHz(kHzOrHz: Double): String {
    val kHz = if (kHzOrHz > 1000.0) kHzOrHz / 1000.0 else kHzOrHz
    val rounded = (kHz * 10.0).roundToInt() / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
}

/**
 * The bit depth displayed for a stream, from the reported depth and its sample
 * rate. A rate above 48kHz is a 24-bit master in practice; above 192kHz it is
 * 32-bit. At or below 48kHz nothing is assumed — unknown depth stays unknown.
 */
fun resolveDepthForDisplay(bitDepth: Int?, rateKHz: Double?): Int? {
    val reported = bitDepth?.takeIf { it > 0 }
    val rate = rateKHz ?: 0.0
    val hiRes = rate > 48.0
    return when {
        reported != null && (reported > 16 || !hiRes) -> reported
        rate > 192.0 -> 32
        hiRes -> 24
        else -> null
    }
}

/**
 * Standard detailed badge for downloads and metadata cards, ported from
 * LastWave's `formatDetailedQualityBadge`: e.g. "24-BIT / 96k", "16-BIT / 44.1k".
 */
fun detailedBadge(bitDepth: Int?, sampleRateKHzOrHz: Double?, isAtmos: Boolean = false): String {
    if (isAtmos) return "DOLBY ATMOS"
    val rateKHz = if ((sampleRateKHzOrHz ?: 0.0) > 1000.0) (sampleRateKHzOrHz ?: 0.0) / 1000.0 else (sampleRateKHzOrHz ?: 0.0)
    val depth = resolveDepthForDisplay(bitDepth, rateKHz)
    return when {
        depth != null && rateKHz > 0.0 -> "$depth-BIT / ${formatSampleRateKHz(rateKHz)}k"
        rateKHz > 0.0 -> "${formatSampleRateKHz(rateKHz)}k FLAC"
        depth != null -> "$depth-BIT FLAC"
        else -> "FLAC"
    }
}
