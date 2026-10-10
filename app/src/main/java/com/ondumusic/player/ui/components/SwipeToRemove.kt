package com.ondumusic.player.ui.components

import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Swipe state for a row whose removal can be undone. The saveable default is restored by
 * key when a lazy list brings the row back, so an undone row would return still swiped away
 * and be removed all over again.
 */
@Composable
fun rememberSwipeToRemoveState(): SwipeToDismissBoxState {
    val threshold = SwipeToDismissBoxDefaults.positionalThreshold
    return remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, threshold) }
}
