package com.ondumusic.player.model

import java.time.Instant
import java.util.UUID

data class Album(
    val id: UUID,
    val artistId: UUID,
    val name: String,
    val sortName: String,
    val slug: String,
    val releaseYear: Int? = null,
    val tracks: List<Track>? = null,
    val isUnknown: Boolean = false,
    val artwork: List<ArtworkVariant> = emptyList(),
    /**
     * The largest variant. Retained because most of the app still asks for one URL;
     * prefer `artwork.covering(pixels)` where the display size is known.
     */
    val artworkUrl: String? = artwork.largest?.url,
    val insertedAt: Instant? = null,
    val artistName: String? = null,
    val artistSortName: String? = null,
)
