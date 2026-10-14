package com.ondumusic.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeasuredDurationTest {
    @Test
    fun aTranscodedStreamsEstimateDoesNotReplaceTheTracksDuration() =
        assertNull(PlayerService.measuredDuration(0.4, known = 245.0))

    @Test
    fun theEnginesDurationFillsInWhenTheTrackHasNone() {
        assertEquals(245.0, PlayerService.measuredDuration(245.0, known = 0.0)!!, 0.0)
        assertEquals(245.0, PlayerService.measuredDuration(245.0, known = null)!!, 0.0)
        assertNull(PlayerService.measuredDuration(Double.NaN, known = 0.0))
        assertNull(PlayerService.measuredDuration(null, known = 0.0))
    }
}
