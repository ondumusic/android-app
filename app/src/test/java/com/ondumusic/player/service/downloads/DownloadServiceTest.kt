package com.ondumusic.player.service.downloads

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ondumusic.player.R
import com.ondumusic.player.api.ApiClient
import com.ondumusic.player.api.SyncAlbum
import com.ondumusic.player.api.SyncArtist
import com.ondumusic.player.api.SyncTrack
import com.ondumusic.player.data.LibraryDatabase
import com.ondumusic.player.data.MusicLibrary
import com.ondumusic.player.data.downloadQueue
import com.ondumusic.player.data.downloadRelativePath
import com.ondumusic.player.data.downloadedTrackIds
import com.ondumusic.player.data.markDownloaded
import com.ondumusic.player.model.Track
import com.ondumusic.player.settings.DownloadQuality
import com.ondumusic.player.settings.SettingsStore
import com.ondumusic.player.util.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Ports `DownloadServiceTests.swift` and adds what iOS leaves to URLSession: the HTTP
 * side, against a real server. Transfers run on real threads, so these use runBlocking
 * and wait for conditions rather than virtual time.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("downloads-test", Context.MODE_PRIVATE)
    private lateinit var settings: SettingsStore
    private lateinit var db: LibraryDatabase
    private lateinit var library: MusicLibrary
    private lateinit var filesDir: File
    private val server = MockWebServer()
    private val scopes = mutableListOf<CoroutineScope>()

    private val now = Instant.parse("2026-10-01T12:00:00Z")
    private val artistId = UUID.randomUUID()
    private val albumId = UUID.randomUUID()

    /** Requests wait here until the test ends, so downloads stay in flight. */
    private val gate = CountDownLatch(1)
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile private var respond: (RecordedRequest) -> MockResponse = { held() }

    private val api = MutableStateFlow<ApiClient?>(null)
    private val online = MutableStateFlow(true)
    private val conditions = FakeConditions()
    private var unauthorizedCount = 0
    private var startedCount = 0

    private class FakeConditions : DownloadConditions {
        @Volatile var free = Long.MAX_VALUE
        override fun freeBytes() = free
        override val isPowerSaveMode = MutableStateFlow(false)
    }

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        settings = SettingsStore(prefs)
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        filesDir = File(context.cacheDir, "downloads-${UUID.randomUUID()}").apply { mkdirs() }
        library = MusicLibrary(db, filesDir)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return respond(request)
            }
        }
        server.start()
        api.value = client()
    }

    @After
    fun tearDown() {
        gate.countDown()
        scopes.forEach { it.cancel() }
        // Closing the server unblocks transfers stuck reading a response. The services have to
        // be gone before the database closes: one still mid-query would throw, and the
        // exception surfaces in whichever coroutine test happens to run next.
        server.close()
        runBlocking { scopes.forEach { it.coroutineContext.job.join() } }
        db.close()
        filesDir.deleteRecursively()
    }

    private fun client(token: String = "tok") = ApiClient(server.url("/api"), token, OkHttpClient())

    private fun service(): DownloadService {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return DownloadService(
            library, settings, OkHttpClient(), api, online, conditions, scope,
            DownloadService.Hooks(onUnauthorized = { unauthorizedCount++ }, onStarted = { startedCount++ }),
            DownloadService.Timing(progressIntervalMillis = 20, busyRetryDelayMillis = 10, diskCheckIntervalMillis = 0),
        )
    }

    // Fixtures

    private suspend fun seed(count: Int, mimetype: String = "audio/flac", album: UUID = albumId): List<Track> {
        library.upsertArtists(listOf(SyncArtist(artistId, "Test Artist", null, "test-artist", albumCount = 1, insertedAt = now)))
        library.upsertAlbums(listOf(SyncAlbum(album, artistId, "Test Album", null, "test-album", 2000, null, emptyList(), false, count, now)))
        val tracks = (1..count).map {
            val id = UUID.randomUUID()
            SyncTrack(id, artistId, artistId, album, "Track $it", 1, it, 200.0, mimetype, "/api/tracks/$id/stream", "/api/tracks/$id/download", now)
        }
        library.upsertTracks(tracks)
        val byId = library.tracks(tracks.map { it.id })
        return tracks.map { byId.getValue(it.id) }
    }

    private suspend fun seedOne(mimetype: String = "audio/flac"): Track = seed(1, mimetype).single()

    private fun held(): MockResponse {
        gate.await(10, TimeUnit.SECONDS)
        return MockResponse.Builder().code(500).build()
    }

    private val audio = ByteArray(4096) { (it % 251).toByte() }

    private fun audioResponse(bytes: ByteArray = audio, type: String = "audio/flac", code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", type).body(Buffer().write(bytes)).build()

    /** A minimal M4A: `ftyp`, `moov`, and an `mdat` big enough to pass the size check. */
    private fun m4a(withMoov: Boolean = true): ByteArray {
        fun atom(type: String, payload: Int): ByteArray {
            val size = 8 + payload
            return byteArrayOf((size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()) +
                type.toByteArray(Charsets.US_ASCII) + ByteArray(payload)
        }
        return atom("ftyp", 8) + (if (withMoov) atom("moov", 16) else ByteArray(0)) + atom("mdat", 4000)
    }

    private fun isDownload(request: RecordedRequest) = request.url.encodedPath.endsWith("/download")

    private suspend fun awaitUntil(message: String = "condition", condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $message")
            delay(10)
        }
    }

    private fun DownloadService.queuedIds() = state.value.queued.map { it.trackId }
    private fun DownloadService.activeIds() = state.value.active.map { it.trackId }

    // Initial state

    @Test
    fun initialStateIsEmpty() {
        val service = service()
        val state = service.state.value
        assertTrue(state.active.isEmpty())
        assertTrue(state.queued.isEmpty())
        assertFalse(state.isPaused)
        assertTrue(service.progress.value.isEmpty())
        assertTrue(state.failed.isEmpty())
        assertFalse(state.hasActivity)
    }

    // Enqueueing

    @Test
    fun downloadAddsToActiveState() = runBlocking {
        val service = service()
        val track = seedOne()

        service.download(track)

        assertTrue(service.state.value.isDownloading(track.id))
        assertEquals(listOf(track.id), service.activeIds())
        assertTrue(service.state.value.hasActivity)
        assertEquals(1, startedCount)
    }

    @Test
    fun downloadSetsCorrectMetadata() = runBlocking {
        val service = service()
        val track = seedOne()

        service.download(track)

        val active = service.state.value.active.single()
        assertEquals("Track 1", active.trackName)
        assertEquals("Test Artist", active.artistName)
        assertEquals(albumId, active.albumId)
    }

    @Test
    fun duplicateDownloadIsIgnored() = runBlocking {
        val service = service()
        val track = seedOne()

        service.download(track)
        service.download(track)

        assertEquals(1, service.activeIds().count { it == track.id })
        assertEquals(1, library.downloadQueue().size)
    }

    @Test
    fun alreadyDownloadedTrackIsIgnored() = runBlocking {
        val track = seedOne()
        library.markDownloaded(track.id, "Downloads/test.flac", 1)
        val service = service()

        // The caller's Track predates the download; the library is what counts.
        service.download(track)

        assertFalse(service.state.value.isDownloading(track.id))
        assertTrue(service.state.value.active.isEmpty())
    }

    @Test
    fun downloadAlbumEnqueuesAllTracks() = runBlocking {
        val service = service()
        val tracks = seed(5)

        service.downloadAlbum(albumId)

        // With the default of three at a time, three run and two wait.
        val state = service.state.value
        assertEquals(3, state.active.size)
        assertEquals(2, state.queued.size)
        tracks.forEach { assertTrue(state.isDownloading(it.id) || state.isQueued(it.id)) }
    }

    @Test
    fun downloadAlbumWithNoTracksIsNoop() = runBlocking {
        val service = service()

        service.downloadAlbum(UUID.randomUUID())

        assertFalse(service.state.value.hasActivity)
    }

    @Test
    fun downloadTracksSkipsUnknownIds() = runBlocking {
        val service = service()
        val track = seedOne()

        service.downloadTracks(setOf(track.id, UUID.randomUUID()))

        assertEquals(listOf(track.id), service.activeIds())
    }

    @Test
    fun concurrencyLimitRespected() = runBlocking {
        settings.maxConcurrentDownloads.value = 2
        val service = service()

        service.download(seed(5))

        assertEquals(2, service.state.value.active.size)
        assertEquals(3, service.state.value.queued.size)
    }

    @Test
    fun raisingTheLimitStartsMoreAtOnce() = runBlocking {
        settings.maxConcurrentDownloads.value = 1
        val service = service()
        service.download(seed(3))

        settings.maxConcurrentDownloads.value = 3

        awaitUntil("all three to start") { service.state.value.active.size == 3 }
    }

    // Queries

    @Test
    fun isDownloadingReturnsFalseForUnknownTrack() {
        assertFalse(service().state.value.isDownloading(UUID.randomUUID()))
    }

    @Test
    fun isQueuedReturnsTrueForPendingTrack() = runBlocking {
        settings.maxConcurrentDownloads.value = 1
        val service = service()
        val (first, second) = seed(2)

        service.download(first)
        service.download(second)

        assertTrue(service.state.value.isDownloading(first.id))
        assertFalse(service.state.value.isDownloading(second.id))
        assertTrue(service.state.value.isQueued(second.id))
    }

    @Test
    fun isAlbumDownloadingDetectsActiveAndQueued() = runBlocking {
        val service = service()
        val track = seedOne()
        assertFalse(service.state.value.isAlbumDownloading(albumId))

        service.download(track)

        assertTrue(service.state.value.isAlbumDownloading(albumId))
    }

    @Test
    fun hasActivityReflectsState() = runBlocking {
        val service = service()
        assertFalse(service.state.value.hasActivity)

        service.download(seedOne())

        assertTrue(service.state.value.hasActivity)
    }

    // Cancel

    @Test
    fun cancelActiveDownloadCleansUp() = runBlocking {
        val service = service()
        val track = seedOne()
        service.download(track)
        assertTrue(service.state.value.isDownloading(track.id))

        service.cancel(track.id)

        assertFalse(service.state.value.isDownloading(track.id))
        assertFalse(service.state.value.hasActivity)
        assertTrue(library.downloadQueue().isEmpty())
    }

    @Test
    fun cancelQueuedDownloadRemovesFromPending() = runBlocking {
        settings.maxConcurrentDownloads.value = 1
        val service = service()
        val (first, second) = seed(2)
        service.download(listOf(first, second))
        assertTrue(service.state.value.isQueued(second.id))

        service.cancel(second.id)

        assertFalse(service.state.value.isQueued(second.id))
    }

    @Test
    fun cancelPromotesNextFromQueue() = runBlocking {
        settings.maxConcurrentDownloads.value = 1
        val service = service()
        val (first, second) = seed(2)
        service.download(listOf(first, second))

        service.cancel(first.id)

        assertTrue(service.state.value.isDownloading(second.id))
        assertFalse(service.state.value.isQueued(second.id))
    }

    @Test
    fun cancelAllClearsEverything() = runBlocking {
        val service = service()
        service.download(seed(5))
        assertTrue(service.state.value.hasActivity)

        service.cancelAll()

        val state = service.state.value
        assertTrue(state.active.isEmpty())
        assertTrue(state.queued.isEmpty())
        assertFalse(state.isPaused)
        assertFalse(state.hasActivity)
        assertTrue(library.downloadQueue().isEmpty())
    }

    @Test
    fun cancelNonexistentTrackIsNoop() = runBlocking {
        val service = service()
        val track = seedOne()
        service.download(track)

        service.cancel(UUID.randomUUID())

        assertTrue(service.state.value.isDownloading(track.id))
    }

    @Test
    fun cancelAlbumDownloadsLeavesOtherAlbums() = runBlocking {
        val service = service()
        val here = seed(2)
        val otherAlbum = UUID.randomUUID()
        val there = seed(1, album = otherAlbum)
        service.download(here + there)

        service.cancelAlbum(albumId)

        assertEquals(there.map { it.id }, service.activeIds())
        assertFalse(service.state.value.isAlbumDownloading(albumId))
    }

    // Pause / resume

    @Test
    fun pauseActiveDownloadSetsState() = runBlocking {
        val service = service()
        val track = seedOne()
        service.download(track)

        service.pause(track.id)

        assertTrue(service.state.value.active.single().isPaused)
        assertTrue(library.downloadQueue().single().paused)
    }

    @Test
    fun resumePausedDownloadReactivates() = runBlocking {
        val service = service()
        val track = seedOne()
        service.download(track)
        service.pause(track.id)

        service.resume(track.id)

        assertTrue(service.state.value.isDownloading(track.id))
        assertFalse(service.state.value.active.single().isPaused)
        assertFalse(library.downloadQueue().single().paused)
    }

    @Test
    fun pauseAllSetsFlag() = runBlocking {
        val service = service()
        service.download(seedOne())

        service.pauseAll()

        assertTrue(service.state.value.isPaused)
        assertTrue(service.state.value.active.all { it.isPaused })
    }

    @Test
    fun resumeAllClearsFlag() = runBlocking {
        val service = service()
        service.download(seedOne())
        service.pauseAll()

        service.resumeAll()

        assertFalse(service.state.value.isPaused)
        assertEquals(1, service.state.value.transferringCount)
    }

    @Test
    fun pauseAllBlocksNewDownloads() = runBlocking {
        settings.maxConcurrentDownloads.value = 1
        val service = service()
        val (first, second) = seed(2)
        service.download(first)

        service.pauseAll()
        service.download(second)

        assertTrue(service.state.value.isQueued(second.id))
    }

    @Test
    fun pauseNonexistentTrackIsNoop() = runBlocking {
        val service = service()
        service.download(seedOne())

        service.pause(UUID.randomUUID())

        assertFalse(service.state.value.active.single().isPaused)
    }

    @Test
    fun cancelPausedDownloadCleansUp() = runBlocking {
        val service = service()
        val track = seedOne()
        service.download(track)
        service.pause(track.id)

        service.cancel(track.id)

        assertFalse(service.state.value.isDownloading(track.id))
        assertFalse(service.state.value.hasActivity)
    }

    /** Matches iOS: pausing frees the slot but doesn't fill it — the next action does. */
    @Test
    fun pauseDoesNotPromoteTheNextDownload() = runBlocking {
        settings.maxConcurrentDownloads.value = 1
        val service = service()
        val (first, second) = seed(2)
        service.download(listOf(first, second))

        service.pause(first.id)

        assertTrue(service.state.value.isQueued(second.id))
    }

    // Failures

    @Test
    fun aServerErrorFailsTheDownloadWithItsStatus() = runBlocking {
        respond = { MockResponse.Builder().code(404).build() }
        val service = service()
        val track = seedOne()

        service.download(track)

        awaitUntil("the failure") { service.state.value.failed.isNotEmpty() }
        val failure = service.state.value.failed.single()
        assertEquals(track.id, failure.trackId)
        assertEquals(UiText.Res(R.string.download_error_http, listOf(404)), failure.reason)
        assertTrue(service.state.value.active.isEmpty())
        assertTrue(library.downloadQueue().isEmpty())
        assertTrue(service.state.value.hasActivity)
    }

    @Test
    fun retryRequeuesAFailedDownload() = runBlocking {
        respond = { MockResponse.Builder().code(500).build() }
        val service = service()
        val track = seedOne()
        service.download(track)
        awaitUntil("the failure") { service.state.value.failed.isNotEmpty() }

        respond = { audioResponse() }
        service.retry(track.id)

        assertTrue(service.state.value.failed.isEmpty())
        awaitUntil("the retry to finish") { track.id in library.downloadedTrackIds() }
    }

    @Test
    fun dismissAndClearRemoveFailures() = runBlocking {
        respond = { MockResponse.Builder().code(500).build() }
        val service = service()
        val (first, second, third) = seed(3)
        service.download(listOf(first, second, third))
        awaitUntil("three failures") { service.state.value.failed.size == 3 }

        service.dismissFailure(first.id)
        assertEquals(listOf(second.id, third.id).toSet(), service.state.value.failed.map { it.trackId }.toSet())

        service.clearFailures()
        assertTrue(service.state.value.failed.isEmpty())
    }

    // Order

    @Test
    fun pendingQueuePreservesInsertionOrder() = runBlocking {
        settings.maxConcurrentDownloads.value = 1
        val service = service()
        val tracks = seed(4)
        tracks.forEach { service.download(it) }

        assertEquals(tracks.drop(1).map { it.id }, service.queuedIds())
    }

    @Test
    fun cancelRemovesFromPendingQueue() = runBlocking {
        settings.maxConcurrentDownloads.value = 1
        val service = service()
        val tracks = seed(4)
        tracks.forEach { service.download(it) }

        service.cancel(tracks[2].id)

        assertEquals(listOf(tracks[1].id, tracks[3].id), service.queuedIds())
    }

    // Pause conditions

    /** The override lasts the session and isn't persisted, so a relaunch defers to the system setting again. */
    @Test
    fun batterySaverOverrideDefaultsOff() {
        assertFalse(service().state.value.ignoresBatterySaver)
    }

    @Test
    fun batterySaverHoldsTheQueueUntilOverridden() = runBlocking {
        conditions.isPowerSaveMode.value = true
        val service = service()
        val track = seedOne()

        service.download(track)
        assertEquals(DownloadPausedReason.BatterySaver, service.state.value.pausedReason)
        assertTrue(service.state.value.isQueued(track.id))

        service.ignoreBatterySaver()

        assertTrue(service.state.value.ignoresBatterySaver)
        assertNull(service.state.value.pausedReason)
        assertTrue(service.state.value.isDownloading(track.id))
    }

    @Test
    fun turningBatterySaverOffStartsTheQueue() = runBlocking {
        conditions.isPowerSaveMode.value = true
        val service = service()
        val track = seedOne()
        service.download(track)

        conditions.isPowerSaveMode.value = false

        awaitUntil("the download to start") { service.state.value.isDownloading(track.id) }
        assertNull(service.state.value.pausedReason)
    }

    /** Taking the override re-evaluates the conditions, so the storage guard still has its say. */
    @Test
    fun lowStorageCannotBeOverridden() = runBlocking {
        conditions.free = 50L * 1024 * 1024
        val service = service()
        service.download(seedOne())
        assertEquals(DownloadPausedReason.LowStorage, service.state.value.pausedReason)

        service.ignoreBatterySaver()

        assertEquals(DownloadPausedReason.LowStorage, service.state.value.pausedReason)
        assertTrue(service.state.value.active.isEmpty())
    }

    // Transfers

    /** The same server under another name: to the app that's another host, which never gets the token. */
    @Test
    fun aDownloadUrlOnAnotherHostIsFetchedWithoutTheToken() = runBlocking {
        respond = { audioResponse() }
        val service = service()
        seed(0)
        val otherHost = if (server.hostName == "localhost") "127.0.0.1" else "localhost"
        val elsewhere = server.url("/elsewhere/download").newBuilder().host(otherHost).build()
        val id = UUID.randomUUID()
        library.upsertTracks(listOf(
            SyncTrack(id, artistId, artistId, albumId, "Track 1", 1, 1, 200.0, "audio/flac", "/api/tracks/$id/stream", elsewhere.toString(), now),
        ))

        service.download(library.tracks(listOf(id)).getValue(id))
        service.awaitIdle()

        assertTrue(id in library.downloadedTrackIds())
        val request = requests.single()
        assertEquals("/elsewhere/download", request.url.encodedPath)
        assertNull(request.headers["Authorization"])
    }

    @Test
    fun downloadsTheOriginalAndRecordsIt() = runBlocking {
        respond = { audioResponse() }
        val service = service()
        val track = seedOne()

        service.download(track)
        service.awaitIdle()

        val path = downloadRelativePath(artistId, albumId, track.id, "flac")
        assertArrayEquals(audio, File(filesDir, path).readBytes())
        val stored = db.tracks().get(track.id)!!.track
        assertTrue(stored.isDownloaded)
        assertEquals(path, stored.localFilePath)
        assertEquals(audio.size.toLong(), stored.downloadedFileSize)
        assertTrue(library.downloadQueue().isEmpty())
        assertFalse(service.state.value.hasActivity)
        assertTrue(service.progress.value.isEmpty())

        val request = requests.single()
        assertEquals("/api/tracks/${track.id}/download", request.url.encodedPath)
        assertEquals("Bearer tok", request.headers["Authorization"])
        assertNull(request.url.queryParameter("transcode"))
    }

    @Test
    fun aLowerQualityPreparesTheTranscodeFirst() = runBlocking {
        settings.downloadQuality.value = DownloadQuality.MobileAac
        val body = m4a()
        respond = { request ->
            if (request.url.encodedPath.endsWith("/transcode")) {
                MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
                    .body("event: progress\ndata: {\"percent\": 50}\n\nevent: ready\ndata: {\"size\": ${body.size}}\n\n").build()
            } else {
                audioResponse(body, "audio/mp4")
            }
        }
        val service = service()
        val track = seedOne()

        service.download(track)
        service.awaitIdle()

        val (prepare, download) = requests.toList()
        assertEquals("/api/tracks/${track.id}/transcode", prepare.url.encodedPath)
        assertEquals("mobile_aac", prepare.url.queryParameter("preset"))
        assertEquals("GET", prepare.method)
        assertEquals("1", download.url.queryParameter("transcode"))
        assertEquals("mobile_aac", download.url.queryParameter("preset"))
        assertEquals(downloadRelativePath(artistId, albumId, track.id, "m4a"), db.tracks().get(track.id)!!.track.localFilePath)
    }

    @Test
    fun aFormatExoPlayerCantPlayIsTranscodedEvenAtOriginalQuality() = runBlocking {
        respond = { request ->
            if (request.url.encodedPath.endsWith("/transcode")) {
                MockResponse.Builder().body("data: {\"size\": 1}\n\n").build()
            } else {
                audioResponse(m4a(), "audio/mp4")
            }
        }
        val service = service()
        val track = seedOne(mimetype = "audio/aiff")

        service.download(track)
        service.awaitIdle()

        assertEquals("default_aac", requests.first().url.queryParameter("preset"))
        assertTrue(track.id in library.downloadedTrackIds())
    }

    @Test
    fun aTranscodeThatNeverReportsASizeFails() = runBlocking {
        settings.downloadQuality.value = DownloadQuality.DefaultAac
        respond = { MockResponse.Builder().body("event: error\ndata: {\"error\": \"ffmpeg\"}\n\n").build() }
        val service = service()
        service.download(seedOne())

        awaitUntil("the failure") { service.state.value.failed.isNotEmpty() }
        assertEquals(UiText.Res(R.string.download_error_transcode), service.state.value.failed.single().reason)
        assertEquals("only the prepare was asked for", 1, requests.size)
    }

    @Test
    fun anM4aWithoutItsIndexIsRejected() = runBlocking {
        settings.downloadQuality.value = DownloadQuality.DefaultAac
        respond = { request ->
            if (request.url.encodedPath.endsWith("/transcode")) MockResponse.Builder().body("data: {\"size\": 1}\n").build()
            else audioResponse(m4a(withMoov = false), "audio/mp4")
        }
        val service = service()
        val track = seedOne()
        service.download(track)

        awaitUntil("the failure") { service.state.value.failed.isNotEmpty() }
        assertEquals(UiText.Res(R.string.download_error_save), service.state.value.failed.single().reason)
        assertTrue(library.downloadedTrackIds().isEmpty())
        assertFalse(File(filesDir, MusicLibrary.DOWNLOADS_DIR).walkTopDown().any { it.isFile })
    }

    @Test
    fun aTinyResponseIsNotTakenForAudio() = runBlocking {
        respond = { audioResponse(ByteArray(100)) }
        val service = service()
        service.download(seedOne())

        awaitUntil("the failure") { service.state.value.failed.isNotEmpty() }
        assertEquals(UiText.Res(R.string.download_error_save), service.state.value.failed.single().reason)
    }

    @Test
    fun aPartialDownloadResumesWithARangeRequest() = runBlocking {
        val track = seedOne()
        val part = File(filesDir, downloadRelativePath(artistId, albumId, track.id, "original.part"))
        part.parentFile!!.mkdirs()
        part.writeBytes(audio.copyOfRange(0, 1000))
        respond = { request ->
            assertEquals("bytes=1000-", request.headers["Range"])
            MockResponse.Builder().code(206).addHeader("Content-Type", "audio/flac")
                .addHeader("Content-Range", "bytes 1000-${audio.size - 1}/${audio.size}")
                .body(Buffer().write(audio.copyOfRange(1000, audio.size))).build()
        }
        val service = service()

        service.download(track)
        service.awaitIdle()

        assertArrayEquals(audio, File(filesDir, downloadRelativePath(artistId, albumId, track.id, "flac")).readBytes())
        assertFalse(part.exists())
    }

    @Test
    fun aServerIgnoringRangeStartsTheFileOver() = runBlocking {
        val track = seedOne()
        val part = File(filesDir, downloadRelativePath(artistId, albumId, track.id, "original.part"))
        part.parentFile!!.mkdirs()
        part.writeBytes(ByteArray(1000) { 7 })
        respond = { audioResponse() }
        val service = service()

        service.download(track)
        service.awaitIdle()

        assertEquals("bytes=1000-", requests.single().headers["Range"])
        assertArrayEquals(audio, File(filesDir, downloadRelativePath(artistId, albumId, track.id, "flac")).readBytes())
    }

    @Test
    fun anUnsatisfiableRangeDiscardsThePartial() = runBlocking {
        val track = seedOne()
        val part = File(filesDir, downloadRelativePath(artistId, albumId, track.id, "original.part"))
        part.parentFile!!.mkdirs()
        part.writeBytes(ByteArray(9000))
        respond = { request ->
            if (request.headers["Range"] != null) MockResponse.Builder().code(416).build() else audioResponse()
        }
        val service = service()

        service.download(track)
        service.awaitIdle()

        assertEquals(listOf("bytes=9000-", null), requests.map { it.headers["Range"] })
        assertArrayEquals(audio, File(filesDir, downloadRelativePath(artistId, albumId, track.id, "flac")).readBytes())
    }

    @Test
    fun aPartialOfAnotherQualityIsNotSplicedIn() = runBlocking {
        val track = seedOne()
        val stale = File(filesDir, downloadRelativePath(artistId, albumId, track.id, "mobile_aac.part"))
        stale.parentFile!!.mkdirs()
        stale.writeBytes(ByteArray(1000))
        respond = { audioResponse() }
        val service = service()

        service.download(track)
        service.awaitIdle()

        assertNull(requests.single().headers["Range"])
    }

    @Test
    fun cancellingDeletesThePartialFile() = runBlocking {
        val track = seedOne()
        val part = File(filesDir, downloadRelativePath(artistId, albumId, track.id, "original.part"))
        part.parentFile!!.mkdirs()
        part.writeBytes(ByteArray(1000))
        val service = service()
        service.download(track)

        service.cancel(track.id)

        awaitUntil("the partial to go") { !part.exists() }
    }

    @Test
    fun pausingKeepsThePartialFile() = runBlocking {
        val track = seedOne()
        val part = File(filesDir, downloadRelativePath(artistId, albumId, track.id, "original.part"))
        part.parentFile!!.mkdirs()
        part.writeBytes(ByteArray(1000))
        val service = service()
        service.download(track)

        service.pause(track.id)
        delay(100)

        assertTrue(part.exists())
    }

    @Test
    fun aBusyServerIsRetriedAfterItsDelay() = runBlocking {
        var calls = 0
        respond = {
            if (++calls == 1) MockResponse.Builder().code(503).addHeader("Retry-After", "0").build() else audioResponse()
        }
        val service = service()
        val track = seedOne()

        service.download(track)
        awaitUntil("the download") { track.id in library.downloadedTrackIds() }

        assertEquals(2, calls)
        assertTrue(service.state.value.failed.isEmpty())
    }

    @Test
    fun aServerBusyFourTimesFails() = runBlocking {
        respond = { MockResponse.Builder().code(502).build() }
        val service = service()
        service.download(seedOne())

        awaitUntil("the failure") { service.state.value.failed.isNotEmpty() }
        assertEquals(4, requests.size)
        assertEquals(UiText.Res(R.string.download_error_http, listOf(502)), service.state.value.failed.single().reason)
    }

    @Test
    fun aDroppedConnectionIsRetried() = runBlocking {
        var calls = 0
        respond = {
            if (++calls == 1) MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build() else audioResponse()
        }
        val service = service()
        val track = seedOne()

        service.download(track)

        awaitUntil("the download") { track.id in library.downloadedTrackIds() }
    }

    @Test
    fun goingOfflineRequeuesInsteadOfFailing() = runBlocking {
        respond = { MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build() }
        online.value = false
        val service = service()
        val track = seedOne()

        service.download(track)
        awaitUntil("the requeue") { service.state.value.isQueued(track.id) }
        assertTrue(service.state.value.failed.isEmpty())
        assertEquals(1, library.downloadQueue().size)

        respond = { audioResponse() }
        online.value = true

        awaitUntil("the download") { track.id in library.downloadedTrackIds() }
    }

    @Test
    fun aRejectedSessionPausesUntilReauth() = runBlocking {
        respond = { MockResponse.Builder().code(401).build() }
        settings.maxConcurrentDownloads.value = 1
        val service = service()
        val (first, second) = seed(2)

        service.download(listOf(first, second))
        awaitUntil("the pause") { service.state.value.isPaused }

        assertEquals(1, unauthorizedCount)
        assertEquals(listOf(first.id, second.id), service.queuedIds())
        assertTrue(service.state.value.failed.isEmpty())

        respond = { audioResponse() }
        api.value = client("new-token")

        awaitUntil("both downloads") { library.downloadedTrackIds() == setOf(first.id, second.id) }
        assertFalse(service.state.value.isPaused)
        assertEquals("Bearer new-token", requests.last().headers["Authorization"])
    }

    @Test
    fun aRejectedTranscodePrepareAlsoPauses() = runBlocking {
        settings.downloadQuality.value = DownloadQuality.MobileAac
        respond = { MockResponse.Builder().code(401).build() }
        val service = service()
        service.download(seedOne())

        awaitUntil("the pause") { service.state.value.isPaused }
        assertEquals(1, unauthorizedCount)
    }

    @Test
    fun nothingStartsWhileSignedOut() = runBlocking {
        api.value = null
        val service = service()
        val track = seedOne()

        service.download(track)

        assertTrue(service.state.value.isQueued(track.id))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun progressIsSampledWhileTransferring() = runBlocking {
        val big = ByteArray(256 * 1024)
        respond = {
            MockResponse.Builder().addHeader("Content-Type", "audio/flac").body(Buffer().write(big))
                .throttleBody(16 * 1024, 20, TimeUnit.MILLISECONDS).build()
        }
        val service = service()
        val track = seedOne()

        service.download(track)

        awaitUntil("partial progress") { service.progress.value[track.id]?.let { it > 0f && it < 1f } == true }
        service.awaitIdle()
        assertTrue(service.progress.value.isEmpty())
    }

    // Persistence

    @Test
    fun theQueueSurvivesTheProcess() = runBlocking {
        settings.maxConcurrentDownloads.value = 1
        val tracks = seed(3)
        val before = service()
        before.download(tracks)
        before.pause(tracks[0].id)
        scopes.forEach { it.cancel() }

        val after = service()
        after.pauseAll()

        val state = after.state.value
        assertEquals(listOf(tracks[0].id), state.active.map { it.trackId })
        assertTrue(state.active.single().isPaused)
        assertEquals(listOf(tracks[1].id, tracks[2].id), state.queued.map { it.trackId })
    }

    @Test
    fun startRestoresAndRunsTheStoredQueue() = runBlocking {
        respond = { audioResponse() }
        val tracks = seed(2)
        api.value = null
        val before = service()
        before.download(tracks)
        scopes.forEach { it.cancel() }

        api.value = client()
        val after = service()
        after.start()

        awaitUntil("both downloads") { library.downloadedTrackIds() == tracks.map { it.id }.toSet() }
    }

    @Test
    fun resetCancelsAndForgetsEverything() = runBlocking {
        settings.maxConcurrentDownloads.value = 1
        val service = service()
        service.download(seed(3))

        service.reset()

        assertFalse(service.state.value.hasActivity)
        assertTrue(library.downloadQueue().isEmpty())
    }

    // Removal

    @Test
    fun removingCancelsQueuedTracksAndDeletesDownloadedOnes() = runBlocking {
        settings.maxConcurrentDownloads.value = 1
        val (done, queued) = seed(2)
        val path = downloadRelativePath(artistId, albumId, done.id, "flac")
        File(filesDir, path).apply { parentFile!!.mkdirs(); writeBytes(audio) }
        library.markDownloaded(done.id, path, audio.size.toLong())
        val service = service()
        service.download(queued)

        service.removeAlbum(albumId)

        assertFalse(service.state.value.hasActivity)
        assertFalse(File(filesDir, path).exists())
        assertTrue(library.downloadedTrackIds().isEmpty())
    }

    @Test
    fun hasUndownloadedTracksIgnoresWhatIsQueuedOrDone() = runBlocking {
        settings.maxConcurrentDownloads.value = 1
        val (done, queued) = seed(2)
        library.markDownloaded(done.id, "Downloads/x.flac", 1)
        val service = service()
        assertTrue(service.hasUndownloadedTracks())

        service.download(queued)

        assertFalse(service.hasUndownloadedTracks())
    }
}
