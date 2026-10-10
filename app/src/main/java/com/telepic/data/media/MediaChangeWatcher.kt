package com.telepic.data.media

/**
 * Detects local media library changes and invokes a callback, so the Photos data can be
 * refreshed without a full manual rescan.
 *
 * Abstracted behind an interface so the ViewModel can start/stop it in a lifecycle-safe way and
 * tests can substitute a fake. The Android implementation uses a [android.database.ContentObserver]
 * over both the images and videos collections.
 */
interface MediaChangeWatcher {
    /** Begin observing. Safe to call once per owner; a no-op if already started. */
    fun start(callback: () -> Unit)

    /** Stop observing and release resources. */
    fun stop()
}
