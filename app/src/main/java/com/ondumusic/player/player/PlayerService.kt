package com.ondumusic.player.player

import com.ondumusic.player.data.MusicLibrary
import com.ondumusic.player.model.LoudnessNormalization
import com.ondumusic.player.model.QueueItem
import com.ondumusic.player.model.RepeatMode
import com.ondumusic.player.model.Track
import com.ondumusic.player.service.SmartShuffle
import com.ondumusic.player.settings.SettingsStore
import com.ondumusic.player.settings.StreamingQuality
import com.ondumusic.player.util.AudioFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant
import java.util.UUID

/**
 * The queue and everything that decides what plays: shuffle, repeat, gapless versus
 * crossfade, loudness, play counting, and saving it all across launches. Sound itself is
 * the [PlaybackEngine]'s job.
 *
 * Main-thread only, like the players underneath it.
 */
class PlayerService(
    private val engine: PlaybackEngine,
    private val settings: SettingsStore,
    private val library: MusicLibrary?,
    private val stateStore: PlaybackStateStore,
    private val scope: CoroutineScope,
    private val hooks: Hooks = Hooks(),
) {
    class Hooks(
        /** Where a stream URL that isn't absolute is resolved against. Null while signed out. */
        val baseUrl: () -> HttpUrl? = { null },
        /** A finished listen, for the server to count. Called only while play recording is on. */
        val recordPlay: (trackId: UUID, playedAt: Instant) -> Unit = { _, _ -> },
        val onUnauthorized: () -> Unit = {},
    )

    enum class QueueConfirmationKind { PlayNext, AddedToQueue }
    data class QueueConfirmation(val kind: QueueConfirmationKind, val count: Int, val id: Long = System.nanoTime())

    data class Entry(val originalIndex: Int, val item: QueueItem)

    data class State(
        val queue: List<QueueItem> = emptyList(),
        val currentIndex: Int = 0,
        val shuffledIndices: List<Int>? = null,
        val repeatMode: RepeatMode = RepeatMode.Off,
        val isPlaying: Boolean = false,
        val isBuffering: Boolean = false,
        val currentTrack: Track? = null,
        val duration: Double = 0.0,
        val playbackError: PlaybackError? = null,
        val isQueueUserModified: Boolean = false,
    ) {
        val isShuffled: Boolean get() = shuffledIndices != null

        val effectiveIndex: Int
            get() = shuffledIndices?.let { if (currentIndex in it.indices) it[currentIndex] else 0 } ?: currentIndex

        /**
         * The item [effectiveIndex] points at, if the queue still reaches that far. An index
         * resolved before an await can outlive the queue it was drawn from.
         */
        val currentItem: QueueItem? get() = queue.getOrNull(effectiveIndex)

        private fun entryAt(position: Int): Entry? {
            val effective = shuffledIndices?.let { if (position < it.size) it[position] else position } ?: position
            return queue.getOrNull(effective)?.let { Entry(effective, it) }
        }

        /**
         * Everything queued after the current track, in the order it will actually play —
         * the shuffled order when shuffle is on. Walking the raw queue and comparing against
         * [effectiveIndex] instead drops whatever shuffle placed earlier.
         */
        val upNext: List<Entry>
            get() = if (queue.isEmpty() || currentIndex < 0) emptyList()
            else (currentIndex + 1 until queue.size).mapNotNull(::entryAt)

        /** What has already played, most recent first, in shuffle order too. */
        val history: List<Entry>
            get() = if (queue.isEmpty() || currentIndex <= 0) emptyList()
            else (currentIndex - 1 downTo 0).mapNotNull(::entryAt)

        val canSkip: Boolean
            get() = queue.isNotEmpty() && (repeatMode != RepeatMode.Off || currentIndex + 1 < queue.size)
    }

    // Published state

    private val _state = MutableStateFlow(State(repeatMode = settings.repeatMode.value))
    val state: StateFlow<State> = _state.asStateFlow()

    private val _progress = MutableStateFlow(0.0)
    /** Seconds into the current track. Separate from [state] because it changes twice a second. */
    val progress: StateFlow<Double> = _progress.asStateFlow()

    private val _queueConfirmation = MutableStateFlow<QueueConfirmation?>(null)
    val queueConfirmation: StateFlow<QueueConfirmation?> = _queueConfirmation.asStateFlow()

    private val _replaceQueuePrompt = MutableStateFlow(false)
    /** Asking before replacing a queue the user built by hand. */
    val replaceQueuePrompt: StateFlow<Boolean> = _replaceQueuePrompt.asStateFlow()

    // Mutable model, published through [publish]

    private var queue: List<QueueItem> = emptyList()
        set(value) {
            field = value
            queueIsSingleAlbum = isSingleAlbum(value)
        }

    /**
     * Whether the whole queue is one album, which picks album gain over track gain.
     * Recomputed on every queue mutation so the answer is ready when a track starts.
     */
    private var queueIsSingleAlbum = false
    private var currentIndex = 0
    private var shuffledIndices: List<Int>? = null
    private var repeatMode = settings.repeatMode.value
    private var isPlaying = false
    private var isBuffering = false
    private var currentTrack: Track? = null
    private var duration = 0.0
    private var playbackError: PlaybackError? = null

    /**
     * True once the user has shaped this queue by hand — Play Next, Add to Queue, reorder,
     * remove. Replacing a queue you built is worth confirming; replacing one that is just
     * "the album I pressed play on" is not.
     */
    private var isQueueUserModified = false

    private var pendingPlayAction: (() -> Unit)? = null
    /** Position to resume at, from a restored snapshot. Keyed by track so a skip before the first play doesn't seek the wrong one. */
    private var pendingResume: Pair<UUID, Double>? = null
    private var loadJob: Job? = null
    private var transitionJob: Job? = null
    private var stallWatchdog: Job? = null
    private var ticker: Job? = null
    private var lastPositionSave = 0L

    /** Gain for what's playing and for what's queued behind it, which takes over at the boundary. */
    private var currentGain = 1f
    private var nextGain = 1f

    // Play reporting

    /** Real listening, from playback deltas rather than position, so seeking to the end doesn't count as having heard it. */
    private var listenedSeconds = 0.0
    private var lastProgressSample: Double? = null
    private var playStartedAt: Instant? = null
    private var hasReportedCurrentPlay = false

    init {
        engine.listener = EngineListener()
        engine.setVolume(settings.volume.value)
        scope.launch {
            // So the toggle lands on the track already playing instead of the next one.
            settings.normalizeVolume.flow.drop(1).collect { refreshNormalization() }
        }
        scope.launch { settings.volume.flow.collect { engine.setVolume(it) } }
        // Off the first-frame path: nothing on screen needs the saved queue.
        scope.launch { restoreState() }
    }

    val effectiveIndex: Int get() = _state.value.effectiveIndex

    private fun publish() {
        _state.value = State(
            queue, currentIndex, shuffledIndices, repeatMode, isPlaying, isBuffering,
            currentTrack, duration, playbackError, isQueueUserModified,
        )
    }

    private fun setCurrentTrack(track: Track?) {
        if (track?.id != currentTrack?.id) {
            listenedSeconds = 0.0
            lastProgressSample = null
            hasReportedCurrentPlay = false
            playStartedAt = if (track == null) null else Instant.now()
        }
        currentTrack = track
    }

    // Replace-queue confirmation

    fun requestPlay(action: () -> Unit) {
        if (currentTrack != null && isQueueUserModified) {
            pendingPlayAction = action
            _replaceQueuePrompt.value = true
        } else {
            action()
        }
    }

    fun confirmPlay() {
        _replaceQueuePrompt.value = false
        pendingPlayAction?.invoke()
        pendingPlayAction = null
    }

    fun cancelPlay() {
        _replaceQueuePrompt.value = false
        pendingPlayAction = null
    }

    fun consumeQueueConfirmation() {
        _queueConfirmation.value = null
    }

    // Queue operations

    private fun items(tracks: List<Track>, artworkUrl: String?) =
        tracks.map { QueueItem(it, artworkUrl = artworkUrl ?: it.album?.artworkUrl) }

    fun playNow(tracks: List<Track>, startingAt: Int = 0, artworkUrl: String? = null) =
        playNowQueue(items(tracks, artworkUrl), startingAt)

    fun playNowQueue(items: List<QueueItem>, startingAt: Int = 0) {
        queue = items
        shuffledIndices = null
        currentIndex = startingAt
        isQueueUserModified = false
        savePlaybackState()
        loadAndPlayCurrent()
    }

    fun playShuffled(tracks: List<Track>, artworkUrl: String? = null) = playShuffledQueue(items(tracks, artworkUrl))

    fun playShuffledQueue(items: List<QueueItem>) {
        if (items.isEmpty()) return
        queue = items
        shuffledIndices = SmartShuffle.shuffledIndices(items)
        currentIndex = 0
        isQueueUserModified = false
        savePlaybackState()
        loadAndPlayCurrent()
    }

    fun playNext(tracks: List<Track>, artworkUrl: String? = null) = playNextQueue(items(tracks, artworkUrl))

    fun playNextQueue(items: List<QueueItem>) {
        if (items.isEmpty()) return
        if (queue.isEmpty()) return playNowQueue(items)
        val insertIndex = minOf(effectiveIndex + 1, queue.size)
        queue = queue.toMutableList().apply { addAll(insertIndex, items) }
        shuffledIndices?.let { shuffled ->
            // Existing indices at or past the insert point move up; the new ones go right
            // after the current position in shuffle order.
            val shifted = shuffled.map { if (it >= insertIndex) it + items.size else it }.toMutableList()
            shifted.addAll(currentIndex + 1, items.indices.map { insertIndex + it })
            shuffledIndices = shifted
        }
        isQueueUserModified = true
        _queueConfirmation.value = QueueConfirmation(QueueConfirmationKind.PlayNext, items.size)
        invalidateTransition()
        savePlaybackState()
    }

    /**
     * Inserts at a position within up-next, where 0 is "plays next" and `upNext.size` is
     * the end. The position is an offset into the up-next slice, matching
     * [moveQueueItems] and [removeFromQueue] — not an index into the queue.
     */
    fun insertIntoQueue(items: List<QueueItem>, position: Int) {
        if (items.isEmpty()) return
        if (queue.isEmpty()) return playNowQueue(items)
        val nextBefore = upcomingTrackId
        val slot = position.coerceIn(0, _state.value.upNext.size)
        val shuffled = shuffledIndices
        if (shuffled != null) {
            // The new items go on the end of the queue and their positions are spliced into
            // the shuffle order, since that's what decides play order.
            val appendIndex = queue.size
            queue = queue + items
            shuffledIndices = shuffled.toMutableList().apply {
                addAll(minOf(currentIndex + 1 + slot, size), items.indices.map { appendIndex + it })
            }
        } else {
            val insertIndex = minOf(currentIndex + 1 + slot, queue.size)
            queue = queue.toMutableList().apply { addAll(insertIndex, items) }
        }
        isQueueUserModified = true
        if (upcomingTrackId != nextBefore) invalidateTransition()
        savePlaybackState()
    }

    fun addToEnd(tracks: List<Track>, artworkUrl: String? = null) = addToEndQueue(items(tracks, artworkUrl))

    fun addToEndQueue(items: List<QueueItem>) {
        if (items.isEmpty()) return
        if (queue.isEmpty()) return playNowQueue(items)
        val nextBefore = upcomingTrackId
        val startIndex = queue.size
        queue = queue + items
        shuffledIndices = shuffledIndices?.let { it + items.indices.map { i -> startIndex + i } }
        isQueueUserModified = true
        _queueConfirmation.value = QueueConfirmation(QueueConfirmationKind.AddedToQueue, items.size)
        // At the end of a queue with nothing after it, this is now what plays next.
        if (upcomingTrackId != nextBefore) invalidateTransition()
        savePlaybackState()
    }

    fun skip() {
        if (queue.isEmpty()) return
        if (repeatMode == RepeatMode.One) return loadAndPlayCurrent()
        val nextIndex = currentIndex + 1
        when {
            nextIndex < queue.size -> {
                currentIndex = nextIndex
                loadAndPlayCurrent()
            }
            repeatMode == RepeatMode.All -> {
                currentIndex = 0
                loadAndPlayCurrent()
            }
            else -> finishQueue()
        }
    }

    /** The end of a queue with nothing to repeat: stopped, cued up at the start. */
    private fun finishQueue() {
        cancelLoading()
        engine.stop()
        isPlaying = false
        isBuffering = false
        currentIndex = 0
        _state.value.copy(currentIndex = 0).currentItem?.let {
            setCurrentTrack(it.track)
            duration = it.track.duration
        }
        _progress.value = 0.0
        publish()
        savePlaybackState()
    }

    fun skipTo(queueIndex: Int) {
        if (queueIndex !in queue.indices) return
        currentIndex = queueIndex
        loadAndPlayCurrent()
    }

    /** Back to the start of the track, unless it's only just begun — then to the one before. */
    fun back() {
        if (queue.isEmpty()) return
        if (_progress.value > 3 || currentIndex == 0) return seek(0.0)
        currentIndex -= 1
        loadAndPlayCurrent()
    }

    fun togglePlayPause() {
        when {
            isPlaying -> {
                engine.pause()
                isPlaying = false
                _progress.value = engine.position
                stopTicker()
                savePlaybackPosition()
            }
            // Retry rather than resume: there's either nothing loaded or something the
            // engine already rejected.
            (!hasLoadedSource || playbackError != null) && queue.isNotEmpty() -> return loadAndPlayCurrent()
            else -> {
                engine.resume()
                isPlaying = true
                startTicker()
            }
        }
        publish()
    }

    /**
     * Resume only if paused. A remote play command arrives while playing more often than
     * you'd think — a headset button, another app handing focus back — and toggling on it
     * would pause the music the caller just asked to hear.
     */
    fun resumePlayback() {
        if (!isPlaying) togglePlayPause()
    }

    fun pausePlayback() {
        if (isPlaying) togglePlayPause()
    }

    /** Stops playback and empties the player, as before anything was asked for. Sign-out needs this. */
    fun stopAndClear() {
        cancelLoading()
        engine.stop()
        hasLoadedSource = false
        queue = emptyList()
        shuffledIndices = null
        currentIndex = 0
        setCurrentTrack(null)
        isPlaying = false
        isBuffering = false
        isQueueUserModified = false
        _progress.value = 0.0
        duration = 0.0
        playbackError = null
        publish()
    }

    fun seek(seconds: Double) {
        _progress.value = seconds
        // A seek is a jump, not listening; the next sample starts counting afresh.
        lastProgressSample = null
        engine.seek(seconds)
    }

    fun clearUpNext() {
        val keep = currentIndex + 1
        val shuffled = shuffledIndices
        if (shuffled != null) {
            rebuildFromShuffled(shuffled.take(keep))
        } else {
            queue = queue.take(keep)
        }
        isQueueUserModified = true
        invalidateTransition()
        savePlaybackState()
    }

    /**
     * Drops everything before the current track. The played entries have to come out of
     * the queue itself, not just out of the shuffle map, or they'd come back on the next shuffle.
     */
    fun clearHistory() {
        if (currentIndex <= 0) return
        val shuffled = shuffledIndices
        if (shuffled != null) {
            rebuildFromShuffled(shuffled.drop(currentIndex))
        } else {
            queue = queue.drop(currentIndex)
        }
        currentIndex = 0
        isQueueUserModified = true
        savePlaybackState()
    }

    /** Keeps just the given real indices, in queue order, and renumbers the shuffle map onto them. */
    private fun rebuildFromShuffled(keptRealIndices: List<Int>) {
        val sortedKept = keptRealIndices.sorted()
        val oldToNew = sortedKept.withIndex().associate { (new, old) -> old to new }
        queue = sortedKept.map { queue[it] }
        shuffledIndices = keptRealIndices.map { oldToNew.getValue(it) }
    }

    /** [offsets] are positions within up-next, as [State.upNext] lists them. */
    fun removeFromQueue(offsets: Set<Int>) {
        val upNextStart = currentIndex + 1
        val positions = offsets.map { upNextStart + it }
        val shuffled = shuffledIndices
        val realIndices = if (shuffled != null) positions.mapNotNull { shuffled.getOrNull(it) } else positions
        val mutableQueue = queue.toMutableList()
        var mutableShuffled = shuffled
        for (idx in realIndices.sortedDescending()) {
            if (idx >= mutableQueue.size) continue
            mutableQueue.removeAt(idx)
            mutableShuffled = mutableShuffled?.mapNotNull { if (it == idx) null else if (it > idx) it - 1 else it }
        }
        queue = mutableQueue
        shuffledIndices = mutableShuffled
        isQueueUserModified = true
        invalidateTransition()
        savePlaybackState()
    }

    /** Positions within up-next, with [destination] as an insertion point before the move, the way list reordering reports it. */
    fun moveQueueItems(source: Set<Int>, destination: Int) {
        val nextBefore = upcomingTrackId
        val upNextStart = currentIndex + 1
        val shuffled = shuffledIndices
        if (shuffled != null) {
            val upNext = shuffled.drop(upNextStart).moved(source, destination)
            shuffledIndices = shuffled.take(upNextStart) + upNext
        } else {
            queue = queue.take(upNextStart) + queue.drop(upNextStart).moved(source, destination)
        }
        isQueueUserModified = true
        if (upcomingTrackId != nextBefore) invalidateTransition()
        savePlaybackState()
    }

    /** The track that plays after the current one, which is all the gapless and crossfade setup depends on. */
    private val upcomingTrackId: UUID?
        get() {
            val next = currentIndex + 1
            if (next >= queue.size) return null
            val effective = shuffledIndices?.let { if (next < it.size) it[next] else next } ?: next
            return queue.getOrNull(effective)?.track?.id
        }

    fun toggleShuffle() {
        val realIndex = effectiveIndex
        if (shuffledIndices != null) {
            shuffledIndices = null
            currentIndex = realIndex
        } else {
            val indices = SmartShuffle.shuffledIndices(queue).toMutableList()
            val pos = indices.indexOf(realIndex)
            if (pos > 0) indices[0] = indices[pos].also { indices[pos] = indices[0] }
            shuffledIndices = indices
            currentIndex = 0
        }
        settings.isShuffled.value = shuffledIndices != null
        invalidateTransition()
        savePlaybackState()
    }

    fun cycleRepeatMode() {
        setRepeatMode(
            when (repeatMode) {
                RepeatMode.Off -> RepeatMode.All
                RepeatMode.All -> RepeatMode.One
                RepeatMode.One -> RepeatMode.Off
            }
        )
    }

    fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
        settings.repeatMode.value = mode
        invalidateTransition()
        publish()
    }

    // Loading

    /** Whether the engine holds something [togglePlayPause] can resume. */
    private var hasLoadedSource = false

    private fun cancelLoading() {
        loadJob?.cancel()
        transitionJob?.cancel()
        stallWatchdog?.cancel()
        stopTicker()
    }

    private fun loadAndPlayCurrent() {
        cancelLoading()
        val idx = effectiveIndex
        val item = queue.getOrNull(idx) ?: return
        val track = item.track

        val resumeAt = pendingResume?.takeIf { it.first == track.id }?.second ?: 0.0
        pendingResume = null

        isBuffering = true
        playbackError = null
        setCurrentTrack(track)
        _progress.value = resumeAt
        duration = track.duration
        isPlaying = true
        publish()
        savePlaybackState()

        loadJob = scope.launch {
            val source = resolveSource(track) ?: return@launch failPlayback(PlaybackError.Unplayable)
            currentGain = normalizationGain(track)
            engine.play(source, currentGain, resumeAt)
            hasLoadedSource = true
            startTicker()
            if (!source.isLocal) startStallWatchdog()
            setUpTransition()
        }
    }

    private suspend fun resolveSource(track: Track): PlaybackEngine.Source? {
        library?.downloadedFile(track.id)?.let { return PlaybackEngine.Source(it.toURI().toString(), isLocal = true) }
        val url = streamUrl(track) ?: return null
        return PlaybackEngine.Source(url.toString(), isLocal = false)
    }

    /** The stream, asking the server to transcode when the setting says so or the format won't play here. */
    fun streamUrl(track: Track): HttpUrl? {
        val base = hooks.baseUrl()
        val url = track.streamUrl.toHttpUrlOrNull() ?: base?.resolve(track.streamUrl) ?: return null
        val quality = settings.streamingQuality.value
        val needsTranscode = quality != StreamingQuality.Original || !AudioFormat.isPlayableNatively(track.mimetype)
        if (!needsTranscode) return url
        val preset = if (quality != StreamingQuality.Original) quality.raw else StreamingQuality.DefaultAdts.raw
        return url.newBuilder().addQueryParameter("transcode", "1").addQueryParameter("preset", preset).build()
    }

    /**
     * A stream that is rejected or black-holed may never report an error or time out on its
     * own — without this the UI would show a pause button at 0:00 indefinitely.
     */
    private fun startStallWatchdog() {
        stallWatchdog?.cancel()
        stallWatchdog = scope.launch {
            delay(STALL_TIMEOUT_MILLIS)
            if (isBuffering) failPlayback(PlaybackError.Unreachable)
        }
    }

    private fun failPlayback(error: PlaybackError) {
        stallWatchdog?.cancel()
        stopTicker()
        isBuffering = false
        isPlaying = false
        engine.pause()
        playbackError = error
        publish()
        if (error == PlaybackError.Unauthorized) hooks.onUnauthorized()
    }

    // Loudness

    /** One track on its own is not "listening to an album" — it gets levelled like any other song. */
    private fun isSingleAlbum(items: List<QueueItem>): Boolean {
        if (items.size < 2) return false
        val first = items.first().track.albumId
        return items.all { it.track.albumId == first }
    }

    /**
     * Album gain when the queue is one album: an album's quiet tracks are quiet on purpose,
     * and flattening them is what album gain exists to prevent. Any other queue is one the
     * user assembled, where every track at the same level is the point.
     */
    private suspend fun normalizationGain(track: Track): Float {
        if (!settings.normalizeVolume.value) return 1f
        if (queueIsSingleAlbum) library?.albumLufs(track.albumId)?.let { return LoudnessNormalization.gain(it) }
        return LoudnessNormalization.gain(track.lufs)
    }

    private fun refreshNormalization() {
        val track = currentTrack ?: return
        scope.launch {
            currentGain = normalizationGain(track)
            engine.setCurrentGain(currentGain)
            invalidateTransition()
        }
    }

    // Gapless and crossfade

    private data class NextTrack(val index: Int, val effectiveIndex: Int, val item: QueueItem)

    private fun nextTrack(): NextTrack? {
        if (queue.isEmpty()) return null
        val next = currentIndex + 1
        val resolved = when {
            next < queue.size -> next
            repeatMode == RepeatMode.All -> 0
            else -> return null
        }
        val effective = shuffledIndices?.let { it.getOrNull(resolved) ?: return null } ?: resolved
        return queue.getOrNull(effective)?.let { NextTrack(resolved, effective, it) }
    }

    /** The next track continues the album without a break: the next number on this disc, or track one of the next disc. */
    private fun isNextSequential(next: NextTrack): Boolean {
        val current = currentTrack ?: return false
        val following = next.item.track
        if (current.albumId != following.albumId) return false
        val number = following.number ?: return false
        if (following.disc == current.disc && current.number != null && number == current.number + 1) return true
        return following.disc == current.disc + 1 && number == 1
    }

    /** Sequential tracks play gapless, as the album intends; anything else crossfades when the setting asks for it. */
    private fun setUpTransition() {
        transitionJob?.cancel()
        engine.setNextGapless(null)
        engine.armCrossfade(null)
        if (repeatMode == RepeatMode.One) return
        val next = nextTrack() ?: return
        transitionJob = scope.launch {
            val source = resolveSource(next.item.track) ?: return@launch
            nextGain = normalizationGain(next.item.track)
            if (isNextSequential(next)) {
                engine.setNextGapless(source, nextGain)
            } else {
                val fade = settings.crossfadeDuration.value.toDouble()
                if (fade > 0) engine.armCrossfade(source, nextGain, fade)
            }
        }
    }

    /** The queue changed under what's playing; whatever was lined up behind it may be wrong now. */
    private fun invalidateTransition() {
        if (hasLoadedSource && currentTrack != null) setUpTransition()
        publish()
    }

    /** The engine moved onto the track that was lined up — by gapless or by crossfade. */
    private fun advanceToLinedUpTrack() {
        val next = nextTrack() ?: return skip()
        currentIndex = next.index
        setCurrentTrack(next.item.track)
        _progress.value = 0.0
        duration = next.item.track.duration
        isPlaying = true
        currentGain = nextGain
        publish()
        savePlaybackState()
        setUpTransition()
    }

    private inner class EngineListener : PlaybackEngine.Listener {
        override fun onReady() {
            stallWatchdog?.cancel()
            isBuffering = false
            playbackError = null
            publish()
        }

        override fun onError(message: PlaybackError) = failPlayback(message)

        override fun onEnded() = skip()

        override fun onAdvancedGapless() = advanceToLinedUpTrack()

        override fun onCrossfadeCompleted() = advanceToLinedUpTrack()

        override fun onPlayingChangedExternally(isPlaying: Boolean) {
            this@PlayerService.isPlaying = isPlaying
            if (isPlaying) startTicker() else {
                stopTicker()
                savePlaybackPosition()
            }
            publish()
        }
    }

    // Progress and play counting

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MILLIS)
                tick()
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun tick() {
        val position = engine.position
        _progress.value = position
        engine.duration?.takeIf { it > 0 && it != duration }?.let {
            duration = it
            publish()
        }
        accumulateListening(position)
        if (System.currentTimeMillis() - lastPositionSave >= POSITION_SAVE_INTERVAL_MILLIS) savePlaybackPosition()
    }

    private fun accumulateListening(seconds: Double) {
        val track = currentTrack ?: return
        val previous = lastProgressSample
        lastProgressSample = seconds
        if (previous == null) return
        // Only forward movement at about real time counts. A seek jumps, and a jump
        // backwards is a repeat rather than listening to count twice.
        val delta = seconds - previous
        if (delta <= 0 || delta >= 2) return
        listenedSeconds += delta

        if (hasReportedCurrentPlay || listenedSeconds < PlayThreshold.seconds(track.duration.takeIf { it > 0 } ?: duration)) return
        hasReportedCurrentPlay = true
        // Checked when it qualifies rather than when it started, so turning the setting
        // off mid-track stops that track being counted.
        if (!settings.recordPlays.value) return
        val playedAt = playStartedAt ?: Instant.now()
        scope.launch { library?.recordLocalPlay(track.id, playedAt) }
        hooks.recordPlay(track.id, playedAt)
    }

    /** Exposed for tests, which have no engine to tick. */
    internal fun accumulateListeningForTest(seconds: Double) = accumulateListening(seconds)

    // Persistence

    /**
     * Writes the queue so a relaunch can pick playback back up. Android may kill a paused
     * app at any time, and nothing about the queue lives anywhere but memory otherwise.
     */
    fun savePlaybackState() {
        publish()
        if (queue.isEmpty()) return
        stateStore.save(PlaybackState(queue.map { it.track.id }, currentIndex, shuffledIndices, isQueueUserModified))
        savePlaybackPosition()
    }

    /** Just how far into the current track playback has got — kept out of the queue file so it isn't rewritten every few seconds. */
    fun savePlaybackPosition() {
        val track = currentTrack ?: return
        lastPositionSave = System.currentTimeMillis()
        stateStore.savePosition(PlaybackPosition(track.id, _progress.value))
    }

    internal suspend fun restoreState() {
        val library = library ?: return
        val saved = stateStore.load()?.takeIf { it.trackIds.isNotEmpty() } ?: return
        // Something may have started playing while this was loading; that wins.
        if (queue.isNotEmpty()) return
        val byId = library.tracks(saved.trackIds)
        val tracks = saved.trackIds.mapNotNull { byId[it] }
        // A partial restore would shift every index, including the shuffle order. The
        // snapshot is a convenience, so drop it rather than reconstruct a different queue.
        if (tracks.size != saved.trackIds.size || queue.isNotEmpty()) return

        queue = tracks.map { QueueItem(it, artworkUrl = it.album?.artworkUrl) }
        shuffledIndices = saved.shuffledIndices?.takeIf { it.size == queue.size }
        currentIndex = saved.currentIndex.coerceIn(0, queue.size - 1)
        // A hand-built queue keeps its protection across a restart.
        isQueueUserModified = saved.isUserModified

        val track = queue.getOrNull(effectiveIndex)?.track ?: run {
            queue = emptyList()
            shuffledIndices = null
            currentIndex = 0
            publish()
            return
        }
        setCurrentTrack(track)
        duration = track.duration
        // The position is written separately and may lag the queue; only trust it for the current track.
        stateStore.loadPosition()?.takeIf { it.trackId == track.id }?.let {
            _progress.value = it.elapsed
            pendingResume = track.id to it.elapsed
        }
        publish()
    }

    fun clearPersistedState() = stateStore.clear()

    /** How much of a track has to be heard before it counts as played. */
    object PlayThreshold {
        /** However long the track runs, four minutes of it is enough. */
        const val CAP = 240.0

        /** Half the track or the cap, whichever comes first — the scrobbling convention. Skipping through an album reports nothing. */
        fun seconds(duration: Double): Double = if (duration <= 0) CAP else minOf(duration / 2, CAP)
    }

    private companion object {
        const val TICK_MILLIS = 500L
        const val POSITION_SAVE_INTERVAL_MILLIS = 5_000L
        /** How long a stream may sit unready before it counts as failed. A rejected token can produce no error of its own. */
        const val STALL_TIMEOUT_MILLIS = 15_000L
    }
}

/** Swift's `move(fromOffsets:toOffset:)`: [destination] is an index in the list before the move. */
internal fun <T> List<T>.moved(source: Set<Int>, destination: Int): List<T> {
    val moving = source.sorted().filter { it in indices }.map { this[it] }
    val before = source.count { it < destination }
    val rest = filterIndexed { i, _ -> i !in source }.toMutableList()
    rest.addAll((destination - before).coerceIn(0, rest.size), moving)
    return rest
}
