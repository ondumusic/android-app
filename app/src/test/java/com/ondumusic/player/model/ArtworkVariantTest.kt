package com.ondumusic.player.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkVariantTest {

    private val sizes = listOf(variant(64), variant(300), variant(640), variant(1200))

    @Test fun picksTheSmallestThatCoversTheRequest() {
        assertEquals(300, sizes.covering(100)?.width)
        assertEquals(300, sizes.covering(300)?.width)
        assertEquals(640, sizes.covering(301)?.width)
    }

    // Artwork is never upscaled by the server, so "bigger than every variant" is a real
    // case rather than an error — the source image simply isn't that large.
    @Test fun fallsBackToTheLargestWhenNothingIsBigEnough() {
        assertEquals(1200, sizes.covering(4000)?.width)
    }

    @Test fun hasNothingToPickFromWhenEmpty() {
        assertNull(emptyList<ArtworkVariant>().covering(300))
        assertNull(emptyList<ArtworkVariant>().largest)
    }

    @Test fun largestIsTheWidest() {
        assertEquals(1200, sizes.largest?.width)
    }

    @Test fun doesNotAssumeTheServerOrdersThem() {
        val shuffled = listOf(variant(640), variant(64), variant(1200), variant(300))
        assertEquals(300, shuffled.covering(100)?.width)
        assertEquals(1200, shuffled.largest?.width)
    }

    private fun variant(width: Int) = ArtworkVariant(url = "https://example.com/$width.jpg", width = width, height = width)
}
