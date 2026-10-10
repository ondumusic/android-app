package com.ondumusic.player.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ondumusic.player.data.LibraryDatabase
import com.ondumusic.player.data.MusicLibrary
import com.ondumusic.player.model.Playlist
import com.ondumusic.player.model.PlaylistItem
import com.ondumusic.player.service.outbox.DeletePlaylistPayload
import com.ondumusic.player.service.outbox.OutboxKind
import com.ondumusic.player.service.outbox.OutboxService
import com.ondumusic.player.service.outbox.RemoveItemsPayload
import com.ondumusic.player.service.outbox.decodeOrNull
import com.ondumusic.player.service.outbox.swapPlaylistItemIdEverywhere
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PendingPlaylistEditsTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: LibraryDatabase
    private lateinit var library: MusicLibrary
    private lateinit var filesDir: File
    private lateinit var outbox: OutboxService
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("pending-edits-test", Context.MODE_PRIVATE)
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

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    private fun TestScope.controller() = PlaylistController(library, outbox, prefs, backgroundScope) {}

    private fun TestScope.edits(controller: PlaylistController = controller()) =
        PendingPlaylistEdits(controller, library, backgroundScope).also { runCurrent() }

    private suspend fun kinds() = library.pendingMutations().map { it.kind }

    /** A playlist the server already has, holding one song. */
    private suspend fun syncedPlaylistWithItem(): PlaylistItem {
        val playlistId = UUID.randomUUID()
        library.insertLocalPlaylist(Playlist(playlistId, "zz", 0, now))
        return PlaylistItem(UUID.randomUUID(), playlistId, UUID.randomUUID(), "a0", now).also { library.insertLocalPlaylistItem(it) }
    }

    // Removing a song

    @Test
    fun aHeldRemovalIsHiddenButNotYetWrittenOrQueued() = test {
        val item = syncedPlaylistWithItem()
        val edits = edits()

        edits.removeItem(item)
        runCurrent()

        assertTrue(edits.hidden.value.hides(item))
        assertEquals(listOf(item.id), library.playlistItems(item.playlistId).map { it.id })
        assertTrue(kinds().isEmpty())
    }

    @Test
    fun committingARemovalWritesAndQueuesIt() = test {
        val item = syncedPlaylistWithItem()
        val edits = edits()
        val token = edits.removeItem(item)

        edits.commit(token)?.join()
        runCurrent()

        assertTrue(library.playlistItems(item.playlistId).isEmpty())
        val payload = RemoveItemsPayload.serializer().decodeOrNull(library.pendingMutations().single().payload)
        assertEquals(RemoveItemsPayload(item.playlistId, listOf(item.id)), payload)
        assertFalse(edits.isPending(token).first())
        assertFalse(edits.hidden.value.hides(item))
    }

    @Test
    fun anUndoneRemovalLeavesNoTrace() = test {
        val item = syncedPlaylistWithItem()
        val edits = edits()
        val token = edits.removeItem(item)

        assertTrue(edits.undo(token))
        // The snackbar going afterwards mustn't carry it out after all.
        edits.commit(token)?.join()
        runCurrent()

        assertFalse(edits.hidden.value.hides(item))
        assertEquals(listOf(item.id), library.playlistItems(item.playlistId).map { it.id })
        assertTrue(kinds().isEmpty())
    }

    @Test
    fun anUndoAfterTheCommitDoesNothing() = test {
        val item = syncedPlaylistWithItem()
        val edits = edits()
        val token = edits.removeItem(item)

        edits.commit(token)?.join()
        assertFalse(edits.undo(token))
    }

    /** A song added and removed again before the add was sent never reaches the server at all. */
    @Test
    fun removingASongWhoseAddIsStillQueuedCancelsTheAdd() = test {
        val playlistId = UUID.randomUUID()
        library.insertLocalPlaylist(Playlist(playlistId, "zz", 0, now))
        val controller = controller()
        controller.addTracks(listOf(UUID.randomUUID()), playlistId)
        val item = library.playlistItems(playlistId).single()
        val edits = edits(controller)

        edits.commit(edits.removeItem(item))?.join()
        runCurrent()

        assertTrue(library.playlistItems(playlistId).isEmpty())
        assertTrue(kinds().isEmpty())
    }

    /** The server confirming the add while the removal is held swaps the item's id; the removal follows it. */
    @Test
    fun aHeldRemovalFollowsTheItemsServerId() = test {
        val item = syncedPlaylistWithItem()
        val edits = edits()
        val token = edits.removeItem(item)
        val confirmed = item.copy(id = UUID.randomUUID())
        library.swapPlaylistItemIdEverywhere(item.id, confirmed)

        assertTrue(edits.hidden.value.hides(confirmed))
        edits.commit(token)?.join()
        runCurrent()

        assertTrue(library.playlistItems(item.playlistId).isEmpty())
        val payload = RemoveItemsPayload.serializer().decodeOrNull(library.pendingMutations().single().payload)
        assertEquals(listOf(confirmed.id), payload?.itemIds)
    }

    // Deleting a playlist

    @Test
    fun aHeldDeletionKeepsThePlaylistUntilCommitted() = test {
        val item = syncedPlaylistWithItem()
        val playlist = library.playlist(item.playlistId)!!
        val edits = edits()

        val token = edits.deletePlaylist(playlist)
        assertEquals(setOf(playlist.id), edits.hidden.value.playlistIds)
        assertNotNull(library.playlist(playlist.id))
        assertTrue(kinds().isEmpty())

        edits.commit(token)?.join()
        runCurrent()

        assertNull(library.playlist(playlist.id))
        assertEquals(listOf(OutboxKind.DeletePlaylist.raw), kinds())
        assertTrue(edits.hidden.value.playlistIds.isEmpty())
    }

    @Test
    fun undoingTheDeletionOfAnUnsentPlaylistKeepsItsCreate() = test {
        val controller = controller()
        val created = controller.createPlaylist("zz")!!
        val edits = edits(controller)

        edits.undo(edits.deletePlaylist(created))
        runCurrent()

        assertNotNull(library.playlist(created.id))
        assertEquals(listOf(OutboxKind.CreatePlaylist.raw), kinds())
    }

    @Test
    fun deletingAnUnsentPlaylistLeavesNothingToSend() = test {
        val controller = controller()
        val created = controller.createPlaylist("zz")!!
        val edits = edits(controller)

        edits.commit(edits.deletePlaylist(created))?.join()
        runCurrent()

        assertNull(library.playlist(created.id))
        assertTrue(kinds().isEmpty())
    }

    /** Created offline, then given its server id while the deletion is held: the server's playlist is the one to delete. */
    @Test
    fun aHeldDeletionFollowsThePlaylistsServerId() = test {
        val tempId = UUID.randomUUID()
        library.insertLocalPlaylist(Playlist(tempId, "zz", 0, now))
        val edits = edits()
        val token = edits.deletePlaylist(library.playlist(tempId)!!)
        val realId = UUID.randomUUID()

        library.remapPlaylistId(tempId, Playlist(realId, "zz", 0, now))
        runCurrent()
        assertEquals(setOf(realId), edits.hidden.value.playlistIds)
        edits.commit(token)?.join()
        runCurrent()

        assertNull(library.playlist(realId))
        val payload = DeletePlaylistPayload.serializer().decodeOrNull(library.pendingMutations().single().payload)
        assertEquals(DeletePlaylistPayload(realId), payload)
    }

    // Leaving the app

    @Test
    fun commitAllCarriesOutEverythingHeld() = test {
        val item = syncedPlaylistWithItem()
        val other = syncedPlaylistWithItem()
        val edits = edits()
        val removal = edits.removeItem(item)
        val deletion = edits.deletePlaylist(library.playlist(other.playlistId)!!)

        edits.commitAll().joinAll()
        runCurrent()

        assertTrue(library.playlistItems(item.playlistId).isEmpty())
        assertNull(library.playlist(other.playlistId))
        assertFalse(edits.isPending(removal).first())
        assertFalse(edits.isPending(deletion).first())
        assertEquals(setOf(OutboxKind.RemoveItems.raw, OutboxKind.DeletePlaylist.raw), kinds().toSet())
    }

    @Test
    fun discardingForgetsWithoutCarryingOut() = test {
        val item = syncedPlaylistWithItem()
        val edits = edits()
        edits.removeItem(item)

        edits.discardAll()
        runCurrent()

        assertEquals(1, library.playlistItems(item.playlistId).size)
        assertTrue(kinds().isEmpty())
    }
}
