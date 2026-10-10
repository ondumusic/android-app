package com.ondumusic.player.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ondumusic.player.api.SyncAlbum
import com.ondumusic.player.api.SyncArtist
import com.ondumusic.player.api.SyncTrack
import com.ondumusic.player.data.LibraryDatabase
import com.ondumusic.player.data.MusicLibrary
import com.ondumusic.player.model.Playlist
import com.ondumusic.player.model.PlaylistItem
import com.ondumusic.player.service.outbox.AddItemsPayload
import com.ondumusic.player.service.outbox.CreatePlaylistPayload
import com.ondumusic.player.service.outbox.MoveItemsPayload
import com.ondumusic.player.service.outbox.OutboxKind
import com.ondumusic.player.service.outbox.OutboxService
import com.ondumusic.player.service.outbox.RemoveItemsPayload
import com.ondumusic.player.service.outbox.decodeOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
class PlaylistControllerTest {
    private lateinit var db: LibraryDatabase
    private lateinit var library: MusicLibrary
    private lateinit var filesDir: File
    private lateinit var outbox: OutboxService
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("playlist-test", Context.MODE_PRIVATE)
    private val downloads = mutableListOf<Set<UUID>>()

    private val now = Instant.parse("2026-10-01T12:00:00Z")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        filesDir = File(context.cacheDir, "lib-${UUID.randomUUID()}").apply { mkdirs() }
        library = MusicLibrary(db, filesDir)
        // Nothing is sent: these tests are about what gets written and queued.
        outbox = OutboxService(library, { null }, CoroutineScope(Job().apply { cancel() }), {})
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        db.close()
        filesDir.deleteRecursively()
    }

    private fun TestScope.controller() = PlaylistController(library, outbox, prefs, backgroundScope) { downloads += it }

    private suspend fun kinds() = library.pendingMutations().map { it.kind }

    private suspend fun seedTracks(vararg ids: UUID) {
        val artistId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        library.upsertArtists(listOf(SyncArtist(artistId, "Artist", null, "artist", albumCount = 1, insertedAt = now)))
        library.upsertAlbums(listOf(SyncAlbum(albumId, artistId, "Album", null, "album", 2001, null, emptyList(), false, ids.size, now)))
        library.upsertTracks(ids.map {
            SyncTrack(it, artistId, artistId, albumId, "Song", 1, 1, 200.0, "audio/flac", "/stream/$it", "/download/$it", now)
        })
    }

    @Test
    fun creatingAPlaylistShowsItAtOnceAndQueuesTheCreate() = runTest {
        val parent = UUID.randomUUID()
        val created = controller().createPlaylist("  Mix  ", parent)!!

        assertEquals("Mix", library.playlist(created.id)!!.name)
        val payload = CreatePlaylistPayload.serializer().decodeOrNull(library.pendingMutations().single().payload)
        assertEquals(CreatePlaylistPayload(created.id, "Mix", isFolder = false, parentId = parent), payload)
    }

    @Test
    fun foldersAreQueuedAsFolders() = runTest {
        val folder = controller().createFolder("Folder")!!
        assertTrue(folder.isFolder)
        assertTrue(CreatePlaylistPayload.serializer().decodeOrNull(library.pendingMutations().single().payload)!!.isFolder)
    }

    @Test
    fun blankNamesAreIgnored() = runTest {
        val controller = controller()
        assertNull(controller.createPlaylist("   "))
        val id = controller.createPlaylist("Mix")!!.id
        controller.renamePlaylist(id, " ")
        assertEquals("Mix", library.playlist(id)!!.name)
        assertEquals(listOf(OutboxKind.CreatePlaylist.raw), kinds())
    }

    @Test
    fun aPlaylistCantBeMovedIntoItself() = runTest {
        val id = UUID.randomUUID()
        controller().movePlaylist(id, id)
        assertTrue(kinds().isEmpty())
    }

    @Test
    fun deletingAPlaylistNeverSyncedLeavesNothingToSend() = runTest {
        val controller = controller()
        val created = controller.createPlaylist("Mix")!!
        controller.addTracks(listOf(UUID.randomUUID()), created.id)

        controller.deletePlaylist(created.id)

        assertNull(library.playlist(created.id))
        assertTrue(kinds().isEmpty())
        assertNull(controller.lastUsedPlaylistId.value)
    }

    @Test
    fun deletingASyncedPlaylistQueuesTheDelete() = runTest {
        val id = UUID.randomUUID()
        library.insertLocalPlaylist(Playlist(id, "Mix", 0, now))
        library.setPlaylistAutoDownload(id, true)

        controller().deletePlaylist(id)

        assertEquals(listOf(OutboxKind.DeletePlaylist.raw), kinds())
    }

    @Test
    fun addedTracksAppendInOrderAndRememberThePlaylist() = runTest {
        val id = UUID.randomUUID()
        library.insertLocalPlaylist(Playlist(id, "Mix", 0, now))
        val tracks = List(3) { UUID.randomUUID() }
        val controller = controller()

        controller.addTracks(tracks, id)

        val items = library.playlistItems(id)
        assertEquals(tracks, items.map { it.trackId })
        assertEquals(items.map { it.position }.sorted(), items.map { it.position })
        val payload = AddItemsPayload.serializer().decodeOrNull(library.pendingMutations().single().payload)!!
        assertEquals(items.map { it.id }, payload.items.map { it.tempItemId })
        assertEquals(id, controller.lastUsedPlaylistId.value)
        // Survives a restart.
        assertEquals(id, controller().lastUsedPlaylistId.value)
    }

    @Test
    fun theRememberedPlaylistFollowsItsServerId() = runTest {
        val controller = controller()
        val created = controller.createPlaylist("Mix")!!
        controller.addTracks(listOf(UUID.randomUUID()), created.id)
        testScheduler.runCurrent()

        val confirmed = Playlist(UUID.randomUUID(), "Mix", 1, now)
        library.remapPlaylistId(created.id, confirmed)
        testScheduler.runCurrent()

        assertEquals(confirmed.id, controller.lastUsedPlaylistId.value)
        assertEquals(confirmed.id, controller().lastUsedPlaylistId.value)
    }

    @Test
    fun addingToAnAutoDownloadPlaylistDownloadsWhatIsMissing() = runTest {
        val id = UUID.randomUUID()
        library.insertLocalPlaylist(Playlist(id, "Mix", 0, now))
        library.setPlaylistAutoDownload(id, true)
        val have = UUID.randomUUID()
        val missing = UUID.randomUUID()
        seedTracks(have, missing)
        db.openHelper.writableDatabase.execSQL("UPDATE tracks SET isDownloaded = 1 WHERE id = '$have'")

        controller().addTracks(listOf(have, missing), id)

        assertEquals(listOf(setOf(missing)), downloads)
    }

    @Test
    fun enablingAutoDownloadFetchesTheTracksNotYetDownloaded() = runTest {
        val id = UUID.randomUUID()
        library.insertLocalPlaylist(Playlist(id, "Mix", 0, now))
        val track = UUID.randomUUID()
        seedTracks(track)
        library.insertLocalPlaylistItem(PlaylistItem(UUID.randomUUID(), id, track, "a0", now))

        controller().enableAutoDownload(id)

        assertTrue(library.playlist(id)!!.autoDownload)
        assertEquals(listOf(setOf(track)), downloads)
    }

    @Test
    fun removingTheDownloadClearsTheFlagAndRemovesEveryTrack() = runTest {
        val id = UUID.randomUUID()
        library.insertLocalPlaylist(Playlist(id, "Mix", 0, now))
        library.setPlaylistAutoDownload(id, true)
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        seedTracks(a, b)
        listOf(a, b).forEachIndexed { i, track -> library.insertLocalPlaylistItem(PlaylistItem(UUID.randomUUID(), id, track, "a$i", now)) }
        val removed = mutableListOf<Set<UUID>>()

        PlaylistController(library, outbox, prefs, backgroundScope, removeDownloads = { removed += it }) {}
            .disableAutoDownloadAndRemove(id)

        assertEquals(false, library.playlist(id)!!.autoDownload)
        assertEquals(listOf(setOf(a, b)), removed)
    }

    @Test
    fun removingAnItemNotYetAddedServerSideSendsNothing() = runTest {
        val id = UUID.randomUUID()
        library.insertLocalPlaylist(Playlist(id, "Mix", 0, now))
        val controller = controller()
        controller.addTracks(listOf(UUID.randomUUID()), id)
        val item = library.playlistItems(id).single()

        controller.removeItem(item.id, id)

        assertTrue(library.playlistItems(id).isEmpty())
        assertTrue(kinds().isEmpty())
    }

    @Test
    fun removingASyncedItemQueuesTheRemove() = runTest {
        val id = UUID.randomUUID()
        library.insertLocalPlaylist(Playlist(id, "Mix", 0, now))
        val item = UUID.randomUUID()
        library.insertLocalPlaylistItem(PlaylistItem(item, id, UUID.randomUUID(), "a0", now))

        controller().removeItem(item, id)

        val payload = RemoveItemsPayload.serializer().decodeOrNull(library.pendingMutations().single().payload)
        assertEquals(RemoveItemsPayload(id, listOf(item)), payload)
    }

    @Test
    fun movingAnItemUpdatesItAndQueuesTheMove() = runTest {
        val id = UUID.randomUUID()
        library.insertLocalPlaylist(Playlist(id, "Mix", 0, now))
        val item = UUID.randomUUID()
        library.insertLocalPlaylistItem(PlaylistItem(item, id, UUID.randomUUID(), "a0", now))

        controller().moveItem(item, "a5", id)

        assertEquals("a5", library.playlistItems(id).single().position)
        val payload = MoveItemsPayload.serializer().decodeOrNull(library.pendingMutations().single().payload)
        assertEquals(MoveItemsPayload(id, listOf(MoveItemsPayload.Entry(item, "a5"))), payload)
    }
}
