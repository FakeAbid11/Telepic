package com.telepic.diagnostics

import android.content.Context
import android.util.Log
import com.telepic.BuildConfig

/**
 * The app's single logging seam. Telepic previously logged nothing at all, which made a
 * device-only failure (TDLib, MediaStore consent, ExoPlayer, a WorkManager stop) undiagnosable from
 * the outside: every such path degraded to a silent fallback and left no trace.
 *
 * Deliberately narrow: [warn] and [error] are for the fallbacks a user can never see working, and
 * [debug] is compiled-in but gated on [BuildConfig.DEBUG]. A logging seam this small is also the
 * place a real backend (Crashlytics/Play vitals) would be attached later, which is why call sites
 * go through it instead of calling [android.util.Log] directly.
 *
 * What must never be passed here: the Telegram `api_hash`, the TDLib database key, phone numbers,
 * access tokens, or absolute file paths of the user's media. Messages are short and about *which
 * subsystem* degraded; the throwable carries the technical detail.
 */
object TelepicLog {

    private const val TAG_PREFIX = "Telepic/"

    /**
     * Attach a bounded local file sink. Must be called once from Application.onCreate; without it
     * warn/error still go to logcat, but there is nothing for a user to share after a device-only
     * failure.
     */
    fun initialize(context: Context) {
        DiagnosticLogFile.initialize(context)
    }

    fun debug(where: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag(where), message)
    }

    /**
     * As [debug], with the throwable attached — still gated on [BuildConfig.DEBUG].
     *
     * For failures that are *expected per item* rather than exceptional: a corrupt file with no
     * readable EXIF, a volume whose free space cannot be measured. A stack trace is worth having when
     * diagnosing, but a `warn` per item would let a library full of them bury the real problems in a
     * release log. Kept as a separate overload rather than a defaulted parameter so a two-argument
     * call cannot become ambiguous.
     */
    fun debug(where: String, message: String, throwable: Throwable?) {
        if (BuildConfig.DEBUG) {
            if (throwable == null) Log.d(tag(where), message) else Log.d(tag(where), message, throwable)
        }
    }

    /**
     * A swallowed failure that a caller degraded to a fallback. This is the log line that makes the
     * difference between "the queue is empty" and "the queue is empty because staging failed".
     */
    fun warn(where: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.w(tag(where), message) else Log.w(tag(where), message, throwable)
        DiagnosticLogFile.append("W", where, message, throwable)
    }

    /** A failure the user is being asked to act on, or that ends an operation outright. */
    fun error(where: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.e(tag(where), message) else Log.e(tag(where), message, throwable)
        DiagnosticLogFile.append("E", where, message, throwable)
    }

    private fun tag(where: String): String = TAG_PREFIX + where
}
