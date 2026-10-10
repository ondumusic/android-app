package com.ondumusic.player.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ServerAddressTest {
    private fun resolved(input: String, allowingInsecure: Boolean = true) =
        ServerAddress.resolve(input, allowingInsecure)?.toString()

    @Test fun hostnameOnly() = assertEquals("https://music.example.com/api", resolved("music.example.com"))
    @Test fun hostnameWithPort() = assertEquals("https://music.example.com:4000/api", resolved("music.example.com:4000"))
    @Test fun hostnameWithHttpScheme() = assertEquals("http://localhost:4000/api", resolved("http://localhost:4000"))
    @Test fun hostnameWithHttpsScheme() = assertEquals("https://music.example.com/api", resolved("https://music.example.com"))
    @Test fun customPathNotOverridden() = assertEquals("https://music.example.com/custom/path", resolved("music.example.com/custom/path"))
    @Test fun customPathWithScheme() = assertEquals("https://myserver.com/v2/api", resolved("https://myserver.com/v2/api"))
    @Test fun trailingSlashGetsApiAppended() = assertEquals("https://music.example.com/api", resolved("music.example.com/"))
    @Test fun emptyStringReturnsNull() = assertNull(resolved(""))
    @Test fun whitespaceOnlyReturnsNull() = assertNull(resolved("   "))
    @Test fun whitespaceIsTrimmed() = assertEquals("https://music.example.com/api", resolved("  music.example.com  "))
    @Test fun ipAddress() = assertEquals("https://192.168.1.100:4000/api", resolved("192.168.1.100:4000"))
    @Test fun localhostShorthand() = assertEquals("https://localhost:4000/api", resolved("localhost:4000"))

    @Test
    fun rejectsHttpWhenInsecureServersAreNotAllowed() {
        assertNull(resolved("http://192.168.1.10:4000", allowingInsecure = false))
        assertNull(resolved("http://localhost:4000", allowingInsecure = false))
    }

    @Test
    fun allowsHttpWhenInsecureServersAreAllowed() =
        assertEquals("http://localhost:4000/api", resolved("http://localhost:4000", allowingInsecure = true))

    /** A bare hostname still becomes https, so the common case never trips the rule. */
    @Test
    fun bareHostnameBecomesHttpsAndIsAccepted() =
        assertEquals("https://music.example.com/api", resolved("music.example.com", allowingInsecure = false))

    @Test
    fun httpsIsAcceptedEitherWay() {
        assertNotNull(resolved("https://music.example.com", allowingInsecure = false))
        assertNotNull(resolved("https://music.example.com", allowingInsecure = true))
    }

    @Test
    fun displayLeavesTheDefaultsImplied() {
        assertEquals("music.example.com", ServerAddress.display("https://music.example.com/api"))
        assertEquals("http://localhost:4000", ServerAddress.display("http://localhost:4000/api"))
        assertEquals("myserver.com/v2", ServerAddress.display("https://myserver.com/v2"))
    }
}
