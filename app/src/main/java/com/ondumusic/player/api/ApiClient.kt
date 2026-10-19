package com.ondumusic.player.api

import com.ondumusic.player.BuildConfig
import android.util.Log
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/** An authenticated client for one server session. */
class ApiClient(
    val baseUrl: HttpUrl,
    val sessionToken: String,
    private val http: OkHttpClient,
) {
    /**
     * Whether [url] may carry the session token. Stream and download URLs arrive in the
     * server's own data; one pointing at another host is requested without the token
     * rather than handing it to that host.
     */
    fun mayReceiveToken(url: HttpUrl): Boolean = isSameOrigin(url, baseUrl)

    private fun request(endpoint: String, query: Map<String, String> = emptyMap()): Request.Builder {
        val url = baseUrl.appendingPath(endpoint).newBuilder()
            .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $sessionToken")
            .header("Accept", "application/json")
    }

    private suspend fun <T> execute(request: Request, deserializer: KSerializer<T>): T {
        http.newCall(request).await().use { response ->
            val body = response.body.string()
            if (response.code == 401) throw ApiException.Unauthorized()
            if (!response.isSuccessful) {
                if (BuildConfig.DEBUG) Log.d(TAG, "${request.method} ${request.url} failed — HTTP ${response.code}, body: $body")
                throw ApiException.Http(response.code)
            }
            return decode(body, deserializer)
        }
    }

    private suspend inline fun <reified T> get(endpoint: String, query: Map<String, String> = emptyMap()): T =
        execute(request(endpoint, query).get().build(), serializer())

    private suspend inline fun <reified T> perform(endpoint: String, method: String, body: RequestBody? = null): T =
        execute(request(endpoint).method(method, body ?: emptyBodyFor(method)).build(), serializer())

    // Auth

    suspend fun fetchCurrentUser(): com.ondumusic.player.model.User = get("auth/me")

    /** Best-effort — local state is cleared even if the server call fails. */
    suspend fun deleteSession() {
        runCatching { http.newCall(request("auth/session").delete().build()).await().close() }
    }

    // Sync

    suspend fun fetchSync(cursor: String?): SyncResponse =
        get("sync", if (cursor != null) mapOf("cursor" to cursor) else emptyMap())

    suspend fun fetchServerInfo(): ServerInfo = get("info")

    // Artist artwork

    /**
     * Replaces the artist's artwork, returning the artist as the server now has it.
     *
     * Not through the outbox: that queue exists to replay small ordered mutations after
     * being offline, and neither half fits an upload of several megabytes.
     */
    suspend fun uploadArtistArtwork(artistId: UUID, image: ByteArray, filename: String, mimeType: String): ArtistDto {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, image.toRequestBody(mimeType.toMediaType()))
            .build()
        val request = request("artists/$artistId/artwork").put(body).build()
        http.newCall(request).await().use { response ->
            val text = response.body.string()
            if (response.code == 401) throw ApiException.Unauthorized()
            if (response.code == 422) ArtworkRejection.decode(text)?.let { throw it }
            if (!response.isSuccessful) throw ApiException.Http(response.code)
            return decode(text, ArtistDto.serializer())
        }
    }

    suspend fun deleteArtistArtwork(artistId: UUID): ArtistDto = perform("artists/$artistId/artwork", "DELETE")

    // Notices

    /** Dismisses for everyone, not just this device; returns what's left. */
    suspend fun dismissNotice(id: UUID): List<ServerNoticeDto> =
        perform<NoticesResponse>("notices/$id/dismiss", "POST").notices

    // Plays

    suspend fun recordPlays(plays: List<PlayBody>) {
        perform<OkResponse>("plays", "POST", jsonBody(ApiJson.encodeToString(RecordPlaysBody(plays))))
    }

    // Playlists

    suspend fun createPlaylist(name: String, isFolder: Boolean = false, parentId: UUID? = null): PlaylistDto =
        perform("playlists", "POST", jsonBody(ApiJson.encodeToString(CreatePlaylistBody(name, isFolder, parentId))))

    suspend fun renamePlaylist(id: UUID, name: String): PlaylistDto =
        perform("playlists/$id", "PATCH", jsonBody(JsonObject(mapOf("name" to JsonPrimitive(name))).toString()))

    /** An explicit `null` parent moves it to the root, so this can't go through the null-omitting encoder. */
    suspend fun movePlaylist(id: UUID, parentId: UUID?): PlaylistDto {
        val parent = parentId?.let { JsonPrimitive(it.toString()) } ?: JsonNull
        return perform("playlists/$id", "PATCH", jsonBody(JsonObject(mapOf("parentId" to parent)).toString()))
    }

    suspend fun deletePlaylist(id: UUID) {
        perform<PlaylistDto>("playlists/$id", "DELETE")
    }

    suspend fun updatePlaylistItems(
        id: UUID,
        add: List<PositionedTrack> = emptyList(),
        move: List<PositionedItem> = emptyList(),
        remove: List<UUID> = emptyList(),
    ): UpdatePlaylistItemsResponse {
        val body = jsonBody(ApiJson.encodeToString(UpdatePlaylistItemsBody(add, move, remove)))
        val request = request("playlists/$id/items").patch(body).build()
        http.newCall(request).await().use { response ->
            val text = response.body.string()
            if (response.code == 401) throw ApiException.Unauthorized()
            // Typed recovery: callers can see which items or tracks were rejected and retry without them.
            if (response.code == 422) PlaylistItemsException.decode(text)?.let { throw it }
            if (!response.isSuccessful) {
                if (BuildConfig.DEBUG) Log.d(TAG, "PATCH playlists/$id/items failed — HTTP ${response.code}, body: $text")
                throw ApiException.Http(response.code)
            }
            return decode(text, UpdatePlaylistItemsResponse.serializer())
        }
    }

    companion object {
        private const val TAG = "ApiClient"
        private val JSON = "application/json".toMediaType()

        private fun jsonBody(json: String) = json.toRequestBody(JSON)

        /** Same scheme, host and port. [HttpUrl] already lowercases the host and fills in a default port. */
        fun isSameOrigin(url: HttpUrl, base: HttpUrl): Boolean =
            url.scheme == base.scheme && url.host == base.host && url.port == base.port

        private fun emptyBodyFor(method: String): RequestBody? =
            if (method == "POST" || method == "PUT" || method == "PATCH") ByteArray(0).toRequestBody() else null

        internal fun <T> decode(body: String, deserializer: KSerializer<T>): T =
            try {
                ApiJson.decodeFromString(deserializer, body)
            } catch (e: SerializationException) {
                throw ApiException.Decoding(e.message ?: "unreadable")
            } catch (e: IllegalArgumentException) {
                throw ApiException.Decoding(e.message ?: "unreadable")
            }

        suspend fun login(http: OkHttpClient, baseUrl: HttpUrl, username: String, password: String): LoginResponse {
            val body = JsonObject(mapOf("username" to JsonPrimitive(username), "password" to JsonPrimitive(password)))
            val request = Request.Builder()
                .url(baseUrl.appendingPath("auth/login"))
                .header("Accept", "application/json")
                .post(jsonBody(body.toString()))
                .build()
            http.newCall(request).await().use { response ->
                when (response.code) {
                    in 200..299 -> Unit
                    401, 422 -> throw LoginRejection.InvalidCredentials()
                    429 -> throw LoginRejection.TooManyAttempts()
                    else -> throw ApiException.Http(response.code)
                }
                return decode(response.body.string(), LoginResponse.serializer())
            }
        }
    }
}
