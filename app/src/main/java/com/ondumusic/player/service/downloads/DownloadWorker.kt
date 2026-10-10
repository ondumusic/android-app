package com.ondumusic.player.service.downloads

import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ondumusic.player.BuildConfig
import com.ondumusic.player.OnduApplication
import com.ondumusic.player.R
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Keeps the process alive while [DownloadService] works, as a foreground worker with a
 * progress notification. The downloads themselves run in the service whether or not this
 * is running; it only waits for the queue to go idle.
 *
 * Without the notification permission (Android 13+) the worker still runs; the
 * notification just isn't shown in the shade.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @OptIn(FlowPreview::class)
    override suspend fun doWork(): Result {
        val downloads = (applicationContext as OnduApplication).container.downloads
        downloads.start()
        if (downloads.state.value.transferringCount == 0) return Result.success()

        createChannel()
        coroutineScope {
            val notifications = launch {
                combine(downloads.state, downloads.progress, ::Pair)
                    .sample(NOTIFICATION_INTERVAL_MILLIS)
                    .collect { (state, progress) -> if (state.transferringCount > 0) showProgress(state, progress) }
            }
            showProgress(downloads.state.value, downloads.progress.value)
            downloads.awaitIdle()
            notifications.cancel()
        }
        return Result.success()
    }

    private suspend fun showProgress(state: DownloadsState, progress: Map<UUID, Float>) {
        try {
            setForeground(foregroundInfo(state, progress))
        } catch (e: IllegalStateException) {
            // Android 12+ refuses a foreground service started from the background. The
            // work still runs, within the time the system gives a background worker.
            if (BuildConfig.DEBUG) Log.d(TAG, "couldn't run in the foreground", e)
        }
    }

    private fun foregroundInfo(state: DownloadsState, progress: Map<UUID, Float>): ForegroundInfo {
        val remaining = state.transferringCount + state.queued.size
        val current = state.active.firstOrNull { !it.isPaused }
        val fraction = current?.let { progress[it.trackId] }
        val openApp = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
            ?.let { PendingIntent.getActivity(applicationContext, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.resources.getQuantityString(R.plurals.downloads_notification_title, remaining, remaining))
            .setContentText(current?.let { "${it.trackName} — ${it.artistName}" })
            .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun createChannel() {
        NotificationManagerCompat.from(applicationContext).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(applicationContext.getString(R.string.downloads_channel_name))
                .setDescription(applicationContext.getString(R.string.downloads_channel_description))
                .setShowBadge(false)
                .build()
        )
    }

    companion object {
        private const val TAG = "DownloadWorker"
        private const val WORK_NAME = "downloads"
        private const val CHANNEL_ID = "downloads"
        /** Clear of Media3's playback notification. */
        private const val NOTIFICATION_ID = 2001
        private const val NOTIFICATION_INTERVAL_MILLIS = 1_000L

        /** Idempotent: while a worker is running, this keeps it rather than queueing another. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
