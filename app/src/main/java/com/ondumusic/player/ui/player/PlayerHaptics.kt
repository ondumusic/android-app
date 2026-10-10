package com.ondumusic.player.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ondumusic.player.ui.LocalContainer

/** Haptic feedback for player controls, silent when the setting is off. */
@Composable
internal fun rememberPlayerHaptics(): (HapticFeedbackType) -> Unit {
    val enabled by LocalContainer.current.settings.hapticsEnabled.flow.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    return remember(haptics) { { type -> if (enabled) haptics.performHapticFeedback(type) } }
}
