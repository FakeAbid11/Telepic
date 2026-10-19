package com.telepic.telegram

import android.content.Context
import java.io.File

/**
 * Where TDLib keeps its state on disk.
 *
 * One owner for the path, because two places genuinely need it and must agree exactly: the session
 * manager (which hands the directory to TDLib) and the key provider (which has to know whether a
 * local session cache exists before it decides whether losing the key is harmless or fatal).
 *
 * `noBackupFilesDir` is deliberate on both counts: the TDLib database is encrypted with a key held
 * in the Android Keystore, and the Keystore key is **not** part of an Android backup — so a restored
 * copy of this directory would be a database nobody can open. Keeping it out of the backup set means
 * the app either has both halves or neither.
 */
object TelegramPaths {

    /** The directory name TDLib is given as its database directory. */
    const val DATABASE_DIR_NAME = "tdlib"

    /** TDLib's own database + downloaded-files directory. */
    fun databaseDir(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, DATABASE_DIR_NAME)
}
