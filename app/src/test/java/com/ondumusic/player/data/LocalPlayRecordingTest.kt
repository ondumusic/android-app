package com.ondumusic.player.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ondumusic.player.api.SyncAlbum
import com.ondumusic.player.api.SyncArtist
import com.ondumusic.player.api.SyncTrack
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class LocalPlayRecordingTest {
    private lateinit var db: LibraryDatabase
    private lateinit var library: MusicLibrary
    private lateinit var filesDir: File

    private val now = Instant.parse("2026-10-01T12:00:00Z")
    private val artistId = UUID.randomUUID()
    private val albumId = UUID.randomUUID()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        filesDir = File(context.cacheDir, "lib-${UUID.randomUUID()}").apply { mkdirs() }
        library = MusicLibrary(db, filesDir)
    }

    @After
    fun tearDown() {
        db.close()
        filesDir.deleteRecursively()
    }

    private fun track(id: UUID, playCount: Int, lastPlayedAt: Instant? = null) =
        SyncTrack(id, artistId, artistId, albumId, "Track", 1, 1, 200.0, "audio/mp4", "/s/1", "/d/1", now,
            playCount = playCount, lastPlayedAt = lastPlayedAt)

    private suspend fun seedTrack(playCount: Int): UUID {
        val trackId = UUID.randomUUID()
        library.upsertArtists(listOf(SyncArtist(artistId, "Artist", "Artist", "artist", albumCount = 1, insertedAt = now)))
        library.upsertAlbums(listOf(SyncAlbum(albumId, artistId, "Album", "Album", "album", null, null, emptyList(), false, 1, now)))
        library.upsertTracks(listOf(track(trackId, playCount)))
        return trackId
    }

    @Test
    fun countsThePlayLocally() = runTest {
        val trackId = seedTrack(playCount = 4)
        val playedAt = Instant.ofEpochSecond(1_700_000_000)

        library.recordLocalPlay(trackId, playedAt)

        val track = library.track(trackId)!!
        assertEquals(5, track.playCount)
        assertEquals(playedAt, track.lastPlayedAt)
    }

    /**
     * The server counts plays from every device, so its total replaces ours rather than
     * adding to it — otherwise this device's optimistic bump would be counted twice.
     */
    @Test
    fun theServersTotalReplacesTheLocalOne() = runTest {
        val trackId = seedTrack(playCount = 4)
        library.recordLocalPlay(trackId, now)
        assertEquals(5, library.track(trackId)!!.playCount)

        val serverLastPlayed = Instant.ofEpochSecond(1_700_000_500)
        library.upsertTracks(listOf(track(trackId, playCount = 9, lastPlayedAt = serverLastPlayed)))

        val track = library.track(trackId)!!
        assertEquals(9, track.playCount)
        assertEquals(serverLastPlayed, track.lastPlayedAt)
    }

    @Test
    fun ignoresATrackThatIsNotInTheLibrary() = runTest {
        library.recordLocalPlay(UUID.randomUUID(), now)
    }
}
