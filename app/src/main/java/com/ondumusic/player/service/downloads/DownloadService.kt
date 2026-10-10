package com.ondumusic.player.service.downloads

import android.util.Log
import com.ondumusic.player.BuildConfig
import com.ondumusic.player.R
import com.ondumusic.player.api.ApiClient
import com.ondumusic.player.api.ErrorMessages
import com.ondumusic.player.api.appendingPath
import com.ondumusic.player.api.await
import com.ondumusic.player.data.DownloadQueueEntity
import com.ondumusic.player.data.MusicLibrary
import com.ondumusic.player.data.clearDownloadQueue
import com.ondumusic.player.data.dequeueDownloads
import com.ondumusic.player.data.downloadQueue
import com.ondumusic.player.data.downloadRelativePath
import com.ondumusic.player.data.downloadedTrackIds
import com.ondumusic.player.data.enqueueDownloads
import com.ondumusic.player.data.isDownloaded
import com.ondumusic.player.data.markDownloaded
import com.ondumusic.player.data.removeAlbumDownloads
import com.ondumusic.player.data.removeAllDownloads
import com.ondumusic.player.data.removeArtistDownloads
import com.ondumusic.player.data.removeDownloads
import com.ondumusic.player.data.setDownloadsPaused
import com.ondumusic.player.model.Track
import com.ondumusic.player.settings.DownloadQuality
import com.ondumusic.player.settings.SettingsStore
import com.ondumusic.player.util.AudioFormat
import com.ondumusic.player.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The download queue. Runs in-process, as coroutines; [DownloadWorker] only keeps the
 * process alive while it works. The queue itself is stored in Room, so a killed process
 * picks it up again on the next [start].
 *
 * Downloads are written to `{trackId}.{variant}.part` beside their final path and renamed
 * when complete, so a paused or interrupted one resumes with a Range request.
 *
 * Every action suspends until the queue has taken it, and runs one at a time.
 */
class DownloadService(
    private val library: MusicLibrary,
    private val settings: SettingsStore,
    http: OkHttpClient,
    /** Null while signed out. A new client after a 401 — re-auth — resumes the queue. */
    private val api: StateFlow<ApiClient?>,
    private val isOnline: StateFlow<Boolean>,
    private val conditions: DownloadConditions,
    /** Should live as long as the app. */
    private val scope: CoroutineScope,
    private val hooks: Hooks = Hooks(),
    private val timing: Timing = Timing(),
) {
    class Hooks(
        /** A download got a 401. Everything pauses until [api] changes. */
        val onUnauthorized: () -> Unit = {},
        /** A download started; keeps the process alive while there's work. */
        val onStarted: () -> Unit = {},
    )

    class Timing(
        val progressIntervalMillis: Long = 500,
        /** For a busy server that doesn't say how long to wait. */
        val busyRetryDelayMillis: Long = 5_000,
        val diskCheckIntervalMillis: Long = 30_000,
        val now: () -> Long = System::currentTimeMillis,
        val io: CoroutineDispatcher = Dispatchers.IO,
    )

    /** Transcode prepare waits for the whole file to be encoded, and a download may stall; both get iOS's 300 s. */
    private val http = http.newBuilder().readTimeout(300, TimeUnit.SECONDS).build()

    private val _state = MutableStateFlow(DownloadsState())
    val state: StateFlow<DownloadsState> = _state.asStateFlow()

    private val _progress = MutableStateFlow<Map<UUID, Float>>(emptyMap())
    /** Fraction done per active download, 0..1. Sampled twice a second, not per byte, so it doesn't flood the UI. */
    val progress: StateFlow<Map<UUID, Float>> = _progress.asStateFlow()

    private sealed interface Phase {
        val track: Track

        /** Preparing a transcode or transferring. */
        class Running(override val track: Track, val job: Job) : Phase

        class Paused(override val track: Track) : Phase
    }

    private val mutex = Mutex()

    // Guarded by mutex.
    /** Insertion order is the order the UI lists them in. */
    private val phases = LinkedHashMap<UUID, Phase>()
    private val pending = LinkedHashMap<UUID, Track>()
    private val failed = LinkedHashMap<UUID, FailedDownload>()
    private var isPaused = false
    /** Paused by a 401 rather than by the user, so re-auth may undo it. */
    private var pausedForAuth = false
    private var pausedReason: DownloadPausedReason? = null
    /**
     * Not persisted: it lasts the session, so the next launch defers to Battery Saver again
     * rather than quietly ignoring it forever.
     */
    private var ignoresBatterySaver = false
    /** A transfer failed while offline; nothing starts until the network is back, or the queue would fail through. */
    private var waitingForNetwork = false
    private var restored = false
    private var lastDiskCheck: Pair<Long, Long>? = null
    private var sampler: Job? = null

    /** Written from transfer threads, read by the sampler. */
    private val rawProgress = ConcurrentHashMap<UUID, Float>()

    init {
        scope.launch { settings.maxConcurrentDownloads.flow.drop(1).collect { locked { startNext() } } }
        scope.launch { conditions.isPowerSaveMode.drop(1).collect { locked { startNext() } } }
        scope.launch {
            isOnline.drop(1).collect { online ->
                if (online) locked {
                    waitingForNetwork = false
                    startNext()
                }
            }
        }
        scope.launch {
            api.drop(1).collect { client ->
                if (client != null) locked {
                    if (pausedForAuth) resumeEverything()
                    startNext()
                }
            }
        }
    }

    // Lifecycle

    /** Restores the stored queue and starts what it can. For launch and returning to the foreground. */
    suspend fun start() = locked {
        lastDiskCheck = null
        // Worth another try: the network monitor can't see a server reachable only on the LAN.
        waitingForNetwork = false
        startNext()
    }

    /** Returns once nothing is transferring: the queue is empty, paused, or blocked. */
    suspend fun awaitIdle() {
        state.first { it.transferringCount == 0 }
    }

    /** Signing out: stops everything and forgets the queue and failures. Returns once no transfer can still write. */
    suspend fun reset() {
        val jobs = locked {
            val jobs = cancelPhases(phases.keys.toList())
            pending.clear()
            failed.clear()
            isPaused = false
            pausedForAuth = false
            waitingForNetwork = false
            library.clearDownloadQueue()
            jobs
        }
        jobs.joinAll()
    }

    // Enqueueing

    suspend fun download(track: Track) = enqueue(listOf(track))

    suspend fun download(tracks: List<Track>) = enqueue(tracks)

    suspend fun downloadAlbum(albumId: UUID) = enqueue(library.tracksForAlbum(albumId))

    /** Everything credited to the artist as album artist, newest album first. */
    suspend fun downloadArtist(artistId: UUID) = enqueue(library.tracksForAlbumArtistInOrder(artistId))

    suspend fun downloadAll() = enqueue(library.allTracks())

    /** For sync and playlists, which know ids. Unknown ids are skipped. */
    suspend fun downloadTracks(ids: Set<UUID>) {
        if (ids.isEmpty()) return
        enqueue(MusicLibrary.sortedTracks(library.tracks(ids).values.toList()))
    }

    /** Whether Download All would queue anything. */
    suspend fun hasUndownloadedTracks(): Boolean {
        val tracks = library.allTracks()
        return locked { tracks.any { !it.isDownloaded && it.id !in phases && it.id !in pending } }
    }

    private suspend fun enqueue(tracks: List<Track>) = locked {
        if (tracks.isEmpty()) return@locked
        // Membership resolved up front: per track, a query plus a scan of the queue made
        // Download All on a full library hang on iOS.
        val downloaded = library.downloadedTrackIds()
        val seen = HashSet<UUID>()
        val added = tracks.filter { it.id !in downloaded && it.id !in phases && it.id !in pending && seen.add(it.id) }
        if (added.isEmpty()) return@locked
        val now = timing.now()
        library.enqueueDownloads(added.map { DownloadQueueEntity(it.id, now) })
        for (track in added) {
            failed.remove(track.id)
            pending[track.id] = track
        }
        startNext()
    }

    // Per-track controls

    suspend fun cancel(trackId: UUID) = locked {
        cancelTracks(listOf(trackId))
        startNext()
    }

    suspend fun cancelAlbum(albumId: UUID) = locked {
        cancelTracks(tracksMatching { it.albumId == albumId })
        startNext()
    }

    /** Keeps the partial file, so [resume] carries on from where it stopped. Doesn't start the next in line. */
    suspend fun pause(trackId: UUID) = locked { pauseTrack(trackId) }

    /** Starts at once if there's a free slot, otherwise goes to the front of the queue. */
    suspend fun resume(trackId: UUID) = locked { resumeTrack(trackId) }

    // Bulk controls

    suspend fun pauseAll() = locked {
        isPaused = true
        phases.values.filterIsInstance<Phase.Running>().map { it.track.id }.forEach { pauseTrack(it) }
    }

    suspend fun resumeAll() = locked {
        resumeEverything()
        startNext()
    }

    /** Failed downloads stay listed. */
    suspend fun cancelAll() = locked {
        isPaused = false
        pausedForAuth = false
        cancelTracks(phases.keys + pending.keys)
    }

    /** Battery Saver only. Low storage is a real limit, not a preference. */
    suspend fun ignoreBatterySaver() = locked {
        if (ignoresBatterySaver) return@locked
        ignoresBatterySaver = true
        startNext()
    }

    // Failures

    suspend fun retry(trackId: UUID) = locked {
        val failure = failed.remove(trackId) ?: return@locked
        val track = library.track(failure.trackId) ?: return@locked
        // In case a bad earlier download left it marked.
        library.removeDownloads(listOf(trackId))
        library.enqueueDownloads(listOf(DownloadQueueEntity(trackId, timing.now())))
        pending[trackId] = track
        startNext()
    }

    suspend fun dismissFailure(trackId: UUID) = locked { failed.remove(trackId) }

    suspend fun clearFailures() = locked { failed.clear() }

    // Removing downloads

    /** Cancels any of them still queued or downloading, then deletes the files. */
    suspend fun remove(trackIds: Set<UUID>) {
        locked { cancelTracks(trackIds) }.joinAll()
        library.removeDownloads(trackIds)
    }

    suspend fun removeAlbum(albumId: UUID) {
        locked { cancelTracks(tracksMatching { it.albumId == albumId }) }.joinAll()
        library.removeAlbumDownloads(albumId)
    }

    /** Everything credited to the artist as album artist. */
    suspend fun removeArtist(artistId: UUID) {
        locked { cancelTracks(tracksMatching { it.albumArtistId == artistId }) }.joinAll()
        library.removeArtistDownloads(artistId)
    }

    suspend fun removeAll() {
        locked {
            isPaused = false
            pausedForAuth = false
            cancelTracks(phases.keys + pending.keys)
        }.joinAll()
        library.removeAllDownloads()
    }

    // Queue management, all under the mutex

    private suspend fun <T> locked(action: suspend () -> T): T =
        // On the service's scope, so a caller that goes away can't cancel an action between
        // its memory and database writes. Undispatched so actions queue in call order.
        scope.async(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock {
                restoreIfNeeded()
                action().also { publish() }
            }
        }.await()

    private suspend fun restoreIfNeeded() {
        if (restored) return
        restored = true
        val rows = library.downloadQueue()
        if (rows.isEmpty()) return
        val tracks = library.tracks(rows.map { it.trackId })
        val downloaded = library.downloadedTrackIds()
        library.dequeueDownloads(rows.map { it.trackId }.filter { it in downloaded })
        // A row whose track is missing stays stored: mid-rebuild the track comes back.
        for (row in rows) {
            val track = tracks[row.trackId]?.takeIf { it.id !in downloaded } ?: continue
            if (row.paused) phases[track.id] = Phase.Paused(track) else pending[track.id] = track
        }
    }

    private fun tracksMatching(predicate: (Track) -> Boolean): List<UUID> =
        (phases.values.map { it.track } + pending.values).filter(predicate).map { it.id }

    private suspend fun startNext() {
        if (isPaused || waitingForNetwork || api.value == null) return
        if (pending.isEmpty()) {
            pausedReason = null
            return
        }
        if (conditions.isPowerSaveMode.value && !ignoresBatterySaver) {
            if (BuildConfig.DEBUG) Log.d(TAG, "not starting — Battery Saver is on (${pending.size} queued)")
            pausedReason = DownloadPausedReason.BatterySaver
            return
        }
        if (cachedFreeBytes() < MINIMUM_FREE_BYTES) {
            if (BuildConfig.DEBUG) Log.d(TAG, "not starting — under $MINIMUM_FREE_BYTES bytes free")
            pausedReason = DownloadPausedReason.LowStorage
            return
        }
        pausedReason = null
        val limit = settings.maxConcurrentDownloads.value.coerceAtLeast(1)
        while (pending.isNotEmpty() && runningCount() < limit) {
            val track = pending.values.first()
            pending.remove(track.id)
            if (library.isDownloaded(track.id)) {
                library.dequeueDownloads(listOf(track.id))
                continue
            }
            launchTransfer(track)
        }
    }

    private fun runningCount() = phases.values.count { it is Phase.Running }

    private suspend fun cachedFreeBytes(): Long {
        val now = timing.now()
        lastDiskCheck?.let { (bytes, at) -> if (now - at < timing.diskCheckIntervalMillis) return bytes }
        val bytes = withContext(timing.io) { conditions.freeBytes() }
        lastDiskCheck = bytes to now
        return bytes
    }

    private fun launchTransfer(track: Track) {
        val job = scope.launch(timing.io, start = CoroutineStart.LAZY) { transfer(track) }
        // Re-added rather than replaced, so a resumed download moves to the end like on iOS.
        phases.remove(track.id)
        phases[track.id] = Phase.Running(track, job)
        rawProgress.putIfAbsent(track.id, 0f)
        job.start()
        hooks.onStarted()
    }

    private suspend fun pauseTrack(trackId: UUID) {
        val phase = phases[trackId] as? Phase.Running ?: return
        phase.job.cancel()
        phases[trackId] = Phase.Paused(phase.track)
        library.setDownloadsPaused(listOf(trackId), true)
    }

    private suspend fun resumeTrack(trackId: UUID) {
        val phase = phases[trackId] as? Phase.Paused ?: return
        phases.remove(trackId)
        library.setDownloadsPaused(listOf(trackId), false)
        if (runningCount() < settings.maxConcurrentDownloads.value.coerceAtLeast(1)) {
            launchTransfer(phase.track)
        } else {
            requeueAtFront(phase.track)
        }
    }

    private suspend fun resumeEverything() {
        isPaused = false
        waitingForNetwork = false
        pausedForAuth = false
        phases.values.filterIsInstance<Phase.Paused>().map { it.track.id }.forEach { resumeTrack(it) }
    }

    private fun requeueAtFront(track: Track) {
        val rest = LinkedHashMap(pending)
        pending.clear()
        pending[track.id] = track
        pending.putAll(rest)
    }

    /** Takes them out of the queue and the store, deleting partial files. Returns the transfers being stopped. */
    private suspend fun cancelTracks(trackIds: Collection<UUID>): List<Job> {
        if (trackIds.isEmpty()) return emptyList()
        val tracks = trackIds.mapNotNull { pending[it] ?: phases[it]?.track }
        tracks.forEach { pending.remove(it.id) }
        val jobs = cancelPhases(trackIds.filter { it in phases })
        library.dequeueDownloads(trackIds)
        discardPartials(tracks, jobs)
        return jobs
    }

    private fun cancelPhases(trackIds: List<UUID>): List<Job> {
        val jobs = trackIds.mapNotNull { (phases.remove(it) as? Phase.Running)?.job }
        jobs.forEach { it.cancel() }
        trackIds.forEach { rawProgress.remove(it) }
        return jobs
    }

    /** Once the transfers have stopped writing, and only if nothing has queued the track again since. */
    private fun discardPartials(tracks: List<Track>, jobs: List<Job>) {
        if (tracks.isEmpty()) return
        scope.launch(timing.io) {
            jobs.joinAll()
            mutex.withLock {
                tracks.filter { it.id !in phases && it.id !in pending }.forEach { partialsOf(it).forEach(File::delete) }
            }
        }
    }

    private fun publish() {
        _state.value = DownloadsState(
            active = phases.values.map {
                ActiveDownload(it.track.id, it.track.name, it.track.albumArtistName, it.track.albumId, isPaused = it is Phase.Paused)
            },
            queued = pending.values.map { QueuedDownload(it.id, it.name, it.albumArtistName, it.albumId) },
            failed = failed.values.toList(),
            isPaused = isPaused,
            pausedReason = pausedReason,
            ignoresBatterySaver = ignoresBatterySaver,
        )
        rawProgress.keys.retainAll(phases.keys)
        if (phases.values.any { it is Phase.Running }) {
            if (sampler?.isActive != true) {
                sampler = scope.launch {
                    while (true) {
                        delay(timing.progressIntervalMillis)
                        _progress.value = HashMap(rawProgress)
                    }
                }
            }
        } else {
            sampler?.cancel()
            sampler = null
            _progress.value = HashMap(rawProgress)
        }
    }

    // Transfers, off the mutex

    private sealed interface Outcome {
        data object Done : Outcome
        data object Unauthorized : Outcome
        /** Back to the front of the queue, without counting as a failure. */
        data class Requeue(val waitForNetwork: Boolean) : Outcome
        data class Failed(val reason: UiText) : Outcome
    }

    /** Ends a transfer early with [outcome]. */
    private class Stop(val outcome: Outcome) : Exception()

    private sealed interface Fetched {
        data class Complete(val mimetype: String?) : Fetched
        data class Busy(val code: Int, val retryAfterSeconds: Long?) : Fetched
        /** The partial file doesn't fit what the server has; start again from nothing. */
        data object Restart : Fetched
    }

    private suspend fun transfer(track: Track) {
        val outcome = try {
            transferOutcome(track)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Stop) {
            e.outcome
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.d(TAG, "download failed for ${track.name}", e)
            Outcome.Failed(ErrorMessages.download(e))
        }
        val self = currentCoroutineContext().job
        mutex.withLock {
            // Paused or cancelled meanwhile: whoever did that has already moved it on.
            if ((phases[track.id] as? Phase.Running)?.job !== self) return
            finish(track, outcome)
            startNext()
            publish()
        }
    }

    private suspend fun finish(track: Track, outcome: Outcome) {
        phases.remove(track.id)
        when (outcome) {
            Outcome.Done -> library.dequeueDownloads(listOf(track.id))
            Outcome.Unauthorized -> {
                requeueAtFront(track)
                isPaused = true
                pausedForAuth = true
                hooks.onUnauthorized()
            }
            is Outcome.Requeue -> {
                requeueAtFront(track)
                if (outcome.waitForNetwork) waitingForNetwork = true
            }
            is Outcome.Failed -> {
                library.dequeueDownloads(listOf(track.id))
                if (track.id !in failed) failed[track.id] = FailedDownload(track.id, track.name, track.albumArtistName, outcome.reason)
            }
        }
    }

    private suspend fun transferOutcome(track: Track): Outcome {
        // Finished just before a pause took effect.
        if (library.isDownloaded(track.id)) return Outcome.Done
        val api = api.value ?: return Outcome.Requeue(waitForNetwork = false)

        val quality = settings.downloadQuality.value
        // Formats ExoPlayer can't play are transcoded even when the setting is Original.
        val transcode = quality != DownloadQuality.Original || !AudioFormat.isPlayableNatively(track.mimetype)
        val preset = if (quality != DownloadQuality.Original) quality.raw else DownloadQuality.DefaultAac.raw
        val base = track.downloadUrl.toHttpUrlOrNull() ?: api.baseUrl.resolve(track.downloadUrl)
            ?: return Outcome.Failed(UiText.Res(R.string.download_error_no_link))
        val url = if (transcode) base.newBuilder().addQueryParameter("transcode", "1").addQueryParameter("preset", preset).build() else base
        // Named for what it holds, so a quality change between pause and resume can't splice two encodings.
        val part = partFile(track, if (transcode) preset else ORIGINAL_VARIANT)

        var retries = 0
        while (true) {
            val fetched = try {
                val expectedSize = if (transcode) prepareTranscode(api, track.id, preset) else null
                fetch(api, url, part, track.id, expectedSize)
            } catch (e: IOException) {
                if (!isOnline.value) return Outcome.Requeue(waitForNetwork = true)
                if (retries >= MAX_RETRIES) throw e
                retries++
                if (BuildConfig.DEBUG) Log.d(TAG, "transient error for ${track.name}, retrying ($retries/$MAX_RETRIES)", e)
                continue
            }
            when (fetched) {
                is Fetched.Complete -> return save(track, part, fetched.mimetype ?: if (transcode) TRANSCODED_MIMETYPE else track.mimetype)
                Fetched.Restart -> {
                    part.delete()
                    continue
                }
                is Fetched.Busy -> {
                    if (retries >= MAX_RETRIES) return Outcome.Failed(UiText.Res(R.string.download_error_http, listOf(fetched.code)))
                    retries++
                    val wait = fetched.retryAfterSeconds?.let { it * 1000 } ?: timing.busyRetryDelayMillis
                    if (BuildConfig.DEBUG) Log.d(TAG, "server busy for ${track.name}, retrying in ${wait}ms ($retries/$MAX_RETRIES)")
                    delay(wait)
                }
            }
        }
    }

    /**
     * Asks the server to transcode ahead of the download, so the download itself has a
     * known size and real progress. The endpoint answers in server-sent events; the first
     * `data:` line carrying a size means it's ready.
     */
    private suspend fun prepareTranscode(api: ApiClient, trackId: UUID, preset: String): Long {
        val url = api.baseUrl.appendingPath("tracks/$trackId/transcode").newBuilder().addQueryParameter("preset", preset).build()
        return http.newCall(authorized(api, url).build()).blockingUse { response ->
            if (response.code == 401) throw Stop(Outcome.Unauthorized)
            if (!response.isSuccessful) throw Stop(Outcome.Failed(UiText.Res(R.string.download_error_transcode)))
            val source = response.body.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data: ")) continue
                val size = runCatching {
                    Json.parseToJsonElement(line.removePrefix("data: ")).jsonObject["size"]?.jsonPrimitive?.longOrNull
                }.getOrNull()
                if (size != null) return@blockingUse size
            }
            throw Stop(Outcome.Failed(UiText.Res(R.string.download_error_transcode)))
        }
    }

    private suspend fun fetch(api: ApiClient, url: HttpUrl, part: File, trackId: UUID, expectedSize: Long?): Fetched {
        withContext(timing.io) { part.parentFile?.mkdirs() }
        val offset = if (part.exists()) part.length() else 0L
        val request = authorized(api, url).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
        return http.newCall(request).blockingUse { response ->
            when (response.code) {
                401 -> throw Stop(Outcome.Unauthorized)
                416 -> if (offset > 0) return@blockingUse Fetched.Restart
                in BUSY_CODES -> return@blockingUse Fetched.Busy(response.code, response.header("Retry-After")?.toLongOrNull())
            }
            if (!response.isSuccessful) {
                if (BuildConfig.DEBUG) Log.d(TAG, "HTTP ${response.code} for $url")
                throw Stop(Outcome.Failed(UiText.Res(R.string.download_error_http, listOf(response.code))))
            }
            // A server that ignores Range answers 200 with the whole file: start over.
            val resumes = offset > 0 && response.code == 206 && contentRangeStart(response) == offset
            val start = if (resumes) offset else 0L
            val length = response.body.contentLength()
            val total = if (length >= 0) start + length else expectedSize ?: -1L
            FileOutputStream(part, resumes).use { out ->
                val source = response.body.source()
                val buffer = ByteArray(64 * 1024)
                var written = start
                while (true) {
                    val read = source.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    written += read
                    if (total > 0) rawProgress[trackId] = (written.toFloat() / total).coerceAtMost(1f)
                }
            }
            Fetched.Complete(response.body.contentType()?.let { "${it.type}/${it.subtype}" }?.takeIf { it.startsWith("audio/") })
        }
    }

    /** Validates the finished file, moves it into place and records it. Not cancellable: a half-moved file is worse than a late pause. */
    private suspend fun save(track: Track, part: File, mimetype: String): Outcome = withContext(NonCancellable + timing.io) {
        val extension = AudioFormat.fileExtension(mimetype)
        val size = part.length()
        // Too small to be audio, or an MP4 cut short before its index.
        val valid = size > MINIMUM_FILE_BYTES && (extension !in MP4_EXTENSIONS || Mp4Atoms.hasMoovAtom(part))
        val relativePath = downloadRelativePath(track.albumArtistId, track.albumId, track.id, extension)
        val destination = File(library.filesDir, relativePath)
        if (!valid || !(destination.delete() || !destination.exists()) || !part.renameTo(destination)) {
            if (BuildConfig.DEBUG) Log.d(TAG, "could not save ${track.name} ($size bytes, valid: $valid)")
            part.delete()
            return@withContext Outcome.Failed(UiText.Res(R.string.download_error_save))
        }
        // Gone from the library while it downloaded.
        if (!library.markDownloaded(track.id, relativePath, size)) destination.delete()
        Outcome.Done
    }

    private fun partFile(track: Track, variant: String): File =
        File(library.filesDir, downloadRelativePath(track.albumArtistId, track.albumId, track.id, "$variant.part"))

    private fun partialsOf(track: Track): List<File> {
        val dir = File(library.filesDir, downloadRelativePath(track.albumArtistId, track.albumId, track.id, "x")).parentFile
        return dir?.listFiles { file -> file.name.startsWith("${track.id}.") && file.name.endsWith(".part") }?.toList().orEmpty()
    }

    private fun authorized(api: ApiClient, url: HttpUrl) =
        Request.Builder().url(url).header("Authorization", "Bearer ${api.sessionToken}")

    private fun contentRangeStart(response: Response): Long? =
        response.header("Content-Range")?.removePrefix("bytes ")?.substringBefore('-')?.trim()?.toLongOrNull()

    /**
     * Reads the response off the coroutine and cancels the call if the coroutine is
     * cancelled: a blocking read ignores cancellation, and a stalled one would otherwise
     * hold a pause or a sign-out until the read timeout.
     */
    private suspend fun <T> Call.blockingUse(block: (Response) -> T): T = coroutineScope {
        val response = await()
        val reading = async(timing.io) { response.use(block) }
        try {
            reading.await()
        } catch (e: CancellationException) {
            cancel()
            throw e
        }
    }.also { currentCoroutineContext().ensureActive() }

    private companion object {
        const val TAG = "DownloadService"
        const val MINIMUM_FREE_BYTES = 100L * 1024 * 1024
        const val MINIMUM_FILE_BYTES = 1024L
        const val MAX_RETRIES = 3
        const val ORIGINAL_VARIANT = "original"
        /** What the AAC presets produce, for a response that doesn't say. */
        const val TRANSCODED_MIMETYPE = "audio/mp4"
        val MP4_EXTENSIONS = setOf("m4a", "mp4")
        val BUSY_CODES = setOf(408, 429, 502, 503, 504)
    }
}
