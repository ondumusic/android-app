package com.ondumusic.player.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID

private const val TRACK_SELECT = """
    SELECT t.*,
        ar.name AS artistName, ar.sortName AS artistSortName, ar.slug AS artistSlug,
        aa.name AS albumArtistName, aa.sortName AS albumArtistSortName,
        al.name AS albumName, al.sortName AS albumSortName,
        al.artworkUrl AS albumArtworkUrl, al.releaseYear AS albumReleaseYear
    FROM tracks t
    LEFT JOIN artists ar ON ar.id = t.artistId
    LEFT JOIN artists aa ON aa.id = t.albumArtistId
    LEFT JOIN albums al ON al.id = t.albumId
"""

private const val ALBUM_SELECT = """
    SELECT al.*, ar.name AS artistName, ar.sortName AS artistSortName
    FROM albums al
    LEFT JOIN artists ar ON ar.id = al.artistId
"""

data class AlbumRow(
    @Embedded val album: AlbumEntity,
    val artistName: String?,
    val artistSortName: String?,
)

data class AlbumPlayStatsRow(
    val albumId: UUID,
    val playCount: Int,
    val lastPlayedAt: Instant?,
)

@Dao
interface ArtistDao {
    @Query("SELECT * FROM artists WHERE albumCount > 0 ORDER BY sortName")
    fun observeAll(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artists WHERE albumCount > 0 ORDER BY sortName")
    suspend fun all(): List<ArtistEntity>

    @Query("SELECT * FROM artists WHERE id = :id")
    suspend fun get(id: UUID): ArtistEntity?

    @Query("SELECT * FROM artists WHERE id = :id")
    fun observe(id: UUID): Flow<ArtistEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(artists: List<ArtistEntity>)

    @Query("SELECT id FROM artists WHERE id IN (:ids)")
    suspend fun existingIds(ids: List<UUID>): List<UUID>

    @Query("UPDATE artists SET artwork = :artwork WHERE id = :id")
    suspend fun setArtwork(id: UUID, artwork: String)

    @Query("DELETE FROM artists WHERE id = :id")
    suspend fun delete(id: UUID)

    @Query("DELETE FROM artists")
    suspend fun clear()
}

@Dao
interface AlbumDao {
    @Query("$ALBUM_SELECT WHERE al.trackCount > 0")
    fun observeAll(): Flow<List<AlbumRow>>

    @Query("$ALBUM_SELECT WHERE al.trackCount > 0")
    suspend fun all(): List<AlbumRow>

    @Query("$ALBUM_SELECT WHERE al.artistId = :artistId ORDER BY IFNULL(al.releaseYear, 0) DESC")
    fun observeForArtist(artistId: UUID): Flow<List<AlbumRow>>

    @Query("$ALBUM_SELECT WHERE al.id = :id")
    suspend fun get(id: UUID): AlbumRow?

    @Query("$ALBUM_SELECT WHERE al.id = :id")
    fun observe(id: UUID): Flow<AlbumRow?>

    @Query("SELECT lufs FROM albums WHERE id = :id")
    suspend fun lufs(id: UUID): Double?

    @Query("SELECT id FROM albums WHERE id IN (:ids)")
    suspend fun existingIds(ids: List<UUID>): List<UUID>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(albums: List<AlbumEntity>)

    /**
     * Per album, what its tracks add up to — for the play-based sorts. The server
     * measures plays per track, so an album's figures are the sum and the latest of its own.
     */
    @Query("SELECT albumId, SUM(playCount) AS playCount, MAX(lastPlayedAt) AS lastPlayedAt FROM tracks WHERE playCount > 0 GROUP BY albumId")
    fun observePlayStats(): Flow<List<AlbumPlayStatsRow>>

    @Query("DELETE FROM albums WHERE id = :id")
    suspend fun delete(id: UUID)

    @Query("DELETE FROM albums WHERE artistId = :artistId")
    suspend fun deleteForArtist(artistId: UUID)

    @Query("DELETE FROM albums")
    suspend fun clear()
}

@Dao
interface TrackDao {
    @Query(TRACK_SELECT)
    suspend fun all(): List<TrackRow>

    @Query("$TRACK_SELECT WHERE t.id IN (:ids)")
    suspend fun byIds(ids: List<UUID>): List<TrackRow>

    @Query("$TRACK_SELECT WHERE t.id = :id")
    suspend fun get(id: UUID): TrackRow?

    @Query("$TRACK_SELECT WHERE t.albumId = :albumId ORDER BY t.disc, IFNULL(t.number, 2147483647)")
    fun observeForAlbum(albumId: UUID): Flow<List<TrackRow>>

    @Query("$TRACK_SELECT WHERE t.albumId = :albumId ORDER BY t.disc, IFNULL(t.number, 2147483647)")
    suspend fun forAlbum(albumId: UUID): List<TrackRow>

    @Query("$TRACK_SELECT WHERE t.albumArtistId = :artistId ORDER BY t.disc, IFNULL(t.number, 2147483647)")
    fun observeForAlbumArtist(artistId: UUID): Flow<List<TrackRow>>

    @Query("$TRACK_SELECT WHERE t.isDownloaded = 1")
    fun observeDownloaded(): Flow<List<TrackRow>>

    /** Items whose track is missing from the library are skipped, e.g. after a cascade delete of the track. */
    @Query("$TRACK_SELECT JOIN playlist_items pi ON pi.trackId = t.id WHERE pi.playlistId = :playlistId ORDER BY pi.position")
    fun observeForPlaylist(playlistId: UUID): Flow<List<TrackRow>>

    @Query("$TRACK_SELECT JOIN playlist_items pi ON pi.trackId = t.id WHERE pi.playlistId = :playlistId ORDER BY pi.position")
    suspend fun forPlaylist(playlistId: UUID): List<TrackRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringExisting(tracks: List<TrackEntity>): List<Long>

    @Update(entity = TrackEntity::class)
    suspend fun updateServerFields(fields: List<TrackServerFields>)

    @Query("SELECT localFilePath FROM tracks WHERE id = :id")
    suspend fun localFilePath(id: UUID): String?

    @Query("SELECT localFilePath FROM tracks WHERE id = :id AND isDownloaded = 1")
    suspend fun downloadedFilePath(id: UUID): String?

    @Query("SELECT localFilePath FROM tracks WHERE (artistId = :artistId) AND localFilePath IS NOT NULL")
    suspend fun localFilePathsForArtist(artistId: UUID): List<String>

    @Query("SELECT localFilePath FROM tracks WHERE (albumId = :albumId) AND localFilePath IS NOT NULL")
    suspend fun localFilePathsForAlbum(albumId: UUID): List<String>

    @Query("UPDATE tracks SET playCount = playCount + 1, lastPlayedAt = :at WHERE id = :id")
    suspend fun recordPlay(id: UUID, at: Instant)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun delete(id: UUID)

    @Query("DELETE FROM tracks WHERE artistId = :artistId")
    suspend fun deleteForArtist(artistId: UUID)

    @Query("DELETE FROM tracks WHERE albumId = :albumId")
    suspend fun deleteForAlbum(albumId: UUID)

    @Query("DELETE FROM tracks")
    suspend fun clear()
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: UUID): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observe(id: UUID): Flow<PlaylistEntity?>

    @Query("SELECT id FROM playlists WHERE parentId = :parentId")
    suspend fun childIds(parentId: UUID): List<UUID>

    @Query("SELECT id FROM playlists WHERE id IN (:ids)")
    suspend fun existingIds(ids: List<UUID>): List<UUID>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringExisting(playlists: List<PlaylistEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playlist: PlaylistEntity)

    @Update(entity = PlaylistEntity::class)
    suspend fun updateServerFields(fields: List<PlaylistServerFields>)

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: UUID, name: String)

    @Query("UPDATE playlists SET parentId = :parentId WHERE id = :id")
    suspend fun move(id: UUID, parentId: UUID?)

    @Query("UPDATE playlists SET parentId = :to WHERE parentId = :from")
    suspend fun reparentChildren(from: UUID, to: UUID)

    @Query("UPDATE playlists SET autoDownload = :enabled WHERE id = :id")
    suspend fun setAutoDownload(id: UUID, enabled: Boolean)

    @Query("UPDATE playlists SET trackCount = MAX(0, trackCount + :delta) WHERE id = :id")
    suspend fun adjustTrackCount(id: UUID, delta: Int)

    /** Tracks that live in an auto-downloaded playlist and aren't downloaded yet. */
    @Query("""
        SELECT DISTINCT t.id FROM tracks t
        JOIN playlist_items pi ON pi.trackId = t.id
        JOIN playlists p ON p.id = pi.playlistId
        WHERE p.autoDownload = 1 AND t.isDownloaded = 0
    """)
    suspend fun tracksNeedingAutoDownload(): List<UUID>

    @Query("""
        SELECT DISTINCT pi.playlistId FROM playlist_items pi
        JOIN tracks t ON t.id = pi.trackId
        WHERE t.isDownloaded = 1
    """)
    fun observeIdsWithDownloads(): Flow<List<UUID>>

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: UUID)

    @Query("DELETE FROM playlists")
    suspend fun clear()
}

@Dao
interface PlaylistItemDao {
    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position")
    suspend fun forPlaylist(playlistId: UUID): List<PlaylistItemEntity>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position")
    fun observeForPlaylist(playlistId: UUID): Flow<List<PlaylistItemEntity>>

    @Query("SELECT position FROM playlist_items WHERE playlistId = :playlistId ORDER BY position DESC LIMIT 1")
    suspend fun lastPosition(playlistId: UUID): String?

    @Query("SELECT * FROM playlist_items WHERE id = :id")
    suspend fun get(id: UUID): PlaylistItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<PlaylistItemEntity>)

    @Query("SELECT id FROM playlist_items WHERE id IN (:ids)")
    suspend fun existingIds(ids: List<UUID>): List<UUID>

    @Query("UPDATE playlist_items SET position = :position WHERE id = :id")
    suspend fun move(id: UUID, position: String)

    @Query("UPDATE playlist_items SET playlistId = :to WHERE playlistId = :from")
    suspend fun reassign(from: UUID, to: UUID)

    @Query("DELETE FROM playlist_items WHERE id = :id")
    suspend fun delete(id: UUID)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: UUID)

    @Query("DELETE FROM playlist_items")
    suspend fun clear()
}

@Dao
interface PendingMutationDao {
    @Query("SELECT * FROM pending_mutations ORDER BY createdAt")
    suspend fun all(): List<PendingMutationEntity>

    @Query("SELECT COUNT(*) FROM pending_mutations")
    fun observeCount(): Flow<Int>

    @Insert
    suspend fun insert(mutation: PendingMutationEntity)

    @Update
    suspend fun update(mutation: PendingMutationEntity)

    @Update
    suspend fun update(mutations: List<PendingMutationEntity>)

    @Query("DELETE FROM pending_mutations WHERE localId = :localId")
    suspend fun delete(localId: UUID)

    @Query("DELETE FROM pending_mutations WHERE localId IN (:localIds)")
    suspend fun delete(localIds: List<UUID>)

    @Query("DELETE FROM pending_mutations")
    suspend fun clear()
}
