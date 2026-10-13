package com.ondumusic.player.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArtworkPrefetcherTest {

    @Test
    fun onlyACoupleRunAtOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val prefetcher = ArtworkPrefetcher(backgroundScope, parallel = 2) { started += it; gate.await() }

        prefetcher.prefetch(listOf("a", "b", "c", "d"))
        runCurrent()
        assertEquals(listOf("a", "b"), started)

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("a", "b", "c", "d"), started)
    }

    @Test
    fun aFailedCoverDoesNotStopTheRest() = runTest {
        val fetched = mutableListOf<String>()
        val prefetcher = ArtworkPrefetcher(backgroundScope, parallel = 1) {
            if (it == "bad") error("unreachable")
            fetched += it
        }

        prefetcher.prefetch(listOf("bad", "good"))
        runCurrent()

        assertEquals(listOf("good"), fetched)
    }

    @Test
    fun clearingDropsWhatHasNotStarted() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val prefetcher = ArtworkPrefetcher(backgroundScope, parallel = 1) { started += it; gate.await() }
        prefetcher.prefetch(listOf("a", "b", "c"))
        runCurrent()

        prefetcher.clear()
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf("a"), started)
    }
}
