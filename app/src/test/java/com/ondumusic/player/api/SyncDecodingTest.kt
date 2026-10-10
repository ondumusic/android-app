package com.ondumusic.player.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.UUID

class SyncDecodingTest {
    private val page = """
        {
          "complete": false,
          "cursor": "v1-abc",
          "artists": [{"id": "b37eb394-6de0-4771-88e2-d238db9990f0", "name": "Amy Winehouse", "__type": "artist",
            "sortName": "amy winehouse", "slug": "amy-winehouse", "insertedAt": "2026-10-06T18:56:43Z",
            "artwork": [{"width": 128, "url": "https://x/thumb.jpg", "height": 128}], "albumCount": 1}],
          "albums": [{"id": "42cc49bb-c1cb-4576-8950-400dcaa2907b", "name": "Back To Black", "trackCount": 11,
            "slug": "back-to-black", "releaseYear": 2006, "loudness": {"peak": -32.0, "lufs": -41.93},
            "isUnknown": false, "insertedAt": "2026-10-06T18:56:43Z",
            "artwork": [{"width": 128, "url": "https://x/s.jpg", "height": 128}, {"width": 600, "url": "https://x/l.jpg", "height": 600}],
            "artistId": "b37eb394-6de0-4771-88e2-d238db9990f0"}],
          "tracks": [{"id": "0898fab4-7263-4bb2-8cc7-f3cd3e495bb5", "name": "Green Calx", "number": 6, "duration": 365.16,
            "loudness": {"peak": -36.2, "lufs": -41.7}, "insertedAt": "2026-10-06T18:56:43Z",
            "artistId": "62ea3162-6506-4155-a826-a7ceda146b20", "streamUrl": "https://x/stream/1", "playCount": 0,
            "mimetype": "audio/mp4", "lastPlayedAt": null, "downloadUrl": "https://x/download/1",
            "albumId": "118c456c-6287-40c4-8eb8-01e661727b45", "albumArtistId": "62ea3162-6506-4155-a826-a7ceda146b20"}],
          "playlists": [{"id": "02922245-1e52-43c2-a6f2-338bb6eab2a0", "name": "Party Mix", "trackCount": 3,
            "insertedAt": "2026-10-07T12:08:50Z", "parentId": null, "isFolder": false}],
          "playlistItems": [{"id": "181ec9d8-fe08-4bba-830b-692588d65375", "position": "a1", "insertedAt": "2026-10-07T12:09:23Z",
            "trackId": "aecce7ac-10a0-4365-adb5-d042a3b9e650", "playlistId": "02922245-1e52-43c2-a6f2-338bb6eab2a0"}],
          "deletions": [{"entityId": "00f674f7-c276-49ef-9dc5-575c92bc6a56", "entityType": "artist"}],
          "notices": []
        }
    """.trimIndent()

    @Test
    fun decodesARealSyncPage() {
        val response = ApiClient.decode(page, SyncResponse.serializer())

        assertFalse(response.complete)
        assertEquals("amy winehouse", response.artists.single().resolvedSortName)

        val album = response.albums.single()
        assertEquals("Back To Black", album.resolvedSortName)
        assertEquals("https://x/l.jpg", album.resolvedArtworkUrl)
        assertEquals(-41.93, album.lufs!!, 0.0)

        val track = response.tracks.single()
        assertEquals(1, track.disc)
        assertEquals(-41.7, track.lufs!!, 0.0)
        assertNull(track.lastPlayedAt)
        assertEquals(Instant.parse("2026-10-06T18:56:43Z"), track.insertedAt)

        assertNull(response.playlists.single().parentId)
        assertEquals(UUID.fromString("00f674f7-c276-49ef-9dc5-575c92bc6a56"), response.deletions.single().entityId)
    }

    @Test
    fun decodesThePlaylistItemsErrorUnion() {
        val error = PlaylistItemsException.decode(
            """{"error": "unknown_tracks", "trackIds": ["aecce7ac-10a0-4365-adb5-d042a3b9e650"]}"""
        )
        assertEquals(
            listOf(UUID.fromString("aecce7ac-10a0-4365-adb5-d042a3b9e650")),
            (error as PlaylistItemsException.UnknownTracks).trackIds,
        )
        assertNull(PlaylistItemsException.decode("""{"error": "something_new"}"""))
    }

    @Test
    fun writesWholeSecondTimestamps() {
        val body = PlayBody(
            UUID.fromString("181ec9d8-fe08-4bba-830b-692588d65375"),
            UUID.fromString("aecce7ac-10a0-4365-adb5-d042a3b9e650"),
            Instant.parse("2026-10-07T12:09:23.456Z"),
        )
        assertEquals(
            """{"id":"181ec9d8-fe08-4bba-830b-692588d65375","trackId":"aecce7ac-10a0-4365-adb5-d042a3b9e650","playedAt":"2026-10-07T12:09:23Z"}""",
            ApiJson.encodeToString(PlayBody.serializer(), body),
        )
    }
}
