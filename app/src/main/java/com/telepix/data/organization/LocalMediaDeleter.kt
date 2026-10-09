package com.telepix.data.organization

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.telepix.domain.media.LocalMedia

/** The outcome of asking to permanently delete a local file. Never claims success it did not get. */
sealed interface DeleteRequest {
    /**
     * The system requires user consent: the UI must launch [intentSender] and only treat the item as
     * deleted if the user accepts (RESULT_OK). Deletion is never implied from this state alone.
     */
    data class NeedsConsent(val intentSender: android.content.IntentSender) : DeleteRequest

    /** Deletion succeeded without a consent step (legacy path where the app already has rights). */
    data object Deleted : DeleteRequest

    /** Deletion could not be performed on this device/permission state — the item stays in trash. */
    data object Failed : DeleteRequest
}

/** Seam so permanent deletion (a device/permission-bound operation) can be faked in tests. */
interface LocalMediaDeleter {
    suspend fun requestDelete(media: LocalMedia): DeleteRequest
}

/**
 * Real deletion through the Android-sanctioned MediaStore APIs. On API 30+ it always asks the user to
 * confirm via the system dialog ([MediaStore.createDeleteRequest]); the app records nothing until the
 * consent result comes back OK (handled by the caller). On API 26–29 it attempts a direct delete and
 * reports [DeleteRequest.Failed] when the platform does not permit it — Telepix never silently
 * deletes and never implies a file was removed when only the app-level trash flag changed.
 *
 * The legacy per-file recoverable-consent flow (Android Q) is intentionally NOT claimed here; it is
 * documented as a known limitation rather than faked.
 */
class MediaStoreLocalDeleter(context: Context) : LocalMediaDeleter {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override suspend fun requestDelete(media: LocalMedia): DeleteRequest = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pending = MediaStore.createDeleteRequest(resolver, listOf(media.contentUri))
            DeleteRequest.NeedsConsent(pending.intentSender)
        } else {
            val rows = resolver.delete(media.contentUri, null, null)
            if (rows > 0) DeleteRequest.Deleted else DeleteRequest.Failed
        }
    } catch (_: SecurityException) {
        DeleteRequest.Failed
    } catch (_: Throwable) {
        DeleteRequest.Failed
    }
}
