package com.ondumusic.player.player

import com.ondumusic.player.data.MusicLibrary
import com.ondumusic.player.model.RepeatMode
import com.ondumusic.player.player.PlayerService.QueueConfirmationKind
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class QueueUndoTest {
    private val dispatcher = StandardTestDispatcher()
    private val libraries = mutableListOf<MusicLibrary>()

    @After
    fun tearDown() = libraries.forEach { it.close() }

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    private fun items(vararg names: String) = names.map { makeQueueItem(name = it) }

    private val PlayerService.names get() = s.queue.map { it.track.name }

    /** Everything an undo has to put back, compared by identity rather than by name. */
    private data class Shape(val ids: List<Any>, val currentIndex: Int, val shuffled: List<Int>?, val userModified: Boolean)

    private val PlayerService.shape get() = Shape(s.queue.map { it.id }, s.currentIndex, s.shuffledIndices, s.isQueueUserModified)

    private fun PlayerService.undoLatest(): Boolean = undo(undoable.value ?: error("Nothing to undo"))

    /** Queue A,B,C,D shuffled to play D,B,A,C, stopped on A — so B and D are history and C is still to come. */
    private suspend fun TestScope.shuffledPlayerMidway(): PlayerService {
        val library = makeLibrary(dispatcher).also { libraries += it }
        val ids = library.seed(listOf("A", "B", "C", "D"))
        val store = makeStore(this, "undo")
        store.save(PlaybackState(ids, 2, listOf(3, 1, 0, 2)))
        store.flush()
        return makePlayer(store = store, library = library)
    }

    // Replacing the queue

    @Test
    fun replacingAQueueNobodyEditedOffersNoUndo() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B"), startingAt = 0)

        player.requestPlay { player.playNowQueue(items("C")) }

        assertEquals(listOf("C"), player.names)
        assertNull(player.undoable.value)
        assertNull(player.queueConfirmation.value)
    }

    @Test
    fun replacingAHandBuiltQueuePlaysAtOnceAndOffersUndo() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B"), startingAt = 0)
        player.addToEndQueue(items("C"))

        player.requestPlay { player.playNowQueue(items("X", "Y")) }

        assertEquals(listOf("X", "Y"), player.names)
        val confirmation = player.queueConfirmation.value!!
        assertEquals(QueueConfirmationKind.Replaced, confirmation.kind)
        assertTrue(confirmation.undo === player.undoable.value)
    }

    @Test
    fun undoBringsBackTheReplacedQueueExactly() = test {
        val engine = FakePlaybackEngine()
        val player = makePlayer(engine)
        player.playShuffledQueue(items("A", "B", "C", "D"))
        player.skip()
        player.playNextQueue(items("E"))
        testScheduler.runCurrent()
        engine.ready()
        engine.position = 42.0
        val before = player.shape
        val playing = player.s.currentItem!!

        player.requestPlay { player.playNowQueue(items("X")) }
        testScheduler.runCurrent()
        // Repeat is a setting, not part of the queue: changed meanwhile, it stays changed.
        player.setRepeatMode(RepeatMode.All)
        assertTrue(player.undoLatest())
        testScheduler.runCurrent()

        assertEquals(before, player.shape)
        assertEquals(playing.id, player.s.currentItem?.id)
        assertTrue(player.s.isPlaying)
        assertEquals(RepeatMode.All, player.s.repeatMode)
        assertEquals(42.0, engine.plays.last().startAt, 0.0)
        assertNull(player.undoable.value)
    }

    @Test
    fun undoLeavesAPausedQueuePausedWhereItWas() = test {
        val engine = FakePlaybackEngine()
        val player = makePlayer(engine)
        player.playNowQueue(items("A", "B"), startingAt = 0)
        player.addToEndQueue(items("C"))
        testScheduler.runCurrent()
        engine.ready()
        engine.position = 30.0
        player.togglePlayPause()

        player.requestPlay { player.playNowQueue(items("X")) }
        testScheduler.runCurrent()
        val playsBeforeUndo = engine.plays.size
        player.undoLatest()
        testScheduler.runCurrent()

        assertFalse(player.s.isPlaying)
        assertEquals("A", player.s.currentTrack?.name)
        assertEquals(30.0, player.progress.value, 0.0)
        assertEquals(playsBeforeUndo, engine.plays.size)

        // Play picks it up from the same point.
        player.togglePlayPause()
        testScheduler.runCurrent()
        assertEquals("A", player.s.currentTrack?.name)
        assertEquals(30.0, engine.plays.last().startAt, 0.0)
    }

    @Test
    fun anotherEditTakesTheUndoAway() = test {
        val player = makePlayer()
        player.playNowQueue(items("A"), startingAt = 0)
        player.addToEndQueue(items("B"))
        player.requestPlay { player.playNowQueue(items("X")) }
        val replaced = player.undoable.value!!

        player.addToEndQueue(items("Y"))

        assertNull(player.undoable.value)
        assertFalse(player.undo(replaced))
        assertEquals(listOf("X", "Y"), player.names)
    }

    @Test
    fun anActionWithNothingToPlayOffersNoUndo() = test {
        val player = makePlayer()
        player.playNowQueue(items("A"), startingAt = 0)
        player.addToEndQueue(items("B"))

        player.requestPlay { player.playShuffledQueue(emptyList()) }

        assertEquals(listOf("A", "B"), player.names)
        assertNull(player.undoable.value)
    }

    // Removing from the queue

    @Test
    fun undoingARemovalPutsTheSongBackInPlace() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B", "C", "D"), startingAt = 0)
        val before = player.shape

        player.removeFromQueue(setOf(1))
        assertEquals(listOf("A", "B", "D"), player.names)
        assertEquals(QueueConfirmationKind.Removed, player.queueConfirmation.value?.kind)
        assertEquals(1, player.queueConfirmation.value?.count)
        player.undoLatest()

        assertEquals(before, player.shape)
    }

    @Test
    fun undoingARemovalWhileShuffledRestoresThePlayOrder() = test {
        val player = shuffledPlayerMidway()
        val before = player.shape

        player.removeFromQueue(setOf(0))
        player.undoLatest()

        assertEquals(before, player.shape)
        assertEquals(listOf("C"), player.s.upNext.map { it.item.track.name })
    }

    /** Playback carrying on while the snackbar shows mustn't be wound back by the undo. */
    @Test
    fun undoingARemovalKeepsWhatIsPlayingNow() = test {
        val engine = FakePlaybackEngine()
        val player = makePlayer(engine)
        player.playNowQueue(items("A", "B", "C", "D"), startingAt = 0)
        testScheduler.runCurrent()

        player.removeFromQueue(setOf(1))
        player.skip()
        testScheduler.runCurrent()
        val plays = engine.plays.size
        player.undoLatest()
        testScheduler.runCurrent()

        assertEquals(listOf("A", "B", "C", "D"), player.names)
        assertEquals(1, player.s.currentIndex)
        assertEquals("B", player.s.currentTrack?.name)
        assertEquals(listOf("C", "D"), player.s.upNext.map { it.item.track.name })
        assertEquals(plays, engine.plays.size)
    }

    @Test
    fun undoingAClearedUpNextBringsItAllBack() = test {
        val player = makePlayer()
        player.playShuffledQueue(items("A", "B", "C", "D"))
        val before = player.shape

        player.clearUpNext()
        assertEquals(QueueConfirmationKind.ClearedUpNext, player.queueConfirmation.value?.kind)
        assertEquals(3, player.queueConfirmation.value?.count)
        player.undoLatest()

        assertEquals(before, player.shape)
    }

    @Test
    fun undoingAClearedHistoryBringsItBackInPlayOrder() = test {
        val player = shuffledPlayerMidway()
        val before = player.shape

        player.clearHistory()
        assertEquals(QueueConfirmationKind.ClearedHistory, player.queueConfirmation.value?.kind)
        player.undoLatest()

        assertEquals(before, player.shape)
        assertEquals(listOf("B", "D"), player.s.history.map { it.item.track.name })
    }

    @Test
    fun undoingARemovalRestoresTheHandBuiltFlag() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B", "C"), startingAt = 0)

        player.removeFromQueue(setOf(0))
        assertTrue(player.s.isQueueUserModified)
        player.undoLatest()

        assertFalse(player.s.isQueueUserModified)
    }

    @Test
    fun clearingAnEmptyUpNextOffersNoUndo() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B"), startingAt = 1)

        player.clearUpNext()

        assertNull(player.undoable.value)
    }

    @Test
    fun reorderingTakesARemovalsUndoAway() = test {
        val player = makePlayer()
        player.playNowQueue(items("A", "B", "C", "D"), startingAt = 0)
        player.removeFromQueue(setOf(0))

        player.moveQueueItems(setOf(1), 0)

        assertNull(player.undoable.value)
    }
}
