package com.ondumusic.player

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.decode.DataSource
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.transitionFactory
import coil3.transition.CrossfadeTransition
import coil3.transition.Transition
import coil3.transition.TransitionTarget

class OnduApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /** Artwork shares the app's HTTP client and keeps a disk cache, so covers survive offline. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("artwork")).maxSizeBytes(512L * 1024 * 1024).build() }
            .transitionFactory(NetworkCrossfade)
            .build()
}

/**
 * Fades in only a cover that had to be downloaded. One already on the device appears at
 * once, as it does on iOS: fading those made a list scrolled back through look like it
 * was loading everything again.
 */
private object NetworkCrossfade : Transition.Factory {
    private val crossfade = CrossfadeTransition.Factory()

    override fun create(target: TransitionTarget, result: ImageResult): Transition =
        if (result is SuccessResult && result.dataSource == DataSource.NETWORK) crossfade.create(target, result)
        else Transition.Factory.NONE.create(target, result)
}
