package com.telepic.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import com.telepic.data.media.SavedCamera
import com.telepic.diagnostics.TelepicLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Remembers where the user last left the photo map, so reopening it returns to the same view
 * instead of snapping back to a world view.
 */
interface MapCameraRepository {

    val camera: Flow<SavedCamera?>

    suspend fun save(camera: SavedCamera)

    suspend fun clear()
}

/**
 * [MapCameraRepository] backed by the app's shared DataStore.
 *
 * Reads are failure-tolerant — any error, or a partially-written record, is treated as "nothing
 * remembered" rather than trusted, so a corrupt preference can never place the map at an invalid
 * coordinate. Writes are validated for the same reason: an out-of-range value is dropped instead of
 * persisted.
 */
class MapCameraRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
) : MapCameraRepository {

    override val camera: Flow<SavedCamera?> = dataStore.data
        .map { preferences -> preferences.toSavedCamera() }
        .catch { throwable ->
            TelepicLog.warn("MapCameraRepository", "saved map camera could not be read; starting from the default view", throwable)
            emit(null)
        }

    override suspend fun save(camera: SavedCamera) {
        if (!camera.centerLat.isFinite() || !camera.centerLon.isFinite() || !camera.zoom.isFinite()) return
        if (camera.centerLat !in -90.0..90.0 || camera.centerLon !in -180.0..180.0) return
        dataStore.edit { preferences ->
            preferences[KEY_LATITUDE] = camera.centerLat
            preferences[KEY_LONGITUDE] = camera.centerLon
            preferences[KEY_ZOOM] = camera.zoom
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(KEY_LATITUDE)
            preferences.remove(KEY_LONGITUDE)
            preferences.remove(KEY_ZOOM)
        }
    }

    private fun Preferences.toSavedCamera(): SavedCamera? {
        val latitude = this[KEY_LATITUDE] ?: return null
        val longitude = this[KEY_LONGITUDE] ?: return null
        val zoom = this[KEY_ZOOM] ?: return null
        if (!latitude.isFinite() || !longitude.isFinite() || !zoom.isFinite()) return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return SavedCamera(latitude, longitude, zoom)
    }

    companion object {
        val KEY_LATITUDE = doublePreferencesKey("map_camera_latitude")
        val KEY_LONGITUDE = doublePreferencesKey("map_camera_longitude")
        val KEY_ZOOM = doublePreferencesKey("map_camera_zoom")
    }
}
