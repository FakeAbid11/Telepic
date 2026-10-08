package com.telepix

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.telepix.di.AppContainer
import com.telepix.di.DefaultAppContainer

/**
 * Application entrypoint. Owns the manual [AppContainer] and configures the app-wide Coil
 * [ImageLoader] so the Photos grid can render both image and video thumbnails from content
 * URIs, thumbnail-first.
 */
class TelepixApplication : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
}
