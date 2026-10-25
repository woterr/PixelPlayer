package com.theveloper.pixelplay.data.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.media.MediaScannerConnection
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kyant.taglib.Picture
import com.kyant.taglib.TagLib
import com.theveloper.pixelplay.MainActivity
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.di.AppScope
import com.theveloper.pixelplay.data.preferences.StreamQualityPreferences
import com.theveloper.pixelplay.data.quality.LosslessMusicApi
import com.theveloper.pixelplay.data.quality.SpatialAudioCapability
import com.theveloper.pixelplay.data.quality.StreamingQuality
import com.theveloper.pixelplay.data.quality.detailedBadge
import com.theveloper.pixelplay.data.youtube.YouTubeRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber

data class DownloadProgress(
    val key: String,
    val title: String,
    val artist: String,
    val progressPercent: Int = 0,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L,
    val formatBadge: String = "AUDIO",
    val isWaitingForConnection: Boolean = false,
    val isFinished: Boolean = false,
    val error: String? = null,
)

private data class DownloadTransfer(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val contentType: String,
)

private data class DownloadRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1L
}

private data class ParsedContentRange(
    val start: Long,
    val endInclusive: Long,
    val total: Long,
)

private class DownloadHttpException(val statusCode: Int) :
    IOException("HTTP $statusCode downloading track")

private class DownloadProtocolException(message: String) : IOException(message)

private class DownloadInterruptedException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * Downloads tracks to public storage with full metadata, ported from
 * LastWave's download manager:
 *
 * 1. Quality waterfall — [StreamingQuality.getQualityAttemptOrder] (Atmos
 *    only when explicitly picked AND the device can render it; lower tiers
 *    cascade down, YouTube last), resolved through [LosslessMusicApi] addons.
 * 2. DASH MPD manifests are fetched segment by segment (init + media); FLAC
 *    inside fMP4 gets unwrapped bit-exactly by [Mp4FlacRemuxer].
 * 3. The YouTube fallback prefers the Opus WebM rendition, remuxed into a
 *    tagged Ogg Opus via [WebmOpusRemuxer].
 * 4. Parallel ranged HTTP with reconnect/backoff, container sniffing, TagLib
 *    tagging, MediaStore insert (Music → Downloads → app dir) and
 *    progress/cancel/reconnect notifications.
 */
@Singleton
class TrackDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttpClient: OkHttpClient,
    private val losslessMusicApi: LosslessMusicApi,
    private val streamQualityPreferences: StreamQualityPreferences,
    private val youTubeRepository: YouTubeRepository,
    @AppScope private val applicationScope: CoroutineScope,
) {
    companion object {
        const val CHANNEL_ID = "pixelplayer_downloads"
        const val ACTION_CANCEL_DOWNLOAD = "com.theveloper.pixelplay.ACTION_CANCEL_DOWNLOAD"
        const val ACTION_RECONNECT_DOWNLOAD = "com.theveloper.pixelplay.ACTION_RECONNECT_DOWNLOAD"
        const val EXTRA_DOWNLOAD_KEY = "download_key"
        const val EXTRA_DOWNLOAD_TITLE = "download_title"
        const val EXTRA_DOWNLOAD_ARTIST = "download_artist"
        const val PUBLIC_DIR_NAME = "PixelPlayer"
        // 128 KB keeps per-connection buffers out of the large-object heap:
        // 512 KB chunks fragmented ART heaps on low-RAM devices and a burst
        // of parallel ranges could OOM the process after a few downloads.
        private const val DOWNLOAD_BUFFER_SIZE = 128 * 1024
        private const val PARALLEL_YOUTUBE_PARTS = 4
        // Up to 5 tracks download at once; per-download buffers stay small
        // (128 KB x 4 ranges) so this remains safe on low-RAM devices.
        private const val MAX_CONCURRENT_DOWNLOADS = 5
        /** Reconnectable transfer failures tolerated before the attempt is abandoned. */
        private const val MAX_TRANSFER_RETRIES = 6
        /** Bound on each YouTube stream-resolve step so one candidate can't wedge a slot. */
        private const val YT_STREAM_RESOLVE_TIMEOUT_MS = 20_000L
        private const val MIN_PARALLEL_DOWNLOAD_BYTES = 2L * 1024 * 1024
        private const val MIN_VALID_AUDIO_BYTES = 1_024L
        private const val RECONNECT_POLL_INTERVAL_MS = 500L
        private const val RECONNECT_RETRY_BASE_DELAY_MS = 1_000L
        private const val RECONNECT_RETRY_MAX_DELAY_MS = 30_000L
        private const val DOWNLOAD_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
        private val CONTENT_RANGE_PATTERN = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
        private const val DURATION_MISMATCH_SECONDS = 45

        fun makeDownloadKey(title: String, artist: String): String =
            "${artist.trim().lowercase()}_${title.trim().lowercase()}"

        /** Same $artist - $title base LastWave stores downloads under. */
        fun downloadFolderName(): String = PUBLIC_DIR_NAME

        fun candidateFilenames(artist: String, title: String): Set<String> {
            val sanitizedBase = sanitizeFilename(
                "${artist.trim()} - ${title.trim()}",
            )
            return setOf("flac", "m4a", "opus", "mp3", "webm", "ogg")
                .map { "$sanitizedBase.$it" }
                .toSet()
        }

        fun sanitizeFilename(title: String): String =
            title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "track" }
    }

    // Dedicated HTTP client with extended timeouts and bounded pooling.
    // Caps are deliberately low: each parallel range holds a socket plus a
    // read buffer, and unbounded pooling kept idle connections (and their
    // buffers) alive for minutes across successive downloads.
    private val downloadClient = okHttpClient.newBuilder()
        .dispatcher(Dispatcher().apply {
            // 5 concurrent downloads x 4 ranges, usually on one googlevideo
            // host: a per-host cap of 8 queued ranges behind each other.
            maxRequests = 40
            maxRequestsPerHost = 24
        })
        .connectionPool(ConnectionPool(20, 5, TimeUnit.MINUTES))
        .connectTimeout(20, TimeUnit.SECONDS)
        // A stalled socket is detected in 30s and resumed by the retry loop
        // instead of hanging the download for 2 minutes per stall.
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    // This class is constructed eagerly inside the Hilt graph. A system
    // service lookup that throws or returns null on a modified ROM would
    // otherwise fail every injection and take down app launch, so the
    // manager is resolved lazily and tolerated as absent.
    private val notificationManager: NotificationManager? by lazy {
        runCatching {
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        }.getOrNull()
    }

    private val activeKeys = ConcurrentHashMap.newKeySet<String>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    /** Shared with [downloadTrack] so a cancel can freeze admission before it snapshots jobs. */
    private val admissionLock = Any()
    @Volatile
    private var downloadsWiping = false
    private val activeUris = ConcurrentHashMap<String, Uri>()
    private val activeFiles = ConcurrentHashMap<String, File>()
    private val reconnectGenerations = ConcurrentHashMap<String, AtomicLong>()
    private val downloadSlots = Semaphore(MAX_CONCURRENT_DOWNLOADS)

    private val _downloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadProgress>> = _downloads.asStateFlow()

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = notificationManager ?: return
        runCatching {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.download_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.download_notification_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }.onFailure { error ->
            Timber.tag("TrackDownloadManager").w(error, "Notification channel unavailable")
        }
    }

    private fun downloadDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), PUBLIC_DIR_NAME)

    private fun downloadSearchDirs(): List<File> = listOf(downloadDir())

    fun isDownloading(title: String, artist: String): Boolean {
        val key = makeDownloadKey(title, artist)
        val progress = _downloads.value[key]
        return activeKeys.contains(key) ||
            (progress != null && !progress.isFinished && progress.error == null)
    }

    /** True when \"$artist - $title.{ext}\" already exists in the download folder / MediaStore. */
    fun isTrackDownloadedBlocking(title: String, artist: String): Boolean {
        val candidateNames = candidateFilenames(artist, title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            for (name in candidateNames) {
                val found = runCatching {
                    resolver.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.Audio.Media._ID),
                        "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Audio.Media.DISPLAY_NAME} = ?",
                        arrayOf("Music/$PUBLIC_DIR_NAME%", name),
                        null,
                    )?.use { cursor -> if (cursor.moveToFirst()) true else false } ?: false
                }.getOrDefault(false)
                if (found) return true
            }
            // Tag-based fallback: metadata may differ from the filename.
            val tagged = runCatching {
                resolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID),
                    "LOWER(${MediaStore.Audio.Media.TITLE}) = ? AND LOWER(${MediaStore.Audio.Media.ARTIST}) = ? AND ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
                    arrayOf(title.trim().lowercase(), artist.trim().lowercase(), "Music/$PUBLIC_DIR_NAME%"),
                    null,
                )?.use { cursor -> if (cursor.moveToFirst()) true else false } ?: false
            }.getOrDefault(false)
            if (tagged) return true
        }
        // Direct filesystem check (public Music folder is readable pre-Q, and
        // often readable with READ_MEDIA_AUDIO on modern builds).
        return runCatching {
            downloadSearchDirs().any { dir ->
                dir.exists() && dir.isDirectory &&
                    dir.walkTopDown().maxDepth(3).any { f ->
                        f.isFile && f.name in candidateNames && f.length() > 0
                    }
            }
        }.getOrDefault(false)
    }

    suspend fun isTrackDownloaded(title: String, artist: String): Boolean = withContext(Dispatchers.IO) {
        isTrackDownloadedBlocking(title, artist)
    }

    fun cancelDownload(key: String) {
        activeKeys.remove(key)
        reconnectGenerations.remove(key)?.incrementAndGet()
        val job = activeJobs.remove(key)
        job?.cancel()

        // Clean up partial MediaStore entry/file
        activeUris.remove(key)?.let { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
        activeFiles.remove(key)?.let { file ->
            runCatching { if (file.exists()) file.delete() }
        }

        notificationManager?.cancel(key.hashCode())
        _downloads.update { it - key }
    }

    fun cancelDownload(title: String, artist: String) {
        cancelDownload(makeDownloadKey(title, artist))
    }

    fun cancelAllDownloads() {
        val allKeys = (activeKeys + _downloads.value.keys).toSet()
        allKeys.forEach { key -> cancelDownload(key) }
    }

    fun reconnectDownload(key: String): Boolean {
        if (key !in activeKeys) return false
        return reconnectGenerations[key]?.incrementAndGet() != null
    }

    fun downloadTrack(
        title: String,
        artist: String,
        album: String? = null,
        artworkUrl: String? = null,
        year: String? = null,
        videoId: String? = null,
        durationMs: Long? = null,
    ) {
        val key = makeDownloadKey(title, artist)
        // Admission and registration share [admissionLock] so a cancel can
        // never strand a key between the two maps.
        synchronized(admissionLock) {
            if (downloadsWiping) return
            if (activeKeys.contains(key)) return
            if (isTrackDownloadedBlocking(title, artist)) return
            reconnectGenerations[key] = AtomicLong()
            val job = applicationScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                runDownloadJob(key, title, artist, album, artworkUrl, year, videoId, durationMs)
            }
            activeJobs[key] = job
            job.start()
        }
    }

    private suspend fun runDownloadJob(
        key: String,
        title: String,
        artist: String,
        album: String?,
        artworkUrl: String?,
        year: String?,
        videoId: String?,
        durationMs: Long?,
    ) {
        // Bound the number of simultaneous downloads: each one holds
        // sockets, buffers and (during tagging) a decoded cover bitmap,
        // and unbounded overlap OOMed low-RAM devices after a few songs.
        // acquire() is cancellable; a cancel while queued must still
        // release the key so the slot never strands.
        try {
            downloadSlots.acquire()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            forgetDownloadKey(key)
            return
        }
        if (downloadsWiping) {
            abandonAcquiredDownload(key)
            return
        }
        // Already downloaded? Skip re-downloading entirely rather than
        // re-fetching the bytes and shattering the library with "(1)" rows.
        val alreadyStored = runCatching { isTrackDownloadedBlocking(title, artist) }.getOrDefault(false)
        if (alreadyStored) {
            downloadSlots.release()
            forgetDownloadKey(key)
            return
        }

        val notifId = key.hashCode()
        updateProgress(DownloadProgress(key = key, title = title, artist = artist, progressPercent = 0))
        // Notifications are best-effort: a PendingIntent/Notification
        // failure must never escape the coroutine and kill the app.
        val preparingStage = context.getString(R.string.download_stage_preparing)
        runCatching {
            showDownloadNotification(notifId, key, title, artist, 0, false, preparingStage)
        }
        // Live stage text: tier failures used to be invisible until the
        // terminal skip. Stage updates are progress-only except on major
        // transitions, so notifications don't spam per tier.
        fun updateStage(stage: String, notify: Boolean = false) {
            updateProgress(
                DownloadProgress(
                    key = key, title = title, artist = artist,
                    progressPercent = 0, formatBadge = stage,
                ),
            )
            if (notify) {
                runCatching {
                    showDownloadNotification(notifId, key, title, artist, 0, false, stage)
                }
            }
        }

        var destinationUri: Uri? = null
        var destinationFile: File? = null
        var tempDownloadFile: File? = null

        try {
            currentCoroutineContext().ensureActive()
            val rawTitle = title.trim()
            val effTitle = rawTitle.ifBlank { title.trim() }
            val effArtist = artist.trim().ifBlank { "Unknown Artist" }
            val finalTitle = effTitle
            val finalArtist = effArtist
            val finalKey = key

            var resolvedUrl: String? = null
            // Exact YouTube videoId that produced this download (when the
            // YouTube path was used) — feeds the artwork fallback and
            // honest logging at tagging time.
            var resolvedYtVideoId: String? = videoId?.takeIf { it.isNotBlank() }
            var mimeType = "audio/flac"
            var extension = "flac"
            // No depth claim until a stream actually resolves one: this
            // seed is what a progress notification shows.
            var formatBadge = "FLAC"
            var isLossless = false
            var durationMsLocal = 0L
            var downloadHeaders = emptyMap<String, String>()
            var expectedContentLength: Long? = null
            var useParallelDownload = false

            val misc = streamQualityPreferences.current()
            val requestedDownloadQuality = misc.downloadQuality
            val isYouTubeRequested = requestedDownloadQuality == StreamingQuality.QUALITY_YOUTUBE

            var isDashDownload = false
            var dashInitUrl: String? = null
            var dashMediaTemplate: String? = null
            var dashSegmentCount = 0
            var dashStartNumber = 1
            /** Real codec from the DASH manifest (`flac`, `mp4a.40.2`, `ec-3`). */
            var dashManifestCodec = ""
            /** Manifest really carries FLAC (so the .m4a wrapper should be unwrapped). */
            var dashIsFlacInMp4 = false
            var bytesReadTotal = 0L
            var totalBytesRecorded = -1L
            var downloadSucceeded = false
            // Playback demotes Atmos to stereo on incapable devices; do
            // the same here so an Atmos request still yields playable
            // stereo FLAC instead of an unplayable spatial file.
            val audioManager = runCatching {
                context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            }.getOrNull()
            val atmosCapable: Boolean by lazy {
                SpatialAudioCapability.isSpatialAudioSupportedOnDevice(audioManager)
            }
            val expectedDurationSec = durationMs?.takeIf { it > 0 }?.let { (it / 1000L).toInt() }
            val downloadingStage = context.getString(R.string.download_stage_finding_lossless)
            val coolingStage = context.getString(R.string.download_stage_lossless_cooling)

            if (!isYouTubeRequested && !losslessMusicApi.isConfigured) {
                Timber.tag("TrackDownloadManager").i(
                    "Lossless addon not configured; '%s' goes straight to YouTube",
                    finalTitle,
                )
                updateStage(coolingStage, notify = true)
            } else if (!isYouTubeRequested && losslessMusicApi.isCoolingDown) {
                Timber.tag("TrackDownloadManager").i(
                    "Lossless backend cooling down; '%s' goes straight to YouTube",
                    finalTitle,
                )
                updateStage(coolingStage, notify = true)
            }

            if (!isYouTubeRequested && losslessMusicApi.isConfigured && !losslessMusicApi.isCoolingDown) {
                updateStage(downloadingStage)
                // Single source of truth: same tier order as streaming
                // (StreamingQuality.getQualityAttemptOrder). Rules:
                // - YouTube requested: strict, never reaches here.
                // - Atmos (28): atmos -> hi-res -> CD -> 320, then YouTube. Only path with 28.
                // - Hi-Res (27/7): step DOWN only, never up to Atmos. YouTube last.
                // - Lower tiers: preferred first, then higher above, then lower. Never Atmos.
                // Data Saver (96k) is skipped unless explicitly chosen (YouTube beats it).
                val qualitiesToAttempt = StreamingQuality.getQualityAttemptOrder(requestedDownloadQuality)
                for (downloadQuality in qualitiesToAttempt) {
                    // Spatial-tier veto (mirrors playback): never download an
                    // Atmos manifest on a device that cannot render it. The
                    // stereo tiers later in this same list are the fallback.
                    if (downloadQuality == StreamingQuality.QUALITY_DOLBY_ATMOS && !atmosCapable) {
                        Timber.tag("TrackDownloadManager").i(
                            "Atmos veto for %s by %s: device cannot render spatial; trying stereo tiers",
                            finalTitle, finalArtist,
                        )
                        continue
                    }
                    try {
                        // One retry: a transient blip must not kill the tier.
                        var losslessStream: com.theveloper.pixelplay.data.quality.LosslessAudioStream? = null
                        for (resolveAttempt in 0..1) {
                            losslessStream = runCatching {
                                losslessMusicApi.resolveStream(
                                    title = finalTitle,
                                    artist = finalArtist,
                                    expectedDurationSeconds = expectedDurationSec,
                                    expectedAlbum = album?.takeIf { it.isNotBlank() },
                                    preferredQuality = downloadQuality,
                                    isDownload = true,
                                )
                            }.getOrNull()
                            if (losslessStream != null) break
                            if (resolveAttempt == 0) delay(400)
                        }
                        if (losslessStream != null && losslessStream.url.isBlank()) losslessStream = null
                        // Duration guard: never store a different
                        // recording/edition under this song's name.
                        if (losslessStream != null && expectedDurationSec != null && expectedDurationSec > 0) {
                            val streamSec = losslessStream.durationSeconds
                            if (streamSec > 0 && abs(streamSec - expectedDurationSec) > DURATION_MISMATCH_SECONDS) {
                                Timber.tag("TrackDownloadManager").w(
                                    "Lossless duration mismatch for %s by %s: want %ss got %ss; trying next tier",
                                    finalTitle, finalArtist, expectedDurationSec, streamSec,
                                )
                                losslessStream = null
                            }
                        }
                        if (losslessStream == null) {
                            Timber.tag("TrackDownloadManager").i(
                                "Lossless tier %s missed for %s by %s; trying next tier",
                                downloadQuality, finalTitle, finalArtist,
                            )
                        }

                        if (losslessStream != null && losslessStream.url.isNotBlank()) {
                            if (isHlsUrl(losslessStream.url, losslessStream.mimeType)) {
                                // No HLS media source in this app's stack: skip
                                // the tier and let the next one (or YouTube) answer.
                                Timber.tag("TrackDownloadManager").i(
                                    "HLS lossless URL unsupported for %s; trying next tier", finalTitle,
                                )
                            } else if (isDashUrl(losslessStream.url, losslessStream.mimeType)) {
                                val parsedDash = parseDashManifest(losslessStream.url)
                                if (parsedDash != null) {
                                    val manifestCodec = parsedDash.codec.ifBlank { "flac" }
                                    // A stereo rendition served for an Atmos
                                    // request is fine (flac codec); a genuine
                                    // spatial manifest on an incapable device
                                    // is unplayable — cascade to stereo tiers.
                                    if (!atmosCapable &&
                                        (manifestCodec.startsWith("ec-3") ||
                                            manifestCodec.startsWith("eac3") ||
                                            manifestCodec.startsWith("ac-3"))
                                    ) {
                                        Timber.tag("TrackDownloadManager").w(
                                            "Spatial manifest veto for %s by %s on incapable device; cascading to stereo tiers",
                                            finalTitle, finalArtist,
                                        )
                                        continue
                                    }
                                    val isAtmosStream = losslessStream.formatId == StreamingQuality.QUALITY_DOLBY_ATMOS ||
                                        manifestCodec.startsWith("ec-3") ||
                                        manifestCodec.startsWith("eac3") ||
                                        manifestCodec.startsWith("ac-3")
                                    val isFlacStream = manifestCodec.contains("flac")
                                    isDashDownload = true
                                    dashInitUrl = parsedDash.initUrl
                                    dashMediaTemplate = parsedDash.mediaTemplate
                                    dashSegmentCount = parsedDash.segmentCount
                                    dashStartNumber = parsedDash.startNumber
                                    dashManifestCodec = manifestCodec
                                    dashIsFlacInMp4 = isFlacStream
                                    resolvedUrl = parsedDash.initUrl
                                    extension = "m4a"
                                    mimeType = "audio/mp4"
                                    val rateKHz = if (losslessStream.samplingRate > 1000.0) {
                                        losslessStream.samplingRate / 1000.0
                                    } else {
                                        losslessStream.samplingRate
                                    }
                                    val depth = losslessStream.bitDepth.takeIf { it > 0 }
                                    formatBadge = when {
                                        isAtmosStream -> "DOLBY ATMOS"
                                        losslessStream.formatId == StreamingQuality.QUALITY_MP3_320 -> "320 kbps"
                                        losslessStream.formatId == StreamingQuality.QUALITY_DATA_SAVER -> "96 kbps"
                                        isFlacStream -> detailedBadge(depth, rateKHz)
                                        manifestCodec.startsWith("mp4a.40.5") -> "96 kbps"
                                        manifestCodec.startsWith("mp4a") -> "320 kbps"
                                        else -> "AAC"
                                    }
                                    isLossless = isAtmosStream || isFlacStream
                                    durationMsLocal =
                                        (losslessStream.durationSeconds * 1000L).takeIf { it > 0 } ?: 0L
                                }
                            } else {
                                val isAtmosStream =
                                    losslessStream.formatId == StreamingQuality.QUALITY_DOLBY_ATMOS ||
                                        losslessStream.audioCodecOverride == "DOLBY ATMOS"
                                resolvedUrl = losslessStream.url
                                mimeType = losslessStream.mimeType.ifBlank {
                                    if (isAtmosStream) "audio/mp4" else "audio/flac"
                                }
                                extension = when {
                                    mimeType.contains("mp3", ignoreCase = true) -> "mp3"
                                    isAtmosStream ||
                                        mimeType.contains("mp4", ignoreCase = true) ||
                                        mimeType.contains("m4a", ignoreCase = true) -> "m4a"
                                    mimeType.contains("aac", ignoreCase = true) -> "m4a"
                                    mimeType.contains("opus", ignoreCase = true) ||
                                        mimeType.contains("ogg", ignoreCase = true) -> "opus"
                                    else -> "flac"
                                }
                                isLossless = !extension.equals("mp3", ignoreCase = true) &&
                                    !extension.equals("m4a", ignoreCase = true) &&
                                    !extension.equals("opus", ignoreCase = true) &&
                                    losslessStream.formatId != StreamingQuality.QUALITY_MP3_320 &&
                                    losslessStream.formatId != StreamingQuality.QUALITY_DATA_SAVER &&
                                    !mimeType.contains("aac", ignoreCase = true)
                                val rateKHz = if (losslessStream.samplingRate > 1000.0) {
                                    losslessStream.samplingRate / 1000.0
                                } else {
                                    losslessStream.samplingRate
                                }
                                val depth = losslessStream.bitDepth.takeIf { it > 0 }
                                formatBadge = when {
                                    isAtmosStream -> "DOLBY ATMOS"
                                    losslessStream.formatId == StreamingQuality.QUALITY_MP3_320 -> "320 kbps"
                                    losslessStream.formatId == StreamingQuality.QUALITY_DATA_SAVER -> "96 kbps"
                                    isLossless -> detailedBadge(depth, rateKHz)
                                    else -> if (extension.equals("mp3", ignoreCase = true)) "MP3" else "AAC"
                                }
                                durationMsLocal =
                                    (losslessStream.durationSeconds * 1000L).takeIf { it > 0 } ?: 0L
                            }
                        }

                        if (resolvedUrl != null) {
                            val rawFile = File.createTempFile("dl_raw_", ".$extension", context.cacheDir)
                            tempDownloadFile = rawFile

                            if (isDashDownload && dashInitUrl != null && dashMediaTemplate != null && dashSegmentCount > 0) {
                                val dashResult = downloadDashSegmentsToTempFile(
                                    downloadKey = finalKey,
                                    notificationId = notifId,
                                    title = title,
                                    artist = artist,
                                    formatBadge = formatBadge,
                                    initUrl = dashInitUrl!!,
                                    mediaTemplate = dashMediaTemplate!!,
                                    segmentCount = dashSegmentCount,
                                    startNumber = dashStartNumber,
                                    headers = emptyMap(),
                                    target = rawFile,
                                    updateStage = { stage -> updateStage(stage) },
                                )
                                bytesReadTotal = dashResult.bytesWritten
                                totalBytesRecorded = bytesReadTotal
                                // A truncated segment list used to be kept
                                // as a successful (short) file. Fail loudly
                                // instead so the next tier or YouTube gets
                                // a chance — unless enough audio arrived
                                // that the manifest simply over-declared.
                                if (dashResult.stoppedEarly) {
                                    val minBytes = if (durationMsLocal > 0) {
                                        (durationMsLocal / 1000L) * 16_000L
                                    } else {
                                        256_000L
                                    }
                                    if (bytesReadTotal < minBytes) {
                                        throw IOException(
                                            "Incomplete DASH download for $title by $artist: " +
                                                "${dashResult.completedSegments}/${dashResult.expectedSegments} segments",
                                        )
                                    }
                                    Timber.tag("TrackDownloadManager").w(
                                        "DASH over-declared segments for %s by %s (%s/%s); keeping %s bytes",
                                        title, artist, dashResult.completedSegments,
                                        dashResult.expectedSegments, bytesReadTotal,
                                    )
                                }
                            } else {
                                val downloadStage = context.getString(R.string.download_stage_downloading)
                                updateStage(downloadStage)
                                var lastProgress = 0
                                var lastNotifTime = 0L
                                var lastUnknownProgressBytes = 0L
                                val progressLock = Any()
                                val transfer = downloadToTempFile(
                                    downloadKey = finalKey,
                                    url = checkNotNull(resolvedUrl),
                                    target = rawFile,
                                    requestHeaders = downloadHeaders,
                                    expectedContentLength = expectedContentLength,
                                    useParallelRanges = useParallelDownload,
                                    onConnectionStateChanged = { isWaiting ->
                                        _downloads.value[finalKey]?.let { current ->
                                            val updated = current.copy(
                                                isWaitingForConnection = isWaiting,
                                            )
                                            updateProgress(updated)
                                            runCatching {
                                                showDownloadNotification(
                                                    notificationId = notifId,
                                                    downloadKey = finalKey,
                                                    title = title,
                                                    artist = artist,
                                                    progress = updated.progressPercent,
                                                    isIndeterminate = updated.totalBytes <= 0L,
                                                    badgeText = updated.formatBadge,
                                                    isWaitingForConnection = isWaiting,
                                                )
                                            }
                                        }
                                    },
                                ) { downloadedBytes, totalBytes ->
                                    synchronized(progressLock) {
                                        val now = SystemClock.uptimeMillis()
                                        if (totalBytes > 0) {
                                            val progress =
                                                ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                                            if (progress > lastProgress) {
                                                lastProgress = progress
                                                updateProgress(
                                                    DownloadProgress(
                                                        key = finalKey, title = title, artist = artist,
                                                        progressPercent = progress,
                                                        bytesDownloaded = downloadedBytes,
                                                        totalBytes = totalBytes,
                                                        formatBadge = formatBadge,
                                                    ),
                                                )
                                                if (progress == 100 || now - lastNotifTime >= 250L) {
                                                    lastNotifTime = now
                                                    runCatching {
                                                        showDownloadNotification(
                                                            notifId, finalKey, title, artist,
                                                            progress, false, formatBadge,
                                                        )
                                                    }
                                                }
                                            }
                                        } else if (downloadedBytes - lastUnknownProgressBytes >= 1024 * 1024) {
                                            lastUnknownProgressBytes = downloadedBytes
                                            val mbDown =
                                                String.format(java.util.Locale.ROOT, "%.1f MB", downloadedBytes / (1024.0 * 1024.0))
                                            updateProgress(
                                                DownloadProgress(
                                                    key = finalKey, title = title, artist = artist,
                                                    progressPercent = 0,
                                                    bytesDownloaded = downloadedBytes,
                                                    totalBytes = -1L,
                                                    formatBadge = "$formatBadge • $mbDown",
                                                ),
                                            )
                                            if (now - lastNotifTime >= 500L) {
                                                lastNotifTime = now
                                                runCatching {
                                                    showDownloadNotification(notifId, finalKey, title, artist, 0, true, formatBadge)
                                                }
                                            }
                                        }
                                    }
                                }
                                val contentType = transfer.contentType.lowercase()
                                if (contentType.contains("webm")) {
                                    extension = "webm"
                                    mimeType = "audio/webm"
                                    formatBadge = "WEBM OPUS"
                                } else if (contentType.contains("ogg") || contentType.contains("opus")) {
                                    extension = "opus"
                                    mimeType = "audio/ogg"
                                    formatBadge = "OPUS"
                                } else if (contentType.contains("mpeg") || contentType.contains("mp3")) {
                                    extension = "mp3"
                                    mimeType = "audio/mpeg"
                                    formatBadge = "MP3"
                                } else if (contentType.contains("mp4") || contentType.contains("m4a") || contentType.contains("aac")) {
                                    extension = "m4a"
                                    mimeType = "audio/mp4"
                                    formatBadge = "M4A AAC"
                                }
                                bytesReadTotal = transfer.bytesDownloaded
                                totalBytesRecorded = transfer.totalBytes
                            }

                            if (!isDashDownload) {
                                val detected = detectValidContainer(rawFile)
                                if (detected != null) {
                                    extension = detected
                                    mimeType = when (detected) {
                                        "m4a" -> "audio/mp4"
                                        "webm" -> "audio/webm"
                                        "opus" -> "audio/ogg"
                                        "mp3" -> "audio/mpeg"
                                        "flac" -> "audio/flac"
                                        else -> mimeType
                                    }
                                } else if (useParallelDownload && !hasExpectedContainer(rawFile, extension)) {
                                    throw IOException("Downloaded payload is not a valid ${extension.uppercase()} audio file")
                                }
                            }
                            downloadSucceeded = true
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (tierError: Throwable) {
                        Timber.tag("TrackDownloadManager").w(
                            tierError,
                            "Download (quality=%s) failed for %s by %s; trying next tier or YouTube",
                            downloadQuality, title, artist,
                        )
                        isDashDownload = false
                        resolvedUrl = null
                        tempDownloadFile?.let { runCatching { if (it.exists()) it.delete() } }
                        tempDownloadFile = null
                        downloadSucceeded = false
                        // Reset state for potential retry at lower quality
                        mimeType = "audio/flac"
                        extension = "flac"
                        // Depth is unknown again until the retry resolves one.
                        formatBadge = "FLAC"
                        isLossless = false
                        durationMsLocal = 0L
                        downloadHeaders = emptyMap()
                        expectedContentLength = null
                        useParallelDownload = false
                        dashInitUrl = null
                        dashMediaTemplate = null
                        dashSegmentCount = 0
                        dashStartNumber = 1
                        dashManifestCodec = ""
                        dashIsFlacInMp4 = false
                        bytesReadTotal = 0L
                        totalBytesRecorded = -1L
                        continue
                    }
                    if (downloadSucceeded) break
                } // end quality retry loop
            }

            // 2. Fallback to YouTube if lossless was not requested or every tier failed
            if (!downloadSucceeded) {
                try {
                    val findingStage = context.getString(R.string.download_stage_finding_youtube)
                    updateStage(findingStage, notify = true)
                    if (videoId.isNullOrBlank()) {
                        throw IOException("No YouTube match found for $finalTitle by $finalArtist")
                    }
                    val ytFallbackStartMs = SystemClock.elapsedRealtime()
                    val ytStream = try {
                        withTimeoutOrNull(YT_STREAM_RESOLVE_TIMEOUT_MS) {
                            youTubeRepository.resolveDownloadStream(videoId)
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        throw IOException("No playable YouTube stream for $finalTitle by $finalArtist", error)
                    }
                    if (ytStream == null) {
                        throw IOException("No playable YouTube stream for $finalTitle by $finalArtist")
                    }
                    if (SystemClock.elapsedRealtime() - ytFallbackStartMs > YT_STREAM_RESOLVE_TIMEOUT_MS) {
                        // Resolve already spent its budget; continue with what arrived.
                        Timber.tag("TrackDownloadManager").w(
                            "YouTube resolve took long for %s by %s",
                            finalTitle, finalArtist,
                        )
                    }
                    resolvedYtVideoId = videoId
                    resolvedUrl = ytStream.url
                    downloadHeaders = ytStream.requestHeaders
                    expectedContentLength = ytStream.contentLength
                        ?: runCatching {
                            Uri.parse(ytStream.url).getQueryParameter("clen")?.toLongOrNull()
                        }.getOrNull()
                    useParallelDownload = true
                    val rawMime = ytStream.mimeType.orEmpty().lowercase()
                    if (rawMime.contains("mp4") || rawMime.contains("m4a") || rawMime.contains("aac")) {
                        extension = "m4a"
                        mimeType = "audio/mp4"
                        formatBadge = "M4A AAC"
                    } else if (rawMime.contains("webm")) {
                        extension = "webm"
                        mimeType = "audio/webm"
                        formatBadge = "WEBM OPUS"
                    } else if (rawMime.contains("ogg") || rawMime.contains("opus")) {
                        extension = "opus"
                        mimeType = "audio/ogg"
                        formatBadge = "OPUS"
                    } else if (rawMime.contains("mpeg") || rawMime.contains("mp3")) {
                        extension = "mp3"
                        mimeType = "audio/mpeg"
                        formatBadge = "MP3"
                    } else {
                        extension = "m4a"
                        mimeType = "audio/mp4"
                        formatBadge = "AUDIO"
                    }
                    isLossless = false

                    val rawFile = File.createTempFile("dl_raw_", ".$extension", context.cacheDir)
                    tempDownloadFile = rawFile

                    var lastProgress = 0
                    var lastNotifTime = 0L
                    var lastUnknownProgressBytes = 0L
                    val progressLock = Any()
                    val transfer = downloadToTempFile(
                        downloadKey = finalKey,
                        url = checkNotNull(resolvedUrl),
                        target = rawFile,
                        requestHeaders = downloadHeaders,
                        expectedContentLength = expectedContentLength,
                        useParallelRanges = useParallelDownload,
                        onConnectionStateChanged = { isWaiting ->
                            _downloads.value[finalKey]?.let { current ->
                                val updated = current.copy(isWaitingForConnection = isWaiting)
                                updateProgress(updated)
                                runCatching {
                                    showDownloadNotification(
                                        notificationId = notifId,
                                        downloadKey = finalKey,
                                        title = title,
                                        artist = artist,
                                        progress = updated.progressPercent,
                                        isIndeterminate = updated.totalBytes <= 0L,
                                        badgeText = updated.formatBadge,
                                        isWaitingForConnection = isWaiting,
                                    )
                                }
                            }
                        },
                    ) { downloadedBytes, totalBytes ->
                        synchronized(progressLock) {
                            val now = SystemClock.uptimeMillis()
                            if (totalBytes > 0) {
                                val progress = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                                if (progress > lastProgress) {
                                    lastProgress = progress
                                    updateProgress(
                                        DownloadProgress(
                                            key = finalKey, title = title, artist = artist,
                                            progressPercent = progress,
                                            bytesDownloaded = downloadedBytes,
                                            totalBytes = totalBytes,
                                            formatBadge = formatBadge,
                                        ),
                                    )
                                    if (progress == 100 || now - lastNotifTime >= 250L) {
                                        lastNotifTime = now
                                        runCatching {
                                            showDownloadNotification(notifId, finalKey, title, artist, progress, false, formatBadge)
                                        }
                                    }
                                }
                            } else if (downloadedBytes - lastUnknownProgressBytes >= 1024 * 1024) {
                                lastUnknownProgressBytes = downloadedBytes
                                val mbDown =
                                    String.format(java.util.Locale.ROOT, "%.1f MB", downloadedBytes / (1024.0 * 1024.0))
                                updateProgress(
                                    DownloadProgress(
                                        key = finalKey, title = title, artist = artist,
                                        progressPercent = 0,
                                        bytesDownloaded = downloadedBytes,
                                        totalBytes = -1L,
                                        formatBadge = "$formatBadge • $mbDown",
                                    ),
                                )
                                if (now - lastNotifTime >= 500L) {
                                    lastNotifTime = now
                                    runCatching {
                                        showDownloadNotification(notifId, finalKey, title, artist, 0, true, formatBadge)
                                    }
                                }
                            }
                        }
                    }
                    val contentType = transfer.contentType.lowercase()
                    if (contentType.contains("webm")) {
                        extension = "webm"
                        mimeType = "audio/webm"
                        formatBadge = "WEBM OPUS"
                    } else if (contentType.contains("ogg") || contentType.contains("opus")) {
                        extension = "opus"
                        mimeType = "audio/ogg"
                        formatBadge = "OPUS"
                    } else if (contentType.contains("mpeg") || contentType.contains("mp3")) {
                        extension = "mp3"
                        mimeType = "audio/mpeg"
                        formatBadge = "MP3"
                    } else if (contentType.contains("mp4") || contentType.contains("m4a") || contentType.contains("aac")) {
                        extension = "m4a"
                        mimeType = "audio/mp4"
                        formatBadge = "M4A AAC"
                    }
                    bytesReadTotal = transfer.bytesDownloaded
                    totalBytesRecorded = transfer.totalBytes

                    val detected = detectValidContainer(rawFile)
                    if (detected != null) {
                        // Accept whatever valid audio YouTube served
                        // (opus/webm/m4a/mp3) — remux below normalizes it.
                        // Never fail on mime mismatch.
                        extension = detected
                        mimeType = when (detected) {
                            "m4a" -> "audio/mp4"
                            "webm" -> "audio/webm"
                            "opus" -> "audio/ogg"
                            "mp3" -> "audio/mpeg"
                            "flac" -> "audio/flac"
                            else -> mimeType
                        }
                    } else if (rawFile.length() < MIN_VALID_AUDIO_BYTES || !hasExpectedContainer(rawFile, extension)) {
                        // Only reject when the payload is not audio at all
                        // (HTML error page / truncated). A valid container
                        // with an unexpected extension is kept.
                        if (detectValidContainer(rawFile) == null && rawFile.length() < MIN_VALID_AUDIO_BYTES) {
                            throw IOException("Downloaded payload is not valid audio (${rawFile.length()} bytes)")
                        }
                    }
                    downloadSucceeded = true
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (ytError: Throwable) {
                    // Surface the real reason (no match vs no stream vs
                    // network) so a playable song never shows a generic skip.
                    val reason = ytError.message?.takeIf { it.isNotBlank() } ?: "Stream unavailable"
                    val shortReason = if (reason.length > 90) reason.take(90) + "…" else reason
                    Timber.tag("TrackDownloadManager").w(
                        ytError,
                        "YouTube download failed for %s by %s: %s",
                        title, artist, reason,
                    )
                    tempDownloadFile?.let { runCatching { if (it.exists()) it.delete() } }
                    tempDownloadFile = null
                    updateProgress(
                        DownloadProgress(
                            key = finalKey,
                            title = title,
                            artist = artist,
                            progressPercent = 0,
                            error = "Skipped: $shortReason",
                        ),
                    )
                    runCatching {
                        showErrorNotification(notifId, finalKey, title, artist, "Skipped: $shortReason")
                    }
                    return
                }
            }

            val downloadedFile = tempDownloadFile ?: throw IOException("Downloaded file is missing")
            var currentAudioFile = downloadedFile

            // Losslessly remux WebM Opus into standard Ogg Opus for universal
            // player & tag compatibility
            if (extension == "webm") {
                val opusFile = File.createTempFile("dl_remux_", ".opus", context.cacheDir)
                if (WebmOpusRemuxer.remux(currentAudioFile, opusFile)) {
                    currentAudioFile.delete()
                    currentAudioFile = opusFile
                    tempDownloadFile = opusFile
                    extension = "opus"
                    mimeType = "audio/ogg"
                    formatBadge = "OPUS"
                } else {
                    opusFile.delete()
                }
            }

            // Tidal lossless DASH is FLAC carried inside MP4. Users who
            // asked for FLAC expect a real .flac file, and an .m4a holding
            // FLAC reads as "AAC" to players and tag tools. Unwrap the
            // container losslessly — STREAMINFO from the dfLa box plus the
            // raw frames from every mdat; nothing is decoded or re-encoded,
            // so 24-bit hi-res stays bit-exact. On failure the .m4a is kept
            // rather than losing a completed download.
            if (dashIsFlacInMp4 && extension == "m4a") {
                val nativeFlac = File.createTempFile("dl_flac_", ".flac", context.cacheDir)
                if (Mp4FlacRemuxer.remux(currentAudioFile, nativeFlac)) {
                    runCatching { currentAudioFile.delete() }
                    currentAudioFile = nativeFlac
                    tempDownloadFile = nativeFlac
                    extension = "flac"
                    mimeType = "audio/flac"
                    isLossless = true
                } else {
                    runCatching { nativeFlac.delete() }
                    Timber.tag("TrackDownloadManager").w(
                        "FLAC unwrap failed for %s by %s (codec=%s); keeping .m4a",
                        title, artist, dashManifestCodec,
                    )
                }
            }

            val safeFilename = sanitizeFilename("$finalArtist - $finalTitle") + ".$extension"

            // 3. Resolve exact audio duration from the downloaded file
            val durationRetriever = MediaMetadataRetriever()
            try {
                durationRetriever.setDataSource(currentAudioFile.absolutePath)
                val durStr = durationRetriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION,
                )
                durStr?.toLongOrNull()?.takeIf { it > 0 }?.let { durationMsLocal = it }
            } catch (_: Exception) {
            } finally {
                runCatching { durationRetriever.release() }
            }

            // 4. Embed metadata + cover art directly into the downloaded audio
            // file (TagLib handles Vorbis comments + PICTURE, iTunes atoms and
            // ID3v2.x depending on the container). Failure keeps the plain file.
            var embeddedArtworkUrl = artworkUrl?.takeIf { it.isNotBlank() }
            if (embeddedArtworkUrl.isNullOrBlank()) {
                // Last resort: the YouTube thumbnail for the exact downloaded
                // video always exists.
                embeddedArtworkUrl = resolvedYtVideoId?.takeIf { it.isNotBlank() }
                    ?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
            }
            val metadataEmbedded = embedMetadata(
                audioFile = currentAudioFile,
                title = finalTitle,
                artist = finalArtist,
                album = album?.takeIf { it.isNotBlank() },
                artworkUrl = embeddedArtworkUrl,
                year = year,
            )
            if (!metadataEmbedded) {
                Timber.tag("TrackDownloadManager").w(
                    "Could not safely embed audio metadata; preserving audio file",
                )
            }

            // 5. Transfer the tagged file into public storage / MediaStore
            currentCoroutineContext().ensureActive()
            val (destStream, uri, file) = openPublicOutputStream(
                filename = safeFilename,
                mimeType = mimeType,
                title = finalTitle,
                artist = finalArtist,
                album = album?.takeIf { it.isNotBlank() },
                year = year,
                durationMs = durationMsLocal,
            )
            destinationUri = uri
            destinationFile = file
            if (uri != null) activeUris[finalKey] = uri
            if (file != null) activeFiles[finalKey] = file

            val taggedFileLength = currentAudioFile.length()
            currentAudioFile.inputStream().use { input ->
                destStream.use { output ->
                    val copied = copyStream(input, output)
                    output.flush()
                    (output as? FileOutputStream)?.fd?.sync()
                    if (copied != taggedFileLength) {
                        throw IOException("Public file copy truncated: received $copied of $taggedFileLength bytes")
                    }
                }
            }

            // 6. Mark the public MediaStore file as finished (IS_PENDING = 0)
            finalizePublicFile(uri)
            if (file != null) {
                runCatching {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(file.absolutePath),
                        arrayOf(mimeType),
                        null,
                    )
                }
            }

            updateProgress(
                DownloadProgress(
                    key = finalKey,
                    title = finalTitle,
                    artist = finalArtist,
                    progressPercent = 100,
                    bytesDownloaded = bytesReadTotal,
                    totalBytes = if (totalBytesRecorded > 0) totalBytesRecorded else bytesReadTotal,
                    formatBadge = formatBadge,
                    isFinished = true,
                ),
            )

            // Surface missing extras honestly instead of silently: the file
            // is fine, but the user sees what didn't attach.
            val missingExtras = buildList {
                if (!metadataEmbedded) add(context.getString(R.string.download_missing_tags))
                if (embeddedArtworkUrl.isNullOrBlank()) add(context.getString(R.string.download_missing_art))
            }
            val completedBadge = if (missingExtras.isEmpty()) formatBadge else {
                "$formatBadge • ${missingExtras.joinToString(" + ")}"
            }
            runCatching { showCompletedNotification(notifId, finalTitle, finalArtist, completedBadge) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // Cancelled by user — clean up partial file
            destinationUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            destinationFile?.let { runCatching { if (it.exists()) it.delete() } }
            notificationManager?.cancel(notifId)
            _downloads.update { it - key }
            throw cancelled
        } catch (oom: OutOfMemoryError) {
            // Memory pressure (parallel ranges + decoded cover art) used
            // to escape as an uncaught Error and kill the whole process.
            // Fail just this download instead so the app survives.
            Timber.tag("TrackDownloadManager").e(oom, "Out of memory downloading %s", title)
            destinationUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            destinationFile?.let { runCatching { if (it.exists()) it.delete() } }
            updateProgress(
                DownloadProgress(
                    key = key,
                    title = title,
                    artist = artist,
                    error = context.getString(R.string.download_out_of_memory),
                ),
            )
            runCatching {
                showErrorNotification(notifId, key, title, artist, context.getString(R.string.download_out_of_memory_retry))
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            destinationUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            destinationFile?.let { runCatching { if (it.exists()) it.delete() } }
            updateProgress(
                DownloadProgress(
                    key = key,
                    title = title,
                    artist = artist,
                    error = error.message?.ifBlank { null } ?: "Download failed",
                ),
            )
            runCatching {
                showErrorNotification(notifId, key, title, artist, error.message ?: "Failed")
            }
        } finally {
            runCatching { downloadSlots.release() }
            activeKeys.remove(key)
            activeJobs.remove(key)
            activeUris.remove(key)
            activeFiles.remove(key)
            reconnectGenerations.remove(key)
            tempDownloadFile?.let { runCatching { if (it.exists()) it.delete() } }
        }
    }

    private fun forgetDownloadKey(key: String) {
        activeKeys.remove(key)
        activeJobs.remove(key)
        reconnectGenerations.remove(key)
        _downloads.update { it - key }
    }

    /** Worker that already holds a slot must release it exactly once. */
    private fun abandonAcquiredDownload(key: String) {
        runCatching { downloadSlots.release() }
        activeUris.remove(key)?.let { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
        activeFiles.remove(key)?.let { file ->
            runCatching { if (file.exists()) file.delete() }
        }
        forgetDownloadKey(key)
    }

    private suspend fun downloadToTempFile(
        downloadKey: String,
        url: String,
        target: File,
        requestHeaders: Map<String, String>,
        expectedContentLength: Long?,
        useParallelRanges: Boolean,
        onConnectionStateChanged: (Boolean) -> Unit,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadTransfer {
        val parallelLength = expectedContentLength
            ?.takeIf { useParallelRanges && it >= MIN_PARALLEL_DOWNLOAD_BYTES }
        if (parallelLength != null) {
            try {
                return downloadInParallel(
                    url = url,
                    target = target,
                    requestHeaders = requestHeaders,
                    totalLength = parallelLength,
                    downloadKey = downloadKey,
                    onConnectionStateChanged = onConnectionStateChanged,
                    onProgress = onProgress,
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.tag("TrackDownloadManager").w(
                    error,
                    "Validated range download unavailable; using one stream",
                )
                truncateFile(target)
            }
        }

        return downloadSingleStream(
            downloadKey = downloadKey,
            url = url,
            target = target,
            requestHeaders = requestHeaders,
            expectedContentLength = expectedContentLength,
            onConnectionStateChanged = onConnectionStateChanged,
            onProgress = onProgress,
        )
    }

    private suspend fun downloadInParallel(
        url: String,
        target: File,
        requestHeaders: Map<String, String>,
        totalLength: Long,
        downloadKey: String,
        onConnectionStateChanged: (Boolean) -> Unit,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadTransfer = coroutineScope {
        val partSize = (totalLength + PARALLEL_YOUTUBE_PARTS - 1L) / PARALLEL_YOUTUBE_PARTS
        val ranges = (0 until PARALLEL_YOUTUBE_PARTS).mapNotNull { index ->
            val start = index * partSize
            if (start >= totalLength) return@mapNotNull null
            DownloadRange(start, minOf(totalLength - 1L, start + partSize - 1L))
        }
        RandomAccessFile(target, "rw").use { it.setLength(totalLength) }

        val downloadedBytes = AtomicLong(0L)
        val waitingRanges = AtomicInteger(0)
        val rangeConnectionStateChanged: (Boolean) -> Unit = { isWaiting ->
            val waitingCount = if (isWaiting) {
                waitingRanges.incrementAndGet()
            } else {
                waitingRanges.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
            }
            onConnectionStateChanged(waitingCount > 0)
        }
        val contentTypes = ranges.map { range ->
            async {
                var downloadedBytesInRange = 0L
                retryInterruptedTransfer(downloadKey, rangeConnectionStateChanged) {
                    downloadRange(
                        url = url,
                        target = target,
                        requestHeaders = requestHeaders,
                        range = DownloadRange(range.start + downloadedBytesInRange, range.endInclusive),
                        expectedTotal = totalLength,
                    ) { delta ->
                        downloadedBytesInRange += delta
                        onProgress(downloadedBytes.addAndGet(delta.toLong()), totalLength)
                    }
                }
            }
        }.awaitAll()

        val finalLength = downloadedBytes.get()
        if (finalLength != totalLength || target.length() != totalLength) {
            throw IOException("Parallel download truncated: received $finalLength of $totalLength bytes")
        }
        RandomAccessFile(target, "rw").use { it.fd.sync() }
        DownloadTransfer(
            bytesDownloaded = finalLength,
            totalBytes = totalLength,
            contentType = contentTypes.firstOrNull { it.isNotBlank() }.orEmpty(),
        )
    }

    private suspend fun downloadRange(
        url: String,
        target: File,
        requestHeaders: Map<String, String>,
        range: DownloadRange,
        expectedTotal: Long,
        onBytesRead: (Int) -> Unit,
    ): String = suspendCancellableCoroutine { continuation ->
        val request = buildDownloadRequest(url, requestHeaders, range)
        val call = downloadClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val contentType = response.use { currentResponse ->
                        validateMediaResponse(currentResponse, expectedCode = 206)
                        val parsedRange = parseContentRange(currentResponse.header("Content-Range"))
                            ?: throw DownloadProtocolException("Missing Content-Range for parallel download")
                        if (parsedRange.start != range.start ||
                            parsedRange.endInclusive != range.endInclusive ||
                            parsedRange.total != expectedTotal
                        ) {
                            throw DownloadProtocolException(
                                "Mismatched Content-Range ${currentResponse.header("Content-Range")}",
                            )
                        }

                        val body = currentResponse.body
                            ?: throw DownloadProtocolException("Empty range response body")
                        val bodyLength = body.contentLength()
                        if (bodyLength > 0L && bodyLength != range.length) {
                            throw DownloadProtocolException(
                                "Range length mismatch: received $bodyLength of ${range.length} bytes",
                            )
                        }

                        RandomAccessFile(target, "rw").use { output ->
                            output.seek(range.start)
                            body.byteStream().use { input ->
                                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                                var remaining = range.length
                                while (remaining > 0L) {
                                    if (!continuation.isActive) {
                                        throw kotlinx.coroutines.CancellationException("Download cancelled")
                                    }
                                    val read = try {
                                        input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                    } catch (error: IOException) {
                                        throw DownloadInterruptedException(
                                            "Connection interrupted while reading audio",
                                            error,
                                        )
                                    }
                                    if (read < 0) {
                                        throw DownloadInterruptedException(
                                            "Range truncated with $remaining bytes remaining",
                                        )
                                    }
                                    output.write(buffer, 0, read)
                                    remaining -= read
                                    onBytesRead(read)
                                }
                            }
                        }
                        currentResponse.header("Content-Type").orEmpty()
                    }
                    if (continuation.isActive) continuation.resume(contentType)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private suspend fun downloadSingleStream(
        downloadKey: String,
        url: String,
        target: File,
        requestHeaders: Map<String, String>,
        expectedContentLength: Long?,
        onConnectionStateChanged: (Boolean) -> Unit,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadTransfer {
        val expectedLength = expectedContentLength?.takeIf { it > 0L }
        return retryInterruptedTransfer(downloadKey, onConnectionStateChanged) {
            if (expectedLength != null && target.length() > expectedLength) truncateFile(target)
            if (expectedLength != null && target.length() == expectedLength && expectedLength >= MIN_VALID_AUDIO_BYTES) {
                onProgress(expectedLength, expectedLength)
                return@retryInterruptedTransfer DownloadTransfer(expectedLength, expectedLength, "")
            }
            downloadSingleStreamAttempt(
                url = url,
                target = target,
                requestHeaders = requestHeaders,
                expectedContentLength = expectedLength,
                onProgress = onProgress,
            )
        }
    }

    private suspend fun downloadSingleStreamAttempt(
        url: String,
        target: File,
        requestHeaders: Map<String, String>,
        expectedContentLength: Long?,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadTransfer = suspendCancellableCoroutine { continuation ->
        val resumeOffset = target.length().coerceAtLeast(0L)
        val call = downloadClient.newCall(
            buildDownloadRequest(url, requestHeaders, range = null, resumeOffset = resumeOffset),
        )
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val transfer = response.use { currentResponse ->
                        validateMediaResponse(currentResponse)
                        val body = currentResponse.body
                            ?: throw DownloadProtocolException("Empty response body")
                        val responseLength = body.contentLength().takeIf { it > 0L }
                        val partialRange = if (currentResponse.code == 206) {
                            parseContentRange(currentResponse.header("Content-Range"))
                                ?: throw DownloadProtocolException("Missing Content-Range for partial response")
                        } else {
                            null
                        }
                        if (partialRange != null && (partialRange.start != resumeOffset ||
                                partialRange.endInclusive + 1L != partialRange.total)
                        ) {
                            throw DownloadProtocolException("Server returned an unexpected audio range")
                        }
                        if (partialRange != null && expectedContentLength != null &&
                            partialRange.total != expectedContentLength
                        ) {
                            throw DownloadProtocolException(
                                "Download size changed: expected $expectedContentLength, received ${partialRange.total}",
                            )
                        }

                        val totalLength = partialRange?.total
                            ?: responseLength
                            ?: expectedContentLength?.takeIf { it > 0L }
                            ?: -1L
                        if (responseLength != null && expectedContentLength != null &&
                            currentResponse.code == 200 && responseLength != expectedContentLength
                        ) {
                            throw DownloadProtocolException(
                                "Download size changed: expected $expectedContentLength, received $responseLength",
                            )
                        }

                        val append = resumeOffset > 0L && partialRange != null
                        var downloadedBytes = if (append) resumeOffset else 0L
                        FileOutputStream(target, append).use { output ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                                while (true) {
                                    if (!continuation.isActive) {
                                        throw kotlinx.coroutines.CancellationException("Download cancelled")
                                    }
                                    val read = try {
                                        input.read(buffer)
                                    } catch (error: IOException) {
                                        throw DownloadInterruptedException(
                                            "Connection interrupted while reading audio",
                                            error,
                                        )
                                    }
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    downloadedBytes += read
                                    onProgress(downloadedBytes, totalLength)
                                }
                            }
                            output.flush()
                            output.fd.sync()
                        }
                        if (downloadedBytes < MIN_VALID_AUDIO_BYTES) {
                            throw DownloadProtocolException("Downloaded audio payload is too small")
                        }
                        if (totalLength > 0L && downloadedBytes != totalLength) {
                            if (downloadedBytes < totalLength) {
                                throw DownloadInterruptedException(
                                    "Download truncated: received $downloadedBytes of $totalLength bytes",
                                )
                            }
                            throw DownloadProtocolException(
                                "Download exceeded expected size: received $downloadedBytes of $totalLength bytes",
                            )
                        }

                        DownloadTransfer(
                            bytesDownloaded = downloadedBytes,
                            totalBytes = totalLength.takeIf { it > 0L } ?: downloadedBytes,
                            contentType = currentResponse.header("Content-Type").orEmpty(),
                        )
                    }
                    if (continuation.isActive) continuation.resume(transfer)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private suspend fun <T> retryInterruptedTransfer(
        downloadKey: String,
        onConnectionStateChanged: (Boolean) -> Unit,
        transfer: suspend () -> T,
    ): T {
        var failureCount = 0
        while (true) {
            try {
                return transfer()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                if (!error.isReconnectableTransferFailure()) throw error
                failureCount++
                // Bounded: an endlessly failing URL used to retry forever and
                // freeze the download (and its slot). Offline periods still
                // wait inside awaitRetryOpportunity, costing one attempt each.
                if (failureCount > MAX_TRANSFER_RETRIES) throw error
                onConnectionStateChanged(true)
                try {
                    awaitRetryOpportunity(downloadKey, failureCount)
                } finally {
                    onConnectionStateChanged(false)
                }
            }
        }
    }

    private suspend fun awaitRetryOpportunity(downloadKey: String, failureCount: Int) {
        val reconnectGeneration = reconnectGenerations[downloadKey]
            ?: throw kotlinx.coroutines.CancellationException("Download cancelled")
        val observedGeneration = reconnectGeneration.get()
        val retryDelayMs = (RECONNECT_RETRY_BASE_DELAY_MS *
            (1L shl (failureCount - 1).coerceIn(0, 5)))
            .coerceAtMost(RECONNECT_RETRY_MAX_DELAY_MS)
        var elapsedMs = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            if (reconnectGeneration.get() != observedGeneration) return
            if (elapsedMs >= retryDelayMs && hasUsableNetwork()) return
            delay(RECONNECT_POLL_INTERVAL_MS)
            elapsedMs += RECONNECT_POLL_INTERVAL_MS
        }
    }

    private fun hasUsableNetwork(): Boolean = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching true
        val network = manager.activeNetwork ?: return@runCatching false
        val capabilities = manager.getNetworkCapabilities(network) ?: return@runCatching false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(true)

    private fun IOException.isReconnectableTransferFailure(): Boolean {
        if (this is DownloadProtocolException) return false
        if (this is DownloadHttpException) {
            return statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode in 500..599
        }
        return generateSequence<Throwable>(this) { it.cause }
            .take(8)
            .any {
                it is DownloadInterruptedException ||
                    it is EOFException ||
                    it is ConnectException ||
                    it is NoRouteToHostException ||
                    it is SocketException ||
                    it is UnknownHostException ||
                    it is java.io.InterruptedIOException
            }
    }

    private fun buildDownloadRequest(
        url: String,
        requestHeaders: Map<String, String>,
        range: DownloadRange?,
        resumeOffset: Long = 0L,
    ): Request {
        val builder = Request.Builder().url(url)
        requestHeaders.forEach { (name, value) -> builder.header(name, value) }
        if (requestHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            builder.header("User-Agent", DOWNLOAD_USER_AGENT)
        }
        builder.header("Accept", "*/*")
        builder.header("Accept-Encoding", "identity")
        when {
            range != null -> builder.header("Range", "bytes=${range.start}-${range.endInclusive}")
            resumeOffset > 0L -> builder.header("Range", "bytes=$resumeOffset-")
        }
        return builder.build()
    }

    private fun validateMediaResponse(response: Response, expectedCode: Int? = null) {
        if (response.code != 200 && response.code != 206) {
            throw DownloadHttpException(response.code)
        }
        if (expectedCode != null && response.code != expectedCode) {
            throw DownloadProtocolException("Server does not support resumable byte ranges")
        }

        val contentType = response.header("Content-Type").orEmpty().lowercase()
        if (contentType.contains("text/html") ||
            contentType.contains("application/json") ||
            contentType.contains("text/plain")
        ) {
            throw DownloadProtocolException("Invalid download payload ($contentType)")
        }
    }

    private fun parseContentRange(header: String?): ParsedContentRange? {
        val match = header?.trim()?.let(CONTENT_RANGE_PATTERN::matchEntire) ?: return null
        return ParsedContentRange(
            start = match.groupValues[1].toLongOrNull() ?: return null,
            endInclusive = match.groupValues[2].toLongOrNull() ?: return null,
            total = match.groupValues[3].toLongOrNull() ?: return null,
        )
    }

    private fun truncateFile(file: File) {
        RandomAccessFile(file, "rw").use { it.setLength(0L) }
    }

    private fun isDashUrl(url: String, mime: String = ""): Boolean {
        val trimmed = url.trimStart()
        return trimmed.startsWith("data:application/dash+xml") ||
            trimmed.startsWith("<?xml") ||
            trimmed.startsWith("<MPD") ||
            url.contains(".mpd", ignoreCase = true) ||
            mime.contains("dash", ignoreCase = true)
    }

    private fun isHlsUrl(url: String, mime: String = ""): Boolean {
        val trimmed = url.trimStart().lowercase()
        return trimmed.startsWith("data:application/x-mpegurl") ||
            trimmed.startsWith("data:application/vnd.apple.mpegurl") ||
            url.contains(".m3u8", ignoreCase = true) ||
            mime.contains("mpegurl", ignoreCase = true)
    }

    private fun detectValidContainer(file: File): String? = runCatching {
        if (file.length() < 12L) return@runCatching null
        val header = ByteArray(12)
        RandomAccessFile(file, "r").use { input ->
            if (input.read(header) != header.size) return@runCatching null
        }
        when {
            header.copyOfRange(0, 4).contentEquals(
                byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte()),
            ) -> "flac"
            header.copyOfRange(4, 8).contentEquals(
                byteArrayOf('f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()),
            ) -> "m4a"
            header.copyOfRange(0, 4).contentEquals(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())) -> "webm"
            header.copyOfRange(0, 4).contentEquals(
                byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte()),
            ) -> "opus"
            header.copyOfRange(0, 3).contentEquals(
                byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte()),
            ) ||
                ((header[0].toInt() and 0xFF) == 0xFF && (header[1].toInt() and 0xE0) == 0xE0) -> "mp3"
            else -> null
        }
    }.getOrNull()

    private fun hasExpectedContainer(file: File, extension: String): Boolean = runCatching {
        if (file.length() < 12L) return@runCatching false
        val detected = detectValidContainer(file) ?: return@runCatching false
        val ext = extension.lowercase()
        when (ext) {
            "flac" -> detected == "flac"
            "m4a" -> detected == "m4a"
            "webm" -> detected == "webm"
            "opus" -> detected == "opus" || detected == "webm"
            "ogg" -> detected == "opus"
            "mp3" -> detected == "mp3"
            else -> false
        }
    }.getOrDefault(false)

    private fun updateProgress(progress: DownloadProgress) {
        _downloads.update { it + (progress.key to progress) }
    }

    /** Same buffered copy semantics as LastWave's ranged reads. */
    private fun copyStream(input: java.io.InputStream, output: OutputStream): Long {
        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = try {
                input.read(buffer)
            } catch (error: IOException) {
                throw DownloadInterruptedException("Connection interrupted while reading audio", error)
            }
            if (read < 0) break
            output.write(buffer, 0, read)
            total += read
        }
        return total
    }

    private data class ParsedDashManifest(
        val initUrl: String,
        val mediaTemplate: String,
        val segmentCount: Int,
        val startNumber: Int = 1,
        /**
         * The Representation's declared codec, e.g. `flac`, `mp4a.40.2`, `ec-3`.
         * Tidal serves AAC for the LOW/HIGH tiers and FLAC-in-MP4 for lossless,
         * so the manifest — not the resolver's assumption — decides whether a
         * download really is lossless.
         */
        val codec: String = "",
    )

    private fun parseDashManifest(baseUrl: String): ParsedDashManifest? = runCatching {
        val trimmed = baseUrl.trim()
        val xmlStr = when {
            trimmed.startsWith("data:application/dash+xml") -> {
                if (trimmed.contains("base64,")) {
                    val b64 = trimmed.substringAfter("base64,")
                    String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
                } else {
                    java.net.URLDecoder.decode(trimmed.substringAfter(","), "UTF-8")
                }
            }
            trimmed.startsWith("<?xml") || trimmed.startsWith("<MPD") -> trimmed
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> {
                if (trimmed.contains(".mpd", ignoreCase = true) || trimmed.contains("dash", ignoreCase = true)) {
                    val req = Request.Builder().url(trimmed).get().build()
                    downloadClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
                    }
                } else ""
            }
            else -> return@runCatching null
        }
        if (xmlStr.isBlank()) return@runCatching null

        val initMatch = Regex("""initialization=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(xmlStr)
            ?: Regex("""<Initialization\s+[^>]*sourceURL=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(xmlStr)
            ?: Regex("""sourceURL=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(xmlStr)
            ?: return@runCatching null
        var initUrl = initMatch.groupValues[1].replace("&amp;", "&")

        val mediaMatch = Regex("""media=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(xmlStr)
            ?: Regex("""<SegmentTemplate\s+[^>]*media=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(xmlStr)
            ?: Regex("""<SegmentURL\s+[^>]*media=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(xmlStr)
            ?: return@runCatching null
        var mediaTemplate = mediaMatch.groupValues[1].replace("&amp;", "&")

        val baseMatch = Regex("""<BaseURL[^>]*>([^<]+)</BaseURL>""", RegexOption.IGNORE_CASE).find(xmlStr)
        val baseUrlPrefix = baseMatch?.groupValues?.get(1)?.trim()?.replace("&amp;", "&")
            ?: if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed.substringBeforeLast('/') + "/"
            } else null
        if (!baseUrlPrefix.isNullOrBlank()) {
            if (!initUrl.startsWith("http://", ignoreCase = true) && !initUrl.startsWith("https://", ignoreCase = true)) {
                initUrl = if (baseUrlPrefix.endsWith("/") || initUrl.startsWith("/")) {
                    "${baseUrlPrefix.trimEnd('/')}/${initUrl.trimStart('/')}"
                } else {
                    "$baseUrlPrefix$initUrl"
                }
            }
            if (!mediaTemplate.startsWith("http://", ignoreCase = true) && !mediaTemplate.startsWith("https://", ignoreCase = true)) {
                mediaTemplate = if (baseUrlPrefix.endsWith("/") || mediaTemplate.startsWith("/")) {
                    "${baseUrlPrefix.trimEnd('/')}/${mediaTemplate.trimStart('/')}"
                } else {
                    "$baseUrlPrefix$mediaTemplate"
                }
            }
        }
        if (!initUrl.startsWith("http://", ignoreCase = true) && !initUrl.startsWith("https://", ignoreCase = true)) {
            return@runCatching null
        }
        if (!mediaTemplate.startsWith("http://", ignoreCase = true) && !mediaTemplate.startsWith("https://", ignoreCase = true)) {
            return@runCatching null
        }

        val codec = Regex("""codecs=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(xmlStr)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.lowercase()
            .orEmpty()

        var count = 0
        val sRegex = Regex("""<S\s+[^>]*>""", RegexOption.IGNORE_CASE)
        for (match in sRegex.findAll(xmlStr)) {
            val sTag = match.value
            val rMatch = Regex("""r=["']?(\d+)["']?""", RegexOption.IGNORE_CASE).find(sTag)
            val r = rMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            count += 1 + r
        }
        if (count <= 0) {
            val durationMatch = Regex(
                """mediaPresentationDuration=["']PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?["']""",
                RegexOption.IGNORE_CASE,
            ).find(xmlStr)
            val segTemplateMatch = Regex("""<(?:SegmentTemplate|SegmentList)\b[^>]*>""", RegexOption.IGNORE_CASE)
                .find(xmlStr)?.value
            val segDuration = segTemplateMatch?.let {
                Regex("""\bduration=["']([0-9.]+)["']""", RegexOption.IGNORE_CASE)
                    .find(it)?.groupValues?.get(1)?.toDoubleOrNull()
            } ?: 1.0
            val timescale = segTemplateMatch?.let {
                Regex("""\btimescale=["']([0-9.]+)["']""", RegexOption.IGNORE_CASE)
                    .find(it)?.groupValues?.get(1)?.toDoubleOrNull()
            } ?: 1.0
            if (durationMatch != null) {
                val hours = durationMatch.groupValues.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                val min = durationMatch.groupValues.getOrNull(2)?.toDoubleOrNull() ?: 0.0
                val sec = durationMatch.groupValues.getOrNull(3)?.toDoubleOrNull() ?: 0.0
                val totalSec = hours * 3600.0 + min * 60.0 + sec
                val segSec = segDuration / timescale
                if (segSec > 0) {
                    count = Math.ceil(totalSec / segSec).toInt()
                }
            }
        }
        if (count <= 0) count = 50
        val startNumberMatch = Regex("""startNumber=["'](\d+)["']""", RegexOption.IGNORE_CASE).find(xmlStr)
        val startNumber = startNumberMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

        ParsedDashManifest(
            initUrl = initUrl,
            mediaTemplate = mediaTemplate,
            segmentCount = count,
            startNumber = startNumber,
            codec = codec,
        )
    }.getOrNull()

    private data class DashFetchResult(
        val bytesWritten: Long,
        val stoppedEarly: Boolean,
        val completedSegments: Int,
        val expectedSegments: Int,
    )

    private suspend fun downloadDashSegmentsToTempFile(
        downloadKey: String,
        notificationId: Int,
        title: String,
        artist: String,
        formatBadge: String,
        initUrl: String,
        mediaTemplate: String,
        segmentCount: Int,
        startNumber: Int = 1,
        headers: Map<String, String>,
        target: File,
        updateStage: (String) -> Unit,
    ): DashFetchResult = withContext(Dispatchers.IO) {
        val totalParts = segmentCount + 1
        var completedParts = 0
        var totalBytesWritten = 0L
        var stoppedEarly = false
        val targetStream = FileOutputStream(target, false)

        try {
            // 1. Download initialization segment (0.mp4) containing ftyp and moov boxes
            val initReq = Request.Builder()
                .url(initUrl)
                .apply {
                    headers.forEach { (k, v) -> addHeader(k, v) }
                    if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                        addHeader("User-Agent", DOWNLOAD_USER_AGENT)
                    }
                }
                .build()

            downloadClient.newCall(initReq).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Failed to download DASH init chunk: HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("Empty DASH init body")
                val copied = copyStream(body.byteStream(), targetStream)
                totalBytesWritten += copied
            }
            completedParts++
            val initPercent = ((completedParts * 100) / totalParts).coerceIn(0, 100)
            updateProgress(
                DownloadProgress(
                    key = downloadKey, title = title, artist = artist,
                    progressPercent = initPercent,
                    bytesDownloaded = totalBytesWritten,
                    formatBadge = formatBadge,
                ),
            )

            // 2. Download media segments in order and append directly to the file
            val endNumber = startNumber + segmentCount - 1
            for (segIndex in startNumber..endNumber) {
                currentCoroutineContext().ensureActive()
                val segUrl = mediaTemplate.replace(
                    // regex `\$Number(?:%0(\d+)d)?\$` — Kotlin needs `\\\$`
                    Regex("\\\$Number(?:%0(\\d+)d)?\\\$"),
                ) { m ->
                    val pad = m.groupValues.getOrNull(1)?.toIntOrNull()
                    if (pad != null) segIndex.toString().padStart(pad, '0') else segIndex.toString()
                }
                val segReq = Request.Builder()
                    .url(segUrl)
                    .apply {
                        headers.forEach { (k, v) -> addHeader(k, v) }
                        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                            addHeader("User-Agent", DOWNLOAD_USER_AGENT)
                        }
                    }
                    .build()

                var stopThis = false
                downloadClient.newCall(segReq).execute().use { resp ->
                    if (resp.code == 404 && segIndex > startNumber) {
                        stopThis = true
                        stoppedEarly = true
                        return@use
                    }
                    if (!resp.isSuccessful) {
                        throw IOException("Failed to download DASH segment $segIndex: HTTP ${resp.code}")
                    }
                    val body = resp.body ?: throw IOException("Empty body for DASH segment $segIndex")
                    val copied = copyStream(body.byteStream(), targetStream)
                    totalBytesWritten += copied
                }
                if (stopThis) break

                completedParts++
                val percent = ((completedParts * 100) / totalParts).coerceIn(0, 100)
                updateProgress(
                    DownloadProgress(
                        key = downloadKey, title = title, artist = artist,
                        progressPercent = percent,
                        bytesDownloaded = totalBytesWritten,
                        totalBytes = -1L,
                        formatBadge = formatBadge,
                    ),
                )
                updateStage("%s • %d%%".format(java.util.Locale.ROOT, formatBadge, percent))
                if (percent % 10 == 0 || segIndex == segmentCount) {
                    runCatching {
                        showDownloadNotification(notificationId, downloadKey, title, artist, percent, false, formatBadge)
                    }
                }
            }
            updateStage(formatBadge)
            targetStream.flush()
            targetStream.fd.sync()
            DashFetchResult(
                bytesWritten = totalBytesWritten,
                stoppedEarly = stoppedEarly,
                completedSegments = completedParts - 1,
                expectedSegments = segmentCount,
            )
        } finally {
            runCatching { targetStream.close() }
        }
    }

    private fun openPublicOutputStream(
        filename: String,
        mimeType: String,
        title: String,
        artist: String,
        album: String?,
        year: String? = null,
        durationMs: Long = 0L,
    ): Triple<OutputStream, Uri?, File?> {
        val resolver = context.contentResolver
        val safeDir = PUBLIC_DIR_NAME
        val audioRelativePath = "${Environment.DIRECTORY_MUSIC}/$safeDir"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android MediaStore Audio only accepts standard audio MIME types
            val audioMime = when {
                mimeType.contains("mp4") || mimeType.contains("m4a") || mimeType.contains("aac") -> "audio/mp4"
                mimeType.contains("flac") -> "audio/flac"
                mimeType.contains("mp3") || mimeType.contains("mpeg") -> "audio/mpeg"
                mimeType.contains("webm") -> "audio/webm"
                mimeType.contains("ogg") || mimeType.contains("opus") -> "audio/ogg"
                mimeType.contains("wav") -> "audio/x-wav"
                else -> "audio/mp4"
            }

            // Remove any pre-existing entry with the same filename IN THE SAME
            // FOLDER to avoid Android appending (1), (2), etc.
            runCatching {
                resolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID),
                    "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.RELATIVE_PATH} = ?",
                    arrayOf(filename, "$audioRelativePath/"),
                    null,
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val oldUri = Uri.withAppendedPath(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            id.toString(),
                        )
                        runCatching { resolver.delete(oldUri, null, null) }
                    }
                }
            }

            val audioContentValues = android.content.ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, filename)
                put(MediaStore.Audio.Media.MIME_TYPE, audioMime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, audioRelativePath)
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, artist)
                put(MediaStore.Audio.Media.ALBUM_ARTIST, artist)
                if (!album.isNullOrBlank()) put(MediaStore.Audio.Media.ALBUM, album)
                val yearInt = year?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
                if (yearInt != null && yearInt > 0) {
                    put(MediaStore.Audio.Media.YEAR, yearInt)
                }
                if (durationMs > 0) put(MediaStore.Audio.Media.DURATION, durationMs)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }

            val audioUri = runCatching {
                resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, audioContentValues)
            }.getOrNull()
            if (audioUri != null) {
                val stream = resolver.openOutputStream(audioUri, "wt")
                    ?: resolver.openOutputStream(audioUri)
                if (stream != null) return Triple(stream, audioUri, null)
            }

            // Fallback 1: MediaStore.Downloads (pure download columns only)
            val downloadsRelativePath = "${Environment.DIRECTORY_DOWNLOADS}/$safeDir/Music"
            val downloadContentValues = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(
                    MediaStore.Downloads.MIME_TYPE,
                    if (mimeType.isNotBlank()) mimeType else "application/octet-stream",
                )
                put(MediaStore.Downloads.RELATIVE_PATH, downloadsRelativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val downloadUri = runCatching {
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, downloadContentValues)
            }.getOrNull()
            if (downloadUri != null) {
                val stream = resolver.openOutputStream(downloadUri, "wt")
                    ?: resolver.openOutputStream(downloadUri)
                if (stream != null) return Triple(stream, downloadUri, null)
            }

            // Fallback 2: Direct public / external app music directory
            val fallbackBase = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), safeDir)
                    .apply { if (!exists()) mkdirs() }
            if (!fallbackBase.exists()) fallbackBase.mkdirs()
            val fallbackFile = File(fallbackBase, filename)
            val stream = FileOutputStream(fallbackFile)
            return Triple(stream, null, fallbackFile)
        } else {
            // Android 9 and below
            val musicBase = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), safeDir)
            if (!musicBase.exists()) musicBase.mkdirs()
            val file = File(musicBase, filename)
            val stream = FileOutputStream(file)
            return Triple(stream, null, file)
        }
    }

    private fun finalizePublicFile(uri: Uri?) {
        // IS_PENDING only exists on MediaStore Uris.
        if (uri != null && uri.authority == MediaStore.AUTHORITY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            runCatching { context.contentResolver.update(uri, contentValues, null, null) }
        }
    }

    // ---------------------------------------------------------------------------
    // Notifications
    // ---------------------------------------------------------------------------

    private fun showDownloadNotification(
        notificationId: Int,
        downloadKey: String,
        title: String,
        artist: String,
        progress: Int,
        isIndeterminate: Boolean,
        badgeText: String,
        isWaitingForConnection: Boolean = false,
    ) {
        val cancelIntent = Intent(context, DownloadCancelReceiver::class.java).apply {
            action = ACTION_CANCEL_DOWNLOAD
            putExtra(EXTRA_DOWNLOAD_KEY, downloadKey)
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(
                if (isWaitingForConnection) {
                    context.getString(R.string.download_notification_waiting)
                } else {
                    context.getString(R.string.download_notification_downloading, title)
                },
            )
            .setContentText(
                if (isWaitingForConnection) {
                    context.getString(R.string.download_notification_waiting_detail, title, artist, progress)
                } else {
                    context.getString(R.string.download_notification_text, artist, badgeText, progress)
                },
            )
            .setProgress(100, progress, isIndeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(viewMainPendingIntent(notificationId))

        if (isWaitingForConnection) {
            builder.addAction(
                android.R.drawable.ic_popup_sync,
                context.getString(R.string.download_action_reconnect),
                reconnectPendingIntent(notificationId, downloadKey, title, artist),
            )
        }
        val notification: Notification = builder
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.download_action_cancel),
                cancelPendingIntent,
            )
            .build()

        runCatching { notificationManager?.notify(notificationId, notification) }
    }

    private fun showCompletedNotification(
        notificationId: Int,
        title: String,
        artist: String,
        badgeText: String,
    ) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.download_notification_complete))
            .setContentText(
                context.getString(R.string.download_notification_complete_detail, title, artist, badgeText),
            )
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(viewMainPendingIntent(notificationId))
            .build()

        runCatching { notificationManager?.notify(notificationId, notification) }
    }

    private fun showErrorNotification(
        notificationId: Int,
        downloadKey: String,
        title: String,
        artist: String,
        error: String,
    ) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.download_notification_failed))
            .setContentText(
                context.getString(R.string.download_notification_failed_detail, title, artist, error),
            )
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(viewMainPendingIntent(notificationId))
            .addAction(
                android.R.drawable.ic_popup_sync,
                context.getString(R.string.download_action_reconnect),
                reconnectPendingIntent(notificationId, downloadKey, title, artist),
            )
            .build()

        runCatching { notificationManager?.notify(notificationId, notification) }
    }

    private fun viewMainPendingIntent(requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun reconnectPendingIntent(
        requestCode: Int,
        downloadKey: String,
        title: String,
        artist: String,
    ): PendingIntent {
        val intent = Intent(context, DownloadCancelReceiver::class.java).apply {
            action = ACTION_RECONNECT_DOWNLOAD
            putExtra(EXTRA_DOWNLOAD_KEY, downloadKey)
            putExtra(EXTRA_DOWNLOAD_TITLE, title)
            putExtra(EXTRA_DOWNLOAD_ARTIST, artist)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // ---------------------------------------------------------------------------
    // Metadata (TagLib)
    // ---------------------------------------------------------------------------

    /**
     * Embeds title/artist/album/year plus front-cover artwork into the
     * downloaded file. Container-aware via TagLib (Vorbis comments + PICTURE
     * for FLAC/Opus, iTunes atoms for M4A, ID3v2.3 otherwise).
     */
    private fun embedMetadata(
        audioFile: File,
        title: String,
        artist: String,
        album: String?,
        artworkUrl: String?,
        year: String?,
    ): Boolean = runCatching {
        if (!audioFile.exists()) return false
        val coverBytes = artworkUrl?.let { runCatching { loadCoverBytes(it) }.getOrNull() }

        ParcelFileDescriptor.open(audioFile, ParcelFileDescriptor.MODE_READ_WRITE).use { fd ->
            val metadataFd = fd.dup()
            val existing = TagLib.getMetadata(metadataFd.detachFd())
            val propertyMap = HashMap(existing?.propertyMap ?: emptyMap())
            propertyMap["TITLE"] = arrayOf(title)
            propertyMap["ARTIST"] = arrayOf(artist)
            if (!album.isNullOrBlank()) {
                propertyMap["ALBUM"] = arrayOf(album)
                propertyMap["ALBUMARTIST"] = arrayOf(artist)
            }
            val yearInt = year?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
            if (yearInt != null && yearInt > 0) {
                propertyMap["DATE"] = arrayOf(yearInt.toString())
            }

            val saveFd = fd.dup()
            val saved = TagLib.savePropertyMap(saveFd.detachFd(), propertyMap)
            if (!saved) {
                Timber.tag("TrackDownloadManager").w("savePropertyMap failed for %s", audioFile.name)
                return false
            }

            if (coverBytes != null) {
                val pictureFd = fd.dup()
                TagLib.savePictures(
                    pictureFd.detachFd(),
                    arrayOf(
                        Picture(
                            data = coverBytes,
                            description = "Front Cover",
                            pictureType = "Front Cover",
                            mimeType = sniffImageMime(coverBytes),
                        ),
                    ),
                )
            }
        }
        true
    }.getOrDefault(false)

    private fun sniffImageMime(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
        else -> "image/jpeg"
    }

    /** Small, non-resumable fetch for cover art (a few hundred KB at most). */
    private fun loadCoverBytes(url: String): ByteArray? {
        if (url.startsWith("content://")) {
            return runCatching {
                context.contentResolver.openInputStream(Uri.parse(url))?.use { it.readBytes() }
            }.getOrNull()
        }
        if (url.startsWith("file://")) {
            return runCatching {
                File(Uri.parse(url).path!!).takeIf { it.exists() }?.readBytes()
            }.getOrNull()
        }
        return downloadClient.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", DOWNLOAD_USER_AGENT)
                .build(),
        ).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.bytes()
        }
    }
}
