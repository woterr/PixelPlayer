package com.theveloper.pixelplay.data.quality

import android.media.AudioManager
import android.media.MediaCodecList
import android.os.Build
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.ffmpeg.FfmpegLibrary

/**
 * Whether a device can actually render Dolby Atmos / spatial streams.
 *
 * Ported from LastWave's `MusicPlayer` capability checks: the bundled FFmpeg
 * decoder answers E-AC-3 everywhere, so an Atmos master is playable whenever
 * either a platform decoder advertises the types or FFmpeg is available.
 * An Atmos request on an incapable device demotes to hi-res stereo instead
 * of surfacing an unplayable spatial manifest.
 */
object SpatialAudioCapability {

    /** Platform or bundled-FFmpeg E-AC-3 / AC-3 decoder, evaluated once. */
    private val atmosDecoderAvailable: Boolean by lazy {
        val platform = runCatching {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            codecList.codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { type ->
                    type.equals("audio/eac3-joc", ignoreCase = true) ||
                        type.equals("audio/eac3", ignoreCase = true) ||
                        type.equals("audio/ac3", ignoreCase = true)
                }
            }
        }.getOrDefault(false)
        val ffmpeg = runCatching {
            FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_E_AC3)
        }.getOrDefault(false)
        platform || ffmpeg
    }

    /** True when spatial playback is feasible: decoder present or system spatializer active. */
    fun isSpatialAudioSupportedOnDevice(audioManager: AudioManager?): Boolean {
        if (atmosDecoderAvailable) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
            val am = audioManager ?: return false
            val spatializer = am.spatializer
            if (spatializer.isAvailable || spatializer.isEnabled) {
                return true
            }
        }
        return false
    }
}
