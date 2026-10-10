package com.ondumusic.player.service.outbox

import android.util.Log
import androidx.room.withTransaction
import com.ondumusic.player.BuildConfig
import com.ondumusic.player.data.MusicLibrary
import com.ondumusic.player.data.PendingMutationEntity
import com.ondumusic.player.model.PlaylistItem
import java.util.UUID

// Queue rewrites that keep pending mutations consistent with the local store as temp ids
// resolve and local deletes make queued work moot.

private const val TAG = "OutboxStore"

/**
 * Replaces an optimistic item's temp id with the server's, in the store and in any queued
 * move/remove still targeting the temp id, so those later calls name an item the server knows.
 */
suspend fun MusicLibrary.swapPlaylistItemIdEverywhere(tempId: UUID, confirmed: PlaylistItem) = db.withTransaction {
    swapLocalPlaylistItemId(tempId, confirmed)
    remapQueuedPlaylistItemId(tempId, confirmed.id)
}

/** Rewrites queued move/remove payloads so any reference to [tempId] becomes [realId]. */
suspend fun MusicLibrary.remapQueuedPlaylistItemId(tempId: UUID, realId: UUID) = db.withTransaction {
    val rewritten = pendingMutations().mapNotNull { mutation ->
        val kind = OutboxKind.from(mutation.kind) ?: return@mapNotNull null
        rewrittenItemIdPayload(kind, mutation.payload, tempId, realId)?.let { mutation.copy(payload = it) }
    }
    if (rewritten.isNotEmpty()) {
        updatePendingMutations(rewritten)
        if (BuildConfig.DEBUG) Log.d(TAG, "remapQueuedPlaylistItemId: temp=$tempId → real=$realId, rewritten=${rewritten.size}")
    }
}

private fun rewrittenItemIdPayload(kind: OutboxKind, payload: String, tempId: UUID, realId: UUID): String? =
    when (kind) {
        OutboxKind.MoveItems -> {
            val p = MoveItemsPayload.serializer().decodeOrNull(payload)
            if (p == null || p.items.none { it.itemId == tempId }) null
            else MoveItemsPayload.serializer().encode(
                p.copy(items = p.items.map { if (it.itemId == tempId) it.copy(itemId = realId) else it })
            )
        }
        OutboxKind.RemoveItems -> {
            val p = RemoveItemsPayload.serializer().decodeOrNull(payload)
            if (p == null || tempId !in p.itemIds) null
            else RemoveItemsPayload.serializer().encode(
                p.copy(itemIds = p.itemIds.map { if (it == tempId) realId else it })
            )
        }
        else -> null
    }

/**
 * Rewrites queued payloads so any reference to [tempId] becomes [realId]. Called after a
 * successful `create_playlist` so dependent ops fire against the real playlist.
 */
suspend fun MusicLibrary.remapQueuedPlaylistId(tempId: UUID, realId: UUID) = db.withTransaction {
    val mutations = pendingMutations()
    val remap = mapOf(tempId to realId)
    val rewritten = mutations.mapNotNull { mutation ->
        val kind = OutboxKind.from(mutation.kind) ?: return@mapNotNull null
        rewrittenPayload(kind, mutation.payload, remap)?.let { mutation.copy(payload = it) }
    }
    if (rewritten.isNotEmpty()) updatePendingMutations(rewritten)
    if (BuildConfig.DEBUG) {
        Log.d(TAG, "remapQueuedPlaylistId: temp=$tempId → real=$realId, scanned=${mutations.size}, rewritten=${rewritten.size}")
    }
}

internal fun rewrittenPayload(kind: OutboxKind, payload: String, remap: Map<UUID, UUID>): String? =
    when (kind) {
        // Nothing in a play refers to a playlist, so nothing in it can need remapping.
        OutboxKind.RecordPlays -> null
        OutboxKind.CreatePlaylist -> {
            // The temp playlist id can't change here, but a child folder/playlist may carry
            // the temp parent id.
            val s = CreatePlaylistPayload.serializer()
            val p = s.decodeOrNull(payload)
            val mapped = p?.parentId?.let(remap::get)
            if (p == null || mapped == null) null else s.encode(p.copy(parentId = mapped))
        }
        OutboxKind.RenamePlaylist -> {
            val s = RenamePlaylistPayload.serializer()
            val p = s.decodeOrNull(payload)
            val mapped = p?.playlistId?.let(remap::get)
            if (p == null || mapped == null) null else s.encode(p.copy(playlistId = mapped))
        }
        OutboxKind.MovePlaylist -> {
            val s = MovePlaylistPayload.serializer()
            val p = s.decodeOrNull(payload)
            if (p == null) null
            else {
                val playlistId = remap[p.playlistId]
                val parentId = p.parentId?.let(remap::get)
                if (playlistId == null && parentId == null) null
                else s.encode(p.copy(playlistId = playlistId ?: p.playlistId, parentId = parentId ?: p.parentId))
            }
        }
        OutboxKind.DeletePlaylist -> {
            val s = DeletePlaylistPayload.serializer()
            val p = s.decodeOrNull(payload)
            val mapped = p?.playlistId?.let(remap::get)
            if (p == null || mapped == null) null else s.encode(p.copy(playlistId = mapped))
        }
        OutboxKind.AddItems -> {
            val s = AddItemsPayload.serializer()
            val p = s.decodeOrNull(payload)
            val mapped = p?.playlistId?.let(remap::get)
            if (p == null || mapped == null) null else s.encode(p.copy(playlistId = mapped))
        }
        OutboxKind.MoveItems -> {
            val s = MoveItemsPayload.serializer()
            val p = s.decodeOrNull(payload)
            val mapped = p?.playlistId?.let(remap::get)
            if (p == null || mapped == null) null else s.encode(p.copy(playlistId = mapped))
        }
        OutboxKind.RemoveItems -> {
            val s = RemoveItemsPayload.serializer()
            val p = s.decodeOrNull(payload)
            val mapped = p?.playlistId?.let(remap::get)
            if (p == null || mapped == null) null else s.encode(p.copy(playlistId = mapped))
        }
    }

/**
 * Drops every pending mutation whose target is [playlistId]. Returns true when a pending
 * `create_playlist` was among them — the playlist never reached the server, so the caller
 * should skip enqueueing `delete_playlist`. When false, item ops queued against a
 * server-known playlist are still dropped (the impending delete will cascade them server-side).
 */
suspend fun MusicLibrary.compactPendingForDeletedPlaylist(playlistId: UUID): Boolean = db.withTransaction {
    var foundPendingCreate = false
    val doomed = pendingMutations().filter { mutation ->
        val target = when (OutboxKind.from(mutation.kind)) {
            null, OutboxKind.RecordPlays -> null
            OutboxKind.CreatePlaylist -> CreatePlaylistPayload.serializer().decodeOrNull(mutation.payload)?.tempPlaylistId
                ?.also { if (it == playlistId) foundPendingCreate = true }
            OutboxKind.RenamePlaylist -> RenamePlaylistPayload.serializer().decodeOrNull(mutation.payload)?.playlistId
            OutboxKind.MovePlaylist -> MovePlaylistPayload.serializer().decodeOrNull(mutation.payload)?.playlistId
            OutboxKind.DeletePlaylist -> DeletePlaylistPayload.serializer().decodeOrNull(mutation.payload)?.playlistId
            OutboxKind.AddItems -> AddItemsPayload.serializer().decodeOrNull(mutation.payload)?.playlistId
            OutboxKind.MoveItems -> MoveItemsPayload.serializer().decodeOrNull(mutation.payload)?.playlistId
            OutboxKind.RemoveItems -> RemoveItemsPayload.serializer().decodeOrNull(mutation.payload)?.playlistId
        }
        target == playlistId
    }
    if (doomed.isNotEmpty()) {
        deletePendingMutations(doomed.map { it.localId })
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "compactPendingForDeletedPlaylist: $playlistId — dropped ${doomed.size} pending mutation(s); pendingCreate=$foundPendingCreate")
        }
    }
    foundPendingCreate
}

/**
 * Strips each id in [itemIds] from any pending `add_items` and `move_items` payloads.
 * Mutations emptied by the strip are deleted. Returns the subset of ids found in a pending
 * `add_items` — the server doesn't know those items yet, so the caller should skip
 * enqueueing `remove_items` for them.
 */
suspend fun MusicLibrary.compactPendingForRemovedItems(itemIds: Set<UUID>): Set<UUID> {
    if (itemIds.isEmpty()) return emptySet()
    return db.withTransaction {
        val foundInPendingAdds = mutableSetOf<UUID>()
        val emptied = mutableListOf<UUID>()
        val rewritten = mutableListOf<PendingMutationEntity>()
        var stripped = 0

        for (mutation in pendingMutations()) {
            when (OutboxKind.from(mutation.kind)) {
                OutboxKind.AddItems -> {
                    val s = AddItemsPayload.serializer()
                    val p = s.decodeOrNull(mutation.payload) ?: continue
                    val kept = p.items.filter { it.tempItemId !in itemIds }
                    if (kept.size == p.items.size) continue
                    p.items.filter { it.tempItemId in itemIds }.mapTo(foundInPendingAdds) { it.tempItemId }
                    stripped += p.items.size - kept.size
                    if (kept.isEmpty()) emptied += mutation.localId
                    else rewritten += mutation.copy(payload = s.encode(p.copy(items = kept)))
                }
                OutboxKind.MoveItems -> {
                    val s = MoveItemsPayload.serializer()
                    val p = s.decodeOrNull(mutation.payload) ?: continue
                    val kept = p.items.filter { it.itemId !in itemIds }
                    if (kept.size == p.items.size) continue
                    stripped += p.items.size - kept.size
                    if (kept.isEmpty()) emptied += mutation.localId
                    else rewritten += mutation.copy(payload = s.encode(p.copy(items = kept)))
                }
                else -> continue
            }
        }

        if (rewritten.isNotEmpty()) updatePendingMutations(rewritten)
        if (emptied.isNotEmpty()) deletePendingMutations(emptied)
        if (BuildConfig.DEBUG && stripped > 0) {
            Log.d(TAG, "compactPendingForRemovedItems: stripped $stripped entr(ies); dropped ${emptied.size} empty mutation(s); absorbed ${foundInPendingAdds.size} temp id(s)")
        }
        foundInPendingAdds
    }
}
