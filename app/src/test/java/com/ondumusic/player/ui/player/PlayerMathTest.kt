package com.ondumusic.player.ui.player

import com.ondumusic.player.model.QueueItem
import com.ondumusic.player.model.RepeatMode
import com.ondumusic.player.player.PlayerService
import com.ondumusic.player.player.makeTrack
import com.ondumusic.player.player.moved
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerMathTest {
    @Test fun formatsMinutesAndSeconds() {
        assertEquals("0:00", formatPlaybackTime(0.0))
        assertEquals("0:09", formatPlaybackTime(9.99))
        assertEquals("3:05", formatPlaybackTime(185.4))
        assertEquals("59:59", formatPlaybackTime(3599.0))
    }

    @Test fun formatsHoursPastTheHour() {
        assertEquals("1:00:00", formatPlaybackTime(3600.0))
        assertEquals("1:35:12", formatPlaybackTime(5712.0))
    }

    @Test fun formatsNonsenseAsZero() {
        assertEquals("0:00", formatPlaybackTime(-4.0))
        assertEquals("0:00", formatPlaybackTime(Double.NaN))
        assertEquals("0:00", formatPlaybackTime(Double.POSITIVE_INFINITY))
    }

    @Test fun elapsedAndRemainingAddUpToTheDuration() {
        val duration = 200.7
        for (position in listOf(0.0, 0.4, 1.5, 99.99, 200.0, 200.7)) {
            val elapsed = position.toLong()
            assertEquals(200L, elapsed + remainingSeconds(position, duration).toLong())
        }
    }

    @Test fun remainingNeverGoesNegative() {
        assertEquals(0.0, remainingSeconds(250.0, 200.0), 0.0)
        assertEquals(0.0, remainingSeconds(10.0, 0.0), 0.0)
        assertEquals(0.0, remainingSeconds(10.0, Double.NaN), 0.0)
    }

    @Test fun upNextAndHistoryPositionsSurroundTheCurrentOne() {
        assertEquals(5, QueuePositions.upNext(currentIndex = 4, offset = 0))
        assertEquals(7, QueuePositions.upNext(currentIndex = 4, offset = 2))
        assertEquals(3, QueuePositions.history(currentIndex = 4, offset = 0))
        assertEquals(0, QueuePositions.history(currentIndex = 4, offset = 3))
    }

    /** The positions must name the same entries `State.upNext`/`history` list, shuffled or not. */
    @Test fun positionsResolveToTheEntriesTheStateLists() {
        val queue = List(6) { QueueItem(makeTrack("t$it")) }
        for (shuffled in listOf(null, listOf(3, 0, 5, 1, 4, 2))) {
            val current = 2
            val state = PlayerService.State(queue = queue, currentIndex = current, shuffledIndices = shuffled, repeatMode = RepeatMode.Off)
            state.upNext.forEachIndexed { offset, entry ->
                val moved = state.copy(currentIndex = QueuePositions.upNext(current, offset))
                assertEquals(entry.item.id, moved.currentItem?.id)
            }
            state.history.forEachIndexed { offset, entry ->
                val moved = state.copy(currentIndex = QueuePositions.history(current, offset))
                assertEquals(entry.item.id, moved.currentItem?.id)
            }
        }
    }

    /** A drag from any row to any row must land exactly there once `moveQueueItems` applies it. */
    @Test fun moveDestinationLandsTheRowWhereItWasDropped() {
        val list = listOf("a", "b", "c", "d", "e")
        for (from in list.indices) for (to in list.indices) {
            val result = list.moved(setOf(from), QueuePositions.moveDestination(from, to))
            assertEquals("from $from to $to", list[from], result[to])
            assertEquals(list.size, result.size)
        }
    }
}
