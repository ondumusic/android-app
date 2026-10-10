package com.ondumusic.player.model

import kotlinx.serialization.Serializable

/**
 * One rendition of an album's artwork.
 *
 * The server renders a set of square versions and never upscales, so a variant's
 * dimensions can be smaller than the size it nominally represents — a 300px cover has
 * no 640 variant larger than itself. That's why selection works off the actual `width`
 * rather than a name like "large".
 */
@Serializable
data class ArtworkVariant(
    val url: String,
    val width: Int,
    val height: Int,
)

/** The largest variant, which is what the deprecated `artworkUrl` used to be. */
val List<ArtworkVariant>.largest: ArtworkVariant?
    get() = maxByOrNull { it.width }

/**
 * The cheapest variant that still covers [pixels] across.
 *
 * Smallest-that-fits rather than nearest: going under means visibly resampling a cover
 * up, while going over only costs bytes. When nothing is big enough the largest is the
 * best available — the source image simply isn't that large.
 */
fun List<ArtworkVariant>.covering(pixels: Int): ArtworkVariant? =
    filter { it.width >= pixels }.minByOrNull { it.width } ?: largest
