package com.ondumusic.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.ondumusic.player.model.Artist
import com.ondumusic.player.model.ArtworkSize
import com.ondumusic.player.model.ArtworkVariant
import com.ondumusic.player.model.covering

/** A square cover, or a placeholder in its place while it loads or when there isn't one. */
@Composable
fun Artwork(
    url: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
    placeholder: ImageVector = Icons.Outlined.Album,
) = ArtworkImage(url, modifier, RoundedCornerShape(cornerRadius), placeholder, outlined = true)

/** Picks the cheapest variant that still covers the size it's drawn at. */
@Composable
fun Artwork(
    variants: List<ArtworkVariant>,
    fallbackUrl: String?,
    size: ArtworkSize,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
) = Artwork(variants.covering(size.pixels)?.url ?: fallbackUrl, modifier, cornerRadius)

/**
 * Artists in a circle, the convention for people. Unlike album covers these aren't square
 * at the source — the server keeps the aspect ratio and leaves the crop to us.
 */
@Composable
fun ArtistArtwork(artist: Artist, size: Dp, modifier: Modifier = Modifier) = ArtworkImage(
    artist.artwork.covering(ArtworkSize.Medium.pixels)?.url,
    modifier.size(size), CircleShape, Icons.Outlined.Person, outlined = false,
)

@Composable
private fun ArtworkImage(url: String?, modifier: Modifier, shape: Shape, placeholder: ImageVector, outlined: Boolean) {
    // Pale covers otherwise dissolve into a light page.
    val edge = if (outlined) Modifier.border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape) else Modifier
    Box(modifier.clip(shape).then(edge)) {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = { Placeholder(placeholder) },
            error = { Placeholder(placeholder) },
        )
    }
}

@Composable
private fun Placeholder(icon: ImageVector) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxSize(0.4f))
    }
}
