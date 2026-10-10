package com.ondumusic.player.service

import com.ondumusic.player.data.MusicLibrary
import com.ondumusic.player.model.Playlist
import com.ondumusic.player.model.PlaylistItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Playlist removals the user can still undo: a song taken out of a playlist, a playlist or
 * folder deleted. Each is held back — hidden on screen, but neither written locally nor
 * queued for the server — until [commit], and then goes through [PlaylistController] like
 * any other edit. [undo] just forgets it.
 *
 * Held back rather than done and reversed: a deleted playlist can't be brought back on the
 * server — creating it again gives a new playlist with new item ids — and a removed song
 * put back is a new item too. Holding the edit keeps the outbox free of anything the user
 * took back.
 *
 * Main-thread only.
 */
class PendingPlaylistEdits(
    private val playlists: PlaylistController,
    private val library: MusicLibrary,
    /** Should live as long as the app: an edit is carried out after the screen that made it has gone. */
    private val scope: CoroutineScope,
) {
    sealed interface Edit {
        data class RemoveItem(val item: PlaylistItem) : Edit
        data class DeletePlaylist(val playlistId: UUID) : Edit
    }

    /** One held-back edit, told apart by identity. */
    class Token internal constructor(edit: Edit) {
        var edit: Edit = edit
            internal set
    }

    private val _pending = MutableStateFlow<List<Token>>(emptyList())

    private val _hidden = MutableStateFlow(Hidden(emptyList()))
    /** What screens leave out while edits are held. Updated with the edits, so a removed row never flashes back. */
    val hidden: StateFlow<Hidden> = _hidden.asStateFlow()

    private fun publish(tokens: List<Token>) {
        _pending.value = tokens
        _hidden.value = Hidden(tokens.map { it.edit })
    }

    init {
        // A playlist created offline gets its server id while its deletion may be held; the
        // deletion has to follow it, or it would delete nothing and the playlist would stay.
        scope.launch { library.playlistIdRemapped.collect { (temp, real) -> remap(temp, real) } }
    }

    fun removeItem(item: PlaylistItem): Token = hold(Edit.RemoveItem(item))

    fun deletePlaylist(playlist: Playlist): Token = hold(Edit.DeletePlaylist(playlist.id))

    fun isPending(token: Token): Flow<Boolean> = _pending.map { token in it }

    /** Drops the edit. False when it has already been carried out. */
    fun undo(token: Token): Boolean = release(token)

    /** Carries out the edit, unless it was undone or already carried out. The work runs on the app's scope. */
    fun commit(token: Token): Job? = if (release(token)) carryOut(token.edit) else null

    /** Carries out everything held, for when the app leaves the foreground and may not come back. */
    fun commitAll(): List<Job> {
        val all = _pending.value
        publish(emptyList())
        return all.map { carryOut(it.edit) }
    }

    /** Forgets everything held without carrying it out; signing out discards the library it applied to. */
    fun discardAll() = publish(emptyList())

    private fun hold(edit: Edit): Token = Token(edit).also { publish(_pending.value + it) }

    private fun release(token: Token): Boolean {
        if (token !in _pending.value) return false
        publish(_pending.value - token)
        return true
    }

    private fun carryOut(edit: Edit): Job = scope.launch {
        when (edit) {
            is Edit.DeletePlaylist -> playlists.deletePlaylist(edit.playlistId)
            is Edit.RemoveItem -> {
                // The server may have confirmed the item meanwhile, swapping its temporary
                // id for its own; position and track still identify it. Gone altogether —
                // removed on another device — leaves nothing to do.
                val current = library.playlistItems(edit.item.playlistId).firstOrNull { it.sameEntryAs(edit.item) }
                if (current != null) playlists.removeItem(current.id, current.playlistId)
            }
        }
    }

    private fun remap(tempId: UUID, realId: UUID) {
        var changed = false
        for (token in _pending.value) {
            val remapped = when (val edit = token.edit) {
                is Edit.DeletePlaylist -> if (edit.playlistId == tempId) edit.copy(playlistId = realId) else null
                is Edit.RemoveItem -> if (edit.item.playlistId == tempId) edit.copy(item = edit.item.copy(playlistId = realId)) else null
            }
            if (remapped != null) {
                token.edit = remapped
                changed = true
            }
        }
        if (changed) publish(_pending.value)
    }

    /** What to leave out of the playlists and items on screen. */
    class Hidden internal constructor(edits: List<Edit>) {
        private val items = edits.filterIsInstance<Edit.RemoveItem>().map { it.item }
        val playlistIds: Set<UUID> = edits.filterIsInstance<Edit.DeletePlaylist>().mapTo(mutableSetOf()) { it.playlistId }

        fun hides(item: PlaylistItem): Boolean = items.any { it.sameEntryAs(item) }
    }
}

private fun PlaylistItem.sameEntryAs(other: PlaylistItem) =
    id == other.id || (playlistId == other.playlistId && trackId == other.trackId && position == other.position)
