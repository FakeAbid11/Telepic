package com.telepic.data.media

import android.database.Cursor

/**
 * Positions a newest-first paged query cursor before the requested [offset].
 *
 * The first page ([offset] 0) must NOT seek: a cursor is already positioned before its first row,
 * so the caller simply iterates with [Cursor.moveToNext]. Seeking to `offset - 1` at offset 0 would
 * ask for position -1, which is out of bounds and returns false — the exact bug that made the first
 * page read as an empty library on a real device. For a positive offset, [Cursor.moveToPosition]
 * lands just before the target row; an offset at or past the end legitimately returns false (end of
 * the stream). Callers only begin iterating when this returns true.
 */
internal fun Cursor.seekToPageStart(offset: Int): Boolean =
    offset <= 0 || moveToPosition(offset - 1)
