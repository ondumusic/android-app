package com.ondumusic.player.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.composed

fun Modifier.clickableRow(onClick: () -> Unit): Modifier = clickable(onClick = onClick)

/** Tap to open, long-press for the actions, with the haptic tick a long press is expected to give. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.rowGestures(enabled: Boolean = true, onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier = composed {
    val haptics = LocalHapticFeedback.current
    combinedClickable(
        enabled = enabled,
        onClick = onClick,
        onLongClick = onLongClick?.let { { haptics.performHapticFeedback(HapticFeedbackType.LongPress); it() } },
    )
}
