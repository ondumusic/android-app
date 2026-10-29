package com.ondumusic.player.player

/**
 * Records what [PlayerService] asks of the audio layer and lets a test play the part of
 * the player reporting back.
 */
class FakePlaybackEngine : PlaybackEngine {
    data class Play(val source: PlaybackEngine.Source, val gain: Float, val startAt: Double)
    data class Gapless(val source: PlaybackEngine.Source?, val gain: Float)
    data class Crossfade(val source: PlaybackEngine.Source?, val gain: Float, val fadeSeconds: Double, val duration: Double?)

    override var listener: PlaybackEngine.Listener? = null

    override var position: Double = 0.0
    override var duration: Double? = null

    val plays = mutableListOf<Play>()
    val gaplessCalls = mutableListOf<Gapless>()
    val crossfadeCalls = mutableListOf<Crossfade>()
    val seeks = mutableListOf<Double>()
    val volumes = mutableListOf<Float>()
    val currentGains = mutableListOf<Float>()
    var pauseCount = 0
    var resumeCount = 0
    var stopCount = 0
    var released = false

    /** What follows the current source once the latest calls are applied — null when nothing is lined up. */
    val linedUpGapless: Gapless? get() = gaplessCalls.lastOrNull()?.takeIf { it.source != null }
    val armedCrossfade: Crossfade? get() = crossfadeCalls.lastOrNull()?.takeIf { it.source != null }

    override fun play(source: PlaybackEngine.Source, gain: Float, startAt: Double) {
        plays += Play(source, gain, startAt)
        position = startAt
    }

    override fun setNextGapless(source: PlaybackEngine.Source?, gain: Float) {
        gaplessCalls += Gapless(source, gain)
    }

    override fun armCrossfade(source: PlaybackEngine.Source?, gain: Float, fadeSeconds: Double, duration: Double?) {
        crossfadeCalls += Crossfade(source, gain, fadeSeconds, duration)
    }

    override fun pause() {
        pauseCount++
    }

    override fun resume() {
        resumeCount++
    }

    override fun seek(seconds: Double) {
        seeks += seconds
        position = seconds
    }

    override fun stop() {
        stopCount++
    }

    override fun setVolume(volume: Float) {
        volumes += volume
    }

    override fun setCurrentGain(gain: Float) {
        currentGains += gain
    }

    override fun release() {
        released = true
    }

    // Events, as the real engine would report them

    fun ready() = listener!!.onReady()
    fun fail(error: PlaybackError) = listener!!.onError(error)
    fun end() = listener!!.onEnded()
    fun advanceGapless() = listener!!.onAdvancedGapless()
    fun completeCrossfade() = listener!!.onCrossfadeCompleted()
    fun playingChangedExternally(isPlaying: Boolean) = listener!!.onPlayingChangedExternally(isPlaying)
}
