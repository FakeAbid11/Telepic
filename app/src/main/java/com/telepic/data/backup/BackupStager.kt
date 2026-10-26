package com.telepic.data.backup

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import com.telepic.diagnostics.TelepicLog
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Seam for copying a MediaStore content URI into an app-private staging file for upload, and cleaning
 * that copy up afterwards. Abstracted so the backup repository's staging lifecycle (including cleanup
 * on cancellation/failure) is unit-testable without a real ContentResolver.
 */
/** A staging failure caused by storage/provider state rather than a vanished source; transient. */
class BackupStagingException(message: String, cause: Throwable? = null) : IOException(message, cause)

interface BackupStager {
    /**
     * Stage the item at [contentUri]. Returns the staged file, or null when the source is genuinely
     * gone (the caller turns that into a permanent failure).
     *
     * Throws [BackupStagingException] for a storage/provider fault (full cache, refused write,
     * unreadable provider) so the queue can retry instead of claiming the media was deleted.
     * Coroutine cancellation propagates unchanged.
     *
     * [expectedBytes] is the source size when known, used for a free-space preflight.
     */
    suspend fun stage(localMediaId: String, contentUri: String, expectedBytes: Long? = null): StagedFile?

    /**
     * Delete the staged copies that no upload can still be using.
     *
     * A process killed between "staged the file" and "upload confirmed" leaves a full-size copy of
     * the media in the staging directory, and nothing else ever removed it: the per-attempt cleanup
     * runs in a `finally` that a kill skips. A 4 GB video left 4 GB behind, unbounded across
     * items.
     *
     * The default is a no-op so a test fake does not have to implement it.
     */
    suspend fun sweep() = Unit
}

/**
 * A staged file handle; [cleanup] must run in a finally block after every upload attempt.
 *
 * [width], [height] and [durationMs] are the source item's own dimensions, read from the same
 * MediaStore row that was copied. They exist because the queue row deliberately stores no
 * dimensions (`BackupQueueEntity` carries identity and state, not presentation metadata), so
 * without them the upload request was built with `null` and TDLib received 0×0 for every video —
 * leaving Telegram's own metadata to whatever the engine managed to probe. They are **nullable on
 * purpose**: a provider that cannot answer must degrade to "unknown" (exactly the old behaviour),
 * never to a fabricated `0` that reads as a real measurement, and never to a failed upload.
 */
class StagedFile(
    val file: File,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
) {
    val path: String = file.absolutePath
    fun cleanup() {
        runCatching { file.delete() }
    }
}

/**
 * Copies a MediaStore content URI into an app-private staging file for upload, streaming in fixed
 * chunks so a large video is never held fully in memory (memory-safety rule), and cleans the copy
 * up afterwards. It never writes outside the app's private storage and never mutates the original.
 */
class MediaStoreBackupStager(
    private val contentResolver: ContentResolver,
    private val stagingDir: File,
) : BackupStager {

    override suspend fun stage(
        localMediaId: String,
        contentUri: String,
        expectedBytes: Long?,
    ): StagedFile? = withContext(Dispatchers.IO) {
        if (!stagingDir.isDirectory && !stagingDir.mkdirs()) {
            throw BackupStagingException("The staging directory is unavailable")
        }
        val uri = Uri.parse(contentUri)
        // Confirm the source still exists. A provider error is transient, not proof of deletion.
        val exists = queryRowCount(uri)
        if (!exists) return@withContext null

        val required = expectedBytes?.takeIf { it > 0L }?.let { it + SPACE_HEADROOM_BYTES }
        if (required != null) {
            val free = runCatching { stagingDir.usableSpace }.getOrNull()
            if (free != null && free > 0L && free < required) {
                throw BackupStagingException(
                    "Not enough free space to stage the item ($free of $required bytes)",
                )
            }
        }

        val target = File(stagingDir, "staging_$localMediaId.tmp")
        // A FileNotFoundException here means the source vanished between the row check and the open.
        // A failure later (including one from the target stream) is a storage fault and is transient.
        val input = try {
            contentResolver.openInputStream(uri) ?: return@withContext null
        } catch (missing: FileNotFoundException) {
            return@withContext null
        }
        try {
            input.use { source ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    while (true) {
                        // Blocking stream IO is not cancellable by itself: checkpoint per block so a
                        // stopped worker abandons a multi-gigabyte copy instead of finishing it.
                        coroutineContext.ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            // Read the dimensions *after* the bytes are staged, so a metadata read that fails
            // cannot affect whether the copy exists — it only decides how much the upload request
            // can say about the media.
            val dimensions = readDimensions(uri)
            StagedFile(
                file = target,
                width = dimensions.width,
                height = dimensions.height,
                durationMs = dimensions.durationMs,
            )
        } catch (cancellation: CancellationException) {
            // Clean up, then let the caller see the cancel rather than a "source unreadable" null.
            target.delete()
            throw cancellation
        } catch (throwable: Throwable) {
            // ENOSPC, a cache eviction mid-copy, or a refused write: the caller must retry rather
            // than permanently mark the user's still-present media as missing.
            TelepicLog.warn("BackupStager", "staging failed for mediaId=$localMediaId", throwable)
            target.delete()
            throw BackupStagingException("The item could not be staged", throwable)
        }
    }

    /**
     * Delete the staged copies no upload can still be using.
     *
     * Only files older than [STALE_STAGING_AGE_MILLIS] are removed. The age is what makes this
     * safe to run at startup: a worker that is staging right now must not have its copy deleted
     * underneath it, and an upload that is still in flight must not lose the file TDLib is
     * reading. Six hours is far longer than any real upload and far shorter than the space an
     * orphaned copy of a large video wastes.
     */
    override suspend fun sweep() = withContext(Dispatchers.IO) {
        if (!stagingDir.isDirectory) return@withContext
        val files = stagingDir.listFiles() ?: return@withContext
        val cutoff = System.currentTimeMillis() - STALE_STAGING_AGE_MILLIS
        for (file in files) {
            if (!file.isFile) continue
            if (file.lastModified() >= cutoff) continue
            if (file.delete()) {
                TelepicLog.debug("BackupStager", "removed an orphaned staging copy")
            } else {
                TelepicLog.warn("BackupStager", "an orphaned staging copy could not be removed")
            }
        }
    }

    private fun queryRowCount(uri: Uri): Boolean = try {
        val cursor: Cursor? = contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
        cursor?.use { it.count > 0 } ?: false
    } catch (cancellation: CancellationException) {
        // Cancellation is control flow, not an unreadable source: rethrow it so a torn-down
        // staging attempt is not recorded as a permanent "media is gone" failure.
        throw cancellation
    } catch (throwable: Throwable) {
        // A provider error (revoked/limited grant, transient provider fault) is not proof that the
        // file was deleted. Surface it as transient so the queue retries.
        TelepicLog.warn("BackupStager", "could not confirm the source row before staging", throwable)
        throw BackupStagingException("The media source could not be read", throwable)
    }

    /**
     * The source item's own dimensions, for the upload request.
     *
     * Read defensively on **every** axis, because none of this may be allowed to affect the upload:
     *
     *  * The projection is a request, not a guarantee — `duration` is a video/audio column, so an
     *    images URI on an older provider may reject it outright, and the whole read is caught.
     *  * Each column is looked up by index, so a provider that silently drops one yields `null`
     *    instead of an `IllegalArgumentException`.
     *  * A non-positive value is reported as `null` ("unknown"), never as a measurement — Telegram's
     *    own 0 means "unknown" too, but a fabricated 0 in *our* request would claim the media has no
     *    dimensions rather than admitting we did not find out.
     *
     * The result is only ever an improvement on the previous behaviour (which always sent nothing):
     * on any failure the request falls back to exactly what it sent before.
     */
    private fun readDimensions(uri: Uri): StagedDimensions = try {
        val cursor: Cursor? = contentResolver.query(uri, DIMENSION_PROJECTION, null, null, null)
        cursor?.use { c ->
            if (!c.moveToFirst()) {
                StagedDimensions()
            } else {
                StagedDimensions(
                    width = c.positiveInt(MediaStore.MediaColumns.WIDTH),
                    height = c.positiveInt(MediaStore.MediaColumns.HEIGHT),
                    durationMs = c.positiveLong(MediaStore.Video.Media.DURATION),
                )
            }
        } ?: StagedDimensions()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        // Debug, not warn: a provider that will not answer this is an expected per-item outcome on
        // some devices, and the upload proceeds identically either way.
        TelepicLog.debug("BackupStager", "could not read the source dimensions; sending none", throwable)
        StagedDimensions()
    }

    /** `null` when the column is absent or the value is not a usable positive measurement. */
    private fun Cursor.positiveInt(column: String): Int? {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return null
        return getInt(index).takeIf { it > 0 }
    }

    /** `DURATION` is already milliseconds in MediaStore; `null` when absent or non-positive. */
    private fun Cursor.positiveLong(column: String): Long? {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return null
        return getLong(index).takeIf { it > 0 }
    }

    private companion object {
        const val STREAM_BUFFER_BYTES = 64 * 1024

        /** How long a staged copy is left alone before the sweep may remove it. */
        const val STALE_STAGING_AGE_MILLIS = 6L * 60 * 60 * 1000

        /** Slack above the item size so the write itself is not what hits the wall. */
        const val SPACE_HEADROOM_BYTES = 16L * 1024 * 1024

        /**
         * The columns [readDimensions] asks for. `DURATION` is the one that can be refused by an
         * images-only provider; [readDimensions] treats that as "unknown" rather than an error.
         *
         * Spelled `MediaStore.Video.Media.DURATION` rather than `MediaColumns.DURATION`: the latter
         * only exists from API 29 and `minSdk` here is 26, so referencing it would be a `NewApi`
         * lint error — and lint runs with `abortOnError = true`.
         */
        val DIMENSION_PROJECTION = arrayOf(
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.Video.Media.DURATION,
        )
    }
}

/** The dimensions a staging pass could determine, or all-null when it could not. */
private data class StagedDimensions(
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
)
