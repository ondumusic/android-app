package com.ondumusic.player.api

import com.ondumusic.player.model.ArtworkVariant
import com.ondumusic.player.model.largest
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID


@Serializable
data class SyncResponse(
    val artists: List<SyncArtist>,
    val albums: List<SyncAlbum>,
    val tracks: List<SyncTrack>,
    val playlists: List<SyncPlaylist> = emptyList(),
    val playlistItems: List<SyncPlaylistItem> = emptyList(),
    val deletions: List<SyncDeletion>,
    val cursor: String,
    val complete: Boolean,
    /** Everything currently raised, not what changed — so a response with none means there are none. */
    val notices: List<ServerNoticeDto> = emptyList(),
) {
    val isEmpty: Boolean
        get() = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty() &&
            playlists.isEmpty() && playlistItems.isEmpty() && deletions.isEmpty()
}

/** The server sends `{ lufs, peak }`. Only `lufs` is read: `peak` guards against clipping when boosting. */
@Serializable
data class Loudness(val lufs: Double? = null)

@Serializable
data class SyncArtist(
    val id: SerialUuid,
    val name: String,
    private val sortName: String? = null,
    val slug: String,
    val albumCount: Int,
    val insertedAt: SerialInstant,
    /**
     * Empty unless someone has uploaded one. Unlike album covers these aren't square —
     * the server keeps the source aspect ratio and leaves the crop to us.
     */
    val artwork: List<ArtworkVariant> = emptyList(),
) {
    val resolvedSortName: String get() = sortName ?: name
}

@Serializable
data class SyncAlbum(
    val id: SerialUuid,
    val artistId: SerialUuid,
    val name: String,
    private val sortName: String? = null,
    val slug: String,
    val releaseYear: Int? = null,
    private val artworkUrl: String? = null,
    val artwork: List<ArtworkVariant> = emptyList(),
    val isUnknown: Boolean,
    val trackCount: Int,
    val insertedAt: SerialInstant,
    private val loudness: Loudness? = null,
) {
    val resolvedSortName: String get() = sortName ?: name

    /**
     * `artworkUrl` is deprecated and documented as the largest variant. Derived from the
     * variants when they're there, so its eventual removal is a no-op.
     */
    val resolvedArtworkUrl: String? get() = artwork.largest?.url ?: artworkUrl
    val lufs: Double? get() = loudness?.lufs
}

@Serializable
data class SyncTrack(
    val id: SerialUuid,
    val artistId: SerialUuid,
    val albumArtistId: SerialUuid,
    val albumId: SerialUuid,
    val name: String,
    val disc: Int = 1,
    val number: Int? = null,
    val duration: Double,
    val mimetype: String,
    val streamUrl: String,
    val downloadUrl: String,
    val insertedAt: SerialInstant,
    private val loudness: Loudness? = null,
    val playCount: Int = 0,
    val lastPlayedAt: SerialInstant? = null,
) {
    val lufs: Double? get() = loudness?.lufs
}

@Serializable
data class SyncPlaylist(
    val id: SerialUuid,
    val name: String,
    val trackCount: Int = 0,
    val insertedAt: SerialInstant,
    val isFolder: Boolean = false,
    val parentId: SerialUuid? = null,
)

@Serializable
data class SyncPlaylistItem(
    val id: SerialUuid,
    val playlistId: SerialUuid,
    val trackId: SerialUuid,
    val position: String,
    val insertedAt: SerialInstant,
)

@Serializable
data class SyncDeletion(
    val entityType: String,
    val entityId: SerialUuid,
)

@Serializable
data class ServerNoticeDto(
    val id: SerialUuid,
    val code: String,
    val level: String = "info",
    val message: String,
    val raisedAt: SerialInstant,
)
