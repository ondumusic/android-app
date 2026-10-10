package com.ondumusic.player.ui.songs

import com.ondumusic.player.model.Artist
import com.ondumusic.player.model.Track
import com.ondumusic.player.model.TrackSort
import com.ondumusic.player.model.TrackSortColumn
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class SongSectionsTest {
    @Test
    fun libraryOrderFilesByAlbumArtistInitial() {
        val tracks = listOf(track("x", "ABBA"), track("y", "Air"), track("z", "Björk"), track("w", "!!!"))
        val sections = songSections(tracks, sort = null)
        assertEquals(listOf("A", "B", "#"), sections.map { it.letter })
        assertEquals(2, sections[0].tracks.size)
    }

    @Test
    fun accentedInitialsFileUnderTheirLetter() {
        val sections = songSections(listOf(track("x", "Eagles"), track("y", "Édith Piaf")), sort = null)
        assertEquals(listOf("E"), sections.map { it.letter })
    }

    @Test
    fun titleOrderFilesByTitle() {
        val sections = songSections(listOf(track("Alpha", "Zed"), track("Beta", "Zed")), TrackSort(TrackSortColumn.Title, true))
        assertEquals(listOf("A", "B"), sections.map { it.letter })
    }

    @Test
    fun numericOrdersHaveNoLetters() {
        val tracks = listOf(track("Alpha", "Zed"), track("Beta", "Abe"))
        val sections = songSections(tracks, TrackSort(TrackSortColumn.Plays, false))
        assertEquals(listOf<String?>(null), sections.map { it.letter })
        assertEquals(2, sections[0].tracks.size)
    }

    @Test
    fun emptyHasNoSections() {
        assertEquals(0, songSections(emptyList(), TrackSort(TrackSortColumn.Duration, true)).size)
    }

    private fun track(name: String, albumArtist: String): Track {
        val artist = Artist(id = UUID.randomUUID(), name = albumArtist, sortName = albumArtist, slug = "a")
        return Track(
            id = UUID.randomUUID(), artistId = artist.id, albumArtistId = artist.id, albumId = UUID.randomUUID(),
            artist = artist, albumArtistSortName = albumArtist, name = name, disc = 1, number = 1,
            duration = 100.0, mimetype = "audio/mp4", streamUrl = "/s",
        )
    }
}
