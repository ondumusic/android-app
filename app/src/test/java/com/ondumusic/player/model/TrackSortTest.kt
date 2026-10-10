package com.ondumusic.player.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Locale
import java.util.UUID

class TrackSortTest {
    private lateinit var savedLocale: Locale

    @Before fun pinLocale() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    // The Artist column shows the album artist, so that's what a tap on it sorts by.
    @Test fun sortsByTheAlbumArtistTheColumnShows() {
        val tracks = listOf(
            track("T1", artist = "Lin-Manuel Miranda", albumArtist = "Original Cast"),
            track("T2", artist = "Zoo", albumArtist = "A Camp"),
        )
        val sorted = TrackSort(TrackSortColumn.Artist, ascending = true).sorted(tracks)
        assertEquals(listOf("A Camp", "Original Cast"), sorted.map { it.albumArtistName })
    }

    // By sort name, as the library's own default order does — so "The Beatles" lands
    // under B rather than T.
    @Test fun artistSortUsesTheSortName() {
        val beatles = track("T1", artist = "The Beatles", albumArtist = "The Beatles")
            .copy(albumArtistSortName = "Beatles, The")
        val cure = track("T2", artist = "Cure", albumArtist = "Cure")
        val sorted = TrackSort(TrackSortColumn.Artist, ascending = true).sorted(listOf(cure, beatles))
        assertEquals(listOf("The Beatles", "Cure"), sorted.map { it.albumArtistName })
    }

    @Test fun descendingPutsTheLastArtistFirst() {
        val tracks = listOf(track("T1", artist = "A Camp"), track("T2", artist = "Zoo"))
        val sorted = TrackSort(TrackSortColumn.Artist, ascending = false).sorted(tracks)
        assertEquals(listOf("Zoo", "A Camp"), sorted.map { it.albumArtistName })
    }

    @Test fun titleSortIsLocalised() {
        val tracks = listOf(track("Ziggy", artist = "A"), track("apple", artist = "A"), track("Émile", artist = "A"))
        val sorted = TrackSort(TrackSortColumn.Title, ascending = true).sorted(tracks)
        assertEquals(listOf("apple", "Émile", "Ziggy"), sorted.map { it.name })
    }

    @Test fun durationSortsNumerically() {
        val tracks = listOf(track("A", artist = "X", duration = 300.0), track("B", artist = "X", duration = 90.0))
        val sorted = TrackSort(TrackSortColumn.Duration, ascending = true).sorted(tracks)
        assertEquals(listOf(90.0, 300.0), sorted.map { it.duration })
    }

    // The point of the whole exercise: sorting by a column groups by it, it doesn't
    // throw the library's ordering away. Artist ascending still walks each artist's
    // albums in order, and each album's tracks in disc/number order.
    @Test fun sortingByArtistKeepsTheAlbumOrderUnderneath() {
        val tracks = listOf(
            track("Late", artist = "A", album = "Second", disc = 1, number = 2),
            track("Early", artist = "A", album = "First", disc = 1, number = 2),
            track("First track", artist = "A", album = "First", disc = 1, number = 1),
            track("Disc two", artist = "A", album = "First", disc = 2, number = 1),
        )
        val sorted = TrackSort(TrackSortColumn.Artist, ascending = true).sorted(tracks)
        assertEquals(listOf("First track", "Early", "Disc two", "Late"), sorted.map { it.name })
    }

    // Descending flips the column you tapped and nothing else — an artist's albums
    // stay in album order rather than running backwards.
    @Test fun descendingReversesOnlyTheColumnTapped() {
        val tracks = listOf(
            track("A1", artist = "Zoo", album = "Alpha", number = 1),
            track("A2", artist = "Zoo", album = "Alpha", number = 2),
            track("B1", artist = "Aviary", album = "Beta", number = 1),
        )
        val sorted = TrackSort(TrackSortColumn.Artist, ascending = false).sorted(tracks)
        assertEquals(listOf("A1", "A2", "B1"), sorted.map { it.name })
    }

    @Test fun sortsByYearWhenTheColumnIsShown() {
        val tracks = listOf(track("New", artist = "A", year = 2020), track("Old", artist = "A", year = 1999))
        val sorted = TrackSort(TrackSortColumn.Year, ascending = true).sorted(tracks)
        assertEquals(listOf("Old", "New"), sorted.map { it.name })
    }

    @Test fun roundTripsThroughItsRawValue() {
        val sort = TrackSort(TrackSortColumn.Album, ascending = false)
        assertEquals(sort, TrackSort.fromRawValue(sort.rawValue))
        assertNull(TrackSort.fromRawValue(""))
    }

    // Most libraries have a lot of tracks played exactly once, where alphabetical is a
    // worse answer than "which of these did I hear recently".
    @Test fun breaksPlayCountTiesByMostRecent() {
        val now = Instant.now()
        val tracks = listOf(
            track("Older", artist = "A", plays = 1, lastPlayed = now.minusSeconds(86_400)),
            track("Newer", artist = "A", plays = 1, lastPlayed = now),
            track("Most", artist = "A", plays = 9, lastPlayed = now.minusSeconds(99_999)),
        )
        val sorted = TrackSort(TrackSortColumn.Plays, ascending = false).sorted(tracks)
        assertEquals(listOf("Most", "Newer", "Older"), sorted.map { it.name })
    }

    @Test fun sortsByPlayCount() {
        val tracks = listOf(
            track("Rarely", artist = "A", plays = 2),
            track("Often", artist = "A", plays = 40),
            track("Never", artist = "A", plays = 0),
        )
        val sorted = TrackSort(TrackSortColumn.Plays, ascending = false).sorted(tracks)
        assertEquals(listOf("Often", "Rarely", "Never"), sorted.map { it.name })
    }

    // Asking "what do I play most", or "what did I play recently", and getting the
    // never-played first is the wrong way round — so those two start descending where
    // every other column starts ascending.
    @Test fun thePlayColumnsStartDescending() {
        val descendingFirst = setOf(TrackSortColumn.Plays, TrackSortColumn.LastPlayed)
        for (column in TrackSortColumn.entries) {
            assertEquals(column.name, column !in descendingFirst, column.defaultAscending)
        }
    }

    @Test fun sortsByWhenItWasLastPlayed() {
        val now = Instant.now()
        val tracks = listOf(
            track("Old", artist = "A", lastPlayed = now.minusSeconds(86_400)),
            track("Recent", artist = "A", lastPlayed = now),
            track("Never", artist = "A", lastPlayed = null),
        )
        val sorted = TrackSort(TrackSortColumn.LastPlayed, ascending = false).sorted(tracks)
        assertEquals(listOf("Recent", "Old", "Never"), sorted.map { it.name })
    }

    // Never played is longest-ago rather than newest, so it sits at the bottom of a
    // recently-played list instead of on top of it.
    @Test fun neverPlayedSortsAsLongestAgo() {
        val tracks = listOf(
            track("Never", artist = "A", lastPlayed = null),
            track("Played", artist = "A", lastPlayed = Instant.now()),
        )
        val ascending = TrackSort(TrackSortColumn.LastPlayed, ascending = true).sorted(tracks)
        assertEquals(listOf("Never", "Played"), ascending.map { it.name })
    }
}

private fun track(
    name: String,
    artist: String,
    albumArtist: String? = null,
    album: String = "Album",
    year: Int? = null,
    disc: Int = 1,
    number: Int = 1,
    duration: Double = 100.0,
    plays: Int = 0,
    lastPlayed: Instant? = null,
): Track {
    val artistModel = Artist(id = UUID.randomUUID(), name = artist, sortName = artist, slug = "a")
    val albumArtistName = albumArtist ?: artist
    return Track(
        id = UUID.randomUUID(), artistId = artistModel.id, albumArtistId = artistModel.id,
        albumId = UUID.randomUUID(), artist = artistModel, albumArtistName = albumArtistName,
        albumArtistSortName = albumArtistName,
        album = Track.TrackAlbum(id = UUID.randomUUID(), name = album, artworkUrl = null, releaseYear = year),
        name = name, disc = disc, number = number, duration = duration,
        mimetype = "audio/mp4", streamUrl = "/s", playCount = plays, lastPlayedAt = lastPlayed,
    )
}
