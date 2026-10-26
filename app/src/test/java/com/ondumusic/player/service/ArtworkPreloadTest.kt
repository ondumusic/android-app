package com.ondumusic.player.service

import com.ondumusic.player.service.ArtworkPreload.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkPreloadTest {
    private val large = ArtworkPreload.SMALL_BATCH + 1

    @Test
    fun everythingPreloadsOnAnUnmeteredNetwork() {
        assertEquals(Decision.Now, ArtworkPreload.decision(5, isMetered = false, isDataSaver = false))
        assertEquals(Decision.Now, ArtworkPreload.decision(large, isMetered = false, isDataSaver = false))
    }

    @Test
    fun onMobileDataABigPreloadWaitsButASmallOneGoesAhead() {
        assertEquals(Decision.Later, ArtworkPreload.decision(large, isMetered = true, isDataSaver = false))
        assertEquals(Decision.Now, ArtworkPreload.decision(5, isMetered = true, isDataSaver = false))
    }

    @Test
    fun dataSaverPreloadsNothingNow() {
        assertEquals(Decision.Later, ArtworkPreload.decision(large, isMetered = true, isDataSaver = true))
        assertEquals(Decision.Never, ArtworkPreload.decision(5, isMetered = true, isDataSaver = true))
    }

    @Test
    fun aDeferredPreloadRunsOnceTheNetworkIsUnmetered() {
        val fetched = mutableListOf<String>()
        var conditions = ArtworkPreload.Conditions(isMetered = true, isDataSaver = false)
        val preload = ArtworkPreload({ fetched += it }) { conditions }
        val urls = (1..large).map { "https://music.example.com/art/$it" }

        preload.preload(urls)
        assertTrue(fetched.isEmpty())

        conditions = ArtworkPreload.Conditions(isMetered = false, isDataSaver = false)
        preload.preloadDeferred()
        assertEquals(urls, fetched)

        preload.preloadDeferred()
        assertEquals("nothing is fetched twice", urls.size, fetched.size)
    }

    @Test
    fun signingOutDropsWhatWasWaiting() {
        val fetched = mutableListOf<String>()
        var conditions = ArtworkPreload.Conditions(isMetered = true, isDataSaver = false)
        val preload = ArtworkPreload({ fetched += it }) { conditions }
        preload.preload((1..large).map { "https://music.example.com/art/$it" })

        preload.clear()
        conditions = ArtworkPreload.Conditions(isMetered = false, isDataSaver = false)
        preload.preloadDeferred()

        assertTrue(fetched.isEmpty())
    }
}
