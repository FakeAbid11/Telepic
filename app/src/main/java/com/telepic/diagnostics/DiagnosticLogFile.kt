package com.telepic.diagnostics

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors

/**
 * Bounded, local-only diagnostic log for warn/error events.
 *
 * Only the curated [TelepicLog] messages are written; debug lines and raw TDLib payloads never
 * reach it, so no phone number, code, token, media path or GPS coordinate is recorded. The file is
 * private to the app and is shared only when the user taps "Share diagnostics" in Settings.
 *
 * Writes run on one daemon thread so a slow disk cannot block a caller. At [MAX_BYTES] the live file
 * is rotated to `.1` (one generation), keeping storage bounded without a logging service.
 */
object DiagnosticLogFile {

    private const val DIRECTORY = "diagnostics"
    private const val FILE_NAME = "telepic.log"
    private const val MAX_BYTES = 256L * 1024L
    private const val MAX_THROWABLE_CHARS = 8_192

    @Volatile
    private var logFile: File? = null

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "telepic-diagnostics").apply { isDaemon = true }
    }

    fun initialize(context: Context) {
        val directory = File(context.applicationContext.filesDir, DIRECTORY).apply { mkdirs() }
        logFile = File(directory, FILE_NAME)
        append("I", "Diagnostics", "diagnostic log initialized", null)
    }

    fun exists(): Boolean = logFile?.isFile == true

    /** A content URI for the current log, or null when nothing has been written yet. */
    fun shareUri(context: Context): Uri? {
        val file = logFile?.takeIf { it.isFile } ?: return null
        return runCatching {
            FileProvider.getUriForFile(
                context.applicationContext,
                "${context.applicationContext.packageName}.fileprovider",
                file,
            )
        }.getOrNull()
    }

    fun append(level: String, where: String, message: String, throwable: Throwable?) {
        val target = logFile ?: return
        val detail = throwable?.stackTraceToString()?.take(MAX_THROWABLE_CHARS)
        val line = buildString {
            append(Instant.now().toString())
            append(' ')
            append(level)
            append(' ')
            append(where)
            append(": ")
            append(message)
            if (detail != null) {
                append('\n')
                append(detail)
            }
            append('\n')
        }
        writer.execute { write(target, line) }
    }

    private fun write(target: File, line: String) {
        synchronized(this) {
            runCatching {
                if (target.length() >= MAX_BYTES) {
                    val previous = File(target.parentFile, "$FILE_NAME.1")
                    previous.delete()
                    target.renameTo(previous)
                }
                target.appendText(line)
            }
        }
    }
}
