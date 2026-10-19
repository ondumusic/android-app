package com.ondumusic.player.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiOriginTest {
    private val base = "https://music.example.com/api".toHttpUrl()

    private fun sameOrigin(url: String) = ApiClient.isSameOrigin(url.toHttpUrl(), base)

    @Test
    fun theServersOwnUrlsGetTheToken() {
        assertTrue(sameOrigin("https://music.example.com/stream/1"))
        assertTrue(sameOrigin("https://MUSIC.example.com/stream/1"))
        assertTrue(sameOrigin("https://music.example.com:443/stream/1"))
    }

    @Test
    fun anyOtherHostPortOrSchemeDoesNot() {
        assertFalse(sameOrigin("https://elsewhere.example.com/stream/1"))
        assertFalse(sameOrigin("https://music.example.com.evil.test/stream/1"))
        assertFalse(sameOrigin("https://music.example.com:8443/stream/1"))
        assertFalse(sameOrigin("http://music.example.com/stream/1"))
    }

    @Test
    fun anExplicitPortOnTheServerMatchesOnlyThatPort() {
        val base = "http://192.168.1.10:4000/api".toHttpUrl()
        assertTrue(ApiClient.isSameOrigin("http://192.168.1.10:4000/stream/1".toHttpUrl(), base))
        assertFalse(ApiClient.isSameOrigin("http://192.168.1.10/stream/1".toHttpUrl(), base))
    }
}
