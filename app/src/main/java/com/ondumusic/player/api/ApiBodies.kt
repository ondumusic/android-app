package com.ondumusic.player.api

import com.ondumusic.player.model.ArtworkVariant
import com.ondumusic.player.model.TranscodePreset
import com.ondumusic.player.model.TranscodePresetList
import com.ondumusic.player.model.User
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID


/** The API generation this build is written against. Any difference means one side can't talk to the other. */
const val SUPPORTED_API_VERSION = 2

@Serializable
data class ServerInfo(
    val version: String,
    val apiVersion: Int,
    /** Absent from servers that predate listing presets. */
    val defaultPreset: String? = null,
    val presets: List<TranscodePreset>? = null,
) {
    val presetList: TranscodePresetList?
        get() {
            if (defaultPreset == null || presets.isNullOrEmpty()) return null
            return TranscodePresetList(defaultPreset, presets)
        }
}

@Serializable
data class LoginResponse(
    val ok: Boolean,
    val sessionToken: String? = null,
    val currentUser: User? = null,
    val fields: Fields? = null,
) {
    @Serializable
    data class Fields(val password: FieldError? = null)

    @Serializable
    data class FieldError(val message: String)
}

@Serializable
data class ArtistDto(
    val id: SerialUuid,
    val name: String,
    val sortName: String? = null,
    val slug: String,
    val albumCount: Int? = null,
    val artwork: List<ArtworkVariant> = emptyList(),
)

@Serializable
data class PlaylistDto(
    val id: SerialUuid,
    val name: String,
    val trackCount: Int = 0,
    val insertedAt: SerialInstant? = null,
    val isFolder: Boolean = false,
    val parentId: SerialUuid? = null,
)

@Serializable
data class PlaylistItemDto(
    val id: SerialUuid,
    val playlistId: SerialUuid,
    val trackId: SerialUuid,
    val position: String,
    val insertedAt: SerialInstant,
)

@Serializable
data class PositionedTrack(val trackId: SerialUuid, val position: String)

@Serializable
data class PositionedItem(val itemId: SerialUuid, val position: String)

@Serializable
data class UpdatePlaylistItemsBody(
    val add: List<PositionedTrack>,
    val move: List<PositionedItem>,
    val remove: List<SerialUuid>,
)

@Serializable
data class UpdatePlaylistItemsResponse(
    val added: List<PlaylistItemDto> = emptyList(),
    val moved: List<PlaylistItemDto> = emptyList(),
    val removed: List<SerialUuid> = emptyList(),
)

@Serializable
data class CreatePlaylistBody(val name: String, val isFolder: Boolean, val parentId: SerialUuid? = null)

@Serializable
data class PlayBody(val id: SerialUuid, val trackId: SerialUuid, val playedAt: SerialInstant)

@Serializable
internal data class RecordPlaysBody(val plays: List<PlayBody>)

@Serializable
internal data class OkResponse(val ok: Boolean)

@Serializable
internal data class NoticesResponse(val notices: List<ServerNoticeDto>)
