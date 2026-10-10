package com.ondumusic.player.model

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.log10

class LoudnessNormalizationTest {

    @Test fun bringsALoudMasterDownToTheTarget() {
        // -9 LUFS is a typical modern master: 5 dB above the -14 target.
        assertEquals(-5.0, dB(LoudnessNormalization.gain(-9.0)), TOLERANCE)
    }

    @Test fun leavesATrackAtTheTargetAlone() {
        assertEquals(1f, LoudnessNormalization.gain(LoudnessNormalization.TARGET_LUFS))
    }

    // We have no headroom above unity, so a track quieter than the target can't be
    // lifted to meet it.
    @Test fun doesNotBoostATrackBelowTheTarget() {
        assertEquals(1f, LoudnessNormalization.gain(-24.0))
    }

    @Test fun leavesAnUnmeasuredTrackAlone() {
        assertEquals(1f, LoudnessNormalization.gain(null))
    }

    // Album gain is one offset for the whole record, so the gap between a quiet track
    // and a loud one survives it — that's the difference from track gain, and the reason
    // album mode exists.
    @Test fun albumGainPreservesTheGapBetweenTracks() {
        val albumGain = LoudnessNormalization.gain(-10.0)

        val quietUnderAlbumGain = dB(albumGain) + -20
        val loudUnderAlbumGain = dB(albumGain) + -8
        assertEquals(12.0, loudUnderAlbumGain - quietUnderAlbumGain, TOLERANCE)

        // Track gain would have closed that gap to the 6 dB the clamp leaves behind:
        // -20 is under the target, so it can't be lifted to meet the other.
        val quietUnderTrackGain = dB(LoudnessNormalization.gain(-20.0)) + -20
        val loudUnderTrackGain = dB(LoudnessNormalization.gain(-8.0)) + -8
        assertEquals(6.0, loudUnderTrackGain - quietUnderTrackGain, TOLERANCE)
    }

    private fun dB(gain: Float): Double = 20 * log10(gain.toDouble())

    private companion object {
        const val TOLERANCE = 0.01
    }
}
