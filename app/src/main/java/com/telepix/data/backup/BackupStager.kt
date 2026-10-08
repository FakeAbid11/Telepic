package com.telepix.data.backup

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Copies a MediaStore content URI into an app-private staging file for upload, streaming in fixed
 * chunks so a large video is never held fully in memory (memory-safety rule), and cleans the copy
 * up afterwards. It never writes outside the app's private storage and never mutates the original.
 */
class BackupStager(
    private val contentResolver: ContentResolver,
    private val stagingDir: File,
) {

    /**
     * Stage the item at [contentUri]. Returns the staged file, or null when the source is gone or
     * unreadable (the caller turns that into a permanent failure). Throws nothing on missing media.
     */
    suspend fun stage(localMediaId: String, contentUri: String): StagedFile? = withContext(Dispatchers.IO) {
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
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                }
            } ?: return@withContext null
            StagedFile(target)
        } catch (throwable: Throwable) {
            target.delete()
            null
        }
    }

    private fun queryRowCount(uri: Uri): Boolean = try {
        val cursor: Cursor? = contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns._ID), null, null, null)
        cursor?.use { it.count > 0 } ?: false
    } catch (throwable: Throwable) {
        false
    }

    /** A staged file handle; [cleanup] must run in a finally block after every upload attempt. */
    class StagedFile(val file: File) {
        val path: String = file.absolutePath
        fun cleanup() {
            runCatching { file.delete() }
        }
    }

    private companion object {
        const val STREAM_BUFFER_BYTES = 64 * 1024
    }
}
