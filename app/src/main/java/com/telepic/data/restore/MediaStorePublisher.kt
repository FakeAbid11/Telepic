package com.telepic.data.restore

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import com.telepic.diagnostics.TelepicLog
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Result of publishing a staged file into public media storage. Never claims success falsely. */
sealed interface PublishResult {
    data class Inserted(val uri: String) : PublishResult
    /** The device rejected the write for space reasons. */
    data object NoSpace : PublishResult
    /** Any other failure (permission, provider) — the item is NOT considered restored. */
    data object Failed : PublishResult
}

/** Seam so MediaStore publication (a device-bound write) can be faked in tests. */
interface MediaStorePublisher {
    suspend fun publish(sourcePath: String, mimeType: String?, displayName: String?): PublishResult
}

/**
 * Publishes a downloaded original into public media storage through the supported Android APIs:
 * a [ContentResolver] insert with the correct collection + MIME + display name (and, on API 29+, a
 * scoped `Pictures/Movies/Telepic` relative path), then a streamed copy of the bytes — never a
 * whole-file `readBytes()`. It writes a NEW entry and lets the provider pick a unique name, so an
 * unrelated existing file is never overwritten. A partial write is abandoned and reported honestly.
 */
class AndroidMediaStorePublisher(context: Context) : MediaStorePublisher {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    /**
     * An already-published copy of the same file, or null.
     *
     * Identity is (display name, MIME type, byte size) inside the same collection. Telegram's
     * re-encoding is deterministic, so restoring the same cloud item twice produces the same name and
     * the same byte count — which makes this a reliable duplicate signal without a schema change.
     * `RELATIVE_PATH` is deliberately not part of the match: the provider normalises it with a
     * trailing slash, so a literal comparison against [relativeDir] would silently never match.
     */
    private fun findPublished(collection: Uri, displayName: String, mime: String, size: Long): Uri? =
        runCatching {
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?" +
                " AND ${MediaStore.MediaColumns.SIZE} = ?" +
                " AND ${MediaStore.MediaColumns.MIME_TYPE} = ?"
            val args = arrayOf(displayName, size.toString(), mime)
            resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
                }
        }.getOrNull()

    override suspend fun publish(sourcePath: String, mimeType: String?, displayName: String?): PublishResult =
        withContext(Dispatchers.IO) {
            val scope: CoroutineScope = this
            val source = File(sourcePath)
            if (!source.exists() || source.length() <= 0L) return@withContext PublishResult.Failed

            // Fail honestly BEFORE inserting a row when the destination volume plainly cannot hold the
            // file; otherwise a full disk would surface as a generic PUBLISH_FAILED and leave a stray
            // pending row behind. Unmeasurable volumes never block the write (the provider has the
            // final say), so this can only ever turn a certain failure into a clearer one.
            if (!hasRoomFor(source.length())) return@withContext PublishResult.NoSpace

            val mime = mimeType?.takeIf { it.isNotBlank() } ?: guessMime(displayName)
                ?: guessMime(source.name) ?: "application/octet-stream"
            val collection = collectionFor(mime)
            val name = displayName ?: source.name
            // Already published: report the existing copy rather than adding a second one. "Save to
            // device" is offered for any ready cloud item, so a repeat tap published the same bytes
            // twice. `RestoreResult.Restored.restoredContentHash` was documented as the dedup key for
            // exactly this and is read by nothing, and recording it would need a new column and a
            // schema migration — so the check is made against the provider, before the insert.
            findPublished(collection, name, mime, source.length())?.let {
                return@withContext PublishResult.Inserted(it.toString())
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.SIZE, source.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir(mime))
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val target: Uri = try {
                resolver.insert(collection, values) ?: return@withContext PublishResult.Failed
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                TelepicLog.warn("MediaPublish", "publishing the restored item failed", throwable)
                return@withContext PublishResult.Failed
            }

            try {
                val expected = source.length()
                val written = resolver.openOutputStream(target)?.use { out ->
                    source.inputStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        var read = input.read(buffer)
                        while (read >= 0) {
                            scope.ensureActive()
                            out.write(buffer, 0, read)
                            total += read
                            read = input.read(buffer)
                        }
                        out.flush()
                        total
                    }
                } ?: throw IOException("Could not open the destination for writing")

                // Only a complete byte-for-byte copy counts; a short write is not a restoration.
                if (written != expected) throw IOException("Incomplete copy ($written of $expected bytes)")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    // Confirm the row actually flipped out of pending before declaring success; a 0-row
                    // update would leave an orphaned, invisible pending entry while we claim "saved".
                    val updated = resolver.update(target, values, null, null)
                    if (updated != 1) throw IOException("Provider did not finalize the media row")
                }
                PublishResult.Inserted(target.toString())
            } catch (throwable: Throwable) {
                // Abandon the partial entry; never report a half-written file as restored.
                runCatching { resolver.delete(target, null, null) }
                // A cancelled restore is not a failed restore: the caller's scope is going away and
                // must see the cancellation rather than a PUBLISH_FAILED it would retry.
                if (throwable is CancellationException) throw throwable
                PublishResult.Failed
            }
        }

    private fun collectionFor(mime: String): Uri = when {
        mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    private fun relativeDir(mime: String): String =
        if (mime.startsWith("video/")) "Movies/Telepic/" else "Pictures/Telepic/"

    private fun guessMime(name: String?): String? = name?.substringAfterLast('.', "")?.lowercase(Locale.US)?.let { ext ->
        when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "heic" -> "image/heic"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            else -> null
        }
    }

    /**
     * Best-effort free-space check on the volume published media lands on. Returns `true` when the
     * volume cannot be measured, so an unreadable [StatFs] never blocks a write we cannot judge.
     */
    @Suppress("DEPRECATION")
    private fun hasRoomFor(bytes: Long): Boolean = try {
        val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
        stat.availableBytes > bytes + SPACE_HEADROOM_BYTES
    } catch (throwable: Throwable) {
        // Best-effort by design: an unreadable StatFs must not block a write we cannot judge. Debug
        // because it is an expected outcome on some volumes, not a failure of the restore.
        TelepicLog.debug("MediaPublish", "free space could not be measured; allowing the write", throwable)
        true
    }

    private companion object {
        /** Keep a little slack so the write itself is not the thing that hits the wall. */
        const val SPACE_HEADROOM_BYTES = 1L * 1024 * 1024
    }
}
