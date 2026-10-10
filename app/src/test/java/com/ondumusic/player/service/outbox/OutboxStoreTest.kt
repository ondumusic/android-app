package com.ondumusic.player.service.outbox

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ondumusic.player.data.LibraryDatabase
import com.ondumusic.player.data.MusicLibrary
import com.ondumusic.player.data.PendingMutationEntity
import com.ondumusic.player.model.Playlist
import com.ondumusic.player.model.PlaylistItem
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
class OutboxStoreTest {
    private lateinit var db: LibraryDatabase
    private lateinit var library: MusicLibrary
    private lateinit var filesDir: File

    private val now = Instant.parse("2026-10-01T12:00:00Z")
    private var clock = now

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

    /** Distinct, increasing timestamps so queue order is the order of these calls. */
    private suspend fun <T> enqueue(kind: OutboxKind, serializer: kotlinx.serialization.KSerializer<T>, payload: T) {
        clock = clock.plusSeconds(1)
        library.enqueuePendingMutation(PendingMutationEntity(kind = kind.raw, payload = serializer.encode(payload), createdAt = clock))
    }

    private suspend fun pending() = library.pendingMutations()

    private suspend fun <T> payloadAt(index: Int, serializer: kotlinx.serialization.KSerializer<T>): T =
        serializer.decodeOrNull(pending()[index].payload)!!

    @Test
    fun kindRawValuesMatchIos() {
        assertEquals(
            listOf("create_playlist", "rename_playlist", "move_playlist", "delete_playlist",
                "add_items", "move_items", "remove_items", "record_plays"),
            OutboxKind.entries.map { it.raw },
        )
        assertEquals(OutboxKind.AddItems, OutboxKind.from("add_items"))
        assertNull(OutboxKind.from("nope"))
    }

    @Test
    fun createPayloadDefaultsWhenOptionalFieldsAreAbsent() {
        val id = UUID.randomUUID()
        val p = CreatePlaylistPayload.serializer().decodeOrNull("""{"tempPlaylistId":"$id","name":"Mix"}""")!!
        assertEquals(CreatePlaylistPayload(id, "Mix", isFolder = false, parentId = null), p)
    }

    @Test
    fun remapsTempItemIdInQueuedMovesAndRemoves() = runTest {
        val playlistId = UUID.randomUUID()
        val temp = UUID.randomUUID()
        val other = UUID.randomUUID()
        val real = UUID.randomUUID()
        enqueue(OutboxKind.MoveItems, MoveItemsPayload.serializer(),
            MoveItemsPayload(playlistId, listOf(MoveItemsPayload.Entry(temp, "a1"), MoveItemsPayload.Entry(other, "a2"))))
        enqueue(OutboxKind.RemoveItems, RemoveItemsPayload.serializer(), RemoveItemsPayload(playlistId, listOf(other, temp)))
        enqueue(OutboxKind.AddItems, AddItemsPayload.serializer(),
            AddItemsPayload(playlistId, listOf(AddItemsPayload.Entry(temp, UUID.randomUUID(), "a3"))))

        library.remapQueuedPlaylistItemId(temp, real)

        assertEquals(listOf(real, other), payloadAt(0, MoveItemsPayload.serializer()).items.map { it.itemId })
        assertEquals(listOf(other, real), payloadAt(1, RemoveItemsPayload.serializer()).itemIds)
        // An add mints the temp id; only references to it change.
        assertEquals(temp, payloadAt(2, AddItemsPayload.serializer()).items.single().tempItemId)
    }

    @Test
    fun swappingAnItemIdAlsoRewritesTheQueue() = runTest {
        val playlistId = UUID.randomUUID()
        val temp = UUID.randomUUID()
        val trackId = UUID.randomUUID()
        library.insertLocalPlaylist(Playlist(playlistId, "Mix", 0, now))
        library.insertLocalPlaylistItem(PlaylistItem(temp, playlistId, trackId, "a0", now))
        enqueue(OutboxKind.RemoveItems, RemoveItemsPayload.serializer(), RemoveItemsPayload(playlistId, listOf(temp)))

        val confirmed = PlaylistItem(UUID.randomUUID(), playlistId, trackId, "a0", now)
        library.swapPlaylistItemIdEverywhere(temp, confirmed)

        assertEquals(listOf(confirmed.id), library.playlistItems(playlistId).map { it.id })
        assertEquals(listOf(confirmed.id), payloadAt(0, RemoveItemsPayload.serializer()).itemIds)
    }

    @Test
    fun remapsTempPlaylistIdAcrossEveryKind() = runTest {
        val temp = UUID.randomUUID()
        val real = UUID.randomUUID()
        val child = UUID.randomUUID()
        val item = UUID.randomUUID()
        enqueue(OutboxKind.CreatePlaylist, CreatePlaylistPayload.serializer(), CreatePlaylistPayload(temp, "Folder", isFolder = true))
        enqueue(OutboxKind.CreatePlaylist, CreatePlaylistPayload.serializer(), CreatePlaylistPayload(child, "Child", parentId = temp))
        enqueue(OutboxKind.RenamePlaylist, RenamePlaylistPayload.serializer(), RenamePlaylistPayload(temp, "Renamed"))
        enqueue(OutboxKind.MovePlaylist, MovePlaylistPayload.serializer(), MovePlaylistPayload(child, temp))
        enqueue(OutboxKind.AddItems, AddItemsPayload.serializer(),
            AddItemsPayload(temp, listOf(AddItemsPayload.Entry(item, UUID.randomUUID(), "a0"))))
        enqueue(OutboxKind.MoveItems, MoveItemsPayload.serializer(), MoveItemsPayload(temp, listOf(MoveItemsPayload.Entry(item, "a1"))))
        enqueue(OutboxKind.RemoveItems, RemoveItemsPayload.serializer(), RemoveItemsPayload(temp, listOf(item)))
        enqueue(OutboxKind.DeletePlaylist, DeletePlaylistPayload.serializer(), DeletePlaylistPayload(temp))
        enqueue(OutboxKind.RecordPlays, RecordPlayPayload.serializer(), RecordPlayPayload(UUID.randomUUID(), UUID.randomUUID(), now))
        val playBefore = pending().last().payload

        library.remapQueuedPlaylistId(temp, real)

        // The create carrying the temp id itself is left alone; its resolution is what triggered this.
        assertEquals(temp, payloadAt(0, CreatePlaylistPayload.serializer()).tempPlaylistId)
        assertEquals(real, payloadAt(1, CreatePlaylistPayload.serializer()).parentId)
        assertEquals(real, payloadAt(2, RenamePlaylistPayload.serializer()).playlistId)
        assertEquals(MovePlaylistPayload(child, real), payloadAt(3, MovePlaylistPayload.serializer()))
        assertEquals(real, payloadAt(4, AddItemsPayload.serializer()).playlistId)
        assertEquals(real, payloadAt(5, MoveItemsPayload.serializer()).playlistId)
        assertEquals(real, payloadAt(6, RemoveItemsPayload.serializer()).playlistId)
        assertEquals(real, payloadAt(7, DeletePlaylistPayload.serializer()).playlistId)
        assertEquals(playBefore, pending()[8].payload)
    }

    @Test
    fun movingAPlaylistItselfCreatedOfflineRemapsItsId() = runTest {
        val temp = UUID.randomUUID()
        val real = UUID.randomUUID()
        enqueue(OutboxKind.MovePlaylist, MovePlaylistPayload.serializer(), MovePlaylistPayload(temp, null))

        library.remapQueuedPlaylistId(temp, real)

        assertEquals(MovePlaylistPayload(real, null), payloadAt(0, MovePlaylistPayload.serializer()))
    }

    @Test
    fun deletingAPlaylistNeverSyncedAbsorbsItsWholeHistory() = runTest {
        val temp = UUID.randomUUID()
        val other = UUID.randomUUID()
        enqueue(OutboxKind.CreatePlaylist, CreatePlaylistPayload.serializer(), CreatePlaylistPayload(temp, "Mix"))
        enqueue(OutboxKind.RenamePlaylist, RenamePlaylistPayload.serializer(), RenamePlaylistPayload(temp, "Renamed"))
        enqueue(OutboxKind.AddItems, AddItemsPayload.serializer(),
            AddItemsPayload(temp, listOf(AddItemsPayload.Entry(UUID.randomUUID(), UUID.randomUUID(), "a0"))))
        enqueue(OutboxKind.RenamePlaylist, RenamePlaylistPayload.serializer(), RenamePlaylistPayload(other, "Untouched"))
        enqueue(OutboxKind.RecordPlays, RecordPlayPayload.serializer(), RecordPlayPayload(UUID.randomUUID(), UUID.randomUUID(), now))

        assertTrue(library.compactPendingForDeletedPlaylist(temp))

        assertEquals(listOf(OutboxKind.RenamePlaylist.raw, OutboxKind.RecordPlays.raw), pending().map { it.kind })
        assertEquals(other, payloadAt(0, RenamePlaylistPayload.serializer()).playlistId)
    }

    @Test
    fun deletingASyncedPlaylistDropsItsOpsButStillNeedsTheServerDelete() = runTest {
        val playlistId = UUID.randomUUID()
        enqueue(OutboxKind.MoveItems, MoveItemsPayload.serializer(),
            MoveItemsPayload(playlistId, listOf(MoveItemsPayload.Entry(UUID.randomUUID(), "a1"))))
        enqueue(OutboxKind.MovePlaylist, MovePlaylistPayload.serializer(), MovePlaylistPayload(playlistId, null))

        assertFalse(library.compactPendingForDeletedPlaylist(playlistId))

        assertTrue(pending().isEmpty())
    }

    @Test
    fun removedItemsAreStrippedFromPendingAddsAndMoves() = runTest {
        val playlistId = UUID.randomUUID()
        val pendingAdd = UUID.randomUUID()
        val keptAdd = UUID.randomUUID()
        val synced = UUID.randomUUID()
        val keptMove = UUID.randomUUID()
        enqueue(OutboxKind.AddItems, AddItemsPayload.serializer(), AddItemsPayload(playlistId, listOf(
            AddItemsPayload.Entry(pendingAdd, UUID.randomUUID(), "a0"),
            AddItemsPayload.Entry(keptAdd, UUID.randomUUID(), "a1"),
        )))
        enqueue(OutboxKind.MoveItems, MoveItemsPayload.serializer(), MoveItemsPayload(playlistId, listOf(
            MoveItemsPayload.Entry(synced, "a2"),
            MoveItemsPayload.Entry(keptMove, "a3"),
        )))

        val absorbed = library.compactPendingForRemovedItems(setOf(pendingAdd, synced))

        assertEquals(setOf(pendingAdd), absorbed)
        assertEquals(listOf(keptAdd), payloadAt(0, AddItemsPayload.serializer()).items.map { it.tempItemId })
        assertEquals(listOf(keptMove), payloadAt(1, MoveItemsPayload.serializer()).items.map { it.itemId })
    }

    @Test
    fun mutationsEmptiedByAStripAreDropped() = runTest {
        val playlistId = UUID.randomUUID()
        val item = UUID.randomUUID()
        enqueue(OutboxKind.AddItems, AddItemsPayload.serializer(),
            AddItemsPayload(playlistId, listOf(AddItemsPayload.Entry(item, UUID.randomUUID(), "a0"))))
        enqueue(OutboxKind.MoveItems, MoveItemsPayload.serializer(), MoveItemsPayload(playlistId, listOf(MoveItemsPayload.Entry(item, "a1"))))
        enqueue(OutboxKind.RemoveItems, RemoveItemsPayload.serializer(), RemoveItemsPayload(playlistId, listOf(item)))

        assertEquals(setOf(item), library.compactPendingForRemovedItems(setOf(item)))

        // A queued remove is left for the caller; only adds and moves are compacted.
        assertEquals(listOf(OutboxKind.RemoveItems.raw), pending().map { it.kind })
    }

    @Test
    fun removingNothingTouchesNothing() = runTest {
        enqueue(OutboxKind.AddItems, AddItemsPayload.serializer(),
            AddItemsPayload(UUID.randomUUID(), listOf(AddItemsPayload.Entry(UUID.randomUUID(), UUID.randomUUID(), "a0"))))
        assertEquals(emptySet<UUID>(), library.compactPendingForRemovedItems(emptySet()))
        assertEquals(1, pending().size)
    }
}
