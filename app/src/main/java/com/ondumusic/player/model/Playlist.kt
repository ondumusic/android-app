package com.ondumusic.player.model

import java.time.Instant
import java.util.UUID

data class Playlist(
    val id: UUID,
    val name: String,
    val trackCount: Int,
    val insertedAt: Instant,
    val isFolder: Boolean = false,
    /** Null is the root. */
    val parentId: UUID? = null,
    /** Local-only; never sent to or from the server. */
    val autoDownload: Boolean = false,
)

data class PlaylistItem(
    val id: UUID,
    val playlistId: UUID,
    val trackId: UUID,
    /** Base-62 fractional index, byte-compatible with the server's. */
    val position: String,
    val insertedAt: Instant,
)
