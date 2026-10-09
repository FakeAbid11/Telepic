package com.telepix.data.media

import com.telepix.data.media.db.MediaLocationDao
import com.telepix.data.media.db.MediaLocationEntity
import com.telepix.domain.media.GeotaggedMedia
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Maintains the geotagged-location cache the map renders from. A [rescan] pages the library through
 * the same bounded [MediaPageLoader] the timeline uses, extracts EXIF GPS only for items not already
 * cached (so repeated opens are cheap and full-resolution EXIF is never re-read on every render), and
 * stores just the coordinate + content URI. Items without a valid coordinate are simply never added —
 * the map shows exactly what the files actually encode.
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
    private val maxPages: Int = 40,
) : MapLocationRepository {

    override val locations: Flow<List<GeotaggedMedia>> =
        dao.observeAll().map { rows ->
            rows.map { GeotaggedMedia(it.localMediaId, com.telepix.domain.media.GeoLocation(it.latitude, it.longitude), it.contentUri) }
        }

    override suspend fun rescan() {
        val cached = dao.cachedIds().toHashSet()
        var offset = 0
        var pages = 0
        while (pages < maxPages) {
            pages++
            val items = pageLoader.load(offset, pageSize)
            if (items.isEmpty()) break
            for (item in items) {
                if (item.id in cached) continue
                val location = extractor.extract(item.contentUri) ?: continue
                dao.upsert(
                    MediaLocationEntity(
                        localMediaId = item.id,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        contentUri = item.contentUri.toString(),
                        extractedAt = clock(),
                    ),
                )
                cached += item.id
            }
            offset += items.size
            if (items.size < pageSize) break
        }
    }
}
