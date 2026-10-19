package com.telepic.data.media

import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Pins the multi-owner contract of the shared observer, which is the whole reason registration is
 * keyed by owner. Without it the old single-callback watcher had two bugs: a second owner's callback
 * was dropped by the "already started" early-return, and the first owner to stop unregistered the
 * observer out from under the rest.
 *
 * Driven through Robolectric's real `ContentResolver`, so this exercises [AndroidMediaChangeWatcher]
 * itself rather than a fake that merely restates the map semantics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidMediaChangeWatcherTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resolver get() = context.contentResolver
    private val imagesUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    /** A MediaStore change lands, then the main looper drains the observer's posted dispatch. */
    private fun fireChange() {
        resolver.notifyChange(imagesUri, null)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun `a change notifies every owner and stopping one leaves the others`() {
        val watcher = AndroidMediaChangeWatcher(context)
        val ownerA = Any()
        val ownerB = Any()
        var callsA = 0
        var callsB = 0

        watcher.start(ownerA) { callsA++ }
        watcher.start(ownerB) { callsB++ }

        fireChange()
        assertEquals(1, callsA)
        assertEquals(1, callsB)

        // The first owner leaving must not silence the owner still watching.
        watcher.stop(ownerA)
        fireChange()
        assertEquals(1, callsA)
        assertEquals(2, callsB)
    }

    @Test
    fun `the shared observer is released only when the last owner stops`() {
        val watcher = AndroidMediaChangeWatcher(context)
        val ownerA = Any()
        val ownerB = Any()

        watcher.start(ownerA) { }
        watcher.start(ownerB) { }
        assertTrue(shadowOf(resolver).getContentObservers(imagesUri).isNotEmpty())

        // One owner remains, so the observer must stay registered.
        watcher.stop(ownerA)
        assertTrue(shadowOf(resolver).getContentObservers(imagesUri).isNotEmpty())

        // Last owner gone: the shared observer is now released.
        watcher.stop(ownerB)
        assertTrue(shadowOf(resolver).getContentObservers(imagesUri).isEmpty())
    }

    @Test
    fun `registering the same owner again replaces its callback`() {
        val watcher = AndroidMediaChangeWatcher(context)
        val owner = Any()
        var replacement = 0
        var replaced = 0

        watcher.start(owner) { replaced++ }
        watcher.start(owner) { replacement++ }
        fireChange()

        assertEquals(0, replaced)
        assertEquals(1, replacement)
    }
}
