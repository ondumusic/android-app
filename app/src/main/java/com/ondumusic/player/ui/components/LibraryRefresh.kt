package com.ondumusic.player.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.ondumusic.player.ui.LocalSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Pull down to sync, on any library list. The indicator answers the pull only: a sync that
 * starts on its own (at launch, on returning to the app) runs without dropping a spinner
 * over every list, as on iOS.
 *
 * The list moves down with the pull and the spinner sits in the gap it leaves, rather than
 * floating over the rows, and the list stays down until the sync is done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryRefresh(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val sync = LocalSession.current.sync
    val scope = rememberCoroutineScope()
    var pulled by remember { mutableStateOf(false) }
    val state = rememberPullToRefreshState()
    val threshold = PullToRefreshDefaults.PositionalThreshold
    PullToRefreshBox(
        isRefreshing = pulled,
        onRefresh = {
            pulled = true
            scope.launch {
                try {
                    // Returns at once if a sync is already running; then this waits on that one.
                    sync.sync()
                    // Held for the sync's own minimum-visible time, so a quick delta still
                    // registers as having happened.
                    sync.isShowingActivity.first { !it }
                } finally {
                    pulled = false
                }
            }
        },
        modifier = modifier,
        state = state,
        indicator = {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(threshold)
                    .graphicsLayer { alpha = state.distanceFraction.coerceIn(0f, 1f) },
                contentAlignment = Alignment.Center,
            ) {
                if (pulled) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                else CircularProgressIndicator(
                    progress = { state.distanceFraction.coerceIn(0f, 1f) },
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.5.dp,
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = state.distanceFraction * threshold.toPx() }, content = content)
    }
}
