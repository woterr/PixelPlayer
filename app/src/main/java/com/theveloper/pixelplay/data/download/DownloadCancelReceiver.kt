package com.theveloper.pixelplay.data.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Handles cancel / reconnect actions from download notifications.
 *
 * Registered non-exported in the manifest; only the app and its
 * [TrackDownloadManager].PendingIntents can deliver to it.
 */
@AndroidEntryPoint
class DownloadCancelReceiver : BroadcastReceiver() {

    @Inject
    lateinit var trackDownloadManager: dagger.Lazy<TrackDownloadManager>

    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(TrackDownloadManager.EXTRA_DOWNLOAD_KEY) ?: return
        when (intent.action) {
            TrackDownloadManager.ACTION_CANCEL_DOWNLOAD -> {
                trackDownloadManager.get().cancelDownload(key)
            }
            TrackDownloadManager.ACTION_RECONNECT_DOWNLOAD -> {
                trackDownloadManager.get().reconnectDownload(key)
            }
        }
    }
}
