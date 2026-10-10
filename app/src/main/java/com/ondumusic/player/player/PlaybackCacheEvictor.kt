package com.ondumusic.player.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/**
 * Least-recently-played first, like Media3's own evictor, except the limit can change while
 * the cache is open — so lowering it in Settings frees the space now rather than at the
 * next launch. A limit of 0 means unlimited.
 */
@OptIn(UnstableApi::class)
class PlaybackCacheEvictor(limitBytes: Long) : CacheEvictor {
    private val spans = TreeSet<CacheSpan> { a, b ->
        val byTime = a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp)
        if (byTime != 0) byTime else a.compareTo(b)
    }
    private var size = 0L
    private var cache: Cache? = null

    @Volatile var limitBytes: Long = limitBytes
        set(value) {
            field = value
            cache?.let { evict(it, 0) }
        }

    override fun requiresCacheSpanTouches() = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        this.cache = cache
        if (length != androidx.media3.common.C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        this.cache = cache
        spans += span
        size += span.length
        evict(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        spans -= span
        size -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    private fun evict(cache: Cache, incoming: Long) {
        val limit = limitBytes
        if (limit <= 0) return
        while (size + incoming > limit && spans.isNotEmpty()) {
            cache.removeSpan(spans.first())
        }
    }
}
