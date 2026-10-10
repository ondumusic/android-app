package com.ondumusic.player.ui.player

import kotlin.math.max

/**
 * `m:ss`, or `h:mm:ss` once a track runs past the hour — a long mix or an audiobook
 * chapter otherwise reads as "95:12". Partial seconds are dropped, so elapsed time ticks
 * over when a whole second has actually passed.
 */
fun formatPlaybackTime(seconds: Double): String {
    val total = if (seconds.isFinite()) max(0L, seconds.toLong()) else 0L
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
}

/**
 * What is left of the track, in whole seconds taken from whole seconds, so elapsed and
 * remaining always add up to the duration the track lists show. Subtracting first and
 * then flooring leaves the pair a second short for most of every second.
 */
fun remainingSeconds(position: Double, duration: Double): Double {
    if (!duration.isFinite() || duration <= 0) return 0.0
    val elapsed = if (position.isFinite()) max(0L, position.toLong()) else 0L
    return max(0L, duration.toLong() - elapsed).toDouble()
}

/**
 * [com.ondumusic.player.player.PlayerService.skipTo] takes a position in play order, the
 * same index space as `currentIndex` — not an index into the raw queue, which differs once
 * shuffle is on. These turn a row's offset within its section into that position.
 */
object QueuePositions {
    /** [offset] counts from the track that plays next. */
    fun upNext(currentIndex: Int, offset: Int): Int = currentIndex + 1 + offset

    /** [offset] counts back from the track that played last, matching `State.history`. */
    fun history(currentIndex: Int, offset: Int): Int = currentIndex - 1 - offset

    /**
     * A drag reports where the row ended up; `moveQueueItems` wants an insertion point in
     * the list as it was before the move. Moving down, that point is past the row the
     * dragged one now sits after.
     */
    fun moveDestination(from: Int, to: Int): Int = if (to > from) to + 1 else to
}
