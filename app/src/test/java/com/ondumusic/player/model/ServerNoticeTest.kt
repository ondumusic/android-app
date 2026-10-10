package com.ondumusic.player.model

import com.ondumusic.player.api.ApiJson
import com.ondumusic.player.api.ServerNoticeDto
import com.ondumusic.player.api.SyncResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerNoticeTest {

    private fun decode(json: String): ServerNotice = ApiJson.decodeFromString<ServerNoticeDto>(json).toNotice()

    private val known = """
        {"id":"6f1a3f1e-0000-4000-8000-000000000001","code":"library_unavailable",
         "level":"warning","message":"No music was found in the music path.",
         "raisedAt":"2026-10-04T18:00:00Z"}
    """.trimIndent()

    private val unknown = """
        {"id":"6f1a3f1e-0000-4000-8000-000000000002","code":"disk_filling_up",
         "level":"info","message":"The disk is nearly full.",
         "raisedAt":"2026-10-04T18:00:00Z"}
    """.trimIndent()

    // A code this build knows gets our own wording, which goes through string resources
    // and can say what to do about it.
    @Test fun recognisesAKnownCode() {
        assertEquals(ServerNotice.Known.LibraryUnavailable, decode(known).known)
    }

    // And the server's English for one it doesn't — which is what lets the server add
    // notices without waiting for an app release.
    @Test fun keepsTheServersMessageForAnUnknownCode() {
        val notice = decode(unknown)
        assertNull(notice.known)
        assertEquals("The disk is nearly full.", notice.message)
    }

    @Test fun readsTheLevel() {
        assertEquals(ServerNotice.Level.Warning, decode(known).level)
        assertEquals(ServerNotice.Level.Info, decode(unknown).level)
    }

    // A level invented later shouldn't fail to decode, and shouldn't shout either.
    @Test fun treatsAnUnknownLevelAsInfo() {
        val json = unknown.replace("\"info\"", "\"catastrophe\"")
        assertEquals(ServerNotice.Level.Info, decode(json).level)
    }

    @Test fun treatsAMissingLevelAsInfo() {
        val json = unknown.replace("\"level\":\"info\",", "")
        assertEquals(ServerNotice.Level.Info, decode(json).level)
    }

    // Sync sends everything currently raised rather than what changed, so an empty list
    // has to mean "none" rather than "no news".
    @Test fun anEmptyListDecodes() {
        val response = ApiJson.decodeFromString<SyncResponse>(
            """{"artists":[],"albums":[],"tracks":[],"deletions":[],"cursor":"c","complete":true}""",
        )
        assertTrue(response.notices.isEmpty())
    }

    @Test fun noticesDecodeFromASyncResponse() {
        val json = """
            {"artists":[],"albums":[],"tracks":[],"deletions":[],"cursor":"c","complete":true,
             "notices":[$known]}
        """.trimIndent()
        val response = ApiJson.decodeFromString<SyncResponse>(json)
        assertEquals(1, response.notices.size)
        assertEquals(ServerNotice.Known.LibraryUnavailable, response.notices.first().toNotice().known)
    }
}
