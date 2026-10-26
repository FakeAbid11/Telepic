package com.telepic.data.backup

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import com.telepic.diagnostics.TelepicLog
import java.io.File
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
interface BackupStager {
    /**
     * Stage the item at [contentUri]. Returns the staged file, or null when the source is gone or
     * unreadable (the caller turns that into a permanent failure). Missing media never throws; only
     * coroutine cancellation propagates, so a stopped worker abandons the copy.
     */
    suspend fun stage(localMediaId: String, contentUri: String): StagedFile?

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

    override suspend fun stage(localMediaId: String, contentUri: String): StagedFile? = withContext(Dispatchers.IO) {
        stagingDir.mkdirs()
        val uri = Uri.parse(contentUri)
        // Confirm the source still exists and is readable before streaming.
        val exists = queryRowCount(uri)
        if (!exists) return@withContext null
        val target = File(stagingDir, "staging_$localMediaId.tmp")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    while (true) {
                        // Blocking stream IO is not cancellable by itself: checkpoint per block so a
                        // stopped worker abandons a multi-gigabyte copy instead of finishing it.
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            } ?: return@withContext null
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
            // Source vanished or became unreadable mid-copy. The caller turns this null into a
            // permanent "local media is no longer available" failure, which is indistinguishable from
            // a provider fault without this line.
            TelepicLog.warn("BackupStager", "staging failed; the source could not be copied", throwable)
            target.delete()
            null
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
        // Not silent: `false` here makes `stage` return null, which the queue records as a *permanent*
        // "local media is no longer available" failure. A provider error (a revoked or limited media
        // grant, a transient provider fault) looks identical to a deleted file without this line, so a
        // recoverable condition was being reported as an unrecoverable one with nothing to diagnose.
        TelepicLog.warn("BackupStager", "could not confirm the source row before staging", throwable)
        false
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
