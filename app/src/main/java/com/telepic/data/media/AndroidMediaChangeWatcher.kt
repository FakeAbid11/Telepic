package com.telepic.data.media

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore

/**
 * [MediaChangeWatcher] backed by a single [ContentObserver] shared by every registered owner.
 *
 * One observer is the right shape here: the OS charges one registration per URI, and two owners
 * watching the same library have no reason to hold two. The earlier one-callback design could not
 * share it safely, and had two failure modes that owner-keyed registration removes:
 *
 *  1. `start` early-returned once an observer existed, so a second owner's callback was silently
 *     dropped and never fired.
 *  2. `stop` unregistered unconditionally, so the first owner to leave tore the observer out from
 *     under every owner still watching — their callbacks simply went quiet.
 *
 * Keyed registration makes the observer's lifetime exactly the set of live owners: it is created on
 * the 0→1 transition and unregistered only on the 1→0 transition.
 *
 * Thread-safety: this relies on main-thread confinement. [start]/[stop] are called from ViewModel
 * `init`/`onCleared` (main thread) and `onChange` is delivered through a main-looper [Handler], so
 * the map is only ever touched on one thread and needs no lock. That is the simplest correct choice
 * for the actual call sites; a mutex would add ceremony without changing any observable behaviour.
 */
class AndroidMediaChangeWatcher(context: Context) : MediaChangeWatcher {

    private val contentResolver = context.applicationContext.contentResolver
    private val callbacks = LinkedHashMap<Any, () -> Unit>()
    private var observer: ContentObserver? = null

    override fun start(owner: Any, callback: () -> Unit) {
        callbacks[owner] = callback
        // Register the shared observer only on the first owner (0→1).
        if (observer != null) return
        val created = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                // Snapshot before dispatch: a callback may stop or re-register an owner, and
                // mutating the live map mid-iteration would either skip or double-invoke an owner.
                callbacks.values.toList().forEach { it() }
            }
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

    override fun stop(owner: Any) {
        callbacks.remove(owner)
        // Release the shared observer only once no owner remains (1→0).
        if (callbacks.isNotEmpty()) return
        observer?.let { contentResolver.unregisterContentObserver(it) }
        observer = null
    }
}
