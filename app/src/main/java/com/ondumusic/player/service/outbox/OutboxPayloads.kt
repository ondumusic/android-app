package com.ondumusic.player.service.outbox

import com.ondumusic.player.api.ApiJson
import com.ondumusic.player.api.SerialInstant
import com.ondumusic.player.api.SerialUuid
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/** [raw] is what's stored in `pending_mutations.kind`, shared with the iOS client's names. */
enum class OutboxKind(val raw: String) {
    CreatePlaylist("create_playlist"),
    RenamePlaylist("rename_playlist"),
    MovePlaylist("move_playlist"),
    DeletePlaylist("delete_playlist"),
    AddItems("add_items"),
    MoveItems("move_items"),
    RemoveItems("remove_items"),
    RecordPlays("record_plays");

    companion object {
        fun from(raw: String): OutboxKind? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * One finished listen. [id] is ours and stable across retries, which is how the server
 * ignores a play it has already stored when the queue replays it.
 */
@Serializable
data class RecordPlayPayload(val id: SerialUuid, val trackId: SerialUuid, val playedAt: SerialInstant)

@Serializable
data class CreatePlaylistPayload(
    val tempPlaylistId: SerialUuid,
    val name: String,
    val isFolder: Boolean = false,
    val parentId: SerialUuid? = null,
)

@Serializable
data class RenamePlaylistPayload(val playlistId: SerialUuid, val name: String)

/** Reparents a playlist or folder. Null [parentId] means root. */
@Serializable
data class MovePlaylistPayload(val playlistId: SerialUuid, val parentId: SerialUuid? = null)

@Serializable
data class DeletePlaylistPayload(val playlistId: SerialUuid)

@Serializable
data class AddItemsPayload(val playlistId: SerialUuid, val items: List<Entry>) {
    @Serializable
    data class Entry(val tempItemId: SerialUuid, val trackId: SerialUuid, val position: String)
}

@Serializable
data class MoveItemsPayload(val playlistId: SerialUuid, val items: List<Entry>) {
    @Serializable
    data class Entry(val itemId: SerialUuid, val position: String)
}

@Serializable
data class RemoveItemsPayload(val playlistId: SerialUuid, val itemIds: List<SerialUuid>)

internal fun <T> KSerializer<T>.encode(payload: T): String = ApiJson.encodeToString(this, payload)

/** Null for a payload that won't decode; callers decide whether that's a discard or a skip. */
internal fun <T> KSerializer<T>.decodeOrNull(payload: String): T? =
    runCatching { ApiJson.decodeFromString(this, payload) }.getOrNull()
