package com.ondumusic.player.service

/**
 * When to preload the artwork a sync brought in. A first sync preloads the whole
 * library's covers — hundreds of megabytes on a large one — which on mobile data is a bill
 * nobody asked for. Browsing loads covers as they come on screen anyway, so the preload is
 * what waits.
 */
class ArtworkPreload(
    private val prefetch: (List<String>) -> Unit,
    private val conditions: () -> Conditions,
) {
    /** [isDataSaver]: Data Saver is on and the network is metered, the only place it applies. */
    data class Conditions(val isMetered: Boolean, val isDataSaver: Boolean)

    enum class Decision {
        Now,
        /** Hold until the network isn't metered. */
        Later,
        /** Leave it to browsing. */
        Never,
    }

    private val deferred = mutableListOf<String>()

    fun preload(urls: List<String>) {
        val now = conditions()
        when (decision(urls.size, now.isMetered, now.isDataSaver)) {
            Decision.Now -> prefetch(urls)
            Decision.Later -> synchronized(deferred) { deferred += urls }
            Decision.Never -> Unit
        }
    }

    /** Called when the network stops being metered. */
    fun preloadDeferred() {
        val urls = synchronized(deferred) { deferred.toList().also { deferred.clear() } }
        if (urls.isNotEmpty()) preload(urls)
    }

    /** For a sign-out: those covers belong to the old library. */
    fun clear() = synchronized(deferred) { deferred.clear() }

    companion object {
        /** New albums from an everyday sync: a few megabytes at most, worth having instant. */
        const val SMALL_BATCH = 20

        fun decision(count: Int, isMetered: Boolean, isDataSaver: Boolean): Decision {
            // Data Saver is the user asking apps to hold back, so even a small batch waits for browsing.
            if (count <= SMALL_BATCH) return if (isDataSaver) Decision.Never else Decision.Now
            return if (isMetered || isDataSaver) Decision.Later else Decision.Now
        }
    }
}
