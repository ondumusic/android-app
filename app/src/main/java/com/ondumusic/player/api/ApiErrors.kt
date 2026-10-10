package com.ondumusic.player.api

import kotlinx.serialization.Serializable
import java.util.UUID

sealed class ApiException(message: String) : Exception(message) {
    class InvalidResponse : ApiException("Invalid server response")
    class Unauthorized : ApiException("Session expired. Please log in again.")
    class Http(val code: Int) : ApiException("Server error ($code)")
    class Decoding(detail: String) : ApiException("Decoding error: $detail")
}

/**
 * The server's own field messages aren't used: which field it blames would tell someone
 * guessing at accounts whether the username exists.
 */
sealed class LoginRejection : Exception() {
    class InvalidCredentials : LoginRejection()
    class TooManyAttempts : LoginRejection()
}

/** Why the server rejected an image, when it says. */
sealed class ArtworkRejection : Exception() {
    class NotAnImage : ArtworkRejection()
    class TooLarge : ArtworkRejection()

    companion object {
        fun decode(body: String): ArtworkRejection? {
            val error = runCatching { ApiJson.decodeFromString<ErrorBody>(body).error }.getOrNull()
            return when (error) {
                "invalid_image" -> NotAnImage()
                "too_large" -> TooLarge()
                else -> null
            }
        }
    }
}

/**
 * Discriminated error returned as HTTP 422 by PATCH /playlists/{id}/items. The caller can
 * recover from [UnknownTracks] / [UnknownItems] by filtering and retrying; the conflict
 * cases, [TooManyOperations], and [PlaylistIsFolder] are surfaced as errors.
 */
sealed class PlaylistItemsException : Exception() {
    class TooManyOperations : PlaylistItemsException()
    class DuplicateItems(val itemIds: List<UUID>) : PlaylistItemsException()
    class MoveRemoveConflict(val itemIds: List<UUID>) : PlaylistItemsException()
    class UnknownItems(val itemIds: List<UUID>) : PlaylistItemsException()
    class UnknownTracks(val trackIds: List<UUID>) : PlaylistItemsException()
    class InvalidPositions(val positions: List<String>) : PlaylistItemsException()
    class PlaylistIsFolder : PlaylistItemsException()

    @Serializable
    private class Body(
        val error: String,
        val itemIds: List<@Serializable(with = UuidSerializer::class) UUID>? = null,
        val trackIds: List<@Serializable(with = UuidSerializer::class) UUID>? = null,
        val positions: List<String>? = null,
    )

    companion object {
        fun decode(body: String): PlaylistItemsException? {
            val b = runCatching { ApiJson.decodeFromString<Body>(body) }.getOrNull() ?: return null
            return when (b.error) {
                "too_many_operations" -> TooManyOperations()
                "duplicate_items" -> DuplicateItems(b.itemIds.orEmpty())
                "move_remove_conflict" -> MoveRemoveConflict(b.itemIds.orEmpty())
                "unknown_items" -> UnknownItems(b.itemIds.orEmpty())
                "unknown_tracks" -> UnknownTracks(b.trackIds.orEmpty())
                "invalid_positions" -> InvalidPositions(b.positions.orEmpty())
                "playlist_is_folder" -> PlaylistIsFolder()
                else -> null
            }
        }
    }
}

@Serializable
internal class ErrorBody(val error: String)
