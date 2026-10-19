package com.telepic.permissions

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.telepic.data.media.MapLocationRepository
import com.telepic.domain.media.GeotaggedMedia
import com.telepic.settings.SettingsRepositoryImpl
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The map's scan memory is an inference ("this file has no GPS") that is invalidated the moment media
 * access is granted, because Android was redacting the coordinate until then. The backfill must clear
 * it exactly once per grant — never on a plain resume (that would force a full EXIF re-read every
 * foreground), and never skipped (that would leave an existing install's map permanently empty).
 *
 * Uses the real DataStore-backed [SettingsRepositoryImpl] rather than a hand-rolled fake so the
 * persisted half of "once" is genuinely exercised across the simulated process restart below.
 */
class MediaLocationBackfillTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newSettings(name: String) = SettingsRepositoryImpl(
        PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.root, "$name.preferences_pb") },
        ),
    )

    private class RecordingMapRepository : MapLocationRepository {
        override val locations: Flow<List<GeotaggedMedia>> = flowOf(emptyList())
        var invalidations = 0
        override suspend fun rescan() = Unit
        override suspend fun invalidateScanMemory() { invalidations++ }
    }

    @Test
    fun `the first grant clears the scan memory`() = runTest {
        val settings = newSettings("first_grant")
        val map = RecordingMapRepository()
        val backfill = MediaLocationBackfill(settings, map)

        backfill.onMediaAccessChanged(false)
        backfill.onMediaAccessChanged(false)
        assertEquals("a state without access invalidates nothing", 0, map.invalidations)

        backfill.onMediaAccessChanged(true)
        assertEquals("the not-granted -> granted edge clears the scan memory", 1, map.invalidations)
        assertTrue(settings.mediaLocationBackfillGranted.first())
    }

    @Test
    fun `staying granted does not clear the scan memory again`() = runTest {
        val settings = newSettings("staying_granted")
        val map = RecordingMapRepository()
        val backfill = MediaLocationBackfill(settings, map)

        backfill.onMediaAccessChanged(true)
        backfill.onMediaAccessChanged(true)
        backfill.onMediaAccessChanged(true)
        assertEquals(1, map.invalidations)
    }

    @Test
    fun `a later revoke and re-grant does not repeat the clear`() = runTest {
        val settings = newSettings("regrant")
        val map = RecordingMapRepository()
        val backfill = MediaLocationBackfill(settings, map)

        backfill.onMediaAccessChanged(true)
        backfill.onMediaAccessChanged(false)
        backfill.onMediaAccessChanged(true)
        assertEquals("the persisted flag survives the in-memory transition", 1, map.invalidations)
    }

    @Test
    fun `a restart while already granted does not clear the scan memory again`() = runTest {
        val settings = newSettings("restart")
        val firstProcess = RecordingMapRepository()
        MediaLocationBackfill(settings, firstProcess).onMediaAccessChanged(true)
        assertEquals(1, firstProcess.invalidations)

        // A fresh instance is a fresh process: the in-memory transition memory is gone, so only the
        // persisted flag can stop the clear happening again at launch.
        val secondProcess = RecordingMapRepository()
        MediaLocationBackfill(settings, secondProcess).onMediaAccessChanged(true)
        assertEquals(0, secondProcess.invalidations)
    }
}
