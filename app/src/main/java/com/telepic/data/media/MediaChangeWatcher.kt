package com.telepic.data.media

/**
 * Detects local media library changes and invokes registered callbacks, so the Photos data can be
 * refreshed without a full manual rescan.
 *
 * Registration is keyed by [owner] because the watcher is process-wide while its consumers are not:
 * the underlying OS observer is one object, but several screens/view models can be alive at once and
 * each needs to know about changes. A single-callback `start`/`stop` cannot express that — a second
 * owner's callback has nowhere to go, and the first owner to stop would silence every other owner
 * still watching. Keying by owner gives each owner an independent lifetime while the shared observer
 * is released only once the last owner leaves.
 *
 * Abstracted behind an interface so the ViewModel can start/stop it in a lifecycle-safe way and
 * tests can substitute a fake. The Android implementation uses a [android.database.ContentObserver]
 * over both the images and videos collections.
 */
interface MediaChangeWatcher {
    /** Register [callback] for [owner]. Multiple owners may observe concurrently; registering the
     *  same owner again replaces its callback. */
    fun start(owner: Any, callback: () -> Unit)

    /** Unregister [owner]. The underlying observer is released only when the last owner stops. */
    fun stop(owner: Any)
}
