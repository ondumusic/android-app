package com.ondumusic.player.service

import com.ondumusic.player.model.QueueItem
import java.util.UUID
import kotlin.random.Random

/**
 * Fisher-Yates alone produces same-artist runs that listeners read as "not random".
 * Instead, each artist's tracks are placed at even intervals across the whole queue
 * (with a random offset per artist and jitter per track so no pattern shows), and an
 * artist's own tracks are ordered the same way by album before placement.
 */
object SmartShuffle {
    fun shuffledIndices(items: List<QueueItem>, random: Random = Random.Default): List<Int> {
        val all = items.indices.toList()
        val byArtist = groups(all) { items[it].track.albumArtistId }
        if (byArtist.size <= 1) return spreadByAlbum(all, items, random)

        val placed = ArrayList<Placement>(all.size)
        for (artist in byArtist) {
            place(spreadByAlbum(artist, items, random), all.size, placed, random)
        }
        return placed.sortedBy { it.position }.map { it.index }
    }

    private fun spreadByAlbum(group: List<Int>, items: List<QueueItem>, random: Random): List<Int> {
        val byAlbum = groups(group) { items[it].track.albumId }
        if (byAlbum.size <= 1) return group.shuffled(random)

        val placed = ArrayList<Placement>(group.size)
        for (album in byAlbum) {
            place(album.shuffled(random), group.size, placed, random)
        }
        return placed.sortedBy { it.position }.map { it.index }
    }

    private class Placement(val position: Double, val index: Int)

    /**
     * Jitter stays well under half the interval, so members keep their given order and
     * can't land on top of each other — consecutive members stay ≥ 0.6 intervals apart.
     */
    private fun place(ordered: List<Int>, span: Int, placed: MutableList<Placement>, random: Random) {
        val spacing = span.toDouble() / ordered.size
        val offset = random.nextDouble(0.0, spacing)
        ordered.forEachIndexed { j, index ->
            val jitter = random.nextDouble(-0.2, 0.2) * spacing
            placed += Placement(offset + j * spacing + jitter, index)
        }
    }

    /**
     * Grouped in a deterministic order so a seeded generator reproduces a shuffle. By
     * the UUID's string form, because `UUID.compareTo` compares signed halves.
     */
    private fun groups(indices: List<Int>, key: (Int) -> UUID): List<List<Int>> =
        indices.groupBy(key).entries.sortedBy { it.key.toString() }.map { it.value }
}
