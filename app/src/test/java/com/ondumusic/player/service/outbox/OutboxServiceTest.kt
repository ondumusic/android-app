package com.ondumusic.player.service.outbox

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ondumusic.player.R
import com.ondumusic.player.api.ApiClient
import com.ondumusic.player.data.LibraryDatabase
import com.ondumusic.player.data.MusicLibrary
import com.ondumusic.player.model.Playlist
import com.ondumusic.player.model.PlaylistItem
import com.ondumusic.player.util.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class OutboxServiceTest {
    private lateinit var db: LibraryDatabase
    private lateinit var library: MusicLibrary
    private lateinit var filesDir: File
    private lateinit var outbox: OutboxService
    private val server = MockWebServer()
    private var unauthorizedCalls = 0

    private val now = Instant.parse("2026-10-01T12:00:00Z")

    @Before
    fun setUp() {
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        filesDir = File(context.cacheDir, "lib-${UUID.randomUUID()}").apply { mkdirs() }
        library = MusicLibrary(db, filesDir)
        val api = ApiClient(server.url("/api"), "tok", OkHttpClient())
        // A cancelled scope: enqueue-triggered drains and retry timers never run, so each
        // test drives the queue with an explicit flush and sees exactly the requests it expects.
        val inert = CoroutineScope(Job().apply { cancel() })
        outbox = OutboxService(library, { api }, inert, { unauthorizedCalls++ })
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
        filesDir.deleteRecursively()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build()

    private fun playlistJson(id: UUID, name: String = "Mix") =
        """{"id": "$id", "name": "$name", "trackCount": 0, "insertedAt": "2026-10-01T12:00:00Z"}"""

    private fun itemsResponse(vararg added: PlaylistItem) = json(
        """{"added": [${added.joinToString { """{"id": "${it.id}", "playlistId": "${it.playlistId}", "trackId": "${it.trackId}", "position": "${it.position}", "insertedAt": "2026-10-01T12:00:00Z"}""" }}]}"""
    )

    private fun takeRequest(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!

    private fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body!!.utf8()).jsonObject

    private fun JsonObject.strings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.objects(key: String) = getValue(key).jsonArray.map { it.jsonObject }

    private suspend fun playlist(id: UUID = UUID.randomUUID()): UUID {
        library.insertLocalPlaylist(Playlist(id, "Mix", 0, now))
        return id
    }

    private fun add(trackId: UUID = UUID.randomUUID(), position: String = "a0", tempItemId: UUID = UUID.randomUUID()) =
        AddItemsPayload.Entry(tempItemId, trackId, position)

    // Playlist ops

    @Test
    fun creatingAPlaylistSwapsTheTempIdLocallyAndInTheQueue() = runTest {
        val temp = playlist()
        val real = UUID.randomUUID()
        val entry = add(position = "a0")
        library.insertLocalPlaylistItem(PlaylistItem(entry.tempItemId, temp, entry.trackId, "a0", now))
        outbox.enqueueCreatePlaylist(temp, "Mix")
        outbox.enqueueAddItems(temp, listOf(entry))
        val confirmed = PlaylistItem(UUID.randomUUID(), real, entry.trackId, "a0", now)
        server.enqueue(json(playlistJson(real)))
        server.enqueue(itemsResponse(confirmed))

        outbox.flush()

        val create = takeRequest()
        assertEquals("POST", create.method)
        assertEquals("/api/playlists", create.url.encodedPath)
        assertEquals("/api/playlists/$real/items", takeRequest().url.encodedPath)
        assertNull(library.playlist(temp))
        assertEquals(listOf(confirmed.id), library.playlistItems(real).map { it.id })
        assertTrue(library.pendingMutations().isEmpty())
    }

    @Test
    fun foldersAreCreatedAsFoldersUnderTheirParent() = runTest {
        val parent = UUID.randomUUID()
        val temp = playlist()
        outbox.enqueueCreateFolder(temp, "Folder", parent)
        server.enqueue(json(playlistJson(UUID.randomUUID(), "Folder")))

        outbox.flush()

        val body = takeRequest().json()
        assertEquals("true", body.getValue("isFolder").jsonPrimitive.content)
        assertEquals(parent.toString(), body.getValue("parentId").jsonPrimitive.content)
    }

    @Test
    fun anInvalidNameIsDiscardedAndReported() = runTest {
        val temp = playlist()
        outbox.enqueueCreatePlaylist(temp, "Mix")
        server.enqueue(json("""{"errors": {}}""", code = 422))

        outbox.flush()

        assertTrue(library.pendingMutations().isEmpty())
        assertEquals(UiText.Res(R.string.outbox_create_playlist_invalid_name, listOf("Mix")), outbox.lastError.value)
    }

    @Test
    fun opsForAPlaylistDeletedLocallyAreDroppedWithoutARequest() = runTest {
        val gone = UUID.randomUUID()
        outbox.enqueueRenamePlaylist(gone, "Renamed")
        outbox.enqueueAddItems(gone, listOf(add()))

        outbox.flush()

        assertEquals(0, server.requestCount)
        assertTrue(library.pendingMutations().isEmpty())
    }

    @Test
    fun deletingAPlaylistTheServerNoLongerHasSucceeds() = runTest {
        outbox.enqueueDeletePlaylist(UUID.randomUUID())
        server.enqueue(json("""{"error": "not_found"}""", code = 404))

        outbox.flush()

        assertEquals("DELETE", takeRequest().method)
        assertTrue(library.pendingMutations().isEmpty())
        assertNull(outbox.lastError.value)
    }

    @Test
    fun movingToTheRootSendsAnExplicitNullParent() = runTest {
        val id = playlist()
        outbox.enqueueMovePlaylist(id, null)
        server.enqueue(json(playlistJson(id)))

        outbox.flush()

        assertEquals("""{"parentId":null}""", takeRequest().body!!.utf8())
    }

    // Batching

    @Test
    fun adjacentItemOpsForOnePlaylistGoInOneRequest() = runTest {
        val p = playlist()
        val other = playlist()
        val moved = UUID.randomUUID()
        val removed = UUID.randomUUID()
        outbox.enqueueAddItems(p, listOf(add(position = "a0")))
        outbox.enqueueMoveItems(p, listOf(MoveItemsPayload.Entry(moved, "a1")))
        outbox.enqueueRemoveItems(p, listOf(removed))
        outbox.enqueueRemoveItems(other, listOf(UUID.randomUUID()))
        server.enqueue(itemsResponse())
        server.enqueue(itemsResponse())

        outbox.flush()

        val first = takeRequest()
        assertEquals("/api/playlists/$p/items", first.url.encodedPath)
        val body = first.json()
        assertEquals(1, body.objects("add").size)
        assertEquals(listOf(moved.toString()), body.objects("move").map { it.getValue("itemId").jsonPrimitive.content })
        assertEquals(listOf(removed.toString()), body.strings("remove"))
        assertEquals("/api/playlists/$other/items", takeRequest().url.encodedPath)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aMoveOfAnItemAddedInTheSameBatchWaitsForItsRealId() = runTest {
        val p = playlist()
        val entry = add(position = "a0")
        library.insertLocalPlaylistItem(PlaylistItem(entry.tempItemId, p, entry.trackId, "a0", now))
        outbox.enqueueAddItems(p, listOf(entry))
        outbox.enqueueMoveItems(p, listOf(MoveItemsPayload.Entry(entry.tempItemId, "a1")))
        val confirmed = PlaylistItem(UUID.randomUUID(), p, entry.trackId, "a0", now)
        server.enqueue(itemsResponse(confirmed))
        server.enqueue(itemsResponse())

        outbox.flush()

        assertTrue(takeRequest().json().objects("move").isEmpty())
        val moves = takeRequest().json().objects("move")
        assertEquals(listOf(confirmed.id.toString()), moves.map { it.getValue("itemId").jsonPrimitive.content })
    }

    @Test
    fun aBatchStopsBeforeExceedingTheOperationCap() = runTest {
        val p = playlist()
        outbox.enqueueRemoveItems(p, List(200) { UUID.randomUUID() })
        outbox.enqueueRemoveItems(p, List(51) { UUID.randomUUID() })
        server.enqueue(itemsResponse())
        server.enqueue(itemsResponse())

        outbox.flush()

        assertEquals(200, takeRequest().json().strings("remove").size)
        assertEquals(51, takeRequest().json().strings("remove").size)
    }

    @Test
    fun repeatedMovesOfOneItemSendOnlyTheLast() = runTest {
        val p = playlist()
        val item = UUID.randomUUID()
        outbox.enqueueMoveItems(p, listOf(MoveItemsPayload.Entry(item, "a1")))
        outbox.enqueueMoveItems(p, listOf(MoveItemsPayload.Entry(item, "a2")))
        server.enqueue(itemsResponse())

        outbox.flush()

        val moves = takeRequest().json().objects("move")
        assertEquals(listOf("a2"), moves.map { it.getValue("position").jsonPrimitive.content })
    }

    // 422 recovery

    @Test
    fun unknownTracksAreDroppedAndTheRestRetried() = runTest {
        val p = playlist()
        val bad = UUID.randomUUID()
        val good = UUID.randomUUID()
        outbox.enqueueAddItems(p, listOf(add(bad, "a0"), add(good, "a1")))
        server.enqueue(json("""{"error": "unknown_tracks", "trackIds": ["$bad"]}""", code = 422))
        server.enqueue(itemsResponse())

        outbox.flush()

        takeRequest()
        val retried = takeRequest().json().objects("add")
        assertEquals(listOf(good.toString()), retried.map { it.getValue("trackId").jsonPrimitive.content })
        assertTrue(library.pendingMutations().isEmpty())
    }

    @Test
    fun unknownTracksNoneOfOursMatchIsDiscarded() = runTest {
        val p = playlist()
        outbox.enqueueAddItems(p, listOf(add()))
        server.enqueue(json("""{"error": "unknown_tracks", "trackIds": ["${UUID.randomUUID()}"]}""", code = 422))

        outbox.flush()

        assertEquals(1, server.requestCount)
        assertEquals(UiText.Res(R.string.outbox_unknown_tracks), outbox.lastError.value)
        assertTrue(library.pendingMutations().isEmpty())
    }

    @Test
    fun aMoveThatCollidesWithARemoveIsDropped() = runTest {
        val p = playlist()
        val item = UUID.randomUUID()
        outbox.enqueueMoveItems(p, listOf(MoveItemsPayload.Entry(item, "a1")))
        outbox.enqueueRemoveItems(p, listOf(item))
        server.enqueue(json("""{"error": "move_remove_conflict", "itemIds": ["$item"]}""", code = 422))
        server.enqueue(itemsResponse())

        outbox.flush()

        takeRequest()
        val retried = takeRequest().json()
        assertTrue(retried.objects("move").isEmpty())
        assertEquals(listOf(item.toString()), retried.strings("remove"))
    }

    @Test
    fun filterRetriesAreBounded() = runTest {
        val p = playlist()
        val tracks = List(4) { UUID.randomUUID() }
        outbox.enqueueAddItems(p, tracks.mapIndexed { i, t -> add(t, "a$i") })
        tracks.take(3).forEach { server.enqueue(json("""{"error": "unknown_tracks", "trackIds": ["$it"]}""", code = 422)) }

        outbox.flush()

        assertEquals(3, server.requestCount)
        assertEquals(UiText.Res(R.string.outbox_filter_retries_exhausted), outbox.lastError.value)
    }

    @Test
    fun anUnrecognised422IsDiscarded() = runTest {
        val p = playlist()
        outbox.enqueueRemoveItems(p, listOf(UUID.randomUUID()))
        server.enqueue(json("""{"error": "something_new"}""", code = 422))

        outbox.flush()

        assertEquals(UiText.Res(R.string.outbox_items_rejected), outbox.lastError.value)
        assertTrue(library.pendingMutations().isEmpty())
    }

    @Test
    fun aPureRemoveOnAMissingPlaylistSucceeds() = runTest {
        val p = playlist()
        outbox.enqueueRemoveItems(p, listOf(UUID.randomUUID()))
        server.enqueue(json("""{"error": "not_found"}""", code = 404))

        outbox.flush()

        assertNull(outbox.lastError.value)
        assertTrue(library.pendingMutations().isEmpty())
    }

    // Failures

    @Test
    fun aServerErrorCountsAnAttemptAndStopsTheDrain() = runTest {
        val p = playlist()
        outbox.enqueueRenamePlaylist(p, "Renamed")
        outbox.enqueueRenamePlaylist(p, "Again")
        server.enqueue(json("{}", code = 500))

        outbox.flush()

        assertEquals(1, server.requestCount)
        val head = library.pendingMutations().first()
        assertEquals(1, head.attemptCount)
        assertNotNull(head.lastError)
        assertEquals(2, library.pendingMutations().size)
    }

    @Test
    fun anExpiredSessionAsksForReauthWithoutCountingAnAttempt() = runTest {
        val p = playlist()
        outbox.enqueueRenamePlaylist(p, "Renamed")
        server.enqueue(json("{}", code = 401))

        outbox.flush()

        assertEquals(1, unauthorizedCalls)
        val head = library.pendingMutations().single()
        assertEquals(0, head.attemptCount)
        assertNotNull(head.lastAttemptAt)
    }

    @Test
    fun aMutationThatKeepsFailingIsEventuallyDropped() = runTest {
        val p = playlist()
        outbox.enqueueRenamePlaylist(p, "Renamed")
        val head = library.pendingMutations().single()
        library.updatePendingMutations(listOf(head.copy(attemptCount = 19)))
        server.enqueue(json("{}", code = 500))

        outbox.flush()

        assertTrue(library.pendingMutations().isEmpty())
        assertTrue((outbox.lastError.value as UiText.Res).id == R.string.outbox_gave_up)
    }

    @Test
    fun overlappingFlushesSendEachMutationOnce() = runTest {
        val p = playlist()
        outbox.enqueueRenamePlaylist(p, "Renamed")
        server.enqueue(MockResponse.Builder().code(200).body(playlistJson(p)).headersDelay(200, TimeUnit.MILLISECONDS).build())

        withContext(Dispatchers.Default) { List(3) { async { outbox.flush() } }.awaitAll() }

        assertEquals(1, server.requestCount)
        assertTrue(library.pendingMutations().isEmpty())
    }

    @Test
    fun nothingIsSentWhileSignedOut() = runTest {
        val signedOut = OutboxService(library, { null }, CoroutineScope(Job().apply { cancel() }), {})
        signedOut.enqueueDeletePlaylist(UUID.randomUUID())

        signedOut.flush()

        assertEquals(0, server.requestCount)
        assertEquals(1, library.pendingMutations().size)
    }

    // Plays

    @Test
    fun playsAtTheHeadGoInOneRequestWithStableIds() = runTest {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        outbox.enqueuePlay(first, now)
        outbox.enqueuePlay(second, now.plusSeconds(300))
        outbox.enqueueDeletePlaylist(UUID.randomUUID())
        val queuedIds = library.pendingMutations().take(2).map { RecordPlayPayload.serializer().decodeOrNull(it.payload)!!.id.toString() }
        server.enqueue(json("""{"ok": true}"""))
        server.enqueue(json(playlistJson(UUID.randomUUID())))

        outbox.flush()

        val request = takeRequest()
        assertEquals("/api/plays", request.url.encodedPath)
        val plays = request.json().objects("plays")
        assertEquals(listOf(first.toString(), second.toString()), plays.map { it.getValue("trackId").jsonPrimitive.content })
        assertEquals(queuedIds, plays.map { it.getValue("id").jsonPrimitive.content })
        assertEquals("2026-10-01T12:00:00Z", plays[0].getValue("playedAt").jsonPrimitive.content)
        assertEquals("DELETE", takeRequest().method)
        assertTrue(library.pendingMutations().isEmpty())
    }

    @Test
    fun aPlayThatFailsToSendStaysQueued() = runTest {
        outbox.enqueuePlay(UUID.randomUUID(), now)
        server.enqueue(json("{}", code = 503))

        outbox.flush()

        assertEquals(1, library.pendingMutations().single().attemptCount)
    }

    // Compaction

    @Test
    fun deletingAPlaylistStillWaitingToBeCreatedIsAbsorbed() = runTest {
        val temp = playlist()
        outbox.enqueueCreatePlaylist(temp, "Mix")

        assertTrue(outbox.compactForDeletedPlaylist(temp))
        assertTrue(library.pendingMutations().isEmpty())
    }

    @Test
    fun removingAnItemStillWaitingToBeAddedNeedsNoRequest() = runTest {
        val p = playlist()
        val pendingItem = UUID.randomUUID()
        val synced = UUID.randomUUID()
        outbox.enqueueAddItems(p, listOf(add(tempItemId = pendingItem)))

        assertEquals(listOf(synced), outbox.compactForRemovedItems(listOf(pendingItem, synced)))
        assertFalse(library.pendingMutations().any { it.kind == OutboxKind.AddItems.raw })
    }
}
