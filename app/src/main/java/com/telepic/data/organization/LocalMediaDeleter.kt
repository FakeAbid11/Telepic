package com.telepic.data.organization

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.telepic.diagnostics.TelepicLog
import com.telepic.domain.media.LocalMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    /**
     * The batch form, for "Delete forever" over a selection.
     *
     * One request covers every uri, because `MediaStore.createDeleteRequest` takes a list: asking the
     * user to confirm item by item would be N system dialogs for one action. [DeleteRequest.Deleted]
     * means **every** requested file was removed — a partial result is reported as
     * [DeleteRequest.Failed] rather than as a success for files that are still on the device, which
     * would have the caller forget rows for media it did not delete.
     *
     * An empty list is [DeleteRequest.Failed]: there is nothing to consent to and nothing to claim.
     */
    suspend fun requestDeleteAll(media: List<LocalMedia>): DeleteRequest
}

/**
 * Real deletion through the Android-sanctioned MediaStore APIs. On API 30+ it always asks the user to
 * confirm via the system dialog ([MediaStore.createDeleteRequest]); the app records nothing until the
 * consent result comes back OK (handled by the caller). On API 26–29 it attempts a direct delete and
 * reports [DeleteRequest.Failed] when the platform does not permit it — Telepic never silently
 * deletes and never implies a file was removed when only the app-level trash flag changed.
 *
 * The legacy per-file recoverable-consent flow (Android Q) is intentionally NOT claimed here; it is
 * documented as a known limitation rather than faked. `createDeleteRequest` is an API 30 addition —
 * binding the batch-consent path to Q looked like a one-character fix and was lint's `NewApi` error
 * instead: on Android 10 it would have thrown at runtime, which is worse than the honest failure.
 */
class MediaStoreLocalDeleter(context: Context) : LocalMediaDeleter {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    /**
     * The single-item form is the batch form with one element, so there is exactly one place that
     * decides what counts as success. Behaviour is unchanged: one uri, `rows > 0` is a deletion.
     */
    override suspend fun requestDelete(media: LocalMedia): DeleteRequest = requestDeleteAll(listOf(media))

    override suspend fun requestDeleteAll(media: List<LocalMedia>): DeleteRequest {
        if (media.isEmpty()) return DeleteRequest.Failed
        // MediaStore is a binder-backed provider: `createDeleteRequest` and `delete` are synchronous IPC,
        // so running them on the caller's dispatcher blocked the main thread (the UI calls this from
        // `viewModelScope`). Move the whole exchange to IO so a slow provider cannot jank or ANR.
        return withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // One consent prompt covers the whole list — the platform accepts a list of uris here.
                    val pending = MediaStore.createDeleteRequest(resolver, media.map { it.contentUri })
                    DeleteRequest.NeedsConsent(pending.intentSender)
                } else {
                    // Pre-R has no batch consent, so the files go one at a time and all of them must go for
                    // this to be reported as a success. A partial delete is Failed, never a success: see
                    // the contract on [LocalMediaDeleter.requestDeleteAll].
                    var deleted = 0
                    media.forEach { if (resolver.delete(it.contentUri, null, null) > 0) deleted++ }
                    if (deleted == media.size) DeleteRequest.Deleted else DeleteRequest.Failed
                }
            } catch (security: SecurityException) {
                // Includes `RecoverableSecurityException` below API 30 for media this app did not create:
                // the item stays in trash, and the reason now leaves a trace instead of vanishing.
                TelepicLog.warn("MediaDelete", "the provider refused the delete; the item stays in trash", security)
                DeleteRequest.Failed
            } catch (cancellation: CancellationException) {
                // Cancellation is control flow, not a failed delete: reporting Failed would leave the
                // caller believing the provider refused, when the caller's own scope simply went away.
                throw cancellation
            } catch (throwable: Throwable) {
                TelepicLog.error("MediaDelete", "delete request failed", throwable)
                DeleteRequest.Failed
            }
        }
    }
}
