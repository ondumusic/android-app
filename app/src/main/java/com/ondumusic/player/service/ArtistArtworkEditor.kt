package com.ondumusic.player.service

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.ondumusic.player.api.ApiClient
import com.ondumusic.player.api.ArtworkRejection
import com.ondumusic.player.data.MusicLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Uploading and removing an artist's picture, and writing what the server returns into
 * the library so the circle changes now rather than at the next sync.
 */
class ArtistArtworkEditor(
    private val api: () -> ApiClient?,
    private val library: MusicLibrary,
    private val resolver: ContentResolver,
) {
    sealed class Failure : Exception() {
        class NotSignedIn : Failure()
        class NotAnImage : Failure()
        class TooLarge : Failure()
        class Failed(val detail: String?) : Failure()
    }

    suspend fun upload(artistId: UUID, image: Uri) {
        val api = api() ?: throw Failure.NotSignedIn()
        val jpeg = try {
            withContext(Dispatchers.Default) { prepare(image) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw Failure.NotAnImage()
        }
        val artist = translating { api.uploadArtistArtwork(artistId, jpeg, "artwork.jpg", "image/jpeg") }
        library.setArtistArtwork(artistId, artist.artwork)
    }

    suspend fun remove(artistId: UUID) {
        val api = api() ?: throw Failure.NotSignedIn()
        val artist = translating { api.deleteArtistArtwork(artistId) }
        library.setArtistArtwork(artistId, artist.artwork)
    }

    /**
     * Re-encoded rather than sent as picked: a phone photo can be 50 MB of HEIC the server
     * may not read, and nothing about an artist circle needs more than this.
     */
    private fun prepare(image: Uri): ByteArray {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, image)) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > MAX_PIXELS) {
                val scale = MAX_PIXELS.toFloat() / longest
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    }

    private suspend fun <T> translating(block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ArtworkRejection.NotAnImage) {
            throw Failure.NotAnImage()
        } catch (e: ArtworkRejection.TooLarge) {
            throw Failure.TooLarge()
        } catch (e: Exception) {
            throw Failure.Failed(e.message)
        }

    private companion object {
        const val MAX_PIXELS = 2000
        const val JPEG_QUALITY = 85
    }
}
