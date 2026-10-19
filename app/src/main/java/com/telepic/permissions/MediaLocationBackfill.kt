package com.telepic.permissions

import com.telepic.data.media.MapLocationRepository
import com.telepic.settings.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * The one-time repair of the map's scan memory, applied the first time the user grants media access.
 *
 * Why this exists at all: the map remembers every file it has examined — coordinate or not — in
 * `media_location_scan`, and that memory is what makes repeated sweeps cheap. But "examined, no GPS"
 * is only ever an inference from what the app was *allowed* to see. Before Telepic requested
 * `ACCESS_MEDIA_LOCATION`, Android redacted the embedded location from every MediaStore open on API
 * 29+, so the sweep wrote down "no coordinate" for photos that are in fact geotagged. Those rows are
 * stale the instant the permission is granted, and a plain re-scan would trust them and skip those
 * files forever — the map would stay empty on exactly the installs the fix is for. Clearing the scan
 * memory forces one full re-read, which can now see the coordinates the platform previously hid.
 *
 * Idempotence has two layers, because the two failure modes differ:
 *  * [lastHadAccess] makes it fire on the *transition* only, so an already-granted app does not clear
 *    its memory on every resume. It is in-memory, so it resets each process — which is correct, and
 *    is why the second layer is needed.
 *  * [SettingsRepository.mediaLocationBackfillGranted] is persisted, so a process restart with the
 *    permission already held (or a first-ever launch after an update) does not redo the clear either.
 *
 * Not thread-safe by design: it is driven by a single collector over one [MediaPermissionMonitor]
 * flow, so calls are serialised by that collection.
 */
class MediaLocationBackfill(
    private val settingsRepository: SettingsRepository,
    private val mapLocationRepository: MapLocationRepository,
) {

    private var lastHadAccess = false

    /**
     * Feeds the current media-access state in. Only a not-granted -> granted edge that has not already
     * been backfilled does any work; every other call — repeated grants, revocations, resumes while
     * already granted — is a no-op.
     */
    suspend fun onMediaAccessChanged(hasAccess: Boolean) {
        val justGranted = hasAccess && !lastHadAccess
        lastHadAccess = hasAccess
        if (!justGranted) return
        if (settingsRepository.mediaLocationBackfillGranted.first()) return
        mapLocationRepository.invalidateScanMemory()
        settingsRepository.setMediaLocationBackfillGranted(true)
    }
}
