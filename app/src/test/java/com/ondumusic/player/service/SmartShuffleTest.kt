package com.ondumusic.player.service

import com.ondumusic.player.model.Artist
import com.ondumusic.player.model.QueueItem
import com.ondumusic.player.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.random.Random

// Each seeded test runs over seeds 1...20: a seeded generator makes the shuffle a pure
// function of its input, so the spacing assertions are exact, not statistical.
class SmartShuffleTest {

    @Test fun producesAPermutation() {
        val items = List(50) { makeItem(UUID.randomUUID(), UUID.randomUUID()) }
        assertEquals(items.indices.toList(), SmartShuffle.shuffledIndices(items).sorted())
    }

    @Test fun tinyQueuesSurvive() {
        assertTrue(SmartShuffle.shuffledIndices(emptyList()).isEmpty())
        val one = listOf(makeItem(UUID.randomUUID(), UUID.randomUUID()))
        assertEquals(listOf(0), SmartShuffle.shuffledIndices(one))
        val two = List(2) { makeItem(UUID.randomUUID(), UUID.randomUUID()) }
        assertEquals(listOf(0, 1), SmartShuffle.shuffledIndices(two).sorted())
    }

    @Test fun spreadsAnArtistAcrossTheQueue() {
        for (seed in 1..20) {
            // 4 tracks by one artist among 56 one-off artists: ideal spacing is 15. The ids come
            // from the seed too: grouping orders by id, so random ids made the seed meaningless.
            val ids = Random(seed * 1000)
            fun id() = UUID(ids.nextLong(), ids.nextLong())
            val artist = id()
            val album = id()
            val items = List(56) { makeItem(id(), id()) } +
                List(4) { makeItem(artist, album) }
            val order = SmartShuffle.shuffledIndices(items, Random(seed))
            val ranks = order.indices.filter { items[order[it]].track.albumArtistId == artist }
            val gaps = ranks.zipWithNext { a, b -> b - a }
            assertTrue("seed $seed: artist clumped: ranks $ranks", gaps.all { it >= 5 })
        }
    }

    @Test fun twoArtistQueueNeverRunsLong() {
        for (seed in 1..20) {
            val a = UUID.randomUUID()
            val b = UUID.randomUUID()
            val items = List(10) { makeItem(a, UUID.randomUUID()) } + List(10) { makeItem(b, UUID.randomUUID()) }
            val order = SmartShuffle.shuffledIndices(items, Random(seed))
            val artists = order.map { items[it].track.albumArtistId }
            assertTrue("seed $seed", longestRun(artists) <= 2)
        }
    }

    @Test fun spreadsAlbumsWithinAnArtist() {
        for (seed in 1..20) {
            val artist = UUID.randomUUID()
            val first = UUID.randomUUID()
            val second = UUID.randomUUID()
            val items = List(8) { makeItem(artist, first) } + List(8) { makeItem(artist, second) }
            val order = SmartShuffle.shuffledIndices(items, Random(seed))
            val albums = order.map { items[it].track.albumId }
            assertTrue("seed $seed", longestRun(albums) <= 2)
        }
    }

    @Test fun singleAlbumQueueIsAPlainShuffle() {
        val artist = UUID.randomUUID()
        val album = UUID.randomUUID()
        val items = List(30) { makeItem(artist, album) }
        val order = SmartShuffle.shuffledIndices(items, Random(7))
        assertEquals(items.indices.toList(), order.sorted())
        assertNotEquals(items.indices.toList(), order)
    }
}

private fun makeItem(artist: UUID, album: UUID): QueueItem {
    val artistModel = Artist(id = artist, name = "Artist", sortName = "Artist", slug = "artist")
    val track = Track(
        id = UUID.randomUUID(), artistId = artist, albumArtistId = artist, albumId = album,
        artist = artistModel, albumArtistSortName = "Artist",
        name = "Track", disc = 1, number = 1, duration = 200.0,
        mimetype = "audio/mp4", streamUrl = "/stream/test",
    )
    return QueueItem(track)
}

private fun <T> longestRun(values: List<T>): Int {
    var run = 1
    var maxRun = if (values.isEmpty()) 0 else 1
    for ((prev, cur) in values.zipWithNext()) {
        run = if (prev == cur) run + 1 else 1
        maxRun = maxOf(maxRun, run)
    }
    return maxRun
}
