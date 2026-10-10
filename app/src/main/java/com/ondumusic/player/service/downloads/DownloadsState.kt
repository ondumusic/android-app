package com.ondumusic.player.service.downloads

import com.ondumusic.player.util.UiText
import java.util.UUID

/** A download started and not yet finished: transferring, or paused part-way. */
data class ActiveDownload(
    val trackId: UUID,
    val trackName: String,
    val artistName: String,
    val albumId: UUID,
    val isPaused: Boolean = false,
)

data class QueuedDownload(
    val trackId: UUID,
    val trackName: String,
    val artistName: String,
    val albumId: UUID,
)

data class FailedDownload(
    val trackId: UUID,
    val trackName: String,
    val artistName: String,
    val reason: UiText,
)

/** Why the queue isn't starting downloads although it has some. */
enum class DownloadPausedReason {
    /** Can be overridden for the session with [DownloadService.ignoreBatterySaver]. */
    BatterySaver,
    /** Under 100 MB free. Not overridable: filling the volume is worse than a stalled queue. */
    LowStorage,
}

/** Everything about the queue except progress, which changes far more often and lives in [DownloadService.progress]. */
data class DownloadsState(
    /** In the order they started. */
    val active: List<ActiveDownload> = emptyList(),
    /** In the order they will start. */
    val queued: List<QueuedDownload> = emptyList(),
    val failed: List<FailedDownload> = emptyList(),
    /** Paused as a whole, by the user or after the session expired. */
    val isPaused: Boolean = false,
    val pausedReason: DownloadPausedReason? = null,
    val ignoresBatterySaver: Boolean = false,
) {
    // Per-row lookups at library scale; built once per state, on first use.
    private val activeIds by lazy { active.mapTo(HashSet()) { it.trackId } }
    private val queuedIds by lazy { queued.mapTo(HashSet()) { it.trackId } }
    private val albumIds by lazy { (active.map { it.albumId } + queued.map { it.albumId }).toHashSet() }

    /** Started and not finished, paused included. */
    fun isDownloading(trackId: UUID): Boolean = trackId in activeIds

    fun isQueued(trackId: UUID): Boolean = trackId in queuedIds

    fun isAlbumDownloading(albumId: UUID): Boolean = albumId in albumIds

    val hasActivity: Boolean get() = active.isNotEmpty() || queued.isNotEmpty() || failed.isNotEmpty()

    /** Downloads actually transferring. */
    val transferringCount: Int get() = active.count { !it.isPaused }
}
