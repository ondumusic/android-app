package com.ondumusic.player.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ondumusic.player.AppContainer
import com.ondumusic.player.ui.auth.LoginScreen
import com.ondumusic.player.ui.banners.WithAppBanners
import com.ondumusic.player.ui.downloads.ProvideDownloadUi
import com.ondumusic.player.ui.theme.OnduTheme

private enum class Stage { Launching, SignedOut, Syncing, Ready }

@Composable
fun OnduApp(container: AppContainer) {
    val accent by container.settings.accentColor.flow.collectAsStateWithLifecycle()
    val restoring by container.auth.isRestoringSession.collectAsStateWithLifecycle()
    val session by container.session.collectAsStateWithLifecycle()

    OnduTheme(accent = accent) {
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
                Stage.Ready -> current?.let { session ->
                    CompositionLocalProvider(LocalContainer provides container, LocalSession provides session) {
                        WithAppBanners { ProvideDownloadUi { MainScreen() } }
                    }
                }
            }
        }
    }
}
