package com.ondumusic.player.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.ondumusic.player.AppContainer
import com.ondumusic.player.Session

/** The app's services, available to every screen the way iOS views read them from the environment. */
val LocalContainer = staticCompositionLocalOf<AppContainer> { error("No AppContainer provided") }

/** The signed-in session. Only provided once one exists, which is everywhere past sign-in. */
val LocalSession = staticCompositionLocalOf<Session> { error("No Session provided") }
