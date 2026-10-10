package com.ondumusic.player.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class AlbumSortTest {

    @Test fun sortsByTotalPlays() {
        val a = album("A")
        val b = album("B")
        val c = album("C")
        val sorted = AlbumSortOption.Plays.sorted(
            listOf(a, b, c), ascending = false,
            playStats = mapOf(a.id to AlbumPlayStats(3), b.id to AlbumPlayStats(40)),
        )
        assertEquals(listOf("B", "A", "C"), sorted.map { it.name })
    }

    @Test fun sortsByMostRecentlyPlayed() {
        val now = Instant.now()
        val a = album("A")
        val b = album("B")
        val c = album("C")
        val sorted = AlbumSortOption.LastPlayed.sorted(
            listOf(a, b, c), ascending = false,
            playStats = mapOf(
                a.id to AlbumPlayStats(1, now.minusSeconds(3600)),
                b.id to AlbumPlayStats(1, now),
            ),
        )
        assertEquals(listOf("B", "A", "C"), sorted.map { it.name })
    }

    // Both start from the top: nobody opens "Plays" wanting what they've played least.
    @Test fun bothPlaySortsStartDescending() {
        assertFalse(AlbumSortOption.Plays.defaultAscending)
        assertFalse(AlbumSortOption.LastPlayed.defaultAscending)
    }

    @Test fun onlyThePlaySortsAskForTheAggregate() {
        assertTrue(AlbumSortOption.Plays.needsPlayStats)
        assertTrue(AlbumSortOption.LastPlayed.needsPlayStats)
        assertFalse(AlbumSortOption.Artist.needsPlayStats)
        assertFalse(AlbumSortOption.DateAdded.needsPlayStats)
    }

    private fun album(name: String) = Album(
        id = UUID.randomUUID(), artistId = UUID.randomUUID(), name = name, sortName = name,
        slug = name.lowercase(), insertedAt = Instant.now(),
    )
}
