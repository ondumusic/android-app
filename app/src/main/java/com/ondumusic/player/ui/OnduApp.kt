package com.ondumusic.player.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ondumusic.player.AppContainer
import com.ondumusic.player.Session
import com.ondumusic.player.ui.auth.LoginScreen
import com.ondumusic.player.ui.theme.OnduTheme

private enum class Stage { Launching, SignedOut, Syncing, Ready }

@Composable
fun OnduApp(container: AppContainer) {
    val accent by container.settings.accentColor.flow.collectAsStateWithLifecycle()
    val customAccent by container.settings.customAccentColor.flow.collectAsStateWithLifecycle()
    val restoring by container.auth.isRestoringSession.collectAsStateWithLifecycle()
    val session by container.session.collectAsStateWithLifecycle()

    OnduTheme(accent = accent, customAccent = Color(customAccent)) {
        val current = session
        val synced = current?.sync?.hasCompletedInitialSync?.collectAsStateWithLifecycle()?.value ?: false
        val stage = when {
            restoring -> Stage.Launching
            current == null -> Stage.SignedOut
            !synced -> Stage.Syncing
            else -> Stage.Ready
        }
        Crossfade(stage, label = "stage") { s ->
            when (s) {
                // Blank rather than a spinner: restoring is quick, and the splash screen covers most of it.
                Stage.Launching -> Surface(Modifier.fillMaxSize()) {}
                Stage.SignedOut -> LoginScreen(container.auth)
                Stage.Syncing -> current?.let { SyncProgressScreen(it.sync, container.auth) }
                Stage.Ready -> current?.let { LibraryPlaceholder(container, it) }
            }
        }
    }
}

@Composable
private fun LibraryPlaceholder(container: AppContainer, session: Session) {
    val artists by container.library.observeArtists().collectAsStateWithLifecycle(emptyList())
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(artists, key = { it.id }) { ListItem(headlineContent = { Text(it.name) }) }
            }
        }
    }
}
