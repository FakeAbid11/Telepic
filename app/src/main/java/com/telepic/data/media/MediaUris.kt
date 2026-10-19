package com.telepic.data.media

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.VisibleForTesting

/**
 * Returns a [Uri] whose stream preserves the original file's embedded location on API 29+.
 *
 * Since Android 10 the platform redacts location metadata from the stream a MediaStore [Uri] opens:
 * [ContentResolver.openInputStream] hands back bytes with the GPS tags stripped unless the caller
 * both holds `ACCESS_MEDIA_LOCATION` and asks for the original via [MediaStore.setRequireOriginal].
 * Without this decoration an EXIF GPS read silently returns null on a modern device even though the
 * coordinate is sitting in the file — the exact "the map is empty but the photos are geotagged"
 * symptom. Below API 29 there is no redaction, so the [uri] is returned untouched.
 *
 * The call is wrapped in [runCatching] because asking for the original is not universally valid: a
 * `file://` URI, a picker URI, or any non-MediaStore authority can fail the conversion (the platform
 * throws `IllegalArgumentException`/`UnsupportedOperationException`). Losing the whole read over a
 * URI that simply has no original to ask for would be worse than reading the redacted stream, so a
 * failure falls back to the original [uri]. The caller's `ACCESS_MEDIA_LOCATION` grant is what the
 * platform actually enforces; this helper only *asks*, and an item the app may not un-redact stays
 * redacted rather than throwing.
 */
internal fun ContentResolver.requireOriginalIfPermitted(uri: Uri): Uri =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
    } else {
        uri
    }

/**
 * The same decision as [requireOriginalIfPermitted], with the platform call injected.
 *
 * Only exists so the fallback branch is testable: the real [MediaStore.setRequireOriginal] merely
 * decorates the URI and never throws for a non-MediaStore URI, so no ordinary input can reach the
 * `getOrDefault` path from a test.
 */
@VisibleForTesting
internal fun ContentResolver.requireOriginalIfPermitted(uri: Uri, setOriginal: (Uri) -> Uri): Uri =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching { setOriginal(uri) }.getOrDefault(uri)
    } else {
        uri
    }
