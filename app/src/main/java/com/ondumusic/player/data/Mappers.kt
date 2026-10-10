package com.ondumusic.player.data

import com.ondumusic.player.api.SyncAlbum
import com.ondumusic.player.api.SyncArtist
import com.ondumusic.player.api.SyncPlaylist
import com.ondumusic.player.api.SyncPlaylistItem
import com.ondumusic.player.api.SyncTrack
import com.ondumusic.player.model.Album
import com.ondumusic.player.model.Artist
import com.ondumusic.player.model.Playlist
import com.ondumusic.player.model.PlaylistItem
import com.ondumusic.player.model.Track

fun ArtistEntity.toArtist() = Artist(id, name, sortName, slug, albumCount, artwork)

fun AlbumRow.toAlbum() = Album(
    id = album.id, artistId = album.artistId, name = album.name, sortName = album.sortName,
    slug = album.slug, releaseYear = album.releaseYear, isUnknown = album.isUnknown,
    artwork = album.artwork, artworkUrl = album.artworkUrl, insertedAt = album.insertedAt,
    artistName = artistName, artistSortName = artistSortName,
)

fun TrackRow.toTrack(): Track {
    val t = track
    val artist = Artist(
        id = t.artistId,
        name = artistName ?: "Unknown",
        sortName = artistSortName ?: "Unknown",
        slug = artistSlug ?: "unknown",
    )
    return Track(
        id = t.id, artistId = t.artistId, albumArtistId = t.albumArtistId, albumId = t.albumId,
        artist = artist,
        albumArtistName = albumArtistName ?: artist.name,
        albumArtistSortName = albumArtistSortName ?: artist.sortName,
        album = albumName?.let { Track.TrackAlbum(t.albumId, it, albumArtworkUrl, albumReleaseYear, albumSortName ?: it) },
        name = t.name, disc = t.disc, number = t.number, duration = t.duration,
        mimetype = t.mimetype, streamUrl = t.streamUrl, downloadUrl = t.downloadUrl,
        isDownloaded = t.isDownloaded, lufs = t.lufs,
        playCount = t.playCount, lastPlayedAt = t.lastPlayedAt,
    )
}

fun PlaylistEntity.toPlaylist() = Playlist(id, name, trackCount, insertedAt, isFolder, parentId, autoDownload)

fun PlaylistItemEntity.toItem() = PlaylistItem(id, playlistId, trackId, position, insertedAt)

fun PlaylistItem.toEntity() = PlaylistItemEntity(id, playlistId, trackId, position, insertedAt)

fun SyncArtist.toEntity() = ArtistEntity(id, name, resolvedSortName, slug, albumCount, insertedAt, artwork)

fun SyncAlbum.toEntity() = AlbumEntity(
    id, artistId, name, resolvedSortName, slug, releaseYear, resolvedArtworkUrl, artwork,
    isUnknown, trackCount, insertedAt, lufs,
)

fun SyncTrack.toEntity() = TrackEntity(
    id, artistId, albumArtistId, albumId, name, disc, number, duration, mimetype,
    streamUrl, downloadUrl, insertedAt, lufs, playCount, lastPlayedAt,
)

fun SyncTrack.toServerFields() = TrackServerFields(
    id, artistId, albumArtistId, albumId, name, disc, number, duration, mimetype,
    streamUrl, downloadUrl, insertedAt, lufs, playCount, lastPlayedAt,
)

fun SyncPlaylist.toEntity() = PlaylistEntity(id, name, trackCount, insertedAt, isFolder, parentId)

fun SyncPlaylist.toServerFields() = PlaylistServerFields(id, name, trackCount, insertedAt, isFolder, parentId)

fun SyncPlaylistItem.toEntity() = PlaylistItemEntity(id, playlistId, trackId, position, insertedAt)
