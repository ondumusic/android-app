package com.ondumusic.player.ui.downloads

import com.ondumusic.player.data.DownloadCount
import com.ondumusic.player.service.downloads.ActiveDownload
import com.ondumusic.player.service.downloads.DownloadPausedReason
import com.ondumusic.player.service.downloads.DownloadsState
import com.ondumusic.player.service.downloads.QueuedDownload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class DownloadStatusTest {
    private val album = UUID.randomUUID()
    private fun active(paused: Boolean = false, albumId: UUID = album) = ActiveDownload(UUID.randomUUID(), "Song", "Artist", albumId, paused)
    private fun queued() = QueuedDownload(UUID.randomUUID(), "Song", "Artist", album)

    @Test
    fun trackStatusFollowsTheQueue() {
        val running = active()
        val paused = active(paused = true)
        val waiting = queued()
        val state = DownloadsState(active = listOf(running, paused), queued = listOf(waiting))

        assertEquals(TrackDownloadStatus.Downloading, state.statusOf(running.trackId, isDownloaded = false))
        assertEquals(TrackDownloadStatus.Paused, state.statusOf(paused.trackId, isDownloaded = false))
        assertEquals(TrackDownloadStatus.Queued, state.statusOf(waiting.trackId, isDownloaded = false))
        assertEquals(TrackDownloadStatus.NotDownloaded, state.statusOf(UUID.randomUUID(), isDownloaded = false))
    }

    @Test
    fun downloadedWinsOverAStaleQueueEntry() {
        val running = active()
        val state = DownloadsState(active = listOf(running))
        assertEquals(TrackDownloadStatus.Downloaded, state.statusOf(running.trackId, isDownloaded = true))
    }

    @Test
    fun inProgressCountsPausedAndWaiting() {
        val state = DownloadsState(active = listOf(active(), active(paused = true)), queued = listOf(queued()))
        assertEquals(3, state.inProgressCount)
    }

    @Test
    fun noticeOnlyWhileSongsAreWaiting() {
        val reason = DownloadPausedReason.BatterySaver
        assertEquals(reason, DownloadsState(queued = listOf(queued()), pausedReason = reason).noticeReason)
        assertNull(DownloadsState(active = listOf(active()), pausedReason = reason).noticeReason)
        assertNull(DownloadsState(queued = listOf(queued())).noticeReason)
    }

    @Test
    fun noticeStepsAsideWhenTheUserPausedEverything() {
        val state = DownloadsState(queued = listOf(queued()), isPaused = true, pausedReason = DownloadPausedReason.LowStorage)
        assertNull(state.noticeReason)
    }

    @Test
    fun collectionActionsByDownloadState() {
        assertEquals(CollectionDownloadActions(download = true, cancel = false, remove = false),
            collectionDownloadActions(DownloadCount(0, 10), isDownloading = false))
        // Partial: finish it, or take back what's there.
        assertEquals(CollectionDownloadActions(download = true, cancel = false, remove = true),
            collectionDownloadActions(DownloadCount(4, 10), isDownloading = false))
        assertEquals(CollectionDownloadActions(download = false, cancel = false, remove = true),
            collectionDownloadActions(DownloadCount(10, 10), isDownloading = false))
        assertEquals(CollectionDownloadActions(download = false, cancel = true, remove = true),
            collectionDownloadActions(DownloadCount(4, 10), isDownloading = true))
    }

    @Test
    fun emptyCollectionOffersNothing() {
        assertEquals(CollectionDownloadActions(download = false, cancel = false, remove = false),
            collectionDownloadActions(DownloadCount(0, 0), isDownloading = false))
    }

    @Test
    fun albumFractionCountsPartialSongsOfThatAlbumOnly() {
        val mine = active()
        val other = active(albumId = UUID.randomUUID())
        val state = DownloadsState(active = listOf(mine, other))
        val progress = mapOf(mine.trackId to 0.5f, other.trackId to 0.9f)
        assertEquals(0.25f, albumDownloadFraction(DownloadCount(2, 10), state, progress, album), 0.0001f)
        assertEquals(0f, albumDownloadFraction(DownloadCount(0, 0), state, progress, album), 0f)
    }
}
