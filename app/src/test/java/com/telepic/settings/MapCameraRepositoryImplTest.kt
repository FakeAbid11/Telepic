package com.telepic.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.telepic.data.media.SavedCamera
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Verifies real map-camera persistence through DataStore, including the "nothing remembered yet"
 * default and the refusal to store a coordinate that could never be a real position.
 */
class MapCameraRepositoryImplTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newRepository(name: String): MapCameraRepositoryImpl {
        val dataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.root, "$name.preferences_pb") },
        )
        return MapCameraRepositoryImpl(dataStore)
    }

    @Test
    fun `nothing is remembered before the first save`() = runTest {
        assertNull(newRepository("empty").camera.first())
    }

    @Test
    fun `persists and reads back the camera position`() = runTest {
        val repository = newRepository("saved")
        repository.save(SavedCamera(51.5, -0.12, 12.0))
        assertEquals(SavedCamera(51.5, -0.12, 12.0), repository.camera.first())
    }

    @Test
    fun `a later save replaces the earlier position`() = runTest {
        val repository = newRepository("replaced")
        repository.save(SavedCamera(1.0, 2.0, 3.0))
        repository.save(SavedCamera(4.0, 5.0, 6.0))
        assertEquals(SavedCamera(4.0, 5.0, 6.0), repository.camera.first())
    }

    @Test
    fun `clear forgets the remembered position`() = runTest {
        val repository = newRepository("cleared")
        repository.save(SavedCamera(1.0, 2.0, 3.0))
        repository.clear()
        assertNull(repository.camera.first())
    }

    @Test
    fun `an out-of-range position is refused instead of persisted`() = runTest {
        val repository = newRepository("invalid")
        repository.save(SavedCamera(120.0, 200.0, 5.0))
        assertNull(repository.camera.first())
    }

    @Test
    fun `a non-finite position is refused instead of persisted`() = runTest {
        val repository = newRepository("nan")
        repository.save(SavedCamera(Double.NaN, 2.0, 5.0))
        assertNull(repository.camera.first())
    }
}
