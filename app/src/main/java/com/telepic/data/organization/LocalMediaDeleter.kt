package com.telepic.data.organization

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.Context
import android.content.IntentSender
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
     * The system requires user consent: the UI must launch [intentSender] and only treat the items
     * in [consentMedia] as deleted if the user accepts (RESULT_OK). Deletion is never implied from
     * this state alone.
     *
     * [deletedIds] are already gone (an API 29 provider can delete some items before refusing one);
     * [failedIds] could not be deleted at all; [remainingMedia] has not been attempted yet and must
     * be retried after consent. The caller may forget only [consentMedia] when the result is OK.
     */
    data class NeedsConsent(
        val intentSender: IntentSender,
        val deletedIds: List<Long> = emptyList(),
        val failedIds: List<Long> = emptyList(),
        val consentMedia: List<LocalMedia> = emptyList(),
        val remainingMedia: List<LocalMedia> = emptyList(),
    ) : DeleteRequest

    /**
     * Some files were deleted and some were not, without a consent step. The caller must forget only
     * [deletedIds] and keep [failedIds] available for retry.
     */
    data class PartiallyDeleted(
        val deletedIds: List<Long>,
        val failedIds: List<Long>,
    ) : DeleteRequest

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
     * API 30+ can cover the whole list with one consent prompt. API 29 can only carry one
     * [RecoverableSecurityException] at a time, so a partial delete returns
     * [DeleteRequest.NeedsConsent] with the already-deleted ids and the unattempted remainder; the
     * caller resumes the remainder after the consent result.
     *
     * [DeleteRequest.Deleted] means **every** requested file was removed. A mixed result is never
     * reported as success: it is either [DeleteRequest.PartiallyDeleted] or
     * [DeleteRequest.NeedsConsent] with the exact split.
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
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                        // One consent prompt covers the whole list — the platform accepts a list of uris here.
                        val pending = MediaStore.createDeleteRequest(resolver, media.map { it.contentUri })
                        DeleteRequest.NeedsConsent(pending.intentSender, consentMedia = media)
                    }
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> deleteWithRecoverableConsent(media)
                    else -> deleteDirectly(media)
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

    /** API 26–28: the app holds the legacy write permission; record exactly which deletes succeeded. */
    private fun deleteDirectly(media: List<LocalMedia>): DeleteRequest {
        val deleted = ArrayList<Long>(media.size)
        val failed = ArrayList<Long>()
        for (item in media) {
            try {
                if (resolver.delete(item.contentUri, null, null) > 0) deleted += item.id else failed += item.id
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                TelepicLog.warn("MediaDelete", "direct delete failed for mediaId=${item.id}", throwable)
                failed += item.id
            }
        }
        return completedOutcome(deleted, failed)
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
        val deleted = ArrayList<Long>(media.size)
        val failed = ArrayList<Long>()
        for (index in media.indices) {
            val item = media[index]
            try {
                if (resolver.delete(item.contentUri, null, null) > 0) deleted += item.id else failed += item.id
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (recoverable: RecoverableSecurityException) {
                val intentSender = recoverable.userAction?.actionIntent?.intentSender
                if (intentSender == null) {
                    TelepicLog.warn(
                        "MediaDelete",
                        "the provider refused the delete without a consent prompt for mediaId=${item.id}",
                        recoverable,
                    )
                    failed += item.id
                    continue
                }
                // The prompt covers only this item. Return the exact split; after the user accepts,
                // the caller resumes the unattempted remainder and asks again if needed.
                return DeleteRequest.NeedsConsent(
                    intentSender = intentSender,
                    deletedIds = deleted.toList(),
                    failedIds = failed.toList(),
                    consentMedia = listOf(item),
                    remainingMedia = media.drop(index + 1),
                )
            } catch (throwable: Throwable) {
                TelepicLog.warn("MediaDelete", "recoverable-consent delete failed for mediaId=${item.id}", throwable)
                failed += item.id
            }
        }
        return completedOutcome(deleted, failed)
    }

    /**
     * The one place the all/none/some mapping is decided. Empty [deleted] is a total failure; empty
     * [failed] is complete success; anything else is a partial result the caller must split.
     */
    private fun completedOutcome(deleted: List<Long>, failed: List<Long>): DeleteRequest = when {
        deleted.isEmpty() -> DeleteRequest.Failed
        failed.isEmpty() -> DeleteRequest.Deleted
        else -> DeleteRequest.PartiallyDeleted(deleted, failed)
    }
}
