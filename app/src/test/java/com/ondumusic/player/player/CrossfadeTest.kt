package com.ondumusic.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeTest {
    @Test
    fun theFadeStartsInsideItsWindowAndNotBefore() {
        assertNull(CrossfadeWindow.remaining(duration = 200.0, position = 180.0, fadeSeconds = 12.0))
        assertEquals(10.0, CrossfadeWindow.remaining(duration = 200.0, position = 190.0, fadeSeconds = 12.0)!!, 0.0)
        assertNull(CrossfadeWindow.remaining(duration = 200.0, position = 200.5, fadeSeconds = 12.0))
    }

    @Test
    fun theFadeWaitsForADurationItCanTrust() {
        assertNull(CrossfadeWindow.remaining(duration = null, position = 5.0, fadeSeconds = 12.0))
        assertNull(CrossfadeWindow.remaining(duration = 0.0, position = 5.0, fadeSeconds = 12.0))
        assertNull(CrossfadeWindow.remaining(duration = Double.NaN, position = 5.0, fadeSeconds = 12.0))
    }

    /** Only time spent playing counts: a pause in the middle of a fade must not finish it. */
    @Test
    fun aFadeOnlyAdvancesWhilePlaying() {
        val ramp = CrossfadeRamp(fadeSeconds = 12.0)
        ramp.advance(3.0)
        assertEquals(0.25f, ramp.fraction, 0f)
        assertFalse(ramp.isComplete)
        ramp.advance(9.0)
        assertEquals(1f, ramp.fraction, 0f)
        assertTrue(ramp.isComplete)
    }

    @Test
    fun aClockThatRanBackwardsDoesNotRewindTheFade() {
        val ramp = CrossfadeRamp(fadeSeconds = 4.0)
        ramp.advance(2.0)
        ramp.advance(-5.0)
        assertEquals(0.5f, ramp.fraction, 0f)
    }
}
