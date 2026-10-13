package com.telepic.data.media

import com.telepic.data.media.db.MediaLocationDao
import com.telepic.data.media.db.MediaLocationEntity
import com.telepic.data.media.db.MediaLocationScanEntity
import com.telepic.domain.media.GeotaggedMedia
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Maintains the geotagged-location cache the map renders from. A [rescan] pages the library through
 * the same bounded [MediaPageLoader] the timeline uses, extracts EXIF GPS only for items it has not
 * already examined, and stores just the coordinate + content URI. Items without a valid coordinate
 * never gain a marker — the map shows exactly what the files actually encode.
 *
 * "Already examined" is tracked separately from "has a coordinate" (see
 * [MediaLocationScanEntity]), and that distinction is what makes repeated opens cheap: a screenshot
 * with no GPS is remembered as *examined*, so it is never re-read, and the sweep can move on to the
 * older part of the library where the geotagged photos actually are.
 */
interface MapLocationRepository {
    val locations: Flow<List<GeotaggedMedia>>
    suspend fun rescan()
}

class DefaultMapLocationRepository(
    private val dao: MediaLocationDao,
    private val pageLoader: MediaPageLoader,
    private val extractor: LocationExtractor,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pageSize: Int = 120,
    private val maxPages: Int = 2_000,
) : MapLocationRepository {

    override val locations: Flow<List<GeotaggedMedia>> =
        dao.observeAll().map { rows ->
            rows.map { GeotaggedMedia(it.localMediaId, com.telepic.domain.media.GeoLocation(it.latitude, it.longitude), it.contentUri) }
        }

    /**
     * Walks the library newest-first, reading EXIF only for items never examined before, and records
     * **every** item it looked at — including the ones with no coordinate.
     *
     * Two defects lived in the previous shape of this loop, and they compounded:
     *
     * 1. Only coordinate-bearing items were written down. `item.id in cached` could therefore never
     *    match a coordinate-less file, so every sweep re-read EXIF for all of them. A library is
     *    mostly screenshots and downloads, so a sweep re-read thousands of the same files forever.
     * 2. The sweep stopped after a fixed number of pages, and because of (1) it always spent that
     *    whole budget on the same already-examined prefix. A genuinely geotagged photo further back
     *    than the cap was therefore **unreachable** — the map could never show it, no matter how many
     *    times the user opened the screen, and the screen sat on its loading state throughout.
     *
     * Fixing (1) is what makes (2) affordable to fix. The page cap is now only a runaway guard
     * (2,000 pages ≈ 240,000 items), not a silent ceiling on what the map can ever find: because an
     * examined item costs nothing on a later sweep, a sweep can afford to walk the whole library.
     * Raising it is the only way coverage can grow — every sweep restarts at offset 0, so a small cap
     * would bound coverage at `maxPages × pageSize` no matter how often the screen is reopened.
     *
     * The cost is that each open re-pages the library. That is reads only (no EXIF, and no writes
     * once every item has been examined), it runs off the main thread, and it is cancellable — and
     * the screen deliberately no longer waits on it (see `MapViewModel.scanSettled`).
     *
     * Reconcile-prune still only runs on a sweep that actually reached the end: a truncated one has
     * an incomplete `present` set and would wrongly delete still-existing markers.
     */
    override suspend fun rescan() {
        val scanned = dao.scannedIds().toHashSet()
        val present = HashSet<Long>()
        var offset = 0
        var pages = 0
        var reachedEnd = false
        while (pages < maxPages) {
            pages++
            val items = pageLoader.load(offset, pageSize)
            if (items.isEmpty()) {
                reachedEnd = true
                break
            }
            val now = clock()
            val newlyScanned = ArrayList<MediaLocationScanEntity>(items.size)
            for (item in items) {
                present += item.id
                if (item.id in scanned) continue
                // A coordinate-less item is still recorded as examined — that record is the whole
                // point: it is what stops the next sweep from reading this file's EXIF again.
                extractor.extract(item.contentUri)?.let { location ->
                    dao.upsert(
                        MediaLocationEntity(
                            localMediaId = item.id,
                            latitude = location.latitude,
                            longitude = location.longitude,
                            contentUri = item.contentUri.toString(),
                            extractedAt = now,
                        ),
                    )
                }
                newlyScanned += MediaLocationScanEntity(item.id, now)
                scanned += item.id
            }
            if (newlyScanned.isNotEmpty()) dao.markScanned(newlyScanned)
            offset += items.size
            if (items.size < pageSize) {
                reachedEnd = true
                break
            }
        }
        // Drop markers for media that is gone (deleted) or now hidden (archived/trashed — the
        // pageLoader excludes those). Both tables are pruned from the union of what they know, so
        // location rows written before this table existed are reconciled too.
        if (reachedEnd) {
            val known = HashSet<Long>(scanned).apply { addAll(dao.cachedIds()) }
            (known - present).forEach { id ->
                dao.delete(id)
                dao.deleteScanned(id)
            }
        }
    }
}
