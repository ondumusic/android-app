package com.ondumusic.player.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCompatibilityTest {

    private fun compare(server: Int, supported: Int = 1) =
        ServerCompatibility.compare(serverApiVersion = server, serverVersion = "1.3.0", supported = supported)

    @Test fun matchingVersionsAreFine() {
        assertEquals(ServerCompatibility.Ok, compare(server = 1))
    }

    // The server bumps its API version only when it breaks clients, so a higher one
    // means this build is the side that can't keep up.
    @Test fun aHigherServerVersionMeansTheAppIsBehind() {
        assertEquals(ServerCompatibility.AppTooOld("1.3.0"), compare(server = 2))
    }

    // The app ships on the store's schedule and the server on its owner's, so this
    // happens as readily as the other way round.
    @Test fun aLowerServerVersionMeansTheServerIsBehind() {
        assertEquals(ServerCompatibility.ServerTooOld("1.3.0"), compare(server = 1, supported = 2))
    }

    @Test fun showsNothingWhenTheVersionsMatch() {
        assertFalse(ServerCompatibility.Ok.shouldShow(dismissedVersion = ""))
    }

    @Test fun showsAMismatchNobodyHasDismissed() {
        assertTrue(compare(server = 2).shouldShow(dismissedVersion = ""))
    }

    @Test fun staysDismissedForTheVersionItWasAbout() {
        assertFalse(compare(server = 2).shouldShow(dismissedVersion = "1.3.0"))
    }

    // A server upgrade that doesn't fix the mismatch is worth mentioning again — the
    // dismissal was about the version, not about the subject.
    @Test fun speaksUpAgainAfterTheServerChangesVersion() {
        val laterServer = ServerCompatibility.compare(serverApiVersion = 2, serverVersion = "1.4.0", supported = 1)
        assertTrue(laterServer.shouldShow(dismissedVersion = "1.3.0"))
    }

    @Test fun carriesTheServerVersionForTheMessage() {
        assertEquals("1.3.0", compare(server = 2).serverVersion)
        assertEquals("1.3.0", compare(server = 1, supported = 2).serverVersion)
        assertEquals(null, ServerCompatibility.Ok.serverVersion)
    }
}
