package com.telepix.onboarding

/**
 * The user's backup choice captured during onboarding (Screen 5).
 *
 * Phase 2 only persists this preference; the actual scanning/backup engine consumes it in
 * later phases (Phase 3/6). Modelled as an enum rather than arbitrary strings so state stays
 * type-safe and testable.
 */
enum class BackupPreference {
    /** Back up the whole photo library, subject to the user's backup settings. */
    BACKUP_ALL,

    /** Back up a specific folder/source chosen by the user. */
    SELECT_FOLDER,

    /** Do not configure backup yet; can be changed later from Settings. */
    NOT_NOW,
    ;

    companion object {
        /**
         * Failure-tolerant parse of a persisted key. Unknown/blank/null returns `null`
         * (meaning "no preference chosen yet") instead of throwing.
         */
        fun fromKey(key: String?): BackupPreference? =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
    }
}
