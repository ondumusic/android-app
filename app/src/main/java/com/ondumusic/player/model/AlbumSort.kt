package com.ondumusic.player.model

import com.ondumusic.player.util.compareNatural
import java.time.Instant
import java.util.UUID

/** An album's figures are its tracks': the sum of their plays and the latest of their dates. */
data class AlbumPlayStats(
    val playCount: Int = 0,
    val lastPlayedAt: Instant? = null,
)

enum class AlbumSortOption {
    Artist,
    DateAdded,
    Name,
    ReleaseYear,
    Plays,
    LastPlayed;

    /**
     * What this ordering means by default. Nobody picks "Date Added" wanting the oldest
     * record they own first, and nobody picks "Name" wanting Z.
     */
    val defaultAscending: Boolean
        get() = this == Artist || this == Name

    /** Only these two need the aggregate, which costs a pass over every played track. */
    val needsPlayStats: Boolean
        get() = this == Plays || this == LastPlayed

    fun sorted(
        albums: List<Album>,
        ascending: Boolean,
        playStats: Map<UUID, AlbumPlayStats> = emptyMap(),
    ): List<Album> {
        val byName = Comparator<Album> { a, b -> a.sortName.compareNatural(b.sortName) }
        val lastPlayed = compareBy<Album> { playStats[it.id]?.lastPlayedAt ?: Instant.MIN }
        val order: Comparator<Album> = when (this) {
            // Within an artist, newest record first under the default A–Z.
            Artist -> Comparator<Album> { a, b -> (a.artistSortName ?: "").compareNatural(b.artistSortName ?: "") }
                .thenByDescending { it.releaseYear ?: 0 }
                .then(byName)
            DateAdded -> compareBy<Album> { it.insertedAt ?: Instant.MIN }.then(byName)
            Name -> byName
            ReleaseYear -> compareBy<Album> { it.releaseYear ?: 0 }.then(byName)
            // Same count: the more recently played is the better answer under the
            // default descending sort.
            Plays -> compareBy<Album> { playStats[it.id]?.playCount ?: 0 }.then(lastPlayed).then(byName)
            // Never played is longest ago, so descending leaves it at the bottom rather
            // than on top of everything that has been played.
            LastPlayed -> lastPlayed.then(byName)
        }
        return albums.sortedWith(if (ascending) order else order.reversed())
    }
}
