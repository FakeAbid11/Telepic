package com.telepic.domain.media

import android.net.Uri
import androidx.compose.runtime.Immutable

/**
 * A **user-created** album: an explicit set of media the user put together, as opposed to [Album],
 * which is a MediaStore bucket the device's folders happen to define.
 *
 * The two are deliberately different types. A folder album's identity *is* the bucket, its contents
 * are whatever the filesystem currently holds, and it cannot be renamed or deleted from Telepic. A
 * custom album's identity is its Room row, its contents are a list of MediaStore ids the user chose,
 * and it exists only because they made it. Sharing one type would have meant every call site asking
 * "which kind is this?", which is exactly the bug this split prevents.
 *
 * Identity is [id], never [name] — so renaming never re-identifies the album. [coverUri] is the most
 * recently added member's content URI, and is null while the album is empty: an empty album shows a
 * placeholder rather than a fabricated cover.
 *
 * [Immutable] because [coverUri] is a `Uri`, which the compiler cannot prove immutable — the same
 * reason [Album] and [LocalMedia] carry it.
 */
@Immutable
data class CustomAlbum(
    val id: Long,
    val name: String,
    val count: Int,
    val coverUri: Uri?,
)
