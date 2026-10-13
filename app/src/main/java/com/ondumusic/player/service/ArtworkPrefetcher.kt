package com.ondumusic.player.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Fetches artwork after a sync so the lists find it on disk instead of downloading each
 * cover as its row scrolls in. A first sync hands over the whole library at once, so only
 * a couple go at a time: the covers on screen share the same connections to the server.
 */
class ArtworkPrefetcher(
    scope: CoroutineScope,
    parallel: Int = 2,
    private val fetch: suspend (String) -> Unit,
) {
    private val queue = Channel<String>(Channel.UNLIMITED)

    init {
        repeat(parallel) {
            scope.launch {
                for (url in queue) {
                    try {
                        fetch(url)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // A cover that can't be fetched now loads when its row shows instead.
                    }
                }
            }
        }
    }

    fun prefetch(urls: List<String>) = urls.forEach(queue::trySend)

    /** Drops what hasn't started, for a sign-out: those covers belong to the old library. */
    fun clear() {
        while (queue.tryReceive().isSuccess) Unit
    }
}
