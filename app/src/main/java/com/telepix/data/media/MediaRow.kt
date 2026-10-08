package com.telepix.data.media

/**
 * A minimal, testable view over one MediaStore result row.
 *
 * The mapper depends on this instead of a raw [android.database.Cursor], so MediaStore column
 * access never leaks into the domain mapping and can be exercised with a simple fake in tests.
 */
interface MediaRow {
    fun hasColumn(name: String): Boolean
    fun string(name: String): String?
    fun long(name: String): Long?
    fun int(name: String): Int?
}
