package com.ondumusic.player.player

import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.ondumusic.player.model.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the media session — notification, lock screen, Bluetooth, headset buttons — sees.
 * The real queue, shuffle and crossfade live in [PlayerService]; this presents its state in
 * Media3's terms and routes commands back to it, so the system controls the same player
 * the app does.
 */
@OptIn(UnstableApi::class)
class SessionPlayer(
    private val player: PlayerService,
    private val engine: PlaybackEngine,
    scope: CoroutineScope,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    init {
        scope.launch {
            // Progress isn't part of this: the position supplier reads it live.
            player.state.collect { invalidateState() }
        }
    }

    override fun getState(): State {
        val s = player.state.value
        val item = s.currentItem
        val commands = Player.Commands.Builder()
            .addAll(
                COMMAND_PLAY_PAUSE, COMMAND_STOP, COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_METADATA,
                COMMAND_SET_SHUFFLE_MODE, COMMAND_SET_REPEAT_MODE,
            )
            .apply {
                if (item != null) addAll(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, COMMAND_SEEK_TO_PREVIOUS)
                if (s.canSkip) add(COMMAND_SEEK_TO_NEXT)
            }
            .build()

        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(s.isPlaying, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setShuffleModeEnabled(s.isShuffled)
            .setRepeatMode(
                when (s.repeatMode) {
                    RepeatMode.Off -> REPEAT_MODE_OFF
                    RepeatMode.One -> REPEAT_MODE_ONE
                    RepeatMode.All -> REPEAT_MODE_ALL
                }
            )
        if (item == null) return builder.setPlaybackState(STATE_IDLE).build()

        val track = item.track
        val metadata = MediaMetadata.Builder()
            .setTitle(track.name)
            .setArtist(track.artist.name)
            .setAlbumTitle(track.album?.name)
            .setAlbumArtist(track.albumArtistName)
            .setTrackNumber(track.number)
            .setDiscNumber(track.disc)
            .setArtworkUri(item.artworkUrl?.toUri())
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build()
        val mediaItem = MediaItem.Builder().setMediaId(track.id.toString()).setMediaMetadata(metadata).build()
        val data = MediaItemData.Builder(item.id)
            .setMediaItem(mediaItem)
            .setDurationUs((s.duration * C.MICROS_PER_SECOND).toLong())
            .build()

        return builder
            .setPlaylist(listOf(data))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(
                when {
                    s.playbackError != null -> STATE_IDLE
                    s.isBuffering -> STATE_BUFFERING
                    else -> STATE_READY
                }
            )
            .setContentPositionMs { (engine.position * 1000).toLong() }
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) player.resumePlayback() else player.pausePlayback()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> player.skip()
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> player.back()
            else -> player.seek(positionMs / 1000.0)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        if (shuffleModeEnabled != player.state.value.isShuffled) player.toggleShuffle()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        player.setRepeatMode(
            when (repeatMode) {
                REPEAT_MODE_ONE -> RepeatMode.One
                REPEAT_MODE_ALL -> RepeatMode.All
                else -> RepeatMode.Off
            }
        )
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        player.pausePlayback()
        return Futures.immediateVoidFuture()
    }
}
