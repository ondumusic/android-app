package com.ondumusic.player.player

import com.ondumusic.player.data.MusicLibrary
import com.ondumusic.player.model.QueueItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaybackStateTest {
    private val dispatcher = StandardTestDispatcher()
    private val libraries = mutableListOf<MusicLibrary>()

    @After
    fun tearDown() = libraries.forEach { it.close() }

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    private fun library() = makeLibrary(dispatcher).also { libraries += it }

    private fun TestScope.store() = makeStore(this, "playback-state")

    // Store

    @Test
    fun roundTripsTheQueueThroughDisk() = test {
        val store = store()
        val state = PlaybackState(listOf(UUID.randomUUID(), UUID.randomUUID()), 1, listOf(1, 0), isUserModified = true)
        store.save(state)
        store.flush()

        assertEquals(state, store.load())
    }

    @Test
    fun roundTripsThePosition() = test {
        val store = store()
        val position = PlaybackPosition(UUID.randomUUID(), 42.5)
        store.savePosition(position)

        assertEquals(position, store.loadPosition())
    }

    @Test
    fun loadPositionReturnsNullWhenNothingSaved() = test {
        assertNull(store().loadPosition())
    }

    @Test
    fun loadReturnsNullWhenNothingSaved() = test {
        assertNull(store().load())
    }

    @Test
    fun clearRemovesBothHalves() = test {
        val store = store()
        store.save(PlaybackState(listOf(UUID.randomUUID()), 0, null))
        store.savePosition(PlaybackPosition(UUID.randomUUID(), 10.0))
        // Without the flush the queued write can land after the clear and recreate it.
        store.flush()
        store.clear()

        assertNull(store.load())
        assertNull(store.loadPosition())
    }

    // Restore

    @Test
    fun restoresQueueAndPositionOnLaunch() = test {
        val lib = library()
        val ids = lib.seed(List(4) { "Track $it" })
        val store = store()
        store.save(PlaybackState(ids, 2, null))
        store.savePosition(PlaybackPosition(ids[2], 61.0))
        store.flush()

        val player = makePlayer(store = store, library = lib)

        assertEquals(ids, player.s.queue.map { it.track.id })
        assertEquals(2, player.s.currentIndex)
        assertEquals(ids[2], player.s.currentTrack?.id)
        assertEquals(61.0, player.progress.value, 0.0)
        assertFalse(player.s.isPlaying)
    }

    @Test
    fun ignoresAPositionBelongingToADifferentTrack() = test {
        val lib = library()
        val ids = lib.seed(List(3) { "Track $it" })
        val store = store()
        store.save(PlaybackState(ids, 0, null))
        store.savePosition(PlaybackPosition(ids[2], 90.0))
        store.flush()

        val player = makePlayer(store = store, library = lib)

        assertEquals(ids[0], player.s.currentTrack?.id)
        assertEquals(0.0, player.progress.value, 0.0)
    }

    @Test
    fun restoresShuffleOrder() = test {
        val lib = library()
        val ids = lib.seed(List(3) { "Track $it" })
        val store = store()
        store.save(PlaybackState(ids, 0, listOf(2, 0, 1)))
        store.flush()

        val player = makePlayer(store = store, library = lib)

        assertEquals(listOf(2, 0, 1), player.s.shuffledIndices)
        assertEquals(ids[2], player.s.currentTrack?.id)
    }

    @Test
    fun dropsSnapshotWhenATrackNoLongerExists() = test {
        val lib = library()
        val ids = lib.seed(List(2) { "Track $it" })
        val store = store()
        store.save(PlaybackState(ids + UUID.randomUUID(), 0, null))
        store.flush()

        val player = makePlayer(store = store, library = lib)

        assertTrue(player.s.queue.isEmpty())
        assertNull(player.s.currentTrack)
    }

    @Test
    fun ignoresShuffleOrderThatNoLongerMatchesTheQueue() = test {
        val lib = library()
        val ids = lib.seed(List(3) { "Track $it" })
        val store = store()
        store.save(PlaybackState(ids, 0, listOf(1, 0)))
        store.flush()

        val player = makePlayer(store = store, library = lib)

        assertNull(player.s.shuffledIndices)
        assertEquals(3, player.s.queue.size)
    }

    @Test
    fun clampsAnOutOfRangeIndex() = test {
        val lib = library()
        val ids = lib.seed(List(2) { "Track $it" })
        val store = store()
        store.save(PlaybackState(ids, 99, null))
        store.flush()

        val player = makePlayer(store = store, library = lib)

        assertEquals(1, player.s.currentIndex)
        assertEquals(ids[1], player.s.currentTrack?.id)
    }

    @Test
    fun restoresNothingWithoutALibrary() = test {
        val store = store()
        store.save(PlaybackState(listOf(UUID.randomUUID()), 0, null))
        store.flush()

        val player = makePlayer(store = store, library = null)

        assertTrue(player.s.queue.isEmpty())
    }

    // Save

    @Test
    fun savesTheQueueOnMutation() = test {
        val lib = library()
        val ids = lib.seed(List(3) { "Track $it" })
        val store = store()
        val player = makePlayer(store = store, library = lib)

        player.playNextQueue(ids.map { QueueItem(lib.track(it)!!) })
        store.flush()

        assertEquals(ids, store.load()?.trackIds)
    }

    @Test
    fun anEmptyQueueLeavesTheLastSnapshotAlone() = test {
        val lib = library()
        val ids = lib.seed(List(2) { "Track $it" })
        val store = store()
        val state = PlaybackState(ids, 0, null)
        store.save(state)
        store.flush()

        val player = makePlayer(store = store, library = null)
        player.savePlaybackState()
        store.flush()

        assertEquals(state, store.load())
    }

    @Test
    fun savedShuffleOrderSurvivesAToggle() = test {
        val lib = library()
        val ids = lib.seed(List(5) { "Track $it" })
        val store = store()
        store.save(PlaybackState(ids, 0, null))
        store.flush()

        val player = makePlayer(store = store, library = lib)
        player.toggleShuffle()
        store.flush()

        assertEquals((0 until 5).toList(), store.load()?.shuffledIndices?.sorted())
    }

    // Resume

    /** The restored position is where the first play starts from, not just what the scrubber shows. */
    @Test
    fun theFirstPlayAfterARestoreResumesWhereItLeftOff() = test {
        val lib = library()
        val ids = lib.seed(List(2) { "Track $it" })
        val store = store()
        store.save(PlaybackState(ids, 1, null))
        store.savePosition(PlaybackPosition(ids[1], 61.0))
        store.flush()
        val engine = FakePlaybackEngine()
        val player = makePlayer(engine, store = store, library = lib)

        player.togglePlayPause()
        testScheduler.runCurrent()

        assertEquals("http://localhost/stream/1", engine.plays.single().source.uri)
        assertEquals(61.0, engine.plays.single().startAt, 0.0)
    }

    /** A skip before the first play must not seek the new track to the old one's position. */
    @Test
    fun aSkipBeforeTheFirstPlayStartsTheNewTrackAtZero() = test {
        val lib = library()
        val ids = lib.seed(List(2) { "Track $it" })
        val store = store()
        store.save(PlaybackState(ids, 0, null))
        store.savePosition(PlaybackPosition(ids[0], 61.0))
        store.flush()
        val engine = FakePlaybackEngine()
        val player = makePlayer(engine, store = store, library = lib)

        player.skip()
        testScheduler.runCurrent()

        assertEquals(ids[1], player.s.currentTrack?.id)
        assertEquals(0.0, engine.plays.single().startAt, 0.0)
    }
}
