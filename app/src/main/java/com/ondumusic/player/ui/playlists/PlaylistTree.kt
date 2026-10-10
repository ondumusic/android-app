package com.ondumusic.player.ui.playlists

import com.ondumusic.player.model.Playlist
import com.ondumusic.player.model.PlaylistItem
import com.ondumusic.player.model.Track
import com.ondumusic.player.service.FractionalIndex
import com.ondumusic.player.util.NaturalOrder
import java.util.UUID

/** Folders first, then by name — the order iOS lists a folder's contents in. */
private val entryOrder: Comparator<Playlist> =
    compareByDescending<Playlist> { it.isFolder }.thenBy(NaturalOrder) { it.name }

/** What a folder (or the root, for a null [parentId]) shows. */
fun childrenOf(all: List<Playlist>, parentId: UUID?): List<Playlist> =
    all.filter { it.parentId == parentId }.sortedWith(entryOrder)

/** How many entries sit directly inside each folder. */
fun childCounts(all: List<Playlist>): Map<UUID, Int> =
    all.mapNotNull { it.parentId }.groupingBy { it }.eachCount()

/** [id] and everything nested under it. */
fun descendantIds(all: List<Playlist>, id: UUID): Set<UUID> {
    val byParent = all.groupBy { it.parentId }
    val found = mutableSetOf(id)
    val pending = ArrayDeque(listOf(id))
    while (pending.isNotEmpty()) {
        byParent[pending.removeFirst()].orEmpty().forEach { if (found.add(it.id)) pending += it.id }
    }
    return found
}

/** [all] without the playlists in [deleted] and everything inside them, which goes with a folder. */
fun withoutDeleted(all: List<Playlist>, deleted: Set<UUID>): List<Playlist> {
    if (deleted.isEmpty()) return all
    val gone = deleted.flatMapTo(mutableSetOf()) { descendantIds(all, it) }
    return all.filterNot { it.id in gone }
}

data class FolderDestination(val folder: Playlist, val depth: Int)

/**
 * Every folder [moving] could go into, as an indented tree. A folder can't go into itself
 * or anything inside it — that would make a cycle.
 */
fun moveDestinations(all: List<Playlist>, moving: Playlist): List<FolderDestination> {
    val excluded = if (moving.isFolder) descendantIds(all, moving.id) else setOf(moving.id)
    val result = mutableListOf<FolderDestination>()
    fun walk(parentId: UUID?, depth: Int) {
        childrenOf(all, parentId).filter { it.isFolder && it.id !in excluded }.forEach {
            result += FolderDestination(it, depth)
            walk(it.id, depth + 1)
        }
    }
    walk(null, 0)
    return result
}

/** A playlist tracks can be added to, with the folders it sits in from the outermost. */
data class AddTarget(val playlist: Playlist, val folderPath: List<String>)

/** Every playlist, grouped by folder path so siblings cluster, then by name. */
fun addTargets(all: List<Playlist>): List<AddTarget> {
    val folders = all.filter { it.isFolder }.associateBy { it.id }
    fun pathOf(parentId: UUID?): List<String> {
        val names = mutableListOf<String>()
        val seen = mutableSetOf<UUID>()
        var cursor = parentId
        while (cursor != null && seen.add(cursor)) {
            val folder = folders[cursor] ?: break
            names += folder.name
            cursor = folder.parentId
        }
        return names.asReversed()
    }
    return all.filter { !it.isFolder }
        .map { AddTarget(it, pathOf(it.parentId)) }
        .sortedWith(compareBy<AddTarget, String>(NaturalOrder) { it.folderPath.joinToString(FOLDER_PATH_SEPARATOR) }.thenBy(NaturalOrder) { it.playlist.name })
}

const val FOLDER_PATH_SEPARATOR = " › "

/** One row of a playlist: the item gives it an identity, since a track can appear more than once. */
data class PlaylistEntry(val item: PlaylistItem, val track: Track)

/** Items in position order with their tracks. Items whose track isn't in the library are left out. */
fun playlistEntries(items: List<PlaylistItem>, tracks: List<Track>): List<PlaylistEntry> {
    val byId = tracks.associateBy { it.id }
    return items.mapNotNull { item -> byId[item.trackId]?.let { PlaylistEntry(item, it) } }
}

/**
 * The new position for the item at [from] once it has moved to [to], between what will be
 * its neighbours. Null when they can't be put in order — a malformed or duplicate position
 * from the server — so the caller can resync instead of writing a move that would be rejected.
 */
fun positionForMove(positions: List<String>, from: Int, to: Int): String? {
    val rest = positions.toMutableList().apply { removeAt(from) }
    return try {
        FractionalIndex.between(rest.getOrNull(to - 1), rest.getOrNull(to))
    } catch (_: FractionalIndex.InvalidKeyException) {
        null
    }
}
