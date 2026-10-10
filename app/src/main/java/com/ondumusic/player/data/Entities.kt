package com.ondumusic.player.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ondumusic.player.model.ArtworkVariant
import java.time.Instant
import java.util.UUID

@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey val id: UUID,
    val name: String,
    val sortName: String,
    val slug: String,
    val albumCount: Int,
    val insertedAt: Instant,
    val artwork: List<ArtworkVariant> = emptyList(),
)

@Entity(tableName = "albums", indices = [Index("artistId")])
data class AlbumEntity(
    @PrimaryKey val id: UUID,
    val artistId: UUID,
    val name: String,
    val sortName: String,
    val slug: String,
    val releaseYear: Int?,
    val artworkUrl: String?,
    val artwork: List<ArtworkVariant> = emptyList(),
    val isUnknown: Boolean,
    val trackCount: Int,
    val insertedAt: Instant,
    /** Integrated loudness in LUFS for the album as a whole. Null until the server has measured it. */
    val lufs: Double? = null,
)

@Entity(
    tableName = "tracks",
    indices = [Index("albumId"), Index("artistId"), Index("albumArtistId"), Index("isDownloaded")],
)
data class TrackEntity(
    @PrimaryKey val id: UUID,
    val artistId: UUID,
    val albumArtistId: UUID,
    val albumId: UUID,
    val name: String,
    val disc: Int,
    val number: Int?,
    val duration: Double,
    val mimetype: String,
    val streamUrl: String,
    val downloadUrl: String,
    val insertedAt: Instant,
    val lufs: Double? = null,
    val playCount: Int = 0,
    val lastPlayedAt: Instant? = null,
    // Local-only from here down: sync never writes these.
    @ColumnInfo(defaultValue = "0") val isDownloaded: Boolean = false,
    /** Actual size of the downloaded file on disk. */
    @ColumnInfo(defaultValue = "0") val downloadedFileSize: Long = 0,
    /** Relative to the app's files directory, e.g. "Downloads/{artistId}/{albumId}/{trackId}.flac". */
    val localFilePath: String? = null,
)

/** The columns sync owns. Updating through this leaves download state untouched. */
data class TrackServerFields(
    val id: UUID,
    val artistId: UUID,
    val albumArtistId: UUID,
    val albumId: UUID,
    val name: String,
    val disc: Int,
    val number: Int?,
    val duration: Double,
    val mimetype: String,
    val streamUrl: String,
    val downloadUrl: String,
    val insertedAt: Instant,
    val lufs: Double?,
    /** The server is authoritative: it has counted plays from every device, where this one has only seen its own. */
    val playCount: Int,
    val lastPlayedAt: Instant?,
)

@Entity(tableName = "playlists", indices = [Index("parentId")])
data class PlaylistEntity(
    @PrimaryKey val id: UUID,
    val name: String,
    val trackCount: Int,
    val insertedAt: Instant,
    val isFolder: Boolean = false,
    val parentId: UUID? = null,
    /**
     * Local-only flag set when the user downloads the playlist; cleared on Remove Download.
     * Drives auto-downloading of later additions, local or synced. Never sent to the server.
     */
    @ColumnInfo(defaultValue = "0") val autoDownload: Boolean = false,
)

/** The columns sync owns; `autoDownload` stays as the user left it. */
data class PlaylistServerFields(
    val id: UUID,
    val name: String,
    val trackCount: Int,
    val insertedAt: Instant,
    val isFolder: Boolean,
    val parentId: UUID?,
)

@Entity(tableName = "playlist_items", indices = [Index("playlistId"), Index("trackId")])
data class PlaylistItemEntity(
    @PrimaryKey val id: UUID,
    val playlistId: UUID,
    val trackId: UUID,
    val position: String,
    val insertedAt: Instant,
)

/**
 * Durable queue of playlist mutations pending replay to the server. Processed in
 * `createdAt` order; temp UUIDs assigned locally are resolved to server UUIDs on
 * successful replay, and later payloads referencing those temp IDs are rewritten.
 */
@Entity(tableName = "pending_mutations", indices = [Index("createdAt")])
data class PendingMutationEntity(
    @PrimaryKey val localId: UUID = UUID.randomUUID(),
    /** One of the outbox kind raw values. */
    val kind: String,
    /** JSON payload specific to [kind]. */
    val payload: String,
    val createdAt: Instant = Instant.now(),
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val lastAttemptAt: Instant? = null,
)

/** One joined row: a track with the names the UI needs, so building a [com.ondumusic.player.model.Track] needs no further lookups. */
data class TrackRow(
    @androidx.room.Embedded val track: TrackEntity,
    val artistName: String?,
    val artistSortName: String?,
    val artistSlug: String?,
    val albumArtistName: String?,
    val albumArtistSortName: String?,
    val albumName: String?,
    val albumSortName: String?,
    val albumArtworkUrl: String?,
    val albumReleaseYear: Int?,
)

/**
 * A track waiting to download, downloading, or paused part-way, kept so the queue
 * survives the process being killed. A failed download leaves the table: the failure
 * list lasts the session, as on iOS.
 */
@Entity(tableName = "download_queue")
data class DownloadQueueEntity(
    @PrimaryKey val trackId: UUID,
    /** Epoch millis. Rows from one batch share it and keep their order through the rowid. */
    val enqueuedAt: Long,
    /** Paused individually by the user, so a restored queue leaves it paused. */
    @ColumnInfo(defaultValue = "0") val paused: Boolean = false,
)
