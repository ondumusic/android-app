package com.ondumusic.player.ui.navigation

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a launcher shortcut asked for, held until the signed-in app is on screen to do it. */
enum class LaunchAction(val intentAction: String) {
    ShuffleAll("com.ondumusic.player.action.SHUFFLE_ALL"),
    Search("com.ondumusic.player.action.SEARCH"),
    Downloads("com.ondumusic.player.action.DOWNLOADS");

    companion object {
        fun from(intent: Intent?): LaunchAction? = entries.firstOrNull { it.intentAction == intent?.action }
    }
}

class LaunchActions {
    private val pending = MutableStateFlow<LaunchAction?>(null)
    val current: StateFlow<LaunchAction?> = pending.asStateFlow()

    fun post(intent: Intent?) {
        LaunchAction.from(intent)?.let { pending.value = it }
    }

    /** Taken once, so a configuration change doesn't run it again. */
    fun consume(): LaunchAction? = pending.value.also { pending.value = null }
}
