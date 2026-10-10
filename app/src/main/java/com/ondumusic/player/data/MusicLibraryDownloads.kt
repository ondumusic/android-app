package com.ondumusic.player.data

import android.util.Log
import com.ondumusic.player.BuildConfig
import com.ondumusic.player.model.Album
import com.ondumusic.player.model.Artist
import com.ondumusic.player.model.Track
import com.ondumusic.player.util.compareNatural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** One row of the Downloads list: an album and what of it is on disk. */
data class DownloadedAlbum(val album: Album, val trackCount: Int, val size: Long)

/** An album artist with something downloaded; [albumCount] counts only albums with downloads. */
data class DownloadedArtist(val artist: Artist, val albumCount: Int, val size: Long)

data class DownloadSummary(val artists: List<DownloadedArtist> = emptyList(), val totalSize: Long = 0)

// Paths

/** `Downloads/{artistId}/{albumId}/{trackId}.{ext}`, relative to the files directory. Reconciliation reads the track id back from the name. */
fun downloadRelativePath(artistId: UUID, albumId: UUID, trackId: UUID, extension: String): String =
    "${MusicLibrary.DOWNLOADS_DIR}/$artistId/$albumId/$trackId.$extension"

private const val PART_SUFFIX = ".part"

/** Track id a download file is named after, or null for a file that isn't one of ours. */
private fun trackIdOf(file: File): UUID? = runCatching { UUID.fromString(file.name.substringBefore('.')) }.getOrNull()

// Queries

fun MusicLibrary.observeDownloadedTrackIds(): Flow<Set<UUID>> = db.tracks().observeDownloadedIds().map { it.toSet() }

suspend fun MusicLibrary.downloadedTrackIds(): Set<UUID> = db.tracks().downloadedIds().toHashSet()

/** By the flag alone; [MusicLibrary.downloadedFile] also checks the file is there. */
suspend fun MusicLibrary.isDownloaded(trackId: UUID): Boolean = db.tracks().downloadedFilePath(trackId) != null

/** Artists with downloads, sorted by sort name, with the total size of everything downloaded. */
fun MusicLibrary.observeDownloadSummary(): Flow<DownloadSummary> =
    db.tracks().observeDownloadedArtists().map { rows ->
        DownloadSummary(
            artists = rows
                .map { DownloadedArtist(it.artist.toArtist(), it.downloadedAlbumCount, it.downloadedSize) }
                .sortedWith { a, b -> a.artist.sortName.compareNatural(b.artist.sortName) },
            totalSize = rows.sumOf { it.downloadedSize },
        )
    }

/** By artist, then album name. */
fun MusicLibrary.observeDownloadedAlbums(): Flow<List<DownloadedAlbum>> =
    db.tracks().observeDownloadedAlbums().map { rows ->
        rows.map { it.toDownloadedAlbum() }.sortedWith { a, b ->
            val byArtist = (a.album.artistSortName ?: a.album.artistName.orEmpty())
                .compareNatural(b.album.artistSortName ?: b.album.artistName.orEmpty())
            if (byArtist != 0) byArtist else a.album.name.compareNatural(b.album.name)
        }
    }

/** One artist's albums with downloads, newest first. */
fun MusicLibrary.observeDownloadedAlbums(artistId: UUID): Flow<List<DownloadedAlbum>> =
    db.tracks().observeDownloadedAlbums().map { rows ->
        rows.filter { it.album.album.artistId == artistId }
            .map { it.toDownloadedAlbum() }
            .sortedByDescending { it.album.releaseYear ?: 0 }
    }

/** In the library's own order, like [MusicLibrary.allTracks]. */
fun MusicLibrary.observeDownloadedTracks(): Flow<List<Track>> =
    db.tracks().observeDownloaded().map { rows -> MusicLibrary.sortedTracks(rows.map { it.toTrack() }) }

fun MusicLibrary.observeAlbumDownloadCount(albumId: UUID): Flow<DownloadCount> = db.tracks().observeAlbumDownloadCount(albumId)

/** Counted over the tracks credited to the artist as album artist. */
fun MusicLibrary.observeArtistDownloadCount(artistId: UUID): Flow<DownloadCount> = db.tracks().observeArtistDownloadCount(artistId)

suspend fun MusicLibrary.isAlbumFullyDownloaded(albumId: UUID): Boolean = observeAlbumDownloadCount(albumId).first().isFull

suspend fun MusicLibrary.isArtistDownloaded(artistId: UUID): Boolean = observeArtistDownloadCount(artistId).first().isAny

/** Offline, only what's on the device can play. */
fun availableTracks(tracks: List<Track>, isOnline: Boolean): List<Track> =
    if (isOnline) tracks else tracks.filter { it.isDownloaded }

private fun DownloadedAlbumRow.toDownloadedAlbum() = DownloadedAlbum(album.toAlbum(), downloadedCount, downloadedSize)

// Writes

/** False when the track has left the library meanwhile, in which case the file is the caller's to delete. */
suspend fun MusicLibrary.markDownloaded(trackId: UUID, relativePath: String, size: Long): Boolean {
    val updated = db.tracks().markDownloaded(trackId, relativePath, size) > 0
    invalidateTracks()
    return updated
}

/** Deletes the files and clears the flags. */
suspend fun MusicLibrary.removeDownloads(trackIds: Collection<UUID>) {
    if (trackIds.isEmpty()) return
    removeDownloaded(trackIds.distinct().chunked(900).flatMap { db.tracks().downloadedPaths(it) })
}

suspend fun MusicLibrary.removeAlbumDownloads(albumId: UUID) = removeDownloaded(db.tracks().downloadedPathsForAlbum(albumId))

/** Everything downloaded that's credited to the artist as album artist. */
suspend fun MusicLibrary.removeArtistDownloads(artistId: UUID) = removeDownloaded(db.tracks().downloadedPathsForAlbumArtist(artistId))

/** Takes the whole directory, partial downloads included. Anything still downloading should be cancelled first. */
suspend fun MusicLibrary.removeAllDownloads() {
    val rows = db.tracks().downloadedPaths()
    withContext(Dispatchers.IO) { File(filesDir, MusicLibrary.DOWNLOADS_DIR).deleteRecursively() }
    rows.map { it.id }.chunked(900).forEach { db.tracks().markNotDownloaded(it) }
    invalidateTracks()
}

private suspend fun MusicLibrary.removeDownloaded(rows: List<DownloadedPathRow>) {
    if (rows.isEmpty()) return
    // Files first: reconciliation re-marks any file it finds, so clearing the flag while the
    // file is still there could bring the download back.
    withContext(Dispatchers.IO) { rows.forEach { row -> row.localFilePath?.let { File(filesDir, it).delete() } } }
    rows.map { it.id }.chunked(900).forEach { db.tracks().markNotDownloaded(it) }
    invalidateTracks()
}

// Download queue storage

suspend fun MusicLibrary.downloadQueue(): List<DownloadQueueEntity> = db.downloadQueue().all()

suspend fun MusicLibrary.enqueueDownloads(rows: List<DownloadQueueEntity>) = rows.chunked(900).forEach { db.downloadQueue().insert(it) }

suspend fun MusicLibrary.setDownloadsPaused(trackIds: Collection<UUID>, paused: Boolean) =
    trackIds.toList().chunked(900).forEach { db.downloadQueue().setPaused(it, paused) }

suspend fun MusicLibrary.dequeueDownloads(trackIds: Collection<UUID>) =
    trackIds.toList().chunked(900).forEach { db.downloadQueue().delete(it) }

suspend fun MusicLibrary.clearDownloadQueue() = db.downloadQueue().clear()

// Reconciliation

/**
 * Puts download state back from the files on disk, which carry their track id in their
 * name. That takes the place of iOS's manifest: a rebuild empties the tables but leaves
 * the files, and once the tracks are back this re-marks every one that still exists.
 *
 * Cheap when nothing changed: files the library already points at are skipped without a
 * query. Orphans — files whose track has left the library — are only deleted when
 * [deleteOrphans] is set, which callers do after a full sync, when the library is known
 * complete; deleting on an incomplete library would destroy the user's music. Partial
 * downloads count as orphans once their track is no longer queued.
 */
suspend fun MusicLibrary.reconcileDownloadedFiles(deleteOrphans: Boolean) {
    val root = File(filesDir, MusicLibrary.DOWNLOADS_DIR)
    val files = withContext(Dispatchers.IO) { root.walkTopDown().filter { it.isFile }.toList() }
    if (files.isEmpty()) return

    val marked = db.tracks().downloadedPaths().associate { it.id to it.localFilePath }
    val unmarked = LinkedHashMap<UUID, MutableList<File>>()
    val partials = mutableListOf<Pair<UUID, File>>()
    for (file in files) {
        val trackId = trackIdOf(file) ?: continue
        when {
            file.name.endsWith(PART_SUFFIX) -> partials += trackId to file
            marked[trackId] == file.relativeTo(filesDir).invariantSeparatorsPath -> Unit
            else -> unmarked.getOrPut(trackId) { mutableListOf() } += file
        }
    }
    if (unmarked.isEmpty() && (!deleteOrphans || partials.isEmpty())) return

    val existing = unmarked.keys.toList().chunked(900).flatMap { db.tracks().existingIds(it) }.toHashSet()
    val orphans = mutableListOf<File>()
    var reclaimed = 0
    for ((trackId, candidates) in unmarked) {
        val current = marked[trackId]?.let { File(filesDir, it) }?.takeIf { it.exists() }
        when {
            trackId !in existing -> orphans += candidates
            // Already downloaded under another name, e.g. before a quality change; this one is a leftover.
            current != null -> orphans += candidates
            else -> {
                val file = candidates.first()
                // It may have been removed since the walk; marking it then would undo the removal.
                if (file.exists()) {
                    markDownloaded(trackId, file.relativeTo(filesDir).invariantSeparatorsPath, file.length())
                    reclaimed++
                }
                orphans += candidates.drop(1)
            }
        }
    }

    if (deleteOrphans) {
        val queued = db.downloadQueue().all().mapTo(HashSet()) { it.trackId }
        partials.filter { (trackId, _) -> trackId !in queued }.mapTo(orphans) { it.second }
        withContext(Dispatchers.IO) {
            orphans.forEach { it.delete() }
            // Album and artist directories left empty.
            root.walkBottomUp().filter { it != root && it.isDirectory && it.list().isNullOrEmpty() }.forEach { it.delete() }
        }
    }
    if (BuildConfig.DEBUG && (reclaimed > 0 || orphans.isNotEmpty())) {
        Log.d("MusicLibrary", "reconciled $reclaimed downloaded tracks, ${if (deleteOrphans) "deleted" else "kept"} ${orphans.size} orphans")
    }
}
