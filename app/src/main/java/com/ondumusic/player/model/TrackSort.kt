package com.ondumusic.player.model

import com.ondumusic.player.util.compareNatural
import java.time.Instant

/**
 * A sort chosen by tapping a column header.
 *
 * A null sort is not "unsorted" — it's the library's own order: album artist, album,
 * disc, track number. That order is why an album's tracks sit together instead of
 * scattering, and no single column expresses it, so it stays the default and the header
 * menu offers a way back to it.
 */
enum class TrackSortColumn(val rawValue: String) {
    Number("number"),
    Disc("disc"),
    Title("title"),
    Artist("artist"),
    Album("album"),
    Year("year"),
    Duration("duration"),
    Plays("plays"),
    LastPlayed("lastPlayed");

    /**
     * What this column sorts by, in order. Only the first is reversed when the sort is
     * descending — the rest are the grouping beneath it, and reversing those would put
     * an artist's albums in backwards order for no reason anyone asked for.
     */
    val keys: List<TrackSortKey>
        get() = when (this) {
            Number -> listOf(TrackSortKey.Number, TrackSortKey.AlbumArtist, TrackSortKey.Album, TrackSortKey.Disc)
            Disc -> listOf(TrackSortKey.Disc, TrackSortKey.AlbumArtist, TrackSortKey.Album, TrackSortKey.Number)
            Title -> listOf(TrackSortKey.Title) + albumOrder
            Artist -> albumOrder
            Album -> listOf(TrackSortKey.Album, TrackSortKey.Disc, TrackSortKey.Number)
            Year -> listOf(TrackSortKey.Year) + albumOrder
            Duration -> listOf(TrackSortKey.Duration) + albumOrder
            // Most libraries have a lot of tracks played exactly once, and "which of these
            // did I hear recently" is a better answer there than alphabetical.
            Plays -> listOf(TrackSortKey.Plays, TrackSortKey.RecentlyPlayed) + albumOrder
            LastPlayed -> listOf(TrackSortKey.LastPlayed) + albumOrder
        }

    /**
     * Which way the first tap sorts. Alphabetical and numeric columns read forwards;
     * play counts and dates are asked about from the top, so the first tap puts the
     * most played, and the most recently played, first rather than the pile of
     * never-played ones.
     */
    val defaultAscending: Boolean
        get() = this != Plays && this != LastPlayed

    companion object {
        fun fromRawValue(rawValue: String): TrackSortColumn? = entries.firstOrNull { it.rawValue == rawValue }

        private val albumOrder = listOf(TrackSortKey.AlbumArtist, TrackSortKey.Album, TrackSortKey.Disc, TrackSortKey.Number)
    }
}

enum class TrackSortKey {
    Number,
    Disc,
    Title,
    AlbumArtist,
    Album,
    Year,
    Duration,
    Plays,
    LastPlayed,

    /**
     * Last played, newest first, for use as a tiebreak. Keys after the first always sort
     * ascending — that's what keeps an artist's albums in order under a reversed primary
     * — so a newest-first tiebreak has to be its own key rather than a direction.
     */
    RecentlyPlayed;

    fun compare(lhs: Track, rhs: Track): Int = when (this) {
        Number -> (lhs.number ?: 0).compareTo(rhs.number ?: 0)
        Disc -> lhs.disc.compareTo(rhs.disc)
        Title -> lhs.name.compareNatural(rhs.name)
        // The album artist by its sort name: it's the value the Artist column shows, and
        // the one the library's default order uses, so "The Beatles" files under B in
        // both.
        AlbumArtist -> lhs.albumArtistSortName.compareNatural(rhs.albumArtistSortName)
        Album -> (lhs.album?.name ?: "").compareNatural(rhs.album?.name ?: "")
        Year -> (lhs.album?.releaseYear ?: 0).compareTo(rhs.album?.releaseYear ?: 0)
        Duration -> lhs.duration.compareTo(rhs.duration)
        Plays -> lhs.playCount.compareTo(rhs.playCount)
        // Never played sorts as longest ago, so descending puts it last where it belongs
        // rather than above everything that has been played.
        LastPlayed -> (lhs.lastPlayedAt ?: Instant.MIN).compareTo(rhs.lastPlayedAt ?: Instant.MIN)
        RecentlyPlayed -> (rhs.lastPlayedAt ?: Instant.MIN).compareTo(lhs.lastPlayedAt ?: Instant.MIN)
    }
}

data class TrackSort(val column: TrackSortColumn, val ascending: Boolean) {
    /** Persisted in settings, which want a plain value. */
    val rawValue: String
        get() = "${column.rawValue}:${if (ascending) "asc" else "desc"}"

    fun sorted(tracks: List<Track>): List<Track> {
        val keys = column.keys
        return tracks.sortedWith { lhs, rhs ->
            for ((index, key) in keys.withIndex()) {
                val order = key.compare(lhs, rhs)
                if (order == 0) continue
                return@sortedWith if (index == 0 && !ascending) -order else order
            }
            // Everything the column groups by is equal; keep it stable by title.
            lhs.name.compareNatural(rhs.name)
        }
    }

    companion object {
        fun fromRawValue(rawValue: String): TrackSort? {
            val parts = rawValue.split(':').filter { it.isNotEmpty() }
            if (parts.size != 2) return null
            val column = TrackSortColumn.fromRawValue(parts[0]) ?: return null
            return TrackSort(column, ascending = parts[1] == "asc")
        }
    }
}
