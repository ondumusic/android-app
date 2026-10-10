package com.ondumusic.player

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.ondumusic.player.player.PlaybackService
import com.ondumusic.player.ui.OnduApp

class MainActivity : ComponentActivity() {
    private var controller: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val container = (application as OnduApplication).container
        // Held until the stored session is read back, so the login screen never flashes.
        installSplashScreen().setKeepOnScreenCondition { container.auth.isRestoringSession.value }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { OnduApp(container) }
    }

    /**
     * The UI drives the player directly, but a connected controller is what keeps the
     * playback service running, and lets Media3 move it to the foreground when music starts.
     */
    override fun onStart() {
        super.onStart()
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
    }

    override fun onStop() {
        controller?.let(MediaController::releaseFuture)
        controller = null
        super.onStop()
    }
}
