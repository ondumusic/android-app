package com.ondumusic.player.player

import android.content.SharedPreferences
import androidx.core.content.edit
import com.ondumusic.player.api.ApiJson
import com.ondumusic.player.api.SerialUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/**
 * The queue as it stood — changes only when the user changes it, so it's written on
 * mutation. Tracks are stored as ids and re-resolved against the library on restore, so a
 * queue picks up whatever metadata and download state sync has landed since.
 */
@Serializable
data class PlaybackState(
    val trackIds: List<SerialUuid>,
    val currentIndex: Int,
    val shuffledIndices: List<Int>? = null,
    val isUserModified: Boolean = false,
)

/**
 * Where playback had got to. Carries the track id so a stale position — written moments
 * before an unclean kill, against a queue that has since moved on — can be recognised.
 */
data class PlaybackPosition(val trackId: UUID, val elapsed: Double)

/**
 * Splits playback state by how often it changes: the queue lands in a JSON file on
 * mutation, the position in preferences every few seconds. Rewriting the whole queue that
 * often would mean tens of KB of file I/O per minute for a large queue, to record two numbers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackStateStore(
    private val file: File,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
) {
    /** One at a time, so writes land in the order they were made. */
    private val writer = Dispatchers.IO.limitedParallelism(1)
    private var pendingWrite: Job? = null

    fun load(): PlaybackState? =
        runCatching { ApiJson.decodeFromString(PlaybackState.serializer(), file.readText()) }.getOrNull()

    /**
     * Encodes on the caller, writes off it: this is called from interactive gestures like
     * reordering the queue, where file I/O on the main thread is felt.
     */
    fun save(state: PlaybackState) {
        val json = ApiJson.encodeToString(PlaybackState.serializer(), state)
        pendingWrite = scope.launch(writer) {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json)
            temp.renameTo(file)
        }
    }

    /** Waits for queued writes — for tests, and anywhere that needs the file before reading it back. */
    suspend fun flush() {
        pendingWrite?.join()
    }

    fun loadPosition(): PlaybackPosition? {
        val id = prefs.getString(KEY_TRACK, null)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        return PlaybackPosition(id, prefs.getFloat(KEY_ELAPSED, 0f).toDouble())
    }

    fun savePosition(position: PlaybackPosition) = prefs.edit {
        putString(KEY_TRACK, position.trackId.toString())
        putFloat(KEY_ELAPSED, position.elapsed.toFloat())
    }

    fun clear() {
        pendingWrite?.cancel()
        file.delete()
        prefs.edit {
            remove(KEY_TRACK)
            remove(KEY_ELAPSED)
        }
    }

    private companion object {
        const val KEY_TRACK = "playback.positionTrackId"
        const val KEY_ELAPSED = "playback.positionElapsed"
    }
}
