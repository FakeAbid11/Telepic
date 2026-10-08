package com.telepix.data.backup.hash

import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Pure, framework-free streaming SHA-256. Hashes an [InputStream] incrementally so a large video is
 * never loaded into memory, checking cancellation between blocks. Produces lowercase hex, the one
 * representation Telepix persists everywhere (queue, manifest, tests).
 */
object ContentHashing {

    const val HEX_ALPHABET = "0123456789abcdef"
    private const val BUFFER_SIZE = 128 * 1024

    /** Hex digest + the number of bytes actually read. */
    data class Streamed(val sha256: String, val bytesRead: Long)

    suspend fun sha256(input: InputStream): Streamed {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            total += read
        }
        return Streamed(digest.digest().toHex(), total)
    }

    fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append(HEX_ALPHABET[v ushr 4]).append(HEX_ALPHABET[v and 0x0F])
        }
        return out.toString()
    }

    fun isLowercaseHexSha256(value: String): Boolean =
        value.length == 64 && value.all { it in HEX_ALPHABET }
}
