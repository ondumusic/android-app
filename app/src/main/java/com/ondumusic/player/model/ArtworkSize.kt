package com.ondumusic.player.model

enum class ArtworkSize(val pixels: Int) {
    /** List rows and small covers. */
    Thumbnail(200),
    /** Grid covers and detail headers. */
    Medium(600),
    /** The players, where artwork is the subject. */
    Large(1200),
}
