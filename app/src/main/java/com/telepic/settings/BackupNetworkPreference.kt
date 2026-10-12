package com.telepic.settings

/**
 * Which networks the backup engine is allowed to upload over.
 *
 * [WIFI_ONLY] is enforced by WorkManager (`NetworkType.UNMETERED`) rather than inside the worker.
 * That distinction matters: WorkManager simply does not *start* the run until an unmetered network is
 * available, so the queue stays `QUEUED` and nothing is ever marked failed for a condition the user
 * deliberately chose. Enforcing it inside the worker would burn an attempt and a retry slot every time
 * the device happened to be on mobile data.
 */
enum class BackupNetworkPreference {
    /** Upload on any connection the system reports as connected — mobile data included. */
    ANY_NETWORK,

    /** Upload only on unmetered connections (Wi-Fi, and ethernet where the device has it). */
    WIFI_ONLY,
    ;

    companion object {
        /** Telepic backs up on whatever connection is available unless told otherwise. */
        val Default: BackupNetworkPreference = ANY_NETWORK

        /**
         * Safe parse used when reading a persisted value; unknown/blank values fall back to
         * [Default] instead of throwing, matching [ThemeMode.fromKey].
         */
        fun fromKey(key: String?): BackupNetworkPreference =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: Default
    }
}
