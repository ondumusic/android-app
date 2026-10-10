package com.ondumusic.player.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ondumusic.player.api.SyncAlbum
import com.ondumusic.player.api.SyncArtist
import com.ondumusic.player.api.SyncDeletion
import com.ondumusic.player.api.SyncPlaylist
import com.ondumusic.player.api.SyncTrack
import com.ondumusic.player.model.Playlist
import com.ondumusic.player.model.PlaylistItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class MusicLibraryTest {
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

    private fun artist(id: UUID = artistId, name: String = "Artist") =
        SyncArtist(id, name, null, name.lowercase(), albumCount = 1, insertedAt = now)

    private fun album(id: UUID = albumId, trackCount: Int = 1) =
        SyncAlbum(id, artistId, "Album", null, "album", 2001, null, emptyList(), false, trackCount, now)

    private fun track(id: UUID = UUID.randomUUID(), name: String = "Song", playCount: Int = 0) =
        SyncTrack(id, artistId, artistId, albumId, name, 1, 1, 200.0, "audio/flac", "/stream/$id", "/download/$id", now, playCount = playCount)

    private suspend fun seed(vararg tracks: SyncTrack) {
        library.upsertArtists(listOf(artist()))
        library.upsertAlbums(listOf(album(trackCount = tracks.size)))
        library.upsertTracks(tracks.toList())
    }

    @Test
    fun upsertReportsOnlyNewTracks() = runTest {
        val first = track()
        seed(first)
        val second = track()
        val inserted = library.upsertTracks(listOf(first, second))
        assertEquals(setOf(second.id), inserted)
    }

    @Test
    fun syncKeepsDownloadStateButTakesServerFields() = runTest {
        val t = track(playCount = 1)
        seed(t)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE tracks SET isDownloaded = 1, localFilePath = 'Downloads/a/b/c.flac', downloadedFileSize = 42"
        )

        library.upsertTracks(listOf(t.copy(name = "Renamed", playCount = 7)))

        val stored = library.track(t.id)!!
        assertEquals("Renamed", stored.name)
        assertEquals(7, stored.playCount)
        assertTrue(stored.isDownloaded)
        assertEquals("Downloads/a/b/c.flac", db.tracks().localFilePath(t.id))
    }

    @Test
    fun syncKeepsThePlaylistAutoDownloadFlag() = runTest {
        val p = SyncPlaylist(UUID.randomUUID(), "Mix", 0, now)
        library.upsertPlaylists(listOf(p))
        library.setPlaylistAutoDownload(p.id, true)

        library.upsertPlaylists(listOf(p.copy(name = "Renamed")))

        val stored = library.playlist(p.id)!!
        assertEquals("Renamed", stored.name)
        assertTrue(stored.autoDownload)
    }

    @Test
    fun joinsArtistAndAlbumOntoTracks() = runTest {
        val t = track()
        seed(t)
        val stored = library.track(t.id)!!
        assertEquals("Artist", stored.artist.name)
        assertEquals("Artist", stored.albumArtistSortName)
        assertEquals("Album", stored.album?.name)
        assertEquals(2001, stored.album?.releaseYear)
    }

    @Test
    fun deletingAnArtistCascadesAndRemovesFiles() = runTest {
        val t = track()
        seed(t)
        val relative = "Downloads/$artistId/$albumId/${t.id}.flac"
        File(filesDir, relative).apply { parentFile!!.mkdirs(); writeText("audio") }
        db.openHelper.writableDatabase.execSQL("UPDATE tracks SET isDownloaded = 1, localFilePath = '$relative'")

        library.applyDeletions(listOf(SyncDeletion("artist", artistId)))

        assertNull(library.track(t.id))
        assertNull(library.album(albumId))
        assertNull(library.artist(artistId))
        assertFalse(File(filesDir, relative).exists())
    }

    @Test
    fun playlistTracksFollowPositionOrderAndSkipMissingTracks() = runTest {
        val a = track(name = "A")
        val b = track(name = "B")
        seed(a, b)
        val playlistId = UUID.randomUUID()
        library.insertLocalPlaylist(Playlist(playlistId, "Mix", 0, now))
        library.insertLocalPlaylistItem(PlaylistItem(UUID.randomUUID(), playlistId, a.id, "a2", now))
        library.insertLocalPlaylistItem(PlaylistItem(UUID.randomUUID(), playlistId, b.id, "a1", now))
        library.insertLocalPlaylistItem(PlaylistItem(UUID.randomUUID(), playlistId, UUID.randomUUID(), "a0", now))

        assertEquals(listOf("B", "A"), library.tracksForPlaylist(playlistId).map { it.name })
        assertEquals(3, library.playlist(playlistId)!!.trackCount)
    }

    @Test
    fun deletingAFolderTakesItsDescendants() = runTest {
        val folder = UUID.randomUUID()
        val child = UUID.randomUUID()
        val grandchild = UUID.randomUUID()
        library.insertLocalPlaylist(Playlist(folder, "Folder", 0, now, isFolder = true))
        library.insertLocalPlaylist(Playlist(child, "Sub", 0, now, isFolder = true, parentId = folder))
        library.insertLocalPlaylist(Playlist(grandchild, "Mix", 0, now, parentId = child))

        library.deleteLocalPlaylist(folder)

        assertTrue(library.observePlaylists().first().isEmpty())
    }

    @Test
    fun remappingATempPlaylistCarriesItemsChildrenAndFlag() = runTest {
        val temp = UUID.randomUUID()
        val real = UUID.randomUUID()
        val childId = UUID.randomUUID()
        val t = track()
        seed(t)
        library.insertLocalPlaylist(Playlist(temp, "Folder", 0, now, isFolder = true))
        library.setPlaylistAutoDownload(temp, true)
        library.insertLocalPlaylist(Playlist(childId, "Child", 0, now, parentId = temp))
        library.insertLocalPlaylistItem(PlaylistItem(UUID.randomUUID(), temp, t.id, "a0", now))

        library.remapPlaylistId(temp, Playlist(real, "Folder", 1, now, isFolder = true))

        assertNull(library.playlist(temp))
        assertTrue(library.playlist(real)!!.autoDownload)
        assertEquals(real, library.playlist(childId)!!.parentId)
        assertEquals(1, library.playlistItems(real).size)
    }

    @Test
    fun clearingKeepsDownloadsUnlessAsked() = runTest {
        val file = File(filesDir, "Downloads/x/y/z.flac").apply { parentFile!!.mkdirs(); writeText("audio") }
        seed(track())

        library.clearAll()
        assertTrue(file.exists())
        assertTrue(library.observeArtists().first().isEmpty())

        library.clearAll(includingDownloads = true)
        assertFalse(file.exists())
    }

    @Test
    fun albumsAreHiddenUntilTheyHaveTracks() = runTest {
        library.upsertArtists(listOf(artist()))
        library.upsertAlbums(listOf(album(trackCount = 0)))
        assertTrue(library.observeAlbums().first().isEmpty())
    }
}
