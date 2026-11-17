package com.ondumusic.player.player

import com.ondumusic.player.data.MusicLibrary
import com.ondumusic.player.model.QueueItem
import com.ondumusic.player.model.RepeatMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The queue as a user experiences it: what has played, what is playing, and what is to
 * come, through shuffle, edits and skips. History is what played and never rewrites
 * itself; only what's still to come gets reordered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class QueueBehaviourTest {
    private val dispatcher = StandardTestDispatcher()
    private val libraries = mutableListOf<MusicLibrary>()

    @After
    fun tearDown() = libraries.forEach { it.close() }

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    private fun items(vararg names: String) = names.map { makeQueueItem(name = it) }

    /** Most recent first, as the queue screen lists it. */
    private val PlayerService.history get() = s.history.map { it.item.track.name }
    private val PlayerService.current get() = s.currentItem?.track?.name
    private val PlayerService.upNext get() = s.upNext.map { it.item.track.name }

    /** A queue in a play order of the test's choosing, as a relaunch restores it. */
    private suspend fun TestScope.restored(names: List<String>, playOrder: List<Int>, currentIndex: Int): PlayerService {
        val library = makeLibrary(dispatcher).also { libraries += it }
        val ids = library.seed(names)
        val store = makeStore(this, "queue-behaviour")
        store.save(PlaybackState(ids, currentIndex, playOrder, isShuffled = true))
        store.flush()
        return makePlayer(store = store, library = library)
    }

    /** Album A–E shuffled to C, A, E, B, D: C and A played, E playing, B and D to come. */
    private suspend fun TestScope.shuffledMidway() =
        restored(listOf("A", "B", "C", "D", "E"), playOrder = listOf(2, 0, 4, 1, 3), currentIndex = 2)

    // Shuffle

    @Test
    fun shuffleOnKeepsHistoryAndCurrent() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B", "C", "D", "E", "F"), startingAt = 2)

        player.toggleShuffle()

        assertEquals(listOf("B", "A"), player.history)
        assertEquals("C", player.current)
        assertEquals(setOf("D", "E", "F"), player.upNext.toSet())
    }

    @Test
    fun shuffleOffNeverBringsBackWhatPlayed() = test {
        val player = shuffledMidway()

        player.toggleShuffle()

        assertFalse(player.s.isShuffled)
        assertEquals(listOf("A", "C"), player.history)
        assertEquals("E", player.current)
        assertEquals(listOf("B", "D"), player.upNext)
    }

    /** Unplayed tracks from before the current one come back too, in album order. */
    @Test
    fun shuffleOffPutsWhatsLeftInQueueOrder() = test {
        val player = restored(
            listOf("1", "2", "3", "4", "5", "6", "7", "8"), playOrder = listOf(6, 3, 7, 0, 1, 2, 4, 5), currentIndex = 1,
        )

        player.toggleShuffle()

        assertEquals(listOf("7"), player.history)
        assertEquals("4", player.current)
        assertEquals(listOf("1", "2", "3", "5", "6", "8"), player.upNext)
    }

    @Test
    fun togglingShuffleBackAndForthLeavesHistoryAlone() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B", "C", "D", "E", "F"), startingAt = 3)

        repeat(4) { player.toggleShuffle() }

        assertFalse(player.s.isShuffled)
        assertEquals(listOf("C", "B", "A"), player.history)
        assertEquals("D", player.current)
        assertEquals(listOf("E", "F"), player.upNext)
    }

    @Test
    fun shuffledPlayDealsTheWholeQueue() = test {
        val player = makePlayer()
        player.playShuffledQueue(items("A", "B", "C", "D"))

        assertTrue(player.s.isShuffled)
        assertTrue(player.history.isEmpty())
        assertEquals(setOf("A", "B", "C", "D"), (listOf(player.current!!) + player.upNext).toSet())
    }

    @Test
    fun playTurnsShuffleOff() = test {
        val player = makePlayer()
        player.playShuffledQueue(items("A", "B"))

        player.playNowQueue(items("C", "D", "E"), startingAt = 1)

        assertFalse(player.s.isShuffled)
        assertEquals(listOf("C"), player.history)
        assertEquals(listOf("E"), player.upNext)
    }

    // Adding while shuffled

    @Test
    fun playNextWhileShuffledKeepsTheSelectionsOrder() = test {
        val player = shuffledMidway()

        player.playNextQueue(items("X", "Y", "Z"))

        assertEquals(listOf("X", "Y", "Z", "B", "D"), player.upNext)
        assertEquals(listOf("A", "C"), player.history)
    }

    @Test
    fun playNextWhileShuffledStaysFirstWhenShuffleTurnsOff() = test {
        val player = shuffledMidway()
        player.playNextQueue(items("X", "Y"))

        player.toggleShuffle()

        assertEquals(listOf("X", "Y", "B", "D"), player.upNext)
    }

    @Test
    fun addToEndWhileShuffledKeepsTheSelectionsOrder() = test {
        val player = shuffledMidway()

        player.addToEndQueue(items("X", "Y", "Z"))

        assertEquals(listOf("B", "D", "X", "Y", "Z"), player.upNext)
    }

    @Test
    fun addToEndWhileShuffledStaysLastWhenShuffleTurnsOff() = test {
        val player = shuffledMidway()
        player.addToEndQueue(items("X", "Y"))

        player.toggleShuffle()

        assertEquals(listOf("B", "D", "X", "Y"), player.upNext)
    }

    // Editing up next

    @Test
    fun removingWhileShuffledLeavesHistoryAndCurrent() = test {
        val player = shuffledMidway()

        player.removeFromQueue(setOf(0))

        assertEquals(listOf("A", "C"), player.history)
        assertEquals("E", player.current)
        assertEquals(listOf("D"), player.upNext)
    }

    @Test
    fun movingUnshuffledChangesTheQueueOrderForGood() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B", "C", "D", "E"), startingAt = 0)

        player.moveQueueItems(setOf(3), 0)
        assertEquals(listOf("E", "B", "C", "D"), player.upNext)

        player.toggleShuffle()
        player.toggleShuffle()
        assertEquals(listOf("E", "B", "C", "D"), player.upNext)
    }

    @Test
    fun movingWhileShuffledOnlyChangesTheShuffledOrder() = test {
        val player = shuffledMidway()

        player.moveQueueItems(setOf(1), 0)
        assertEquals(listOf("D", "B"), player.upNext)

        player.toggleShuffle()
        assertEquals(listOf("B", "D"), player.upNext)
    }

    @Test
    fun removingOneOfTheSameTrackQueuedTwiceKeepsTheOther() = test {
        val player = makePlayer()
        val repeated = makeTrack(name = "R")
        val first = QueueItem(repeated)
        val second = QueueItem(repeated)
        player.playNowQueue(listOf(makeQueueItem(name = "A"), first, second), startingAt = 0)

        player.removeFromQueue(setOf(0))

        assertEquals(listOf(second.id), player.s.upNext.map { it.item.id })
    }

    // Moving through the queue

    @Test
    fun skippingAheadMovesTheTracksInBetweenToHistory() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B", "C", "D", "E"), startingAt = 0)

        player.skipTo(3)

        assertEquals(listOf("C", "B", "A"), player.history)
        assertEquals("D", player.current)
        assertEquals(listOf("E"), player.upNext)
    }

    @Test
    fun skippingBackIntoHistoryRewindsToIt() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B", "C", "D", "E"), startingAt = 3)

        player.skipTo(1)

        assertEquals(listOf("A"), player.history)
        assertEquals("B", player.current)
        assertEquals(listOf("C", "D", "E"), player.upNext)
    }

    @Test
    fun backThenSkipReturnsToWhereItWas() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B", "C"), startingAt = 1)

        player.back()
        assertEquals("A", player.current)
        assertEquals(listOf("B", "C"), player.upNext)

        player.skip()
        assertEquals("B", player.current)
        assertEquals(listOf("A"), player.history)
    }

    @Test
    fun theEndOfTheQueueCuesUpTheStartInTheOrderItPlayed() = test {
        val player = shuffledMidway()
        player.skip()
        player.skip()

        player.skip()

        assertFalse(player.s.isPlaying)
        assertTrue(player.history.isEmpty())
        assertEquals("C", player.current)
        assertEquals(listOf("A", "E", "B", "D"), player.upNext)
    }

    // Repeat

    @Test
    fun repeatOneReplaysATrackThatEndsByItself() = test {
        val engine = FakePlaybackEngine()
        val player = makePlayer(engine = engine)
        player.playNowQueue(items("A", "B"), startingAt = 0)
        player.setRepeatMode(RepeatMode.One)

        engine.end()

        assertEquals("A", player.current)
    }

    @Test
    fun repeatAllPlaysTheSameOrderAgain() = test {
        val player = shuffledMidway()
        player.setRepeatMode(RepeatMode.All)
        player.skip()
        player.skip()

        player.skip()

        assertTrue(player.history.isEmpty())
        assertEquals("C", player.current)
        assertEquals(listOf("A", "E", "B", "D"), player.upNext)
    }

    // Persistence

    @Test
    fun aRelaunchKeepsHistoryAfterShuffleWasTurnedOff() = test {
        val library = makeLibrary(dispatcher).also { libraries += it }
        val ids = library.seed(listOf("A", "B", "C", "D", "E"))
        val store = makeStore(this, "queue-behaviour-relaunch")
        store.save(PlaybackState(ids, 2, listOf(2, 0, 4, 1, 3), isShuffled = true))
        store.flush()
        makePlayer(store = store, library = library).toggleShuffle()
        store.flush()

        val relaunched = makePlayer(store = store, library = library)

        assertFalse(relaunched.s.isShuffled)
        assertEquals(listOf("A", "C"), relaunched.history)
        assertEquals("E", relaunched.current)
        assertEquals(listOf("B", "D"), relaunched.upNext)
    }

    /** Saved before shuffle and play order were told apart, a play order meant shuffled. */
    @Test
    fun anOlderSnapshotWithAPlayOrderRestoresShuffled() = test {
        val library = makeLibrary(dispatcher).also { libraries += it }
        val ids = library.seed(listOf("A", "B", "C"))
        val store = makeStore(this, "queue-behaviour-old")
        store.save(PlaybackState(ids, 0, listOf(2, 0, 1)))
        store.flush()

        val player = makePlayer(store = store, library = library)

        assertTrue(player.s.isShuffled)
        assertEquals("C", player.current)
    }
}
