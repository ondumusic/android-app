package com.ondumusic.player.ui.search

import androidx.compose.runtime.Composable
import com.ondumusic.player.ui.components.ScreenScaffold

@Composable
fun SearchScreen(actions: @Composable () -> Unit) = ScreenScaffold("Search", actions = { actions() }) {}
