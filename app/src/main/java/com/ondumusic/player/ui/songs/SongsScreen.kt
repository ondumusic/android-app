package com.ondumusic.player.ui.songs

import androidx.compose.runtime.Composable
import com.ondumusic.player.ui.components.ScreenScaffold

@Composable
fun SongsScreen(actions: @Composable () -> Unit) = ScreenScaffold("Songs", actions = { actions() }) {}
