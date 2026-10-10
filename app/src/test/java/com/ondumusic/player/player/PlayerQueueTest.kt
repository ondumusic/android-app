package com.ondumusic.player.player

import com.ondumusic.player.data.MusicLibrary
import com.ondumusic.player.model.QueueItem
import com.ondumusic.player.model.RepeatMode
import com.ondumusic.player.player.PlayerService.QueueConfirmationKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlayerQueueTest {
    private val dispatcher = StandardTestDispatcher()
    private val libraries = mutableListOf<MusicLibrary>()

    @After
    fun tearDown() = libraries.forEach { it.close() }

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    private fun items(vararg names: String) = names.map { makeQueueItem(name = it) }

    private val PlayerService.names get() = s.queue.map { it.track.name }

    /**
     * A queue named [names] as a relaunch restores it. The only way to hand the player a
     * shuffle order and position of the test's choosing, which the Swift tests set directly.
     */
    private suspend fun TestScope.restoredPlayer(names: List<String>, currentIndex: Int, shuffled: List<Int>?): PlayerService {
        val library = makeLibrary(dispatcher).also { libraries += it }
        val ids = library.seed(names)
        val store = makeStore(this, "queue")
        store.save(PlaybackState(ids, currentIndex, shuffled))
        store.flush()
        return makePlayer(store = store, library = library)
    }

    /** Queue A,B,C,D shuffled to play D,B,A,C, stopped on A — so B and D are history and C is still to come. */
    private suspend fun TestScope.shuffledPlayerMidway() =
        restoredPlayer(listOf("A", "B", "C", "D"), currentIndex = 2, shuffled = listOf(3, 1, 0, 2))

    // Play now

    @Test
    fun playNowSetsQueue() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B", "C"), startingAt = 1)

        assertEquals(3, player.s.queue.size)
        assertEquals(1, player.s.currentIndex)
        assertNull(player.s.shuffledIndices)
    }

    @Test
    fun playNowClearsShuffle() = test {
        val player = makePlayer()
        player.playShuffledQueue(items("A", "B"))
        assertNotNull(player.s.shuffledIndices)

        player.playNowQueue(items("C"), startingAt = 0)
        assertNull(player.s.shuffledIndices)
    }

    // Play shuffled

    @Test
    fun playShuffledSetsIndices() = test {
        val player = makePlayer()
        player.playShuffledQueue((0 until 10).map { makeQueueItem(name = "T$it") })

        assertEquals(10, player.s.queue.size)
        assertEquals(10, player.s.shuffledIndices?.size)
        assertEquals(0, player.s.currentIndex)
    }

    @Test
    fun playShuffledIsPermutation() = test {
        val player = makePlayer()
        player.playShuffledQueue((0 until 10).map { makeQueueItem(name = "T$it") })

        assertEquals((0 until 10).toSet(), player.s.shuffledIndices!!.toSet())
    }

    @Test
    fun toggleShuffleOn() = test {
        val player = makePlayer()
        player.playNowQueue((0 until 5).map { makeQueueItem(name = "T$it") }, startingAt = 2)

        player.toggleShuffle()

        assertTrue(player.s.isShuffled)
        // The current track leads the shuffled order.
        assertEquals(0, player.s.currentIndex)
        assertEquals(2, player.s.shuffledIndices?.get(0))
    }

    @Test
    fun toggleShuffleOff() = test {
        val player = makePlayer()
        player.playNowQueue((0 until 5).map { makeQueueItem(name = "T$it") }, startingAt = 0)
        player.toggleShuffle()
        val realIndex = player.effectiveIndex

        player.toggleShuffle()

        assertFalse(player.s.isShuffled)
        assertNull(player.s.shuffledIndices)
        assertEquals(realIndex, player.s.currentIndex)
    }

    @Test
    fun playShuffledEmptyQueueNoOp() = test {
        val player = makePlayer()
        player.playShuffledQueue(emptyList())

        assertTrue(player.s.queue.isEmpty())
        assertNull(player.s.shuffledIndices)
    }

    // Play next / add to end

    @Test
    fun playNextInsertsAfterCurrent() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B", "C"), startingAt = 0)

        player.playNextQueue(items("X"))

        assertEquals(4, player.s.queue.size)
        assertEquals("X", player.s.queue[1].track.name)
        assertEquals(QueueConfirmationKind.PlayNext, player.queueConfirmation.value?.kind)
        assertEquals(1, player.queueConfirmation.value?.count)
    }

    @Test
    fun playNextOnEmptyQueueStartsPlayback() = test {
        val player = makePlayer()
        player.playNextQueue(items("A"))

        assertEquals(listOf("A"), player.names)
    }

    @Test
    fun addToEndAppendsToQueue() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B"), startingAt = 0)

        player.addToEndQueue(items("X", "Y"))

        assertEquals(listOf("A", "B", "X", "Y"), player.names)
        assertEquals(QueueConfirmationKind.AddedToQueue, player.queueConfirmation.value?.kind)
        assertEquals(2, player.queueConfirmation.value?.count)
    }

    @Test
    fun addToEndOnEmptyQueueStartsPlayback() = test {
        val player = makePlayer()
        player.addToEndQueue(items("A"))

        assertEquals(1, player.s.queue.size)
    }

    @Test
    fun playNextEmptyItemsNoOp() = test {
        val player = makePlayer()
        player.playNowQueue(items("A"), startingAt = 0)
        player.playNextQueue(emptyList())

        assertEquals(1, player.s.queue.size)
    }

    @Test
    fun playNextWhileShuffledInsertsAfterCurrentInShuffleOrder() = test {
        val player = makePlayer()
        player.playShuffledQueue(items("A", "B", "C", "D"))
        val currentReal = player.s.shuffledIndices!![0]

        player.playNextQueue(items("X"))

        assertEquals(5, player.s.queue.size)
        val shuffled = player.s.shuffledIndices!!
        assertEquals(5, shuffled.size)
        assertEquals(currentReal, shuffled[0])
        assertEquals("X", player.s.queue[shuffled[1]].track.name)
    }

    @Test
    fun playNextWhileShuffledMultipleItemsInsertInOrder() = test {
        val player = makePlayer()
        player.playShuffledQueue(items("A", "B", "C"))

        player.playNextQueue(items("X", "Y"))

        assertEquals(5, player.s.queue.size)
        val shuffled = player.s.shuffledIndices!!
        assertEquals("X", player.s.queue[shuffled[1]].track.name)
        assertEquals("Y", player.s.queue[shuffled[2]].track.name)
    }

    // Skip / back

    @Test
    fun skipAdvancesIndex() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B", "C"), startingAt = 0)

        player.skip()

        assertEquals(1, player.s.currentIndex)
    }

    @Test
    fun skipAtEndWithRepeatAllWraps() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B"), startingAt = 1)
        player.setRepeatMode(RepeatMode.All)

        player.skip()

        assertEquals(0, player.s.currentIndex)
    }

    @Test
    fun skipAtEndWithRepeatOffStops() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B"), startingAt = 1)
        player.setRepeatMode(RepeatMode.Off)

        player.skip()

        assertFalse(player.s.isPlaying)
    }

    @Test
    fun backGoesToPreviousWhenProgressLow() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B", "C"), startingAt = 1)
        player.seek(1.0) // Under the 3s threshold

        player.back()

        assertEquals(0, player.s.currentIndex)
    }

    @Test
    fun skipToJumpsToIndex() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B", "C"), startingAt = 0)

        player.skipTo(2)

        assertEquals(2, player.s.currentIndex)
    }

    @Test
    fun skipToOutOfBoundsNoOp() = test {
        val player = makePlayer()
        player.playNowQueue(items("A"), startingAt = 0)

        player.skipTo(5)

        assertEquals(0, player.s.currentIndex)
    }

    @Test
    fun canSkipTrueWhenNotAtEnd() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B"), startingAt = 0)

        assertTrue(player.s.canSkip)
    }

    @Test
    fun canSkipFalseAtEndWithRepeatOff() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B"), startingAt = 1)
        player.setRepeatMode(RepeatMode.Off)

        assertFalse(player.s.canSkip)
    }

    @Test
    fun canSkipTrueAtEndWithRepeatAll() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B"), startingAt = 1)
        player.setRepeatMode(RepeatMode.All)

        assertTrue(player.s.canSkip)
    }

    @Test
    fun canGoBackFalseOnEmptyQueue() = test {
        assertFalse(makePlayer().canGoBack)
    }

    // Queue manipulation

    @Test
    fun clearUpNextKeepsCurrent() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B", "C"), startingAt = 0)

        player.clearUpNext()

        assertEquals(listOf("A"), player.names)
    }

    @Test
    fun clearUpNextWhileShuffled() = test {
        val player = makePlayer()
        player.playShuffledQueue(items("A", "B", "C", "D"))
        val currentTrackName = player.s.queue[player.effectiveIndex].track.name

        player.clearUpNext()

        assertEquals(listOf(currentTrackName), player.names)
        assertEquals(listOf(0), player.s.shuffledIndices)
    }

    @Test
    fun removeFromQueueRemovesCorrectItem() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B", "C", "D"), startingAt = 0)

        // Up-next offset 0 is queue index 1.
        player.removeFromQueue(setOf(0))

        assertEquals(listOf("A", "C", "D"), player.names)
    }

    @Test
    fun removeFromQueueWhileShuffled() = test {
        val player = makePlayer()
        player.playShuffledQueue(items("A", "B", "C", "D"))
        val countBefore = player.s.queue.size

        player.removeFromQueue(setOf(0))

        assertEquals(countBefore - 1, player.s.queue.size)
        assertEquals(countBefore - 1, player.s.shuffledIndices?.size)
    }

    @Test
    fun moveQueueItemsReorders() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B", "C", "D"), startingAt = 0)

        // Up-next offset 2 (D) to before offset 0 (B).
        player.moveQueueItems(setOf(2), 0)

        assertEquals(listOf("A", "D", "B", "C"), player.names)
    }

    @Test
    fun moveQueueItemsWhileShuffled() = test {
        val player = makePlayer()
        player.playShuffledQueue(items("A", "B", "C", "D"))
        val currentReal = player.s.shuffledIndices!![0]

        player.moveQueueItems(setOf(2), 0)

        val shuffledAfter = player.s.shuffledIndices!!
        assertEquals(currentReal, shuffledAfter[0])
        assertEquals(4, shuffledAfter.size)
    }

    // canGoBack / canSkip edge cases

    @Test
    fun canGoBackTrueWhenProgressOverThreshold() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B"), startingAt = 0)
        player.seek(5.0)

        assertTrue(player.canGoBack)
    }

    @Test
    fun canGoBackFalseAtStartWithLowProgress() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B"), startingAt = 0)
        player.seek(1.0)

        assertFalse(player.canGoBack)
    }

    @Test
    fun canGoBackTrueAtMiddle() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B", "C"), startingAt = 1)
        player.seek(1.0)

        assertTrue(player.canGoBack)
    }

    @Test
    fun canSkipWithRepeatOne() = test {
        val player = makePlayer()
        player.playNowQueue(items("A"), startingAt = 0)
        player.setRepeatMode(RepeatMode.One)

        assertTrue(player.s.canSkip)
    }

    @Test
    fun canSkipEmptyQueue() = test {
        assertFalse(makePlayer().s.canSkip)
    }

    // Effective index

    @Test
    fun effectiveIndexWithoutShuffle() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B"), startingAt = 1)

        assertEquals(1, player.effectiveIndex)
    }

    @Test
    fun effectiveIndexWithShuffle() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B", "C"), startingAt = 1)
        player.toggleShuffle()

        // currentIndex is 0 and the shuffle order leads with the real index.
        assertEquals(1, player.effectiveIndex)
    }

    // Repeat mode

    @Test
    fun cycleRepeatMode() = test {
        val player = makePlayer()
        player.setRepeatMode(RepeatMode.Off)

        player.cycleRepeatMode()
        assertEquals(RepeatMode.All, player.s.repeatMode)

        player.cycleRepeatMode()
        assertEquals(RepeatMode.One, player.s.repeatMode)

        player.cycleRepeatMode()
        assertEquals(RepeatMode.Off, player.s.repeatMode)
    }

    @Test
    fun skipWithRepeatOneReplays() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B"), startingAt = 0)
        player.setRepeatMode(RepeatMode.One)

        player.skip()

        assertEquals(0, player.s.currentIndex)
    }

    // Queue confirmation

    @Test
    fun requestPlayShowsConfirmationForAHandBuiltQueue() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B"), startingAt = 0)
        player.playNextQueue(items("C"))

        var actionCalled = false
        player.requestPlay { actionCalled = true }

        assertTrue(player.replaceQueuePrompt.value)
        assertFalse(actionCalled)

        player.confirmPlay()
        assertTrue(actionCalled)
    }

    /** Spanning several albums isn't the trigger — only hand edits are — so a multi-album queue you didn't build plays straight through. */
    @Test
    fun requestPlayExecutesImmediatelyForAnUntouchedQueue() = test {
        val player = makePlayer()
        player.playNowQueue(listOf(makeQueueItem(name = "A", albumId = UUID.randomUUID()), makeQueueItem(name = "B", albumId = UUID.randomUUID())), startingAt = 0)

        var actionCalled = false
        player.requestPlay { actionCalled = true }

        assertTrue(actionCalled)
        assertFalse(player.replaceQueuePrompt.value)
    }

    @Test
    fun cancelPlayClearsPending() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B"), startingAt = 0)
        player.playNextQueue(items("C"))

        var actionCalled = false
        player.requestPlay { actionCalled = true }
        player.cancelPlay()
        player.confirmPlay()

        assertFalse(actionCalled)
    }

    // Playback failure

    @Test
    fun startsWithNoErrorAndNotBuffering() = test {
        val player = makePlayer()

        assertNull(player.s.playbackError)
        assertFalse(player.s.isBuffering)
    }

    /**
     * Swift checks `!isPlaying` as its proxy for "retried rather than resumed", since its
     * reload only marks playing after an await. Here the reload marks it playing straight
     * away, so the engine calls are what tell the two apart.
     */
    @Test
    fun playPauseRetriesInsteadOfResumingAfterAFailure() = test {
        val engine = FakePlaybackEngine()
        val player = makePlayer(engine)
        player.playNowQueue(items("A"), startingAt = 0)
        testScheduler.runCurrent()
        engine.fail(PlaybackError.Unreachable)
        assertFalse(player.s.isPlaying)

        player.togglePlayPause()
        testScheduler.runCurrent()

        assertEquals(0, engine.resumeCount)
        assertEquals(2, engine.plays.size)
    }

    @Test
    fun pausingAWorkingTrackStillResumes() = test {
        val engine = FakePlaybackEngine()
        val player = makePlayer(engine)
        player.playNowQueue(items("A"), startingAt = 0)
        testScheduler.runCurrent()
        assertTrue(player.s.isPlaying)

        player.togglePlayPause()
        assertFalse(player.s.isPlaying)

        player.togglePlayPause()
        assertTrue(player.s.isPlaying)
        assertEquals(1, engine.resumeCount)
        assertEquals(1, engine.plays.size)
    }

    // Replace-queue confirmation

    @Test
    fun playingAFreshQueueDoesNotPrompt() = test {
        val player = makePlayer()
        player.playNowQueue(items("A"), startingAt = 0)

        var played = false
        player.requestPlay { played = true }

        assertTrue(played)
        assertFalse(player.replaceQueuePrompt.value)
    }

    @Test
    fun replacingAHandBuiltQueuePrompts() = test {
        val player = makePlayer()
        player.playNowQueue(items("A"), startingAt = 0)
        player.addToEndQueue(items("B"))

        var played = false
        player.requestPlay { played = true }

        assertFalse(played)
        assertTrue(player.replaceQueuePrompt.value)
    }

    @Test
    fun aFreshQueueClearsTheHandBuiltFlag() = test {
        val player = makePlayer()
        player.playNowQueue(items("A"), startingAt = 0)
        player.playNextQueue(items("B"))
        assertTrue(player.s.isQueueUserModified)

        player.playNowQueue(items("C"), startingAt = 0)

        assertFalse(player.s.isQueueUserModified)
    }

    @Test
    fun reorderingMarksTheQueueHandBuilt() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B", "C"), startingAt = 0)
        assertFalse(player.s.isQueueUserModified)

        player.moveQueueItems(setOf(0), 2)

        assertTrue(player.s.isQueueUserModified)
    }

    // Up next ordering

    @Test
    fun unshuffledUpNextFollowsQueueOrder() = test {
        val player = makePlayer()
        player.playNowQueue((1..5).map { makeQueueItem(name = "T$it") }, startingAt = 1)

        assertEquals(listOf(2, 3, 4), player.s.upNext.map { it.originalIndex })
    }

    /** Walking the raw queue and comparing against effectiveIndex drops everything shuffle placed before the current track. */
    @Test
    fun shuffledUpNextFollowsPlayOrderAndKeepsEveryTrack() = test {
        val player = restoredPlayer((1..5).map { "T$it" }, currentIndex = 0, shuffled = listOf(3, 0, 4, 1, 2))

        // Everything after position 0 in play order — not queue positions after index 3.
        assertEquals(listOf(0, 4, 1, 2), player.s.upNext.map { it.originalIndex })
        assertEquals(4, player.s.upNext.size)
    }

    @Test
    fun upNextIsEmptyOnTheLastTrack() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B"), startingAt = 1)

        assertTrue(player.s.upNext.isEmpty())
    }

    // History

    @Test
    fun historyIsWhatPlayedMostRecentFirst() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B", "C", "D"), startingAt = 2)

        assertEquals(listOf("B", "A"), player.s.history.map { it.item.track.name })
        assertEquals(listOf("D"), player.s.upNext.map { it.item.track.name })
    }

    @Test
    fun nothingPlayedYetMeansNoHistory() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B"), startingAt = 0)

        assertTrue(player.s.history.isEmpty())
    }

    /** History reads in the order the shuffle actually played, not queue order. */
    @Test
    fun historyFollowsTheShuffleOrder() = test {
        val player = restoredPlayer(listOf("A", "B", "C"), currentIndex = 2, shuffled = listOf(2, 0, 1))

        assertEquals(listOf("A", "C"), player.s.history.map { it.item.track.name })
    }

    @Test
    fun clearHistoryDropsWhatPlayedAndKeepsTheRest() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B", "C"), startingAt = 2)
        player.clearHistory()

        assertEquals(listOf("C"), player.names)
        assertEquals(0, player.s.currentIndex)
        assertTrue(player.s.history.isEmpty())
    }

    /** The played entries have to leave the queue itself, not just the shuffle map, or a reshuffle would deal them back in. */
    @Test
    fun clearHistoryRebuildsTheShuffleMapQueue() = test {
        val player = shuffledPlayerMidway()
        player.clearHistory()
        assertEquals(listOf("A", "C"), player.names.sorted())
    }

    @Test
    fun clearHistoryRebuildsTheShuffleMapIndex() = test {
        val player = shuffledPlayerMidway()
        player.clearHistory()
        assertEquals(0, player.s.currentIndex)
        assertEquals(2, player.s.shuffledIndices?.size)
    }

    @Test
    fun clearHistoryKeepsWhatComesNext() = test {
        val player = shuffledPlayerMidway()
        player.clearHistory()
        assertEquals(listOf("C"), player.s.upNext.map { it.item.track.name })
    }

    // Sign out

    @Test
    fun stopAndClearEmptiesTheQueue() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B"))
        player.stopAndClear()
        assertTrue(player.s.queue.isEmpty())
        assertNull(player.s.currentTrack)
        assertEquals(0, player.s.currentIndex)
    }

    @Test
    fun stopAndClearStopsPlayback() = test {
        val player = makePlayer()
        player.playNowQueue(items("A"))
        player.stopAndClear()
        assertFalse(player.s.isPlaying)
        assertEquals(0.0, player.progress.value, 0.0)
    }

    @Test
    fun stopAndClearDropsShuffle() = test {
        val player = makePlayer()
        player.playShuffledQueue(items("A", "B"))
        player.stopAndClear()
        assertNull(player.s.shuffledIndices)
    }

    // Artwork. Whether the player tints from it is the UI's call on Android, combining
    // this with the setting; what's tested here is the cover it would tint from.

    @Test
    fun aTrackWithNoArtworkHasNoCover() = test {
        val player = makePlayer()
        player.playNowQueue(items("A"))
        assertNull(player.s.currentItem?.artworkUrl)
    }

    @Test
    fun theCurrentItemCarriesItsCover() = test {
        val player = makePlayer()
        player.playNowQueue(listOf(QueueItem(makeTrack("A"), artworkUrl = "https://example.com/cover.jpg")))
        assertEquals("https://example.com/cover.jpg", player.s.currentItem?.artworkUrl)
    }

    // Stale indices. A shuffle order wider than the queue can't be reached through the
    // player's own API, so these check the state that every read goes through.

    @Test
    fun readsSurviveAShuffleOrderWiderThanTheQueue() {
        val album = UUID.randomUUID()
        val state = PlayerService.State(
            queue = listOf(makeQueueItem("A", album, number = 1), makeQueueItem("B", album, number = 2)),
            shuffledIndices = listOf(5, 1),
            currentIndex = 0,
        )

        assertNull(state.currentItem)
        assertEquals(listOf("B"), state.upNext.map { it.item.track.name })
    }

    @Test
    fun nowPlayingSurvivesAShuffleOrderWiderThanTheQueue() {
        val state = PlayerService.State(queue = listOf(makeQueueItem("A")), shuffledIndices = listOf(7), currentIndex = 0)

        assertNull(state.currentItem?.artworkUrl)
    }
}
