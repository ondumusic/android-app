package com.ondumusic.player.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ondumusic.player.MainActivity
import com.ondumusic.player.OnduApplication

/**
 * Keeps playback alive in the background and gives it the system's media controls. The
 * player itself belongs to the app container — the service only hosts its session, so the
 * UI and the notification always drive the same player.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as OnduApplication).container
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, container.sessionPlayer)
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away while paused ends it; while playing, the music keeps going. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (session?.player?.playWhenReady != true) stopSelf()
    }

    override fun onDestroy() {
        // The player outlives the service; only the session goes.
        session?.release()
        session = null
        super.onDestroy()
    }
}
