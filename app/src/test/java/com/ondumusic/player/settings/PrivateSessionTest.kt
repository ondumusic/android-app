package com.ondumusic.player.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ondumusic.player.settings.SettingsStore.Companion.PRIVATE_SESSION_IDLE_WINDOW_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrivateSessionTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("private-session-test", Context.MODE_PRIVATE)
    private var now = 1_700_000_000_000L

    @Before fun reset() { prefs.edit().clear().commit() }

    private fun store() = SettingsStore(prefs) { now }

    @Test
    fun isOffByDefault() = assertFalse(store().isPrivateSession)

    @Test
    fun startingAndEndingFlipIt() {
        val s = store()
        s.startPrivateSession()
        assertTrue(s.isPrivateSession)
        s.endPrivateSession()
        assertFalse(s.isPrivateSession)
    }

    @Test
    fun toggleGoesBothWays() {
        val s = store()
        s.togglePrivateSession()
        assertTrue(s.isPrivateSession)
        s.togglePrivateSession()
        assertFalse(s.isPrivateSession)
    }

    @Test
    fun lapsesOnceTheIdleWindowPasses() {
        val s = store()
        s.startPrivateSession()
        now += PRIVATE_SESSION_IDLE_WINDOW_MS - 1
        assertTrue(s.isPrivateSession)
        now += 2
        assertFalse(s.isPrivateSession)
    }

    /** The session should outlast the workout, not a fixed half hour: as long as music plays the deadline keeps moving. */
    @Test
    fun playbackPushesTheDeadlineForward() {
        val s = store()
        s.startPrivateSession()
        now += PRIVATE_SESSION_IDLE_WINDOW_MS / 2
        s.extendPrivateSession()
        assertEquals(now + PRIVATE_SESSION_IDLE_WINDOW_MS, s.privateSessionEndsAt.value)
        now += PRIVATE_SESSION_IDLE_WINDOW_MS - 1
        assertTrue(s.isPrivateSession)
    }

    /** Called every second from the player, so it mustn't write on every call. */
    @Test
    fun extendingWithinAMinuteIsANoOp() {
        val s = store()
        s.startPrivateSession()
        val original = s.privateSessionEndsAt.value
        now += 30_000
        s.extendPrivateSession()
        assertEquals(original, s.privateSessionEndsAt.value)
    }

    @Test
    fun extendingWithoutASessionStartsNothing() {
        val s = store()
        s.extendPrivateSession()
        assertFalse(s.isPrivateSession)
        assertEquals(0L, s.privateSessionEndsAt.value)
    }

    /** Already lapsed, a session shouldn't come back because a track happened to play. */
    @Test
    fun extendingALapsedSessionDoesNotReviveIt() {
        val s = store()
        s.startPrivateSession()
        now += PRIVATE_SESSION_IDLE_WINDOW_MS + 1
        s.extendPrivateSession()
        assertFalse(s.isPrivateSession)
    }

    @Test
    fun survivesARelaunch() {
        store().startPrivateSession()
        assertTrue(store().isPrivateSession)
    }

    /** Killed at the gym and reopened the next morning: no cleanup should be needed. */
    @Test
    fun aLapsedSessionIsOverOnRelaunch() {
        store().startPrivateSession()
        now += PRIVATE_SESSION_IDLE_WINDOW_MS + 1
        assertFalse(store().isPrivateSession)
    }
}
