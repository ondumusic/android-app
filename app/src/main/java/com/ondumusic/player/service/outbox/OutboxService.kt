package com.ondumusic.player.service.outbox

import android.util.Log
import com.ondumusic.player.BuildConfig
import com.ondumusic.player.R
import com.ondumusic.player.api.ApiClient
import com.ondumusic.player.api.ApiException
import com.ondumusic.player.api.PlayBody
import com.ondumusic.player.api.PlaylistDto
import com.ondumusic.player.api.PlaylistItemsException
import com.ondumusic.player.api.PositionedItem
import com.ondumusic.player.api.PositionedTrack
import com.ondumusic.player.data.MusicLibrary
import com.ondumusic.player.data.PendingMutationEntity
import com.ondumusic.player.model.Playlist
import com.ondumusic.player.model.PlaylistItem
import com.ondumusic.player.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.KSerializer
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Durable replay queue for playlist mutations and plays. Callers write to the local store
 * and enqueue the corresponding op here; this service drains the queue whenever a network
 * path is available.
 *
 * Temp UUIDs minted for create_playlist / add_items are swapped for server-assigned UUIDs
 * on successful replay, both in the local store and in downstream payloads still waiting
 * in the queue.
 *
 * Item-level ops (add/move/remove) coalesce at flush time: adjacent mutations targeting the
 * same playlist merge into one API call — across kinds too, since PATCH /items takes all
 * three arrays — capped at [MAX_OPERATIONS_PER_BATCH]. A move/remove referencing a temp id
 * whose add is in the same batch would fail (the server doesn't know the temp id yet), so
 * those break the batch and run in separate calls.
 *
 * One drain runs at a time. [flush] while a drain is running doesn't wait for it: the
 * running drain re-reads the queue between calls, and if it has already found the queue
 * empty it goes round once more, so nothing enqueued meanwhile is stranded.
 */
class OutboxService(
    private val musicLibrary: MusicLibrary,
    /** Null while signed out; the queue then waits for the next trigger. */
    private val api: () -> ApiClient?,
    /** Runs triggered drains and the retry timer; should live as long as the app. */
    private val scope: CoroutineScope,
    /** Called on a 401. Should ask for the password again — never sign out. */
    private val onUnauthorized: () -> Unit,
    private val retryDelay: Duration = 30.seconds,
) {
    val pendingCount: Flow<Int> = musicLibrary.db.pendingMutations().observeCount()

    private val _lastError = MutableStateFlow<UiText?>(null)
    /** Why the most recent change was dropped, for the UI to show once and then [clearLastError]. */
    val lastError: StateFlow<UiText?> = _lastError.asStateFlow()

    fun clearLastError() {
        _lastError.value = null
    }

    private val drainLock = Mutex()
    private val flushRequested = AtomicBoolean(false)
    /** Only touched while holding [drainLock]. */
    private var retryJob: Job? = null

    // The queue is ordered by createdAt, stored in milliseconds; two enqueues in the same
    // millisecond would otherwise have no defined order.
    private val lastCreatedAt = AtomicLong(0)

    // Enqueue

    suspend fun enqueueCreatePlaylist(tempId: UUID, name: String, parentId: UUID? = null) =
        enqueue(OutboxKind.CreatePlaylist, CreatePlaylistPayload.serializer(), CreatePlaylistPayload(tempId, name, false, parentId))

    suspend fun enqueueCreateFolder(tempId: UUID, name: String, parentId: UUID?) =
        enqueue(OutboxKind.CreatePlaylist, CreatePlaylistPayload.serializer(), CreatePlaylistPayload(tempId, name, true, parentId))

    suspend fun enqueueRenamePlaylist(id: UUID, name: String) =
        enqueue(OutboxKind.RenamePlaylist, RenamePlaylistPayload.serializer(), RenamePlaylistPayload(id, name))

    suspend fun enqueueMovePlaylist(id: UUID, parentId: UUID?) =
        enqueue(OutboxKind.MovePlaylist, MovePlaylistPayload.serializer(), MovePlaylistPayload(id, parentId))

    suspend fun enqueueDeletePlaylist(id: UUID) =
        enqueue(OutboxKind.DeletePlaylist, DeletePlaylistPayload.serializer(), DeletePlaylistPayload(id))

    suspend fun enqueueAddItems(playlistId: UUID, entries: List<AddItemsPayload.Entry>) {
        if (entries.isEmpty()) return
        enqueue(OutboxKind.AddItems, AddItemsPayload.serializer(), AddItemsPayload(playlistId, entries))
    }

    suspend fun enqueueMoveItems(playlistId: UUID, entries: List<MoveItemsPayload.Entry>) {
        if (entries.isEmpty()) return
        enqueue(OutboxKind.MoveItems, MoveItemsPayload.serializer(), MoveItemsPayload(playlistId, entries))
    }

    suspend fun enqueueRemoveItems(playlistId: UUID, itemIds: List<UUID>) {
        if (itemIds.isEmpty()) return
        enqueue(OutboxKind.RemoveItems, RemoveItemsPayload.serializer(), RemoveItemsPayload(playlistId, itemIds))
    }

    /**
     * One finished listen. The id is generated here and never changes, so a replay the
     * server has already stored is ignored rather than counted twice.
     */
    suspend fun enqueuePlay(trackId: UUID, playedAt: Instant) =
        enqueue(OutboxKind.RecordPlays, RecordPlayPayload.serializer(), RecordPlayPayload(UUID.randomUUID(), trackId, playedAt))

    private suspend fun <P> enqueue(kind: OutboxKind, serializer: KSerializer<P>, payload: P) {
        val now = System.currentTimeMillis()
        val createdAt = lastCreatedAt.updateAndGet { maxOf(now, it + 1) }
        musicLibrary.enqueuePendingMutation(
            PendingMutationEntity(kind = kind.raw, payload = serializer.encode(payload), createdAt = Instant.ofEpochMilli(createdAt))
        )
        drainSoon()
    }

    // Flush

    /** Starts a drain without waiting for it — for launch, foreground and connectivity triggers. */
    fun drainSoon() {
        scope.launch { flush() }
    }

    /** Drains the queue, returning once it's empty, paused for a retry, or another drain owns it. */
    suspend fun flush() {
        flushRequested.set(true)
        while (flushRequested.get()) {
            if (!drainLock.tryLock()) {
                if (BuildConfig.DEBUG) Log.d(TAG, "flush deferred to the drain in progress")
                return
            }
            val emptied = try {
                flushRequested.set(false)
                drain()
            } finally {
                drainLock.unlock()
            }
            // A paused drain has a retry scheduled; going round again now would only fail again.
            if (!emptied) return
        }
    }

    /** True when it stopped because the queue was empty. */
    private suspend fun drain(): Boolean {
        // A retry timer calling flush must not cancel itself.
        val self = currentCoroutineContext()[Job]
        retryJob?.takeIf { it !== self }?.cancel()
        retryJob = null

        val api = api() ?: return false

        if (BuildConfig.DEBUG) {
            val count = musicLibrary.pendingMutations().size
            if (count > 0) Log.d(TAG, "flushing $count mutation(s)")
        }

        while (true) {
            val pending = musicLibrary.pendingMutations()
            val head = pending.firstOrNull() ?: return true

            val plays = collectPlays(pending)
            val batch = if (plays == null) collectBatch(pending) else null
            val consumed: List<PendingMutationEntity>
            val outcome: Outcome
            when {
                plays != null -> {
                    consumed = plays.consumed
                    outcome = sendPlays(api, plays.payloads)
                }
                batch != null -> {
                    if (BuildConfig.DEBUG && batch.consumed.size > 1) {
                        Log.d(TAG, "batched ${batch.summary} — ${batch.consumed.size} mutations, ${batch.operationCount} ops")
                    }
                    consumed = batch.consumed
                    outcome = processBatch(api, batch)
                }
                else -> {
                    consumed = listOf(head)
                    outcome = process(api, head)
                }
            }

            if (!applyOutcome(outcome, head, consumed)) return false
        }
    }

    private suspend fun applyOutcome(outcome: Outcome, head: PendingMutationEntity, consumedOnSuccess: List<PendingMutationEntity>): Boolean =
        when (outcome) {
            Outcome.Success -> {
                if (BuildConfig.DEBUG) Log.d(TAG, "${head.kind} succeeded (${consumedOnSuccess.size} mutation(s))")
                musicLibrary.deletePendingMutations(consumedOnSuccess.map { it.localId })
                true
            }
            is Outcome.Discard -> {
                if (BuildConfig.DEBUG) Log.d(TAG, "${head.kind} discarded — ${outcome.message}")
                _lastError.value = outcome.message
                musicLibrary.deletePendingMutations(listOf(head.localId))
                true
            }
            is Outcome.Retry -> {
                val attempts = head.attemptCount + 1
                if (attempts >= MAX_RETRY_ATTEMPTS) {
                    // One bad mutation mustn't block the queue forever.
                    if (BuildConfig.DEBUG) Log.d(TAG, "${head.kind} giving up after $attempts attempts — ${outcome.reason}")
                    _lastError.value = UiText.Res(R.string.outbox_gave_up, listOf(outcome.reason))
                    musicLibrary.deletePendingMutations(listOf(head.localId))
                    true
                } else {
                    if (BuildConfig.DEBUG) Log.d(TAG, "${head.kind} retry (attempt $attempts) — ${outcome.reason}")
                    musicLibrary.updatePendingMutations(listOf(
                        head.copy(attemptCount = attempts, lastError = outcome.reason, lastAttemptAt = Instant.now())
                    ))
                    scheduleRetry()
                    false
                }
            }
            is Outcome.RetryNoPenalty -> {
                if (BuildConfig.DEBUG) Log.d(TAG, "${head.kind} paused (no-penalty) — ${outcome.reason}")
                musicLibrary.updatePendingMutations(listOf(head.copy(lastError = outcome.reason, lastAttemptAt = Instant.now())))
                scheduleRetry()
                false
            }
        }

    // Batching

    private class Batch(
        val playlistId: UUID,
        val consumed: List<PendingMutationEntity>,
        val addEntries: List<AddItemsPayload.Entry>,
        val moveEntries: List<MoveItemsPayload.Entry>,
        val removeIds: List<UUID>,
    ) {
        val operationCount get() = addEntries.size + moveEntries.size + removeIds.size
        val summary get() = listOfNotNull(
            "add=${addEntries.size}".takeIf { addEntries.isNotEmpty() },
            "move=${moveEntries.size}".takeIf { moveEntries.isNotEmpty() },
            "remove=${removeIds.size}".takeIf { removeIds.isNotEmpty() },
        ).joinToString(" ")
    }

    /**
     * Walks from the head collecting adjacent item-level mutations (add/move/remove) for the
     * same playlist into a single multi-op batch. Stops at: different playlist, op cap
     * reached, a move/remove that references a temp id whose add is in this same batch (the
     * server doesn't know that id yet), or a non-batchable kind.
     */
    private fun collectBatch(mutations: List<PendingMutationEntity>): Batch? {
        val headKind = mutations.firstOrNull()?.let { OutboxKind.from(it.kind) }
        if (headKind == null || !isBatchable(headKind)) return null

        val consumed = mutableListOf<PendingMutationEntity>()
        val addEntries = mutableListOf<AddItemsPayload.Entry>()
        val moveEntries = mutableListOf<MoveItemsPayload.Entry>()
        val removeIds = mutableListOf<UUID>()
        var playlistId: UUID? = null
        var opCount = 0
        val pendingAddItemIds = mutableSetOf<UUID>()

        for (mutation in mutations) {
            when (OutboxKind.from(mutation.kind)) {
                OutboxKind.AddItems -> {
                    val p = AddItemsPayload.serializer().decodeOrNull(mutation.payload) ?: break
                    if (playlistId != null && playlistId != p.playlistId) break
                    if (opCount + p.items.size > MAX_OPERATIONS_PER_BATCH) break
                    playlistId = p.playlistId
                    addEntries += p.items
                    p.items.mapTo(pendingAddItemIds) { it.tempItemId }
                    opCount += p.items.size
                }
                OutboxKind.MoveItems -> {
                    val p = MoveItemsPayload.serializer().decodeOrNull(mutation.payload) ?: break
                    if (playlistId != null && playlistId != p.playlistId) break
                    // A move of a temp item added in this batch would fail — the server doesn't
                    // know about it yet. Break so the add runs first, gets remapped, then the move follows.
                    if (p.items.any { it.itemId in pendingAddItemIds }) break
                    if (opCount + p.items.size > MAX_OPERATIONS_PER_BATCH) break
                    playlistId = p.playlistId
                    moveEntries += p.items
                    opCount += p.items.size
                }
                OutboxKind.RemoveItems -> {
                    val p = RemoveItemsPayload.serializer().decodeOrNull(mutation.payload) ?: break
                    if (playlistId != null && playlistId != p.playlistId) break
                    if (p.itemIds.any { it in pendingAddItemIds }) break
                    if (opCount + p.itemIds.size > MAX_OPERATIONS_PER_BATCH) break
                    playlistId = p.playlistId
                    removeIds += p.itemIds
                    opCount += p.itemIds.size
                }
                else -> break
            }
            consumed += mutation
        }

        if (consumed.isEmpty() || playlistId == null) return null
        return Batch(playlistId, consumed, addEntries, moveEntries, removeIds)
    }

    private fun isBatchable(kind: OutboxKind) =
        kind == OutboxKind.AddItems || kind == OutboxKind.MoveItems || kind == OutboxKind.RemoveItems

    private suspend fun processBatch(api: ApiClient, batch: Batch): Outcome {
        if (musicLibrary.playlist(batch.playlistId) == null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "batched ops skipped — local playlist ${batch.playlistId} no longer exists")
            return Outcome.Success
        }
        return executeItemOps(api, batch.playlistId, batch.addEntries, batch.moveEntries, batch.removeIds, depth = 0)
    }

    // Unified item-ops executor

    /**
     * Runs one PATCH /items with the given combined payload. Recursive when the server
     * returns `unknown_tracks` / `unknown_items` — those are filtered out and the call
     * retried, up to [MAX_FILTER_RETRIES]. All single-mutation and batched paths end up here.
     */
    private suspend fun executeItemOps(
        api: ApiClient,
        playlistId: UUID,
        addEntries: List<AddItemsPayload.Entry>,
        moveEntries: List<MoveItemsPayload.Entry>,
        removeIds: List<UUID>,
        depth: Int,
    ): Outcome {
        if (addEntries.isEmpty() && moveEntries.isEmpty() && removeIds.isEmpty()) return Outcome.Success
        if (depth >= MAX_FILTER_RETRIES) return Outcome.Discard(UiText.Res(R.string.outbox_filter_retries_exhausted, listOf(depth)))

        // Two drags of the same track batch into two moves of one item, which the server
        // rejects outright as duplicate_items. The later op is the newer intent, so it wins
        // and the earlier one goes.
        val moves = lastPerItem(moveEntries)
        val removes = deduplicated(removeIds)

        return try {
            val response = api.updatePlaylistItems(
                playlistId,
                add = addEntries.map { PositionedTrack(it.trackId, it.position) },
                move = moves.map { PositionedItem(it.itemId, it.position) },
                remove = removes,
            )
            if (addEntries.isNotEmpty()) {
                val addedByPosition = response.added.associateBy { it.position }
                for (entry in addEntries) {
                    val confirmed = addedByPosition[entry.position] ?: continue
                    musicLibrary.swapPlaylistItemIdEverywhere(
                        entry.tempItemId,
                        PlaylistItem(confirmed.id, confirmed.playlistId, confirmed.trackId, confirmed.position, confirmed.insertedAt),
                    )
                }
            }
            Outcome.Success
        } catch (e: PlaylistItemsException) {
            recover(api, e, playlistId, addEntries, moves, removes, depth)
        } catch (e: Exception) {
            when {
                // The 422 body didn't decode into a known error.
                e is ApiException.Http && e.code == 422 -> Outcome.Discard(UiText.Res(R.string.outbox_items_rejected))
                // Playlist or items gone on a pure-remove call; the local removal was still correct.
                e is ApiException.Http && e.code == 404 && addEntries.isEmpty() && moves.isEmpty() -> Outcome.Success
                else -> classify(e)
            }
        }
    }

    /** Filter-and-retry recovery for the errors whose user intent is clear; the rest discard. */
    private suspend fun recover(
        api: ApiClient,
        error: PlaylistItemsException,
        playlistId: UUID,
        addEntries: List<AddItemsPayload.Entry>,
        moveEntries: List<MoveItemsPayload.Entry>,
        removeIds: List<UUID>,
        depth: Int,
    ): Outcome = when (error) {
        is PlaylistItemsException.UnknownTracks -> {
            val bad = error.trackIds.toSet()
            val filtered = addEntries.filter { it.trackId !in bad }
            if (filtered.size == addEntries.size) {
                // The server said unknown but none of our entries matched — something is off.
                Outcome.Discard(UiText.Res(R.string.outbox_unknown_tracks))
            } else {
                if (BuildConfig.DEBUG) Log.d(TAG, "dropped ${addEntries.size - filtered.size} unknown track(s); retrying")
                executeItemOps(api, playlistId, filtered, moveEntries, removeIds, depth + 1)
            }
        }
        is PlaylistItemsException.UnknownItems -> {
            val bad = error.itemIds.toSet()
            val filteredMoves = moveEntries.filter { it.itemId !in bad }
            val filteredRemoves = removeIds.filter { it !in bad }
            if (filteredMoves.size == moveEntries.size && filteredRemoves.size == removeIds.size) {
                Outcome.Discard(UiText.Res(R.string.outbox_unknown_items))
            } else {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "dropped ${moveEntries.size - filteredMoves.size} move(s) and ${removeIds.size - filteredRemoves.size} remove(s) referencing unknown items; retrying")
                }
                executeItemOps(api, playlistId, addEntries, filteredMoves, filteredRemoves, depth + 1)
            }
        }
        // Every request is de-duplicated before sending, so the server seeing the same item
        // twice means our idea of an item's identity disagrees with the server's — retrying
        // the same ops would just fail again.
        is PlaylistItemsException.DuplicateItems ->
            Outcome.Discard(UiText.Res(R.string.outbox_duplicate_items, listOf(error.itemIds.size)))
        is PlaylistItemsException.MoveRemoveConflict -> {
            // The same item was both moved and removed. Remove wins — drop the move.
            val conflicts = error.itemIds.toSet()
            val filteredMoves = moveEntries.filter { it.itemId !in conflicts }
            if (filteredMoves.size == moveEntries.size) {
                // The server flagged ids that weren't in our moves — can't self-heal.
                Outcome.Discard(UiText.Res(R.string.outbox_move_remove_conflict))
            } else {
                if (BuildConfig.DEBUG) Log.d(TAG, "move_remove_conflict — dropping ${moveEntries.size - filteredMoves.size} conflicting move(s); retrying")
                executeItemOps(api, playlistId, addEntries, filteredMoves, removeIds, depth + 1)
            }
        }
        is PlaylistItemsException.InvalidPositions -> {
            // FractionalIndex is structurally correct, so the realistic trigger is a key that
            // grew past 100 chars from many insertions into the same gap — which needs a
            // playlist-wide rebalance to fix properly. Discard rather than mask the bug by
            // silently regenerating, and surface it so it can be investigated.
            if (BuildConfig.DEBUG) Log.d(TAG, "invalid_positions — server rejected ${error.positions.size} position(s): ${error.positions}")
            Outcome.Discard(UiText.Res(R.string.outbox_invalid_positions))
        }
        // The UI shouldn't offer adding tracks to a folder, but if a stale local copy
        // disagrees with the server, drop the op rather than retry forever.
        is PlaylistItemsException.PlaylistIsFolder -> Outcome.Discard(UiText.Res(R.string.outbox_playlist_is_folder))
        // MAX_OPERATIONS_PER_BATCH should match the server's cap; hitting this means the
        // server lowered it without the client being updated. Splitting the batch, or the
        // server reporting its limit, would need a server-side API change.
        is PlaylistItemsException.TooManyOperations -> Outcome.Discard(UiText.Res(R.string.outbox_too_many_operations))
    }

    // Single-mutation processing

    private sealed interface Outcome {
        data object Success : Outcome
        data class Retry(val reason: String) : Outcome
        /** Waiting on something outside the mutation (network, sign-in), so it doesn't count toward giving up. */
        data class RetryNoPenalty(val reason: String) : Outcome
        data class Discard(val message: UiText) : Outcome
    }

    private suspend fun process(api: ApiClient, mutation: PendingMutationEntity): Outcome {
        val kind = OutboxKind.from(mutation.kind)
            ?: return Outcome.Discard(UiText.Res(R.string.outbox_unknown_kind, listOf(mutation.kind)))
        val malformed = Outcome.Discard(UiText.Res(R.string.outbox_malformed_payload, listOf(kind.raw)))

        return when (kind) {
            OutboxKind.CreatePlaylist -> {
                val p = CreatePlaylistPayload.serializer().decodeOrNull(mutation.payload) ?: return malformed
                if (musicLibrary.playlist(p.tempPlaylistId) == null) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "create_playlist skipped — local playlist ${p.tempPlaylistId} no longer exists")
                    return Outcome.Success
                }
                try {
                    val server = api.createPlaylist(p.name, p.isFolder, p.parentId).toPlaylist()
                    if (BuildConfig.DEBUG) Log.d(TAG, "create_playlist OK — temp=${p.tempPlaylistId} → real=${server.id} (isFolder=${p.isFolder})")
                    musicLibrary.remapPlaylistId(p.tempPlaylistId, server)
                    musicLibrary.remapQueuedPlaylistId(p.tempPlaylistId, server.id)
                    Outcome.Success
                } catch (e: Exception) {
                    if (e is ApiException.Http && e.code == 422) {
                        val res = if (p.isFolder) R.string.outbox_create_folder_invalid_name else R.string.outbox_create_playlist_invalid_name
                        Outcome.Discard(UiText.Res(res, listOf(p.name)))
                    } else classify(e)
                }
            }

            OutboxKind.RenamePlaylist -> {
                val p = RenamePlaylistPayload.serializer().decodeOrNull(mutation.payload) ?: return malformed
                if (musicLibrary.playlist(p.playlistId) == null) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "rename_playlist skipped — local playlist ${p.playlistId} no longer exists")
                    return Outcome.Success
                }
                try {
                    api.renamePlaylist(p.playlistId, p.name)
                    Outcome.Success
                } catch (e: Exception) {
                    if (e is ApiException.Http && e.code == 422) Outcome.Discard(UiText.Res(R.string.outbox_rename_invalid_name))
                    else classify(e)
                }
            }

            OutboxKind.MovePlaylist -> {
                val p = MovePlaylistPayload.serializer().decodeOrNull(mutation.payload) ?: return malformed
                if (musicLibrary.playlist(p.playlistId) == null) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "move_playlist skipped — local playlist ${p.playlistId} no longer exists")
                    return Outcome.Success
                }
                try {
                    api.movePlaylist(p.playlistId, p.parentId)
                    Outcome.Success
                } catch (e: Exception) {
                    when {
                        e is ApiException.Http && e.code == 422 -> Outcome.Discard(UiText.Res(R.string.outbox_move_invalid_destination))
                        e is ApiException.Http && e.code == 404 -> Outcome.Success
                        else -> classify(e)
                    }
                }
            }

            OutboxKind.DeletePlaylist -> {
                val p = DeletePlaylistPayload.serializer().decodeOrNull(mutation.payload) ?: return malformed
                try {
                    api.deletePlaylist(p.playlistId)
                    Outcome.Success
                } catch (e: Exception) {
                    if (e is ApiException.Http && e.code == 404) Outcome.Success else classify(e)
                }
            }

            OutboxKind.AddItems -> {
                val p = AddItemsPayload.serializer().decodeOrNull(mutation.payload) ?: return malformed
                if (musicLibrary.playlist(p.playlistId) == null) return Outcome.Success
                executeItemOps(api, p.playlistId, p.items, emptyList(), emptyList(), depth = 0)
            }

            OutboxKind.MoveItems -> {
                val p = MoveItemsPayload.serializer().decodeOrNull(mutation.payload) ?: return malformed
                if (musicLibrary.playlist(p.playlistId) == null) return Outcome.Success
                executeItemOps(api, p.playlistId, emptyList(), p.items, emptyList(), depth = 0)
            }

            OutboxKind.RemoveItems -> {
                val p = RemoveItemsPayload.serializer().decodeOrNull(mutation.payload) ?: return malformed
                if (musicLibrary.playlist(p.playlistId) == null) return Outcome.Success
                executeItemOps(api, p.playlistId, emptyList(), emptyList(), p.itemIds, depth = 0)
            }

            OutboxKind.RecordPlays -> {
                val p = RecordPlayPayload.serializer().decodeOrNull(mutation.payload) ?: return malformed
                sendPlays(api, listOf(p))
            }
        }
    }

    private class Plays(val consumed: List<PendingMutationEntity>, val payloads: List<RecordPlayPayload>)

    /**
     * Every play waiting at the head of the queue, in one request. Plays carry no ordering
     * or conflict rules — unlike playlist ops, nothing here can reference something a later
     * call creates — so the only cap is request size.
     */
    private fun collectPlays(pending: List<PendingMutationEntity>): Plays? {
        if (pending.firstOrNull()?.kind != OutboxKind.RecordPlays.raw) return null
        val consumed = mutableListOf<PendingMutationEntity>()
        val payloads = mutableListOf<RecordPlayPayload>()
        for (mutation in pending) {
            if (mutation.kind != OutboxKind.RecordPlays.raw || payloads.size >= MAX_PLAYS_PER_REQUEST) break
            consumed += mutation
            // A payload that won't decode is dropped with its mutation: there's nothing to
            // recover and nothing downstream depends on it.
            RecordPlayPayload.serializer().decodeOrNull(mutation.payload)?.let { payloads += it }
        }
        return Plays(consumed, payloads)
    }

    private suspend fun sendPlays(api: ApiClient, payloads: List<RecordPlayPayload>): Outcome {
        if (payloads.isEmpty()) return Outcome.Success
        return try {
            api.recordPlays(payloads.map { PlayBody(it.id, it.trackId, it.playedAt) })
            Outcome.Success
        } catch (e: Exception) {
            classify(e)
        }
    }

    private fun classify(error: Exception): Outcome = when (error) {
        is CancellationException -> throw error
        is ApiException.Unauthorized -> {
            onUnauthorized()
            Outcome.RetryNoPenalty("Session expired — waiting for re-authentication")
        }
        is IOException -> Outcome.RetryNoPenalty(error.message ?: error.javaClass.simpleName)
        else -> Outcome.Retry(error.message ?: error.javaClass.simpleName)
    }

    private fun scheduleRetry() {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(retryDelay)
            flush()
        }
    }

    // Compaction, called by PlaylistController on local delete/remove

    /**
     * Compacts the queue after a playlist was just deleted locally. Returns true when the
     * deletion was fully absorbed by dropping a pending `create_playlist` — the caller should
     * then skip enqueueing `delete_playlist`.
     *
     * While a drain is in progress a mid-roundtrip mutation can't safely be stripped, so this
     * returns false and lets the normal delete enqueue happen.
     */
    suspend fun compactForDeletedPlaylist(playlistId: UUID): Boolean =
        whileNotDraining(fallback = false) { musicLibrary.compactPendingForDeletedPlaylist(playlistId) }

    /**
     * Compacts the queue after items were just removed locally. Returns the subset of
     * [itemIds] that still need a `remove_items` roundtrip — the rest were stripped from
     * pending `add_items` (the server never saw those temp ids).
     */
    suspend fun compactForRemovedItems(itemIds: List<UUID>): List<UUID> {
        if (itemIds.isEmpty()) return itemIds
        val absorbed = whileNotDraining(fallback = emptySet()) { musicLibrary.compactPendingForRemovedItems(itemIds.toSet()) }
        return itemIds.filter { it !in absorbed }
    }

    private suspend fun <T> whileNotDraining(fallback: T, block: suspend () -> T): T {
        if (!drainLock.tryLock()) return fallback
        val result = try { block() } finally { drainLock.unlock() }
        // A flush that arrived while this held the lock returned early, leaving the drain to us.
        if (flushRequested.get()) drainSoon()
        return result
    }

    companion object {
        private const val TAG = "OutboxService"
        /** After this many transient failures in a row, discard so one bad mutation can't block the queue. */
        private const val MAX_RETRY_ATTEMPTS = 20
        /** The server accepts up to 250 operations per PATCH /items request. */
        internal const val MAX_OPERATIONS_PER_BATCH = 250
        /** The spec sets no cap on a plays request; this just keeps a backlog from becoming one enormous POST. */
        internal const val MAX_PLAYS_PER_REQUEST = 250
        /** Bound on the filter-and-retry recursion for `unknown_tracks` / `unknown_items`. */
        private const val MAX_FILTER_RETRIES = 3

        /** The last move of each item, in the order the survivors were queued. */
        internal fun lastPerItem(entries: List<MoveItemsPayload.Entry>): List<MoveItemsPayload.Entry> {
            val seen = mutableSetOf<UUID>()
            return entries.asReversed().filter { seen.add(it.itemId) }.asReversed()
        }

        internal fun deduplicated(ids: List<UUID>): List<UUID> {
            val seen = mutableSetOf<UUID>()
            return ids.filter { seen.add(it) }
        }

        /** The server may omit insertedAt; the next sync brings the real value. */
        private fun PlaylistDto.toPlaylist() = Playlist(id, name, trackCount, insertedAt ?: Instant.now(), isFolder, parentId)
    }
}
