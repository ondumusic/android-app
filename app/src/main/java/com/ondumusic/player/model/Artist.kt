package com.ondumusic.player.model

import java.util.UUID

data class Artist(
    val id: UUID,
    val name: String,
    val sortName: String,
    val slug: String,
    val albumCount: Int? = null,
    val artwork: List<ArtworkVariant> = emptyList(),
)
