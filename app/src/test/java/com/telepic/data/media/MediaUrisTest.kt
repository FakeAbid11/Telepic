package com.telepic.data.media

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [requireOriginalIfPermitted] is the difference between reading a photo's embedded GPS and reading a
 * silently redacted stream on API 29+. These pin the version gate and the fallback that keeps a
 * non-MediaStore URI readable.
 */
@RunWith(RobolectricTestRunner::class)
class MediaUrisTest {

    private val uri: Uri = Uri.parse("content://media/external/images/media/42")

    private val resolver
        get() = ApplicationProvider.getApplicationContext<Context>().contentResolver

    @Test
    @Config(sdk = [29])
    fun `on Android 10 and above the original is requested`() {
        val result = resolver.requireOriginalIfPermitted(uri)
        assertEquals("1", result.getQueryParameter("requireOriginal"))
    }

    @Test
    @Config(sdk = [28])
    fun `below Android 10 nothing is redacted so the uri is returned untouched`() {
        assertSame(uri, resolver.requireOriginalIfPermitted(uri))
    }

    @Test
    @Config(sdk = [29])
    fun `a uri the platform cannot re-open falls back to the original`() {
        val result = resolver.requireOriginalIfPermitted(uri) { _ ->
            throw IllegalArgumentException("not a MediaStore item")
        }
        assertSame(uri, result)
    }
}
