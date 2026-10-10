package com.ondumusic.player.data

import androidx.room.withTransaction
import com.ondumusic.player.api.SyncAlbum
import com.ondumusic.player.api.SyncArtist
import com.ondumusic.player.api.SyncDeletion
import com.ondumusic.player.api.SyncPlaylist
import com.ondumusic.player.api.SyncPlaylistItem
import com.ondumusic.player.api.SyncTrack
import com.ondumusic.player.model.Album
import com.ondumusic.player.model.AlbumPlayStats
import com.ondumusic.player.model.Artist
import com.ondumusic.player.model.ArtworkVariant
import com.ondumusic.player.model.Playlist
import com.ondumusic.player.model.PlaylistItem
import com.ondumusic.player.model.Track
import androidx.room.InvalidationTracker
import com.ondumusic.player.util.NaturalOrder
import com.ondumusic.player.util.compareNatural
import com.ondumusic.player.util.containsNatural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * The on-device library: everything synced from the server plus local-only state
 * (download state, queued playlist edits). All browsing reads from here — there is no
 * API pagination for browse views.
 *
 * Reads are Room flows, so screens update when the tables they read change; that takes
 * the place of the iOS store's `revision` counter.
 */
class MusicLibrary(
    internal val db: LibraryDatabase,
    /** Root that stored download paths are relative to. */
    internal val filesDir: File,
) {
    private val _playlistIdRemapped = MutableSharedFlow<Pair<UUID, UUID>>(extraBufferCapacity = 16)

    /** (temp, real) when a playlist created offline gets its server id, so screens showing it can follow. */
    val playlistIdRemapped: SharedFlow<Pair<UUID, UUID>> = _playlistIdRemapped

    /**
     * `allTracks()` is the most expensive query in the app at library scale, and every
     * visit to Songs asks for it. Held until a table it reads from changes.
     */
    @Volatile private var cachedAllTracks: List<Track>? = null
    /** Bumped on every change, so a rebuild that raced a write doesn't install what it read before it. */
    private val tracksGeneration = java.util.concurrent.atomic.AtomicLong()
    private val allTracksLock = Mutex()

    init {
        // Room reports changes asynchronously, which covers writes made elsewhere (downloads);
        // this class's own writes also invalidate directly so a read right after sees them.
        db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(arrayOf("tracks", "artists", "albums")) {
            override fun onInvalidated(tables: Set<String>) = invalidateTracks()
        })
    }

    internal fun invalidateTracks() {
        tracksGeneration.incrementAndGet()
        cachedAllTracks = null
    }

    // Artists

    fun observeArtists(): Flow<List<Artist>> = db.artists().observeAll().map { rows -> rows.map { it.toArtist() } }

    fun observeArtist(id: UUID): Flow<Artist?> = db.artists().observe(id).map { it?.toArtist() }

    suspend fun artist(id: UUID): Artist? = db.artists().get(id)?.toArtist()

    // Albums

    fun observeAlbums(): Flow<List<Album>> = db.albums().observeAll().map { rows -> rows.map { it.toAlbum() } }

    /** Newest first. */
    fun observeAlbumsForArtist(artistId: UUID): Flow<List<Album>> =
        db.albums().observeForArtist(artistId).map { rows -> rows.map { it.toAlbum() } }

    fun observeAlbum(id: UUID): Flow<Album?> = db.albums().observe(id).map { it?.toAlbum() }

    /** Albums with at least one downloaded track — what the Downloaded filter shows. */
    fun observeDownloadedAlbumIds(): Flow<Set<UUID>> = db.tracks().observeDownloadedAlbumIds().map { it.toSet() }

    /** Only albums that have been played appear; the rest count as never played. */
    fun observeAlbumPlayStats(): Flow<Map<UUID, AlbumPlayStats>> = db.albums().observePlayStats()
        .map { rows -> rows.associate { it.albumId to AlbumPlayStats(it.playCount, it.lastPlayedAt) } }

    suspend fun album(id: UUID): Album? = db.albums().get(id)?.toAlbum()

    /** The album's loudness as the server measured it. Null until it has. */
    suspend fun albumLufs(albumId: UUID): Double? = db.albums().lufs(albumId)

    // Tracks

    suspend fun track(id: UUID): Track? = db.tracks().get(id)?.toTrack()

    /** Resolves many tracks in one query, keyed by id — restoring a saved queue needs hundreds at once. */
    suspend fun tracks(ids: Collection<UUID>): Map<UUID, Track> {
        if (ids.isEmpty()) return emptyMap()
        // SQLite caps bound parameters; a long queue can exceed it.
        return ids.distinct().chunked(900)
            .flatMap { db.tracks().byIds(it) }
            .associate { it.track.id to it.toTrack() }
    }

    /** The downloaded file for a track, if it's downloaded and the file is still there. */
    suspend fun downloadedFile(trackId: UUID): File? =
        db.tracks().downloadedFilePath(trackId)?.let { File(filesDir, it) }?.takeIf { it.exists() }

    /** Disc, then track number. */
    fun observeTracksForAlbum(albumId: UUID): Flow<List<Track>> =
        db.tracks().observeForAlbum(albumId).map { rows -> rows.map { it.toTrack() } }

    suspend fun tracksForAlbum(albumId: UUID): List<Track> = db.tracks().forAlbum(albumId).map { it.toTrack() }

    /** Every track credited to the artist as album artist, in disc/number order within each album. */
    fun observeTracksForAlbumArtist(artistId: UUID): Flow<List<Track>> =
        db.tracks().observeForAlbumArtist(artistId).map { rows -> rows.map { it.toTrack() } }

    /** The library's own order: album artist, album, disc, track number. */
    suspend fun allTracks(): List<Track> {
        cachedAllTracks?.let { return it }
        return allTracksLock.withLock {
            cachedAllTracks ?: run {
                val generation = tracksGeneration.get()
                val built = withContext(Dispatchers.Default) { sortedTracks(db.tracks().all().map { it.toTrack() }) }
                if (tracksGeneration.get() == generation) cachedAllTracks = built
                built
            }
        }
    }

    fun observeAllTracks(): Flow<List<Track>> =
        db.invalidationTracker.createFlow("tracks", "artists", "albums")
            .conflate()
            .map { allTracks() }

    /**
     * Matches artists and albums on their names, and tracks on their title — plus, so that
     * searching an artist's name lists their discography and songs rather than only the
     * titles containing that word, albums by a matched artist and tracks by a matched
     * artist or on a matched album.
     */
    suspend fun search(query: String): SearchResults = withContext(Dispatchers.Default) {
        if (query.isBlank()) return@withContext SearchResults()
        val artists = db.artists().all()
            .filter { it.name.containsNatural(query) || it.sortName.containsNatural(query) }
            .map { it.toArtist() }
        val artistIds = artists.mapTo(HashSet()) { it.id }

        val albums = db.albums().all().map { it.toAlbum() }
            .filter { it.name.containsNatural(query) || it.sortName.containsNatural(query) || it.artistId in artistIds }
            .sortedWith(
                // Name matches first, then the artist's discography in its own order.
                compareByDescending<Album> { it.name.containsNatural(query) }
                    .thenBy { it.artistSortName.orEmpty() }
                    .thenByDescending { it.releaseYear ?: 0 }
                    .thenComparator { a, b -> a.sortName.compareNatural(b.sortName) }
            )
        val albumIds = albums.mapTo(HashSet()) { it.id }

        // Title matches rank above tracks that only matched through their artist or album —
        // typing a song name should not bury it under that artist's discography.
        val tracks = allTracks()
            .filter {
                it.name.containsNatural(query) || it.artistId in artistIds ||
                    it.albumArtistId in artistIds || it.albumId in albumIds
            }
            .sortedWith(
                compareByDescending<Track> { it.name.containsNatural(query) }
                    .thenBy { it.artist.sortName }
                    .thenBy { it.name }
            )
        SearchResults(artists, albums, tracks)
    }

    suspend fun tracksForAlbumArtistInOrder(artistId: UUID): List<Track> =
        db.tracks().forArtistInPlayOrder(artistId).map { it.toTrack() }

    // Playlists

    fun observePlaylists(): Flow<List<Playlist>> = db.playlists().observeAll().map { rows -> rows.map { it.toPlaylist() } }

    fun observePlaylist(id: UUID): Flow<Playlist?> = db.playlists().observe(id).map { it?.toPlaylist() }

    suspend fun playlist(id: UUID): Playlist? = db.playlists().get(id)?.toPlaylist()

    suspend fun playlistItems(playlistId: UUID): List<PlaylistItem> =
        db.playlistItems().forPlaylist(playlistId).map { it.toItem() }

    fun observePlaylistItems(playlistId: UUID): Flow<List<PlaylistItem>> =
        db.playlistItems().observeForPlaylist(playlistId).map { rows -> rows.map { it.toItem() } }

    /** In position order. Items whose track has gone from the library are skipped. */
    fun observeTracksForPlaylist(playlistId: UUID): Flow<List<Track>> =
        db.tracks().observeForPlaylist(playlistId).map { rows -> rows.map { it.toTrack() } }

    suspend fun tracksForPlaylist(playlistId: UUID): List<Track> = db.tracks().forPlaylist(playlistId).map { it.toTrack() }

    suspend fun setPlaylistAutoDownload(id: UUID, enabled: Boolean) = db.playlists().setAutoDownload(id, enabled)

    suspend fun tracksNeedingAutoDownload(): Set<UUID> = db.playlists().tracksNeedingAutoDownload().toSet()

    /** Playlists holding at least one downloaded track — what the downloaded-only filter keeps. */
    fun observePlaylistIdsWithDownloads(): Flow<Set<UUID>> = db.playlists().observeIdsWithDownloads().map { it.toSet() }

    // Sync writes

    suspend fun upsertArtists(artists: List<SyncArtist>) {
        if (artists.isEmpty()) return
        invalidateTracks()
        db.artists().upsert(artists.map { it.toEntity() })
    }

    /** Returns the ids of albums that weren't in the library before. */
    suspend fun upsertAlbums(albums: List<SyncAlbum>): Set<UUID> {
        if (albums.isEmpty()) return emptySet()
        invalidateTracks()
        return db.withTransaction {
            val existing = existingIn(albums.map { it.id }) { db.albums().existingIds(it) }
            db.albums().upsert(albums.map { it.toEntity() })
            albums.map { it.id }.filterNot { it in existing }.toSet()
        }
    }

    /**
     * Returns the ids of tracks that weren't in the library before. Existing rows get only
     * their server-owned columns rewritten, so download state survives every sync.
     */
    suspend fun upsertTracks(tracks: List<SyncTrack>): Set<UUID> {
        if (tracks.isEmpty()) return emptySet()
        invalidateTracks()
        return db.withTransaction {
            val results = db.tracks().insertIgnoringExisting(tracks.map { it.toEntity() })
            val inserted = mutableSetOf<UUID>()
            val existing = mutableListOf<TrackServerFields>()
            tracks.forEachIndexed { i, track ->
                if (results[i] == -1L) existing += track.toServerFields() else inserted += track.id
            }
            db.tracks().updateServerFields(existing)
            inserted
        }
    }

    suspend fun upsertPlaylists(playlists: List<SyncPlaylist>) {
        if (playlists.isEmpty()) return
        db.withTransaction {
            val results = db.playlists().insertIgnoringExisting(playlists.map { it.toEntity() })
            db.playlists().updateServerFields(playlists.filterIndexed { i, _ -> results[i] == -1L }.map { it.toServerFields() })
        }
    }

    suspend fun upsertPlaylistItems(items: List<SyncPlaylistItem>) {
        if (items.isEmpty()) return
        db.playlistItems().upsert(items.map { it.toEntity() })
    }

    suspend fun applyDeletions(deletions: List<SyncDeletion>) {
        if (deletions.isEmpty()) return
        invalidateTracks()
        db.withTransaction {
            for (deletion in deletions) {
                val id = deletion.entityId
                when (deletion.entityType) {
                    "artist" -> deleteArtistCascade(id)
                    "album" -> deleteAlbumCascade(id)
                    "track" -> {
                        db.tracks().localFilePath(id)?.let(::deleteDownloadedFile)
                        db.tracks().delete(id)
                    }
                    "playlist" -> deletePlaylistCascade(id)
                    "playlistItem" -> db.playlistItems().delete(id)
                }
            }
        }
    }

    /**
     * Empties the library.
     *
     * [includingDownloads] is for logging out, which discards the user's data on purpose. A
     * re-sync must not pass it: an initial sync clears the tables before repopulating them,
     * and taking the audio files with it would leave reconciliation nothing to put back.
     */
    suspend fun clearAll(includingDownloads: Boolean = false) {
        if (includingDownloads) File(filesDir, DOWNLOADS_DIR).deleteRecursively()
        db.withTransaction {
            db.tracks().clear()
            db.albums().clear()
            db.artists().clear()
            db.playlistItems().clear()
            db.playlists().clear()
            db.pendingMutations().clear()
            // A rebuild keeps the queue: its rows name tracks that are about to come back.
            if (includingDownloads) db.downloadQueue().clear()
        }
        invalidateTracks()
    }

    private suspend fun deleteArtistCascade(artistId: UUID) {
        db.tracks().localFilePathsForArtist(artistId).forEach(::deleteDownloadedFile)
        db.tracks().deleteForArtist(artistId)
        db.albums().deleteForArtist(artistId)
        db.artists().delete(artistId)
    }

    private suspend fun deleteAlbumCascade(albumId: UUID) {
        db.tracks().localFilePathsForAlbum(albumId).forEach(::deleteDownloadedFile)
        db.tracks().deleteForAlbum(albumId)
        db.albums().delete(albumId)
    }

    private suspend fun deletePlaylistCascade(playlistId: UUID) {
        db.playlistItems().deleteForPlaylist(playlistId)
        db.playlists().delete(playlistId)
    }

    private fun deleteDownloadedFile(relativePath: String) {
        File(filesDir, relativePath).delete()
    }

    /** Applies what an artwork upload or delete returned, so the change shows before the next sync. */
    suspend fun setArtistArtwork(artistId: UUID, artwork: List<ArtworkVariant>) =
        db.artists().setArtwork(artistId, ArtworkJson.encode(artwork))

    /**
     * Counts a play locally so the UI doesn't wait for the round trip. The next sync
     * replaces both fields with the server's totals, which include other devices.
     */
    suspend fun recordLocalPlay(trackId: UUID, at: Instant) {
        db.tracks().recordPlay(trackId, at)
        invalidateTracks()
    }

    // Optimistic playlist writes

    suspend fun lastPlaylistItemPosition(playlistId: UUID): String? = db.playlistItems().lastPosition(playlistId)

    suspend fun insertLocalPlaylist(playlist: Playlist) = db.playlists().upsert(
        PlaylistEntity(playlist.id, playlist.name, playlist.trackCount, playlist.insertedAt, playlist.isFolder, playlist.parentId)
    )

    suspend fun renameLocalPlaylist(id: UUID, name: String) = db.playlists().rename(id, name)

    /** Null [parentId] is the root. */
    suspend fun moveLocalPlaylist(id: UUID, parentId: UUID?) = db.playlists().move(id, parentId)

    /**
     * This playlist and, for folders, every nested descendant, depth-first pre-order. The
     * server forbids cycles; the visited set guards against a malformed local copy anyway.
     */
    suspend fun descendantIds(playlistId: UUID): List<UUID> {
        val result = mutableListOf<UUID>()
        val visited = mutableSetOf<UUID>()
        val stack = ArrayDeque(listOf(playlistId))
        while (stack.isNotEmpty()) {
            val next = stack.removeLast()
            if (!visited.add(next)) continue
            result += next
            stack.addAll(db.playlists().childIds(next))
        }
        return result
    }

    /** Folders cascade to everything nested, matching what the server will do. */
    suspend fun deleteLocalPlaylist(id: UUID) = db.withTransaction {
        descendantIds(id).forEach { deletePlaylistCascade(it) }
    }

    suspend fun insertLocalPlaylistItem(item: PlaylistItem) = db.withTransaction {
        db.playlistItems().upsert(listOf(item.toEntity()))
        db.playlists().adjustTrackCount(item.playlistId, 1)
    }

    suspend fun deleteLocalPlaylistItem(id: UUID) = db.withTransaction {
        val item = db.playlistItems().get(id) ?: return@withTransaction
        db.playlistItems().delete(id)
        db.playlists().adjustTrackCount(item.playlistId, -1)
    }

    suspend fun moveLocalPlaylistItem(itemId: UUID, position: String) = db.playlistItems().move(itemId, position)

    /** Replaces an optimistic item's temp id with the one the server assigned. */
    suspend fun swapLocalPlaylistItemId(tempId: UUID, confirmed: PlaylistItem) = db.withTransaction {
        db.playlistItems().delete(tempId)
        db.playlistItems().upsert(listOf(confirmed.toEntity()))
    }

    /**
     * Rewrites a playlist created offline from its temp id to the server's, carrying its
     * items, any children that point at it, and the local auto-download flag along.
     */
    suspend fun remapPlaylistId(tempId: UUID, confirmed: Playlist) {
        db.withTransaction {
            val local = db.playlists().get(tempId)
            val autoDownload = local?.autoDownload ?: false
            // The server counted the playlist when it was created; anything added since is only
            // counted here until the next sync, so the local figure is the right one to keep.
            val trackCount = maxOf(confirmed.trackCount, local?.trackCount ?: 0)
            db.playlists().delete(tempId)
            db.playlists().upsert(
                PlaylistEntity(confirmed.id, confirmed.name, trackCount, confirmed.insertedAt,
                    confirmed.isFolder, confirmed.parentId, autoDownload)
            )
            db.playlistItems().reassign(tempId, confirmed.id)
            db.playlists().reparentChildren(tempId, confirmed.id)
        }
        _playlistIdRemapped.tryEmit(tempId to confirmed.id)
    }

    // Outbox storage

    suspend fun pendingMutations(): List<PendingMutationEntity> = db.pendingMutations().all()

    suspend fun enqueuePendingMutation(mutation: PendingMutationEntity) = db.pendingMutations().insert(mutation)

    suspend fun updatePendingMutations(mutations: List<PendingMutationEntity>) = db.pendingMutations().update(mutations)

    suspend fun deletePendingMutations(localIds: List<UUID>) = db.pendingMutations().delete(localIds)

    private suspend fun existingIn(ids: List<UUID>, query: suspend (List<UUID>) -> List<UUID>): Set<UUID> =
        ids.chunked(900).flatMap { query(it) }.toSet()

    companion object {
        /**
         * Sorted per album rather than per track: every track in an album shares both
         * string keys, so comparing albums once instead of collating on every one of
         * n log n track comparisons is most of the cost saved.
         */
        fun sortedTracks(tracks: List<Track>): List<Track> =
            tracks.groupBy { it.albumArtistId to it.albumId }
                .values
                .sortedWith(
                    compareBy<List<Track>> { it.first().albumArtistSortName }
                        .thenBy(NaturalOrder) { it.first().album?.sortName.orEmpty() }
                )
                .flatMap { album -> album.sortedWith(compareBy({ it.disc }, { it.number ?: Int.MAX_VALUE })) }

        /** Downloads/{artistId}/{albumId}/{trackId}.ext under the app's files directory. */
        const val DOWNLOADS_DIR = "Downloads"
    }
}

data class SearchResults(
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
) {
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty()
}
