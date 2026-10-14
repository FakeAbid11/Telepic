package com.telepic.data.media

import com.telepic.data.ScanCursorStore
import com.telepic.data.media.db.MediaLocationDao
import com.telepic.data.media.db.MediaLocationEntity
import com.telepic.data.media.db.MediaLocationScanEntity
import com.telepic.diagnostics.TelepicLog
import com.telepic.domain.media.GeotaggedMedia
import kotlinx.coroutines.CancellationException
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
    private val cursorStore: ScanCursorStore,
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
     * Fixing (1) made (2) affordable, but the cap was still a real ceiling: every sweep restarted at
     * offset 0, so coverage could never exceed `maxPages × pageSize`. The sweep now resumes from a
     * persisted [ScanCursorStore] offset, which is what lets a library larger than the cap converge
     * over successive opens; a sweep that reaches the end rewinds the cursor to 0, so newly added
     * media is still found.
     *
     * What this does *not* change: an open on a library smaller than the cap still pages the whole
     * library. That is reads only (no EXIF, and no writes once every item has been examined), it runs
     * off the main thread, it is cancellable, and the screen deliberately does not wait on it (see
     * `MapViewModel.scanSettled`). Skipping that read entirely needs change-detection, which is a
     * different change from this one.
     *
     * Reconcile-prune still only runs on a sweep that covered the **whole** library — it reached the
     * end *and* started at offset 0. A truncated sweep, or one resumed part-way, has an incomplete
     * `present` set and would otherwise delete markers that still exist.
     */
    override suspend fun rescan() {
        val startOffset = cursorStore.offset(CURSOR_KEY).coerceAtLeast(0)
        val scanned = dao.scannedIds().toHashSet()
        val present = HashSet<Long>()
        var offset = startOffset
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
            // Collected and written per page rather than per item: a per-item upsert made a first
            // sweep of a geotagged-heavy library issue one transaction per geotagged file, which is
            // the same batching rule `markScanned` below already follows.
            val newlyLocated = ArrayList<MediaLocationEntity>()
            for (item in items) {
                present += item.id
                if (item.id in scanned) continue
                // A coordinate-less item is still recorded as examined — that record is the whole
                // point: it is what stops the next sweep from reading this file's EXIF again.
                extractor.extract(item.contentUri)?.let { location ->
                    newlyLocated += MediaLocationEntity(
                        localMediaId = item.id,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        contentUri = item.contentUri.toString(),
                        extractedAt = now,
                    )
                }
                newlyScanned += MediaLocationScanEntity(item.id, now)
                scanned += item.id
            }
            if (newlyLocated.isNotEmpty()) dao.upsertAll(newlyLocated)
            if (newlyScanned.isNotEmpty()) dao.markScanned(newlyScanned)
            offset += items.size
            if (items.size < pageSize) {
                reachedEnd = true
                break
            }
        }
        // A sweep that saw the end restarts from the newest item next time, so newly added media is
        // still discovered; a capped one resumes where it stopped.
        persistCursor(if (reachedEnd) 0 else offset)

        // Drop markers for media that is gone (deleted) or now hidden (archived/trashed — the
        // pageLoader excludes those). Both tables are pruned from the union of what they know, so
        // location rows written before this table existed are reconciled too.
        if (reachedEnd && startOffset == 0) {
            val known = HashSet<Long>(scanned).apply { addAll(dao.cachedIds()) }
            (known - present).forEach { id ->
                dao.delete(id)
                dao.deleteScanned(id)
            }
        }
    }

    /** Best-effort: a cursor that cannot be written costs a re-scan, never correctness. */
    private suspend fun persistCursor(offset: Int) {
        try {
            cursorStore.save(CURSOR_KEY, offset)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            TelepicLog.warn("MapScan", "could not persist the map scan cursor", throwable)
        }
    }

    private companion object {
        /** One loader, so one key. */
        const val CURSOR_KEY = "map_scan"
    }
}
