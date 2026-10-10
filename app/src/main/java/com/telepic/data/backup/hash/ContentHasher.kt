package com.telepic.data.backup.hash

import android.content.ContentResolver
import android.net.Uri
import com.telepic.domain.backup.ContentHashResult
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Thrown when the content changed while it was being hashed, so a stale/partial hash is discarded. */
class ContentChangedException : IOException("Content changed while hashing")

/** Supplies the bytes to hash. Abstracted so hashing is testable without a real MediaStore. */
fun interface ContentStreamSource {
    /** Returns a fresh stream positioned at the start, or throws if the source is unreadable/gone. */
    fun open(): InputStream
}

/**
 * Computes a content hash for a local item. Recognition uses SHA-256 of the *file bytes* — never
 * decoded frames, thumbnails or first video frames — so identical bytes always yield an identical
 * hash regardless of filename, id or MIME type. Runs on [Dispatchers.IO]; never blocks the main
 * thread.
 */
interface ContentHasher {
    suspend fun hash(uri: Uri): ContentHashResult
    suspend fun hashSize(uri: Uri): Long

    /** Whether the content at [uri] is currently readable (permission granted, not deleted). */
    suspend fun isReadable(uri: Uri): Boolean
}

/** [ContentHasher] over a [ContentResolver], streaming the original bytes. */
class AndroidContentHasher(
    private val contentResolver: ContentResolver,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ContentHasher {

    override suspend fun hash(uri: Uri): ContentHashResult = withContext(dispatcher) {
        val source = ContentStreamSource {
            contentResolver.openInputStream(uri) ?: throw IOException("Media is no longer readable")
        }
        hashContent(source, expectedSize = null)
    }

    override suspend fun hashSize(uri: Uri): Long = withContext(dispatcher) {
        contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.SIZE)
                    if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else -1L
                } else {
                    -1L
                }
            } ?: -1L
    }

    override suspend fun isReadable(uri: Uri): Boolean = withContext(dispatcher) {
        try {
            contentResolver.openInputStream(uri)?.close()
            true
        } catch (_: Throwable) {
            false
        }
    }
}

/**
 * Hashes bytes from a [ContentStreamSource], optionally re-verifying the byte count afterward so a
 * source that changed mid-stream yields [ContentChangedException] rather than a stale hash. Shared
 * by the Android and test implementations.
 */
suspend fun hashContent(
    source: ContentStreamSource,
    expectedSize: Long?,
): ContentHashResult {
    val first = source.open().use { ContentHashing.sha256(it) }
    if (expectedSize != null && expectedSize >= 0 && first.bytesRead != expectedSize) {
        throw ContentChangedException()
    }
    return ContentHashResult(first.sha256, first.bytesRead)
}
