package com.ondumusic.player.model

import kotlin.math.min
import kotlin.math.pow

/**
 * Playing every track at a similar loudness.
 *
 * The server measures each track's integrated loudness (EBU R128, reported in LUFS) and
 * we scale playback toward a common target, so a loudness-war master and a dynamic one
 * don't jump 10 dB apart when they follow each other.
 *
 * **We can only attenuate.** ExoPlayer's volume is a multiplier clamped to 0.0–1.0, so
 * there is no headroom above unity to boost a quiet track into — getting one would mean
 * putting a gain stage in the audio processor chain. The gain is therefore clamped at
 * 0 dB: loud masters come down to the target, anything already quieter is left where
 * it is.
 */
object LoudnessNormalization {

    /**
     * The level playback is scaled toward — the streaming convention, and what this
     * library's own distribution asks for.
     *
     * Started at -18 (ReplayGain 2.0's reference) on the theory that a lower target
     * leaves fewer tracks below it and so uncorrected. Measured against a real 4,500
     * track library that turned out to be paying for almost nothing: its 10th percentile
     * sat at -16.5 LUFS, so -18 bought about half a dB of extra evenness and charged
     * 3.4 dB of level for it, running a typical track at 45% of full scale.
     *
     * Because we can only attenuate, target level and evenness are the same dial: every
     * dB the target rises is a dB of residual spread against the tracks already below
     * it, which can't be lifted. -14 leaves roughly the quietest tenth uncorrected by at
     * most a couple of dB, which is the better end of that trade on a phone at full
     * volume. Boosting is what removes the trade rather than repositioning it.
     */
    const val TARGET_LUFS: Double = -14.0

    /**
     * A linear multiplier for the player's volume.
     *
     * An unmeasured track gets 1.0 and plays as-is. That makes a partly-measured library
     * uneven — the unmeasured tracks are the loud ones — but it is honest, and it fixes
     * itself as the server finishes measuring.
     */
    fun gain(lufs: Double?): Float {
        if (lufs == null) return 1f
        return 10.0.pow(min(0.0, TARGET_LUFS - lufs) / 20).toFloat()
    }
}
