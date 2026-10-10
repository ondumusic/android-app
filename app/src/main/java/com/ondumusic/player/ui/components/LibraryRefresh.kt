package com.ondumusic.player.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ondumusic.player.ui.LocalSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Pull down to sync, on any library list. The indicator answers the pull only: a sync that
 * starts on its own (at launch, on returning to the app) runs without dropping a spinner
 * over every list, as on iOS.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryRefresh(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val sync = LocalSession.current.sync
    val scope = rememberCoroutineScope()
    var pulled by remember { mutableStateOf(false) }
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
        content = content,
    )
}
