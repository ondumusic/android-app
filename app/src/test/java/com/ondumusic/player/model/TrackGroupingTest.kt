package com.ondumusic.player.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class TrackGroupingTest {

    @Test fun singleDiscAlbumGetsNoBreaks() {
        val tracks = (1..5).map { makeTrack(disc = 1, number = it) }
        assertTrue(TrackGrouping.discBreaks(tracks).isEmpty())
    }

    @Test fun breaksMarkTheFirstTrackOfEachDisc() {
        val tracks = (1..3).map { makeTrack(disc = 1, number = it) } +
            (1..2).map { makeTrack(disc = 2, number = it) }
        assertEquals(mapOf(0 to 1, 3 to 2), TrackGrouping.discBreaks(tracks))
    }

    // A disc the order returns to later gets a second marker — the divider describes
    // the run of rows beneath it, not the disc as a whole.
    @Test fun interleavedDiscsBreakEveryRun() {
        val tracks = listOf(
            makeTrack(disc = 1, number = 1),
            makeTrack(disc = 2, number = 1),
            makeTrack(disc = 1, number = 2),
        )
        assertEquals(listOf(0, 1, 2), TrackGrouping.discBreaks(tracks).keys.sorted())
    }

    @Test fun sectionBreaksAreKeyedByFlatIndex() {
        val single = listOf(makeTrack(disc = 1, number = 1), makeTrack(disc = 1, number = 2))
        val double = listOf(makeTrack(disc = 1, number = 1), makeTrack(disc = 2, number = 1))
        // The single-disc album contributes nothing; the next album's indices start at 2.
        assertEquals(mapOf(2 to 1, 3 to 2), TrackGrouping.discBreaksForSections(listOf(single, double)))
    }

    @Test fun emptyAlbumGetsNoBreaks() {
        assertTrue(TrackGrouping.discBreaks(emptyList()).isEmpty())
    }
}

private val groupingArtist = Artist(
    id = UUID.fromString("00000000-0000-0000-0000-000000000002"),
    name = "Test Artist",
    sortName = "Test Artist",
    slug = "test-artist",
)

private fun makeTrack(disc: Int, number: Int) = Track(
    id = UUID.randomUUID(),
    artistId = groupingArtist.id,
    albumArtistId = groupingArtist.id,
    albumId = UUID.randomUUID(),
    artist = groupingArtist,
    albumArtistSortName = groupingArtist.sortName,
    name = "Track $number",
    disc = disc,
    number = number,
    duration = 100.0,
    mimetype = "audio/mp4",
    streamUrl = "/stream/test",
)
