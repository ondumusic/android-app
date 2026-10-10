package com.ondumusic.player.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ondumusic.player.R
import com.ondumusic.player.player.PlaybackError

@Composable
fun PlaybackError.text(): String = when (this) {
    PlaybackError.Unauthorized -> stringResource(R.string.playback_error_unauthorized)
    PlaybackError.Unreachable -> stringResource(R.string.playback_error_unreachable)
    PlaybackError.Unplayable -> stringResource(R.string.playback_error_unplayable)
    is PlaybackError.Other -> stringResource(R.string.playback_error_other, message)
}
