package com.ondumusic.player.ui.navigation

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter

/** A tab tapped while already showing its root. */
val LocalTabReselects = staticCompositionLocalOf<SharedFlow<Tab>> { MutableSharedFlow() }

/** Scrolls a tab's root list back to the top when its tab is tapped again. */
@Composable
fun ScrollToTopOnReselect(tab: Tab, state: ScrollableState) {
    val reselects = LocalTabReselects.current
    LaunchedEffect(reselects, state) {
        reselects.filter { it == tab }.collect {
            when (state) {
                is LazyListState -> state.animateScrollToItem(0)
                is LazyGridState -> state.animateScrollToItem(0)
            }
        }
    }
}
