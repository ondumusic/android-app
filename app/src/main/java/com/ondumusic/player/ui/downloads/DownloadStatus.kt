package com.ondumusic.player.ui.downloads

import com.ondumusic.player.data.DownloadCount
import com.ondumusic.player.service.downloads.DownloadPausedReason
import com.ondumusic.player.service.downloads.DownloadsState
import java.util.UUID

/** Where one track stands, which decides the download actions its menu offers. */
enum class TrackDownloadStatus { NotDownloaded, Queued, Downloading, Paused, Downloaded }

fun DownloadsState.statusOf(trackId: UUID, isDownloaded: Boolean): TrackDownloadStatus = when {
    isDownloaded -> TrackDownloadStatus.Downloaded
    isDownloading(trackId) ->
        if (active.first { it.trackId == trackId }.isPaused) TrackDownloadStatus.Paused else TrackDownloadStatus.Downloading
    isQueued(trackId) -> TrackDownloadStatus.Queued
    else -> TrackDownloadStatus.NotDownloaded
}

/** Started or waiting to start, paused ones included: what the account menu counts. */
val DownloadsState.inProgressCount: Int get() = active.size + queued.size

/**
 * Why the queue is held up, while that's worth saying: only with songs waiting to start,
 * and not when the user paused everything themselves — the reason would then read as the cause.
 */
val DownloadsState.noticeReason: DownloadPausedReason?
    get() = pausedReason?.takeIf { queued.isNotEmpty() && !isPaused }

/** Which download actions an album or artist offers. A partial download can be both completed and removed. */
data class CollectionDownloadActions(val download: Boolean, val cancel: Boolean, val remove: Boolean)

fun collectionDownloadActions(count: DownloadCount, isDownloading: Boolean) = CollectionDownloadActions(
    download = !isDownloading && count.total > 0 && !count.isFull,
    cancel = isDownloading,
    remove = count.isAny,
)

/** How far an album's download has got, counting part-done songs too, so the ring moves between songs. */
fun albumDownloadFraction(count: DownloadCount, state: DownloadsState, progress: Map<UUID, Float>, albumId: UUID): Float {
    if (count.total == 0) return 0f
    val partial = state.active.filter { it.albumId == albumId }.sumOf { (progress[it.trackId] ?: 0f).toDouble() }
    return ((count.downloaded + partial) / count.total).toFloat().coerceIn(0f, 1f)
}
