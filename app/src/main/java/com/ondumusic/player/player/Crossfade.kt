package com.ondumusic.player.player

import kotlin.math.max
import kotlin.math.min

/** When a fade should be under way. */
object CrossfadeWindow {
    /**
     * How long is left once the fade should have started, or null outside the window.
     * Measured against [duration] as the app knows it, when it does: a decoder's own
     * duration for a transcode is a guess from what has arrived, and by that clock the
     * window may never be reached and the track just ends.
     */
    fun remaining(duration: Double?, position: Double, fadeSeconds: Double): Double? {
        if (duration == null || !duration.isFinite() || !position.isFinite() || duration <= 0) return null
        val remaining = duration - position
        return if (remaining > 0 && remaining <= fadeSeconds) remaining else null
    }
}

/**
 * A fade in progress, measured in time spent playing rather than time since it began,
 * so pausing freezes it where it is and resuming picks it up from there.
 */
class CrossfadeRamp(val fadeSeconds: Double) {
    private var elapsed = 0.0

    /** 0 at the start of the fade, 1 once it's done. */
    val fraction: Float get() = min(elapsed / fadeSeconds, 1.0).toFloat()

    val isComplete: Boolean get() = elapsed >= fadeSeconds

    fun advance(seconds: Double) {
        elapsed += max(0.0, seconds)
    }
}
