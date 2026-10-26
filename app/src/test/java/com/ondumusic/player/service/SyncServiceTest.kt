package com.ondumusic.player.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ondumusic.player.R
import com.ondumusic.player.api.ApiClient
import com.ondumusic.player.api.SUPPORTED_API_VERSION
import com.ondumusic.player.data.LibraryDatabase
import com.ondumusic.player.data.MusicLibrary
import com.ondumusic.player.model.ServerCompatibility
import com.ondumusic.player.settings.SettingsStore
import com.ondumusic.player.util.UiText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
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

@RunWith(RobolectricTestRunner::class)
class SyncServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("sync-test", Context.MODE_PRIVATE)
    private val settings = SettingsStore(context.getSharedPreferences("sync-settings-test", Context.MODE_PRIVATE))
    private lateinit var db: LibraryDatabase
    private lateinit var library: MusicLibrary
    private val server = MockWebServer()

    /** Sync pages served in order, keyed by the cursor the client sends. */
    private val pages = mutableMapOf<String?, String>()
    private var info = """{"version": "1.0.0", "apiVersion": $SUPPORTED_API_VERSION}"""
    private var syncStatus = 200

    private val artistId = "11111111-1111-1111-1111-111111111111"
    private val albumId = "22222222-2222-2222-2222-222222222222"

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        library = MusicLibrary(db, File(context.cacheDir, "sync-${UUID.randomUUID()}"))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/info" -> json(info)
                "/api/sync" -> pages[request.url.queryParameter("cursor")]
                    ?.let { MockResponse.Builder().code(syncStatus).body(it).build() }
                    ?: MockResponse.Builder().code(500).build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private fun json(body: String) = MockResponse.Builder().body(body).addHeader("Content-Type", "application/json").build()

    private fun TestScope.service(hooks: SyncService.Hooks = SyncService.Hooks()) =
        SyncService(ApiClient(server.url("/api"), "tok", OkHttpClient()), library, settings, prefs, this, hooks)

    private fun track(id: String, name: String) = """
        {"id": "$id", "artistId": "$artistId", "albumArtistId": "$artistId", "albumId": "$albumId",
         "name": "$name", "duration": 1.0, "mimetype": "audio/flac", "streamUrl": "/s", "downloadUrl": "/d",
         "insertedAt": "2025-01-01T00:00:00Z"}
    """

    private fun page(cursor: String, complete: Boolean, tracks: List<String> = emptyList(), withArtist: Boolean = false,
                     deletions: String = "[]", notices: String = "[]") = """
        {"artists": ${if (withArtist) """[{"id": "$artistId", "name": "Radiohead", "slug": "radiohead", "albumCount": 1, "insertedAt": "2025-01-01T00:00:00Z"}]""" else "[]"},
         "albums": ${if (withArtist) """[{"id": "$albumId", "artistId": "$artistId", "name": "OK Computer", "slug": "ok", "isUnknown": false, "trackCount": 2, "insertedAt": "2025-01-01T00:00:00Z"}]""" else "[]"},
         "tracks": [${tracks.joinToString(",")}], "deletions": $deletions, "notices": $notices,
         "cursor": "$cursor", "complete": $complete}
    """

    private val t1 = "33333333-3333-3333-3333-333333333331"
    private val t2 = "33333333-3333-3333-3333-333333333332"

    @Test
    fun initialStateHasNoSync() = runTest {
        val sync = service()
        assertFalse(sync.hasCompletedInitialSync.value)
        assertNull(sync.lastSyncDate.value)
        assertNull(sync.progress.value)
    }

    @Test
    fun pagesUntilCompleteAndStoresTheCursor() = runTest {
        pages[null] = page("c1", complete = false, tracks = listOf(track(t1, "Airbag")), withArtist = true)
        pages["c1"] = page("c2", complete = true, tracks = listOf(track(t2, "Paranoid Android")))
        val sync = service()

        sync.sync()

        assertTrue(sync.hasCompletedInitialSync.value)
        assertNotNull(sync.lastSyncDate.value)
        assertNull(sync.error.value)
        assertEquals(listOf("Airbag", "Paranoid Android"), library.allTracks().map { it.name })
        assertTrue(service().hasCompletedInitialSync.value)
    }

    @Test
    fun deltaSyncSendsTheCursorAndKeepsTheLibrary() = runTest {
        pages[null] = page("c1", complete = true, tracks = listOf(track(t1, "Airbag")), withArtist = true)
        service().sync()
        pages["c1"] = page("c2", complete = true, tracks = listOf(track(t2, "Paranoid Android")))

        service().sync()

        assertEquals(2, library.allTracks().size)
    }

    @Test
    fun initialSyncClearsTheLibrary() = runTest {
        pages[null] = page("c1", complete = true, tracks = listOf(track(t1, "Airbag"), track(t2, "Lucky")), withArtist = true)
        service().sync()
        SyncService.clearState(prefs)
        pages[null] = page("c1", complete = true, tracks = listOf(track(t1, "Airbag")), withArtist = true)

        service().sync()

        assertEquals(listOf("Airbag"), library.allTracks().map { it.name })
    }

    @Test
    fun appliesDeletions() = runTest {
        pages[null] = page("c1", complete = true, tracks = listOf(track(t1, "Airbag"), track(t2, "Lucky")), withArtist = true)
        service().sync()
        pages["c1"] = page("c2", complete = true, deletions = """[{"entityType": "track", "entityId": "$t2"}]""")

        service().sync()

        assertEquals(listOf("Airbag"), library.allTracks().map { it.name })
    }

    @Test
    fun syncSetsAnErrorOnFailure() = runTest {
        pages[null] = "not json"
        val sync = service()
        sync.sync()
        assertEquals(UiText.Res(R.string.error_unreadable_response), sync.error.value)
        assertFalse(sync.hasCompletedInitialSync.value)
        assertFalse(sync.isSyncing.value)
    }

    @Test
    fun anUnauthorizedSyncAsksForSignInAndStopsSyncing() = runTest {
        pages[null] = "{}"
        syncStatus = 401
        var unauthorized = 0
        val sync = service(SyncService.Hooks(onUnauthorized = { unauthorized++ }))

        sync.sync()
        sync.sync()

        assertEquals(2, unauthorized)
        assertEquals(1, server.requestCount - 1) // one /info, one /sync
        assertNull(sync.error.value)
    }

    @Test
    fun syncIfNeededRespectsTheCooldown() = runTest {
        pages[null] = page("c1", complete = true)
        val sync = service()
        sync.sync()
        val before = server.requestCount

        sync.syncIfNeeded()

        assertEquals(before, server.requestCount)
    }

    @Test
    fun syncIfNeededRunsAfterTheCooldown() = runTest {
        pages[null] = page("c1", complete = true)
        pages["c1"] = page("c2", complete = true)
        prefs.edit().putString("syncCursor", "c1").putLong("lastSyncDate", Instant.now().minusSeconds(600).toEpochMilli()).commit()
        val sync = service()

        sync.syncIfNeeded()

        assertTrue(server.requestCount > 0)
        assertTrue(sync.lastSyncDate.value!! > Instant.now().minusSeconds(5))
    }

    @Test
    fun noticesAreReplacedByEachSync() = runTest {
        pages[null] = page("c1", complete = true, notices = """[{"id": "$t1", "code": "library_unavailable", "level": "warning", "message": "gone", "raisedAt": "2025-01-01T00:00:00Z"}]""")
        val sync = service()
        sync.sync()
        assertEquals(1, sync.notices.value.size)

        pages["c1"] = page("c2", complete = true)
        sync.sync()
        assertTrue(sync.notices.value.isEmpty())
    }

    @Test
    fun checksCompatibilityOnce() = runTest {
        info = """{"version": "2.0.0", "apiVersion": ${SUPPORTED_API_VERSION + 1}}"""
        pages[null] = page("c1", complete = true)
        pages["c1"] = page("c2", complete = true)
        val sync = service()

        sync.sync()
        sync.sync()

        assertEquals(ServerCompatibility.AppTooOld("2.0.0"), sync.compatibility.value)
        assertEquals("2.0.0", sync.serverVersion.value)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun newTracksAutoDownloadOnlyOnDeltasAndWhenEnabled() = runTest {
        settings.autoDownloadNewTracks.value = true
        val queued = mutableListOf<Set<UUID>>()
        pages[null] = page("c1", complete = true, tracks = listOf(track(t1, "Airbag")), withArtist = true)
        service(SyncService.Hooks(downloadTracks = { queued += it })).sync()
        assertTrue(queued.isEmpty())

        pages["c1"] = page("c2", complete = true, tracks = listOf(track(t2, "Lucky")))
        service(SyncService.Hooks(downloadTracks = { queued += it })).sync()

        assertEquals(listOf(setOf(UUID.fromString(t2))), queued)
        settings.autoDownloadNewTracks.value = false
    }

    @Test
    fun reconcilesDownloadsAfterEachCompleteSyncSayingWhetherItWasInitial() = runTest {
        val reconciled = mutableListOf<Boolean>()
        val hooks = SyncService.Hooks(reconcileDownloads = { reconciled += it })
        pages[null] = page("c1", complete = true, tracks = listOf(track(t1, "Airbag")), withArtist = true)
        service(hooks).sync()
        pages["c1"] = page("c2", complete = false)
        pages["c2"] = page("c3", complete = true)
        service(hooks).sync()

        assertEquals(listOf(true, false), reconciled)
    }

    @Test
    fun anIncompleteSyncLeavesDownloadsAlone() = runTest {
        val reconciled = mutableListOf<Boolean>()
        pages[null] = page("c1", complete = false, withArtist = true)
        service(SyncService.Hooks(reconcileDownloads = { reconciled += it })).sync()

        assertTrue(reconciled.isEmpty())
    }

    @Test
    fun flushesTheOutboxBeforePulling() = runTest {
        pages[null] = page("c1", complete = true)
        val order = mutableListOf<String>()
        val sync = service(SyncService.Hooks(flushOutbox = { order += "flush:${server.requestCount}" }))

        sync.sync()

        assertEquals(listOf("flush:1"), order) // after /info, before /sync
    }

    @Test
    fun theActivityIndicatorClearsAfterTheFloor() = runTest {
        pages[null] = page("c1", complete = true)
        val sync = service()
        sync.sync()
        assertFalse(sync.isShowingActivity.first { !it })
    }
}
