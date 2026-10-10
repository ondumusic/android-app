package com.ondumusic.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.ondumusic.player.ui.OnduApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val container = (application as OnduApplication).container
        // Held until the stored session is read back, so the login screen never flashes.
        installSplashScreen().setKeepOnScreenCondition { container.auth.isRestoringSession.value }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { OnduApp(container) }
    }
}
