package com.ondumusic.player.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ondumusic.player.ui.LocalContainer
import com.ondumusic.player.ui.LocalSession
import kotlinx.coroutines.launch

/** Pull down to sync, on any library list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryRefresh(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val container = LocalContainer.current
    val sync = LocalSession.current.sync
    val refreshing by sync.isShowingActivity.collectAsStateWithLifecycle()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { container.scope.launch { sync.sync() } },
        modifier = modifier,
        content = content,
    )
}
