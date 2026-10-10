package com.ondumusic.player.model

import java.time.Instant
import java.util.UUID

data class Track(
    val id: UUID,
    val artistId: UUID,
    val albumArtistId: UUID,
    val albumId: UUID,
    val artist: Artist,
    val albumArtistName: String = artist.name,
    val albumArtistSortName: String,
    val album: TrackAlbum? = null,
    val name: String,
    val disc: Int,
    val number: Int?,
    /** Seconds. */
    val duration: Double,
    val mimetype: String,
    /** May be relative; resolve against the API base URL before use. */
    val streamUrl: String,
    val downloadUrl: String = "",
    val isDownloaded: Boolean = false,
    /** Integrated loudness in LUFS. Null until the server has measured the file. */
    val lufs: Double? = null,
    val playCount: Int = 0,
    /** Null until it has been played once. */
    val lastPlayedAt: Instant? = null,
) {
    data class TrackAlbum(
        val id: UUID,
        val name: String,
        val artworkUrl: String?,
        val releaseYear: Int?,
        val sortName: String = name,
    )

    val formattedDuration: String
        get() {
            val total = duration.toInt()
            return "%d:%02d".format(total / 60, total % 60)
        }
}
