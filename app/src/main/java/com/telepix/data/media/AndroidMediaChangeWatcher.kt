package com.telepix.data.media

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore

/**
 * [MediaChangeWatcher] backed by a single [ContentObserver] registered on both the images and
 * videos MediaStore collections. [start] is idempotent and [stop] fully unregisters, so the
 * observer is never leaked or duplicated (the ViewModel owns its lifecycle).
 */
class AndroidMediaChangeWatcher(context: Context) : MediaChangeWatcher {

    private val contentResolver = context.applicationContext.contentResolver
    private var observer: ContentObserver? = null

    override fun start(callback: () -> Unit) {
        if (observer != null) return
        val created = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = callback()
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            created,
        )
        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            created,
        )
        observer = created
    }

    override fun stop() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        observer = null
    }
}
