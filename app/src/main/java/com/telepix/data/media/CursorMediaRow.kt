package com.telepix.data.media

import android.database.Cursor

/** [MediaRow] backed by a MediaStore [Cursor], with a small column-index cache. */
class CursorMediaRow(private val cursor: Cursor) : MediaRow {

    private val indexCache = HashMap<String, Int>()

    private fun indexOf(name: String): Int =
        indexCache.getOrPut(name) { cursor.getColumnIndex(name) }

    override fun hasColumn(name: String): Boolean = indexOf(name) >= 0

    override fun string(name: String): String? =
        if (hasColumn(name)) cursor.getString(indexOf(name)) else null

    override fun long(name: String): Long? =
        if (hasColumn(name) && !cursor.isNull(indexOf(name))) cursor.getLong(indexOf(name)) else null

    override fun int(name: String): Int? =
        if (hasColumn(name) && !cursor.isNull(indexOf(name))) cursor.getInt(indexOf(name)) else null
}
