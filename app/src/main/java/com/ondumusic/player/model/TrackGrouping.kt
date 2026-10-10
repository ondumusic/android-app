package com.ondumusic.player.model

object TrackGrouping {
    /**
     * Flat track index → the disc number of a divider row that belongs above it.
     *
     * Only albums that actually span discs get them: numbering restarts at 1 on each
     * disc, which reads as a duplicate row without something in between.
     */
    fun discBreaks(tracks: List<Track>): Map<Int, Int> {
        if (tracks.map { it.disc }.toSet().size <= 1) return emptyMap()
        val breaks = mutableMapOf<Int, Int>()
        var previous: Int? = null
        tracks.forEachIndexed { index, track ->
            if (track.disc != previous) {
                breaks[index] = track.disc
                previous = track.disc
            }
        }
        return breaks
    }

    /**
     * The same breaks for a list built out of per-album sections, keyed by index into
     * the flat concatenation the list is given.
     */
    fun discBreaksForSections(sections: List<List<Track>>): Map<Int, Int> {
        val breaks = mutableMapOf<Int, Int>()
        var offset = 0
        for (tracks in sections) {
            for ((index, disc) in discBreaks(tracks)) {
                breaks[offset + index] = disc
            }
            offset += tracks.size
        }
        return breaks
    }
}
