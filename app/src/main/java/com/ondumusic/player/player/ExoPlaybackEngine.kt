package com.ondumusic.player.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Two ExoPlayers: the primary plays, and a secondary exists only during a crossfade, after
 * which it becomes the primary. Gapless playback needs no second player — the next source
 * goes into the primary's own playlist, which ExoPlayer joins without a gap.
 */
@OptIn(UnstableApi::class)
class ExoPlaybackEngine(
    private val context: Context,
    cache: Cache,
    /**
     * The Authorization header for a request to this URL, or null to send none. Read per
     * request, so a re-auth's new token reaches streams without rebuilding anything.
     */
    private val authorization: (Uri) -> String?,
) : PlaybackEngine {
    override var listener: PlaybackEngine.Listener? = null

    private val handler = Handler(Looper.getMainLooper())

    private val dataSourceFactory: DataSource.Factory = run {
        val http = ResolvingDataSource.Factory(DefaultHttpDataSource.Factory()) { spec ->
            authorization(spec.uri)?.let { spec.withAdditionalHeaders(mapOf("Authorization" to it)) } ?: spec
        }
        // Streams are cached as they play, so a replay or a seek back costs nothing; local
        // files bypass it.
        val cached = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(http)
        DefaultDataSource.Factory(context, cached)
    }

    private var primary: ExoPlayer = buildPlayer(ownsFocus = true)
    private var secondary: ExoPlayer? = null

    private var userVolume = 1f
    private var primaryGain = 1f
    private var queuedGain = 1f
    private var hasQueuedGapless = false
    private var reportedReady = false

    private data class PendingCrossfade(val source: PlaybackEngine.Source, val gain: Float, val fadeSeconds: Double)
    private var pendingCrossfade: PendingCrossfade? = null
    private var fadeStartedAt = 0L
    private var fadeLength = 0.0
    private var secondaryGain = 1f

    private val primaryListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> if (!reportedReady) {
                    reportedReady = true
                    listener?.onReady()
                }
                Player.STATE_ENDED -> if (secondary == null) listener?.onEnded()
                // PlayerService counts from loading a source to onReady as buffering; idle follows
                // a stop or an error, both reported on their own.
                Player.STATE_BUFFERING, Player.STATE_IDLE -> Unit
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || !hasQueuedGapless) return
            hasQueuedGapless = false
            // The finished source is no use to anyone; dropping it keeps the playlist at one.
            primary.removeMediaItem(0)
            // Both tracks share one player here, so the gain has to change under the playhead at the boundary.
            primaryGain = queuedGain
            applyVolume()
            listener?.onAdvancedGapless()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
            ) {
                cancelFade()
                listener?.onPlayingChangedExternally(playWhenReady)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            listener?.onError(error.toPlaybackError())
        }
    }

    init {
        primary.addListener(primaryListener)
    }

    /**
     * Only the primary handles audio focus and the headphones-unplugged broadcast; a fading-in
     * secondary asking for focus would make the primary give it up mid-fade.
     */
    private fun buildPlayer(ownsFocus: Boolean): ExoPlayer =
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, OpusGaplessExtractorsFactory()))
            .setAudioAttributes(musicAttributes, ownsFocus)
            .setHandleAudioBecomingNoisy(ownsFocus)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

    override val position: Double get() = primary.currentPosition / 1000.0

    override val duration: Double?
        get() = primary.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { it / 1000.0 }

    override fun play(source: PlaybackEngine.Source, gain: Float, startAt: Double) {
        cancelFade()
        pendingCrossfade = null
        hasQueuedGapless = false
        reportedReady = false
        primaryGain = gain
        applyVolume()
        primary.setMediaItem(MediaItem.fromUri(source.uri), (startAt * 1000).toLong())
        primary.prepare()
        primary.playWhenReady = true
    }

    override fun setNextGapless(source: PlaybackEngine.Source?, gain: Float) {
        while (primary.mediaItemCount > 1) primary.removeMediaItem(primary.mediaItemCount - 1)
        hasQueuedGapless = source != null
        if (source == null) return
        queuedGain = gain
        primary.addMediaItem(MediaItem.fromUri(source.uri))
    }

    override fun armCrossfade(source: PlaybackEngine.Source?, gain: Float, fadeSeconds: Double) {
        cancelFade()
        pendingCrossfade = source?.takeIf { fadeSeconds > 0 }?.let { PendingCrossfade(it, gain, fadeSeconds) }
        if (pendingCrossfade != null) handler.post(watchForFade)
    }

    /** Checks four times a second whether the primary is inside its fade window. */
    private val watchForFade: Runnable = object : Runnable {
        override fun run() {
            val pending = pendingCrossfade ?: return
            val total = duration
            if (total != null && primary.playWhenReady) {
                val remaining = total - position
                if (remaining > 0 && remaining <= pending.fadeSeconds) {
                    beginCrossfade(pending, remaining)
                    return
                }
            }
            handler.postDelayed(this, 250)
        }
    }

    private fun beginCrossfade(pending: PendingCrossfade, remaining: Double) {
        pendingCrossfade = null
        val next = buildPlayer(ownsFocus = false)
        secondary = next
        secondaryGain = pending.gain
        next.volume = 0f
        next.setMediaItem(MediaItem.fromUri(pending.source.uri))
        next.prepare()
        next.playWhenReady = true
        fadeStartedAt = System.currentTimeMillis()
        fadeLength = min(pending.fadeSeconds, remaining)
        handler.post(ramp)
    }

    /** Equal-power: the two curves sum to constant loudness, where a linear fade dips in the middle. */
    private val ramp: Runnable = object : Runnable {
        override fun run() {
            val next = secondary ?: return
            val progress = min((System.currentTimeMillis() - fadeStartedAt) / 1000.0 / fadeLength, 1.0).toFloat()
            primary.volume = userVolume * primaryGain * cos(progress * PI.toFloat() / 2)
            next.volume = userVolume * secondaryGain * sin(progress * PI.toFloat() / 2)
            if (progress >= 1f) completeCrossfade(next) else handler.postDelayed(this, 50)
        }
    }

    /** The secondary is already playing at the right place; promoting it avoids any stutter from re-seeking. */
    private fun completeCrossfade(next: ExoPlayer) {
        val old = primary
        old.removeListener(primaryListener)
        // Taking focus before the old player lets go of it keeps another app from slipping in between.
        next.setAudioAttributes(musicAttributes, true)
        next.setHandleAudioBecomingNoisy(true)
        old.release()
        primary = next
        secondary = null
        primaryGain = secondaryGain
        reportedReady = true
        hasQueuedGapless = false
        applyVolume()
        primary.addListener(primaryListener)
        listener?.onCrossfadeCompleted()
    }

    private fun cancelFade() {
        handler.removeCallbacks(watchForFade)
        handler.removeCallbacks(ramp)
        secondary?.release()
        secondary = null
        secondaryGain = 1f
        applyVolume()
    }

    override fun pause() {
        // Pausing mid-fade would leave both tracks half-loud; settle on the current one.
        cancelFade()
        primary.playWhenReady = false
    }

    override fun resume() {
        primary.playWhenReady = true
        pendingCrossfade?.let { handler.post(watchForFade) }
    }

    override fun seek(seconds: Double) {
        if (secondary != null) cancelFade()
        primary.seekTo(0, (seconds * 1000).toLong())
        pendingCrossfade?.let {
            handler.removeCallbacks(watchForFade)
            handler.post(watchForFade)
        }
    }

    override fun stop() {
        cancelFade()
        pendingCrossfade = null
        hasQueuedGapless = false
        primary.stop()
        primary.clearMediaItems()
    }

    override fun setVolume(volume: Float) {
        userVolume = volume
        applyVolume()
    }

    override fun setCurrentGain(gain: Float) {
        primaryGain = gain
        applyVolume()
    }

    private fun applyVolume() {
        primary.volume = userVolume * primaryGain
    }

    override fun release() {
        cancelFade()
        primary.release()
    }

    private companion object {
        val musicAttributes: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        fun PlaybackException.toPlaybackError(): PlaybackError {
            val http = generateSequence(cause) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
            return when {
                http?.responseCode == 401 -> PlaybackError.Unauthorized
                errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> PlaybackError.Unreachable
                errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED ||
                    errorCode in PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
                    PlaybackError.Unplayable
                else -> PlaybackError.Other(errorCodeName)
            }
        }
    }
}
