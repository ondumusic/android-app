package com.ondumusic.player.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.materialkolor.ktx.themeColorOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Large enough for the cover on a tablet without decoding the original. */
private const val COVER_PIXELS = 1200

/** Colour extraction only needs the gist of the image, and the quantiser is per pixel. */
private const val SEED_PIXELS = 112

private class Shown(val url: String?, val image: ImageBitmap?)

/**
 * The cover, fading from one track's to the next.
 *
 * Swapping the image request directly fades the old cover into a loading placeholder and
 * then pops the new one in. This keeps the last cover on screen until the next is decoded,
 * and only then crossfades — so skipping never flashes an empty square. A newer request
 * cancels an older one, so a slow cover for a track already skipped past can't land late.
 */
@Composable
fun CrossfadingArtwork(url: String?, shape: Shape, modifier: Modifier = Modifier, pixels: Int = COVER_PIXELS) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<Shown?>(null) }
    LaunchedEffect(url) {
        if (url == null) {
            shown = Shown(null, null)
            return@LaunchedEffect
        }
        if (shown?.url == url) return@LaunchedEffect
        val request = ImageRequest.Builder(context).data(url).size(pixels).build()
        val result = SingletonImageLoader.get(context).execute(request)
        // A missing cover shows the placeholder rather than holding on to the previous
        // track's, which would be a cover for the wrong music.
        shown = Shown(url, (result as? SuccessResult)?.image?.toBitmap()?.asImageBitmap())
    }
    Box(modifier.clip(shape)) {
        Crossfade(targetState = shown, animationSpec = tween(280), label = "artwork") { current ->
            val image = current?.image
            if (image != null) {
                Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else if (current != null) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Album, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxSize(0.35f))
                }
            } else {
                // Still loading the very first cover: an empty tile, not a placeholder icon
                // that would flash for the few milliseconds a cached cover takes.
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh))
            }
        }
    }
}

/**
 * The colour a Material scheme should grow from for this cover, chosen the way the
 * system picks one from a wallpaper. Null without a cover, or for one too grey to have a
 * colour worth building on — the app's own theme is better than a muddy tint.
 */
@Composable
fun rememberArtworkSeedColor(url: String?): Color? {
    val context = LocalContext.current
    val seed by produceState<Color?>(initialValue = null, url) {
        value = url?.let {
            val request = ImageRequest.Builder(context).data(it).size(SEED_PIXELS).allowHardware(false).build()
            val image = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image ?: return@let null
            withContext(Dispatchers.Default) { image.toBitmap().asImageBitmap().themeColorOrNull() }
        }
    }
    return seed
}

/**
 * The cover itself, blurred, as a player background — what iOS does too. A gradient from
 * one extracted colour reduces a cover to its average hue: fine for a monochrome sleeve,
 * muddy for a busy one. Blurring keeps the cover's own structure.
 *
 * A mid-size rendition is plenty, since blur hides detail. The radius has to suit the
 * region rather than the image: too wide for a short strip and everything collapses into
 * one colour.
 */
@Composable
fun ArtworkBackdrop(url: String?, modifier: Modifier = Modifier, blurRadius: Dp = 48.dp, dimming: Float = 0.45f) {
    Box(modifier.clipToBounds()) {
        CrossfadingArtwork(
            url, RectangleShape,
            Modifier
                .fillMaxSize()
                // Overscaled before blurring: the blur samples past the edges, and without the
                // slack it pulls in transparency and vignettes the corners.
                .graphicsLayer { scaleX = 1.4f; scaleY = 1.4f }
                .blur(blurRadius, BlurredEdgeTreatment.Rectangle),
            pixels = BACKDROP_PIXELS,
        )
        // Keeps light text legible over whatever the cover happens to be.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dimming)))
    }
}

private const val BACKDROP_PIXELS = 300
