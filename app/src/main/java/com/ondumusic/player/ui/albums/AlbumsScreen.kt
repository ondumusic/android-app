package com.ondumusic.player.ui.albums

import androidx.compose.runtime.Composable
import com.ondumusic.player.ui.components.ScreenScaffold

@Composable
fun AlbumsScreen(actions: @Composable () -> Unit) = ScreenScaffold("Albums", actions = { actions() }) {}
