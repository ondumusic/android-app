package com.ondumusic.player.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ondumusic.player.R
import com.ondumusic.player.util.UiText
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuthServiceTest {
    private class MemoryTokenStore(var token: String? = null, var fail: Boolean = false) : TokenStore {
        override fun save(token: String) { this.token = token }
        override fun load(): TokenStore.LoadOutcome = when {
            fail -> TokenStore.LoadOutcome.Failed(IllegalStateException("locked"))
            token != null -> TokenStore.LoadOutcome.Found(token!!)
            else -> TokenStore.LoadOutcome.Missing
        }
        override fun clear() { token = null }
    }

    private val server = MockWebServer()
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("auth-test", Context.MODE_PRIVATE)
    private val tokens = MemoryTokenStore()

    @Before fun start() { server.start(); prefs.edit().clear().commit() }
    @After fun stop() = server.close()

    private fun TestScope.service() = AuthService(prefs, tokens, OkHttpClient(), this)
    private val baseUrl get() = server.url("/api")

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build()

    private val loginOk = """{"ok": true, "sessionToken": "tok", "currentUser": {"id": "b69bc822-3052-4063-b383-4fe399a193f6", "username": "max"}}"""

    @Test
    fun initialStateIsLoggedOut() = runTest {
        val auth = service()
        assertNull(auth.api.value)
        assertTrue(auth.isRestoringSession.value)
    }

    @Test
    fun loginStoresSessionAndKeepsTokenOutOfPreferences() = runTest {
        server.enqueue(json(loginOk))
        val auth = service()

        auth.login(baseUrl, "max", "pw")

        assertEquals("tok", auth.api.value?.sessionToken)
        assertEquals("max", auth.currentUser.value?.username)
        assertEquals("tok", tokens.token)
        assertEquals(baseUrl.toString(), auth.storedServerUrl)
        assertEquals("max", auth.storedUsername)
        assertFalse(prefs.all.values.contains("tok"))
        assertEquals("/api/auth/login", server.takeRequest().url.encodedPath)
    }

    @Test
    fun rejectedCredentialsSayWhatToFix() = runTest {
        server.enqueue(json("""{"ok": false}""", code = 401))
        val auth = service()
        auth.login(baseUrl, "max", "wrong")
        assertNull(auth.api.value)
        assertEquals(UiText.Res(R.string.error_wrong_credentials), auth.error.value)
    }

    @Test
    fun somethingOtherThanOnduIsNamedAsSuch() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("<html>hello</html>").build())
        val auth = service()
        auth.login(baseUrl, "max", "pw")
        assertEquals(UiText.Res(R.string.error_not_an_ondu_server), auth.error.value)
    }

    @Test
    fun restoreSessionRequiresAStoredAddress() = runTest {
        tokens.token = "tok"
        val auth = service()
        auth.restoreSession()
        assertNull(auth.api.value)
        assertFalse(auth.isRestoringSession.value)
    }

    @Test
    fun restoreIsOptimisticAndValidatesInTheBackground() = runTest {
        server.enqueue(json(loginOk))
        service().login(baseUrl, "max", "pw")
        server.takeRequest()
        server.enqueue(json("""{"id": "b69bc822-3052-4063-b383-4fe399a193f6", "username": "max"}"""))

        val auth = service()
        auth.restoreSession()
        assertNotNull(auth.api.value)
        assertFalse(auth.isRestoringSession.value)

        val user = withContext(Dispatchers.Default) { withTimeout(5_000) { auth.currentUser.first { it != null } } }
        assertEquals("max", user?.username)
        assertEquals("/api/auth/me", server.takeRequest().url.encodedPath)
    }

    @Test
    fun anExpiredSessionAsksAgainRatherThanSigningOut() = runTest {
        server.enqueue(json(loginOk))
        val auth = service()
        auth.login(baseUrl, "max", "pw")
        server.enqueue(json("""{"error": "Unauthorized"}""", code = 401))

        auth.validateSession()

        assertTrue(auth.isSessionExpired.value)
        assertNotNull(auth.api.value)
    }

    @Test
    fun anUnreachableServerLeavesTheSessionUsableOffline() = runTest {
        server.enqueue(json(loginOk))
        val auth = service()
        auth.login(baseUrl, "max", "pw")
        server.close()

        auth.validateSession()

        assertTrue(auth.isOffline.value)
        assertFalse(auth.isSessionExpired.value)
        assertNotNull(auth.api.value)
    }

    @Test
    fun anUnreadableStoredTokenIsExplained() = runTest {
        server.enqueue(json(loginOk))
        service().login(baseUrl, "max", "pw")
        tokens.fail = true

        val auth = service()
        auth.restoreSession()

        assertNull(auth.api.value)
        assertEquals(UiText.Res(R.string.error_stored_sign_in_unreadable), auth.error.value)
    }

    @Test
    fun logoutPreservesServerUrlAndUsername() = runTest {
        server.enqueue(json(loginOk))
        server.enqueue(json("{}"))
        val auth = service()
        auth.login(baseUrl, "max", "pw")

        auth.logout()

        assertNull(auth.api.value)
        assertNull(tokens.token)
        assertEquals("max", auth.storedUsername)
        assertEquals(baseUrl.toString(), auth.storedServerUrl)
    }

    @Test
    fun reAuthenticationClearsTheExpiredFlag() = runTest {
        server.enqueue(json(loginOk))
        val auth = service()
        auth.login(baseUrl, "max", "pw")
        auth.markSessionExpired()
        server.enqueue(json(loginOk.replace("\"tok\"", "\"tok2\"")))

        assertTrue(auth.reAuthenticate("pw"))
        assertFalse(auth.isSessionExpired.value)
        assertEquals("tok2", tokens.token)
    }
}
