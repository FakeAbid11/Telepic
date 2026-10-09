package com.telepix.data.restore

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.util.Locale
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
 * scoped `Pictures/Movies/Telepix` relative path), then a streamed copy of the bytes — never a
 * whole-file `readBytes()`. It writes a NEW entry and lets the provider pick a unique name, so an
 * unrelated existing file is never overwritten. A partial write is abandoned and reported honestly.
 */
class AndroidMediaStorePublisher(context: Context) : MediaStorePublisher {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override suspend fun publish(sourcePath: String, mimeType: String?, displayName: String?): PublishResult =
        withContext(Dispatchers.IO) {
            val scope: CoroutineScope = this
            val source = File(sourcePath)
            if (!source.exists() || source.length() <= 0L) return@withContext PublishResult.Failed

            val mime = mimeType?.takeIf { it.isNotBlank() } ?: guessMime(displayName)
                ?: guessMime(source.name) ?: "application/octet-stream"
            val collection = collectionFor(mime)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName ?: source.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.SIZE, source.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir(mime))
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val target: Uri = try {
                resolver.insert(collection, values) ?: return@withContext PublishResult.Failed
            } catch (_: Exception) {
                return@withContext PublishResult.Failed
            }

            try {
                resolver.openOutputStream(target)?.use { out ->
                    source.inputStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var read = input.read(buffer)
                        while (read >= 0) {
                            scope.ensureActive()
                            out.write(buffer, 0, read)
                            read = input.read(buffer)
                        }
                        out.flush()
                    }
                } ?: throw IOException("Could not open the destination for writing")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(target, values, null, null)
                }
                PublishResult.Inserted(target.toString())
            } catch (_: Throwable) {
                // Abandon the partial entry; never report a half-written file as restored.
                runCatching { resolver.delete(target, null, null) }
                PublishResult.Failed
            }
        }

    private fun collectionFor(mime: String): Uri = when {
        mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    private fun relativeDir(mime: String): String =
        if (mime.startsWith("video/")) "Movies/Telepix/" else "Pictures/Telepix/"

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
}
