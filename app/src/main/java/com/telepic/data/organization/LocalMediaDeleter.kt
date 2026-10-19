package com.telepic.data.organization

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
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
 * Real deletion through the Android-sanctioned MediaStore APIs.
 *
 * On API 30+ the app always asks the user to confirm via the system dialog
 * ([MediaStore.createDeleteRequest]) and records nothing until the consent result comes back OK
 * (handled by the caller). On API 29 the platform offers the same consent through the
 * [RecoverableSecurityException] it throws for media the app does not own — and that exception
 * carries the very `IntentSender` the system dialog is launched with, so the feature works there
 * too. On API 26–28 the app holds the legacy write permission and the delete is direct.
 *
 * The legacy per-file recoverable-consent flow used to be documented as "not implemented", which
 * made "Delete forever" silently do nothing on Android 10 — a supported OS version on which the
 * user could not delete anything at all.
 *
 * Telepic never silently deletes and never implies a file was removed when only the app-level trash
 * flag changed.
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
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    deleteWithRecoverableConsent(media)
                } else {
                    // Pre-R has no batch consent, so the files go one at a time and all of them must go for
                    // this to be reported as a success. A partial delete is Failed, never a success: see
                    // the contract on [LocalMediaDeleter.requestDeleteAll].
                    var deleted = 0
                    media.forEach { if (resolver.delete(it.contentUri, null, null) > 0) deleted++ }
                    if (deleted == media.size) DeleteRequest.Deleted else DeleteRequest.Failed
                }
            } catch (security: SecurityException) {
                // Any other refusal: the item stays in trash, and the reason now leaves a trace
                // instead of vanishing.
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

    /**
     * Android 10 (API 29): delete directly, and turn the provider's refusal into the system consent
     * prompt it carries.
     *
     * The provider refuses to delete media the app does not own and throws a
     * [RecoverableSecurityException] — whose `userAction` **is** the consent prompt the system
     * dialog is launched with. So the deletion still happens, with the user's confirmation, exactly
     * as API 30 does it through `createDeleteRequest`.
     *
     * Before this, API 29 fell into the plain delete path, every refusal became a failure, and
     * "Delete forever" silently did nothing on a supported OS version.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun deleteWithRecoverableConsent(media: List<LocalMedia>): DeleteRequest {
        var deleted = 0
        try {
            media.forEach { if (resolver.delete(it.contentUri, null, null) > 0) deleted++ }
        } catch (recoverable: RecoverableSecurityException) {
            val intentSender = recoverable.userAction?.actionIntent?.intentSender
            if (intentSender == null) {
                TelepicLog.warn(
                    "MediaDelete",
                    "the provider refused the delete without a consent prompt",
                    recoverable,
                )
                return DeleteRequest.Failed
            }
            return DeleteRequest.NeedsConsent(intentSender)
        }
        // A partial delete is Failed, never a success: see the contract on
        // [LocalMediaDeleter.requestDeleteAll].
        return if (deleted == media.size) DeleteRequest.Deleted else DeleteRequest.Failed
    }
}
