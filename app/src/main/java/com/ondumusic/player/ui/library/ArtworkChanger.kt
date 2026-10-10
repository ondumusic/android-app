package com.ondumusic.player.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ondumusic.player.R
import com.ondumusic.player.service.ArtistArtworkEditor
import com.ondumusic.player.ui.LocalContainer
import com.ondumusic.player.ui.components.Action
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/** Choosing, replacing and removing an artist's photo from the artist page. */
@Stable
class ArtworkChanger internal constructor(
    private val artistId: UUID,
    private val editor: ArtistArtworkEditor,
    private val scope: CoroutineScope,
) {
    var isUpdating by mutableStateOf(false)
        private set
    private var failure by mutableStateOf<ArtistArtworkEditor.Failure?>(null)
    private var lastChange: (suspend () -> Unit)? = null
    internal var pickPhoto: () -> Unit = {}
    internal var pickFile: () -> Unit = {}

    fun upload(image: Uri) = change { editor.upload(artistId, image) }

    private fun change(work: suspend () -> Unit) {
        lastChange = work
        scope.launch {
            isUpdating = true
            try {
                work()
            } catch (e: ArtistArtworkEditor.Failure) {
                failure = e
            } finally {
                isUpdating = false
            }
        }
    }

    @Composable
    fun actions(hasArtwork: Boolean): List<Action> = listOfNotNull(
        Action(stringResource(if (hasArtwork) R.string.artwork_replace_photo else R.string.artwork_choose_photo), Icons.Outlined.Image) { pickPhoto() },
        // An artist photo is as often something saved from a browser as something shot, so Files is a first-class way in.
        Action(stringResource(R.string.artwork_choose_file), Icons.Outlined.FolderOpen) { pickFile() },
        if (hasArtwork) Action(stringResource(R.string.artwork_remove_photo), Icons.Outlined.Delete, destructive = true) { change { editor.remove(artistId) } } else null,
    )

    /**
     * The kinds differ in what would help: a rejected image wants a different one, a failed
     * request wants the same one again.
     */
    @Composable
    fun FailureDialog() {
        val current = failure ?: return
        val dismiss = { failure = null }
        val message = when (current) {
            is ArtistArtworkEditor.Failure.NotSignedIn -> stringResource(R.string.artwork_not_signed_in)
            is ArtistArtworkEditor.Failure.NotAnImage -> stringResource(R.string.artwork_not_an_image)
            is ArtistArtworkEditor.Failure.TooLarge -> stringResource(R.string.artwork_too_large)
            is ArtistArtworkEditor.Failure.Failed -> stringResource(R.string.artwork_failed, current.detail.orEmpty())
        }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.artwork_failed_title)) },
            text = { Text(message) },
            confirmButton = {
                when (current) {
                    is ArtistArtworkEditor.Failure.NotAnImage, is ArtistArtworkEditor.Failure.TooLarge ->
                        TextButton(onClick = { dismiss(); pickPhoto() }) { Text(stringResource(R.string.artwork_choose_another)) }
                    is ArtistArtworkEditor.Failure.Failed ->
                        TextButton(onClick = { dismiss(); lastChange?.let(::change) }) { Text(stringResource(R.string.retry)) }
                    is ArtistArtworkEditor.Failure.NotSignedIn ->
                        TextButton(onClick = dismiss) { Text(stringResource(R.string.done)) }
                }
            },
            dismissButton = if (current is ArtistArtworkEditor.Failure.NotSignedIn) null else {
                { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } }
            },
        )
    }
}

@Composable
fun rememberArtworkChanger(artistId: UUID): ArtworkChanger {
    val container = LocalContainer.current
    val resolver = LocalContext.current.contentResolver
    val changer = remember(artistId) {
        ArtworkChanger(artistId, ArtistArtworkEditor({ container.auth.api.value }, container.library, resolver), container.scope)
    }
    // The system photo picker needs no storage permission and no Google services.
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(changer::upload) }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(changer::upload) }
    changer.pickPhoto = { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    changer.pickFile = { file.launch(arrayOf("image/*")) }
    return changer
}
