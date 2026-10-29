package com.ondumusic.player.player

/**
 * The part of playback that makes sound. [PlayerService] owns the queue and decides what
 * plays next and how the two tracks meet; the engine carries that out and reports back.
 * Kept behind an interface so the queue logic can be tested without audio.
 */
interface PlaybackEngine {
    /** A playable track: a stream URL (with whatever transcode query it needs) or a downloaded file. */
    data class Source(val uri: String, val isLocal: Boolean)

    interface Listener {
        /** The current source started producing audio. */
        fun onReady()
        /** The current source can't be played. [unauthorized] when the server rejected the session. */
        fun onError(message: PlaybackError)
        /** The current source played to its end with nothing queued behind it. */
        fun onEnded()
        /** Playback moved onto the source given to [setNextGapless] without a gap. */
        fun onAdvancedGapless()
        /** A crossfade finished; the source given to [armCrossfade] is now the current one. */
        fun onCrossfadeCompleted()
        /** Playing stopped or started for a reason outside the app — focus loss, headphones unplugged. */
        fun onPlayingChangedExternally(isPlaying: Boolean)
    }

    var listener: Listener?

    /** Seconds into the current source. */
    val position: Double

    /** The current source's length as decoded, when known. */
    val duration: Double?

    /** Stops whatever is playing, drops anything queued or fading, and starts [source] at [startAt] seconds. */
    fun play(source: Source, gain: Float, startAt: Double = 0.0)

    /** Queues [source] to follow the current one with no gap. Null takes back anything queued. */
    fun setNextGapless(source: Source?, gain: Float = 1f)

    /**
     * Fades [source] in over the last [fadeSeconds] of the current one. Null takes it back,
     * including a fade already under way. [duration] is the current source's length as the
     * app shows it; the window is measured against that rather than the decoder's own
     * duration, which for a transcode is a guess from what has arrived so far.
     */
    fun armCrossfade(source: Source?, gain: Float = 1f, fadeSeconds: Double = 0.0, duration: Double? = null)

    fun pause()
    fun resume()
    fun seek(seconds: Double)
    fun stop()

    /** The user's volume, 0..1. Scales everything, including a crossfade's ramp. */
    fun setVolume(volume: Float)

    /** Loudness normalisation for what's playing now — applied mid-track when the setting changes. */
    fun setCurrentGain(gain: Float)

    fun release()
}

sealed interface PlaybackError {
    data object Unauthorized : PlaybackError
    data object Unreachable : PlaybackError
    data object Unplayable : PlaybackError
    data class Other(val message: String) : PlaybackError
}
