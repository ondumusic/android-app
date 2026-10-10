package com.ondumusic.player.model

import java.util.UUID

data class QueueItem(
    val track: Track,
    val playbackUrl: String? = null,
    val artworkUrl: String? = null,
    val id: UUID = UUID.randomUUID(),
)
