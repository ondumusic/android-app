package com.ondumusic.player.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ondumusic.player.api.SyncAlbum
import com.ondumusic.player.api.SyncArtist
import com.ondumusic.player.api.SyncTrack
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class MusicLibraryDownloadsTest {
    private lateinit var db: LibraryDatabase
    private lateinit var library: MusicLibrary
    private lateinit var filesDir: File

    private val now = Instant.parse("2026-10-01T12:00:00Z")
    private val artistA = UUID.randomUUID()
    private val artistB = UUID.randomUUID()
    private val albumA1 = UUID.randomUUID()
    private val albumA2 = UUID.randomUUID()
    private val albumB = UUID.randomUUID()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        filesDir = File(context.cacheDir, "dl-${UUID.randomUUID()}").apply { mkdirs() }
        library = MusicLibrary(db, filesDir)
    }

    @After
    fun tearDown() {
        db.close()
        filesDir.deleteRecursively()
    }

    private fun track(albumId: UUID, artistId: UUID, name: String = "Song") = SyncTrack(
        UUID.randomUUID(), artistId, artistId, albumId, name, 1, 1, 200.0, "audio/flac", "/s", "/d", now,
    )

    private suspend fun seed(tracks: List<SyncTrack>) {
        library.upsertArtists(listOf(
            SyncArtist(artistA, "Beta", null, "beta", albumCount = 2, insertedAt = now),
            SyncArtist(artistB, "Alpha", null, "alpha", albumCount = 1, insertedAt = now),
        ))
        library.upsertAlbums(listOf(
            SyncAlbum(albumA1, artistA, "Old", null, "old", 1999, null, emptyList(), false, 2, now),
            SyncAlbum(albumA2, artistA, "New", null, "new", 2020, null, emptyList(), false, 1, now),
            SyncAlbum(albumB, artistB, "Only", null, "only", 2001, null, emptyList(), false, 1, now),
        ))
        library.upsertTracks(tracks)
    }

    /** Writes a file of [size] bytes where a finished download of [track] would be, and marks it. */
    private suspend fun download(track: SyncTrack, size: Int = 2000, mark: Boolean = true): File {
        val path = downloadRelativePath(track.albumArtistId, track.albumId, track.id, "flac")
        val file = File(filesDir, path).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(size)) }
        if (mark) assertTrue(library.markDownloaded(track.id, path, size.toLong()))
        return file
    }

    @Test
    fun summariesCountOnlyWhatIsDownloaded() = runTest {
        val a1 = track(albumA1, artistA)
        val a1b = track(albumA1, artistA)
        val a2 = track(albumA2, artistA)
        val b = track(albumB, artistB)
        seed(listOf(a1, a1b, a2, b))
        download(a1, 100)
        download(a2, 200)
        download(b, 400)

        val summary = library.observeDownloadSummary().first()
        assertEquals(700L, summary.totalSize)
        assertEquals(listOf("Alpha", "Beta"), summary.artists.map { it.artist.name })
        assertEquals(listOf(1, 2), summary.artists.map { it.albumCount })
        assertEquals(listOf(400L, 300L), summary.artists.map { it.size })

        assertEquals(listOf("Only", "New", "Old"), library.observeDownloadedAlbums().first().map { it.album.name })
        assertEquals(listOf("New", "Old"), library.observeDownloadedAlbums(artistA).first().map { it.album.name })
        assertEquals(1, library.observeDownloadedAlbums(artistA).first().last().trackCount)

        assertEquals(setOf(a1.id, a2.id, b.id), library.observeDownloadedTrackIds().first())
        assertEquals(3, library.observeDownloadedTracks().first().size)

        assertTrue(library.observeAlbumDownloadCount(albumA1).first().isPartial)
        assertFalse(library.isAlbumFullyDownloaded(albumA1))
        assertTrue(library.isAlbumFullyDownloaded(albumA2))
        assertTrue(library.isArtistDownloaded(artistB))
    }

    @Test
    fun markingATrackThatLeftTheLibraryReportsIt() = runTest {
        assertFalse(library.markDownloaded(UUID.randomUUID(), "Downloads/x.flac", 1))
    }

    @Test
    fun removingAnAlbumDeletesItsFilesAndFlagsOnly() = runTest {
        val a1 = track(albumA1, artistA)
        val a2 = track(albumA2, artistA)
        seed(listOf(a1, a2))
        val f1 = download(a1)
        val f2 = download(a2)

        library.removeAlbumDownloads(albumA1)

        assertFalse(f1.exists())
        assertTrue(f2.exists())
        assertEquals(setOf(a2.id), library.downloadedTrackIds())
        assertEquals(false, library.track(a1.id)!!.isDownloaded)
    }

    @Test
    fun removingAnArtistOrTracksOrEverything() = runTest {
        val a1 = track(albumA1, artistA)
        val a2 = track(albumA2, artistA)
        val b = track(albumB, artistB)
        seed(listOf(a1, a2, b))
        download(a1); download(a2); val fb = download(b)

        library.removeArtistDownloads(artistA)
        assertEquals(setOf(b.id), library.downloadedTrackIds())

        library.removeDownloads(listOf(b.id))
        assertFalse(fb.exists())
        assertTrue(library.downloadedTrackIds().isEmpty())

        download(a1)
        library.removeAllDownloads()
        assertTrue(library.downloadedTrackIds().isEmpty())
        assertFalse(File(filesDir, MusicLibrary.DOWNLOADS_DIR).exists())
    }

    @Test
    fun reconciliationRemarksFilesAfterARebuild() = runTest {
        val kept = track(albumA1, artistA)
        val gone = track(albumA2, artistA)
        seed(listOf(kept, gone))
        val keptFile = download(kept, 3000)
        val goneFile = download(gone)

        // A rebuild empties the tables and leaves the files; the server no longer has `gone`.
        library.clearAll()
        seed(listOf(kept))
        library.reconcileDownloadedFiles(deleteOrphans = false)

        val track = db.tracks().get(kept.id)!!.track
        assertTrue(track.isDownloaded)
        assertEquals(keptFile.relativeTo(filesDir).invariantSeparatorsPath, track.localFilePath)
        assertEquals(3000L, track.downloadedFileSize)
        assertTrue("orphans stay until the library is known complete", goneFile.exists())

        library.reconcileDownloadedFiles(deleteOrphans = true)
        assertFalse(goneFile.exists())
        assertFalse("its emptied album directory goes too", goneFile.parentFile!!.exists())
        assertTrue(keptFile.exists())
    }

    @Test
    fun reconciliationKeepsPartialsStillQueued() = runTest {
        val queued = track(albumA1, artistA)
        val abandoned = track(albumA1, artistA)
        seed(listOf(queued, abandoned))
        val dir = File(filesDir, "${MusicLibrary.DOWNLOADS_DIR}/$artistA/$albumA1").apply { mkdirs() }
        val queuedPart = File(dir, "${queued.id}.original.part").apply { writeBytes(ByteArray(10)) }
        val abandonedPart = File(dir, "${abandoned.id}.original.part").apply { writeBytes(ByteArray(10)) }
        library.enqueueDownloads(listOf(DownloadQueueEntity(queued.id, 1)))

        library.reconcileDownloadedFiles(deleteOrphans = true)

        assertTrue(queuedPart.exists())
        assertFalse(abandonedPart.exists())
        assertTrue("a partial is never taken for a download", library.downloadedTrackIds().isEmpty())
    }

    @Test
    fun reconciliationDropsALeftoverBesideTheMarkedFile() = runTest {
        val t = track(albumA1, artistA)
        seed(listOf(t))
        val current = download(t)
        val leftover = File(current.parentFile, "${t.id}.m4a").apply { writeBytes(ByteArray(10)) }
        val foreign = File(current.parentFile, "notes.txt").apply { writeText("mine") }

        library.reconcileDownloadedFiles(deleteOrphans = true)

        assertTrue(current.exists())
        assertFalse(leftover.exists())
        assertTrue("files not named for a track aren't ours to delete", foreign.exists())
        assertEquals(current.relativeTo(filesDir).invariantSeparatorsPath, db.tracks().get(t.id)!!.track.localFilePath)
    }

    @Test
    fun loggingOutClearsTheQueueButARebuildKeepsIt() = runTest {
        val t = track(albumA1, artistA)
        seed(listOf(t))
        library.enqueueDownloads(listOf(DownloadQueueEntity(t.id, 1)))

        library.clearAll()
        assertEquals(1, library.downloadQueue().size)

        library.clearAll(includingDownloads = true)
        assertTrue(library.downloadQueue().isEmpty())
    }

    @Test
    fun offlineOnlyDownloadedTracksAreAvailable() = runTest {
        val a = track(albumA1, artistA)
        val b = track(albumA1, artistA)
        seed(listOf(a, b))
        download(a)
        val tracks = listOf(library.track(a.id)!!, library.track(b.id)!!)

        assertEquals(tracks, availableTracks(tracks, isOnline = true))
        assertEquals(listOf(a.id), availableTracks(tracks, isOnline = false).map { it.id })
    }
}
