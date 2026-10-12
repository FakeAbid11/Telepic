package com.telepic.ui.screens.map

import com.telepic.data.media.MapLocationRepository
import com.telepic.data.media.SavedCamera
import com.telepic.domain.media.GeoLocation
import com.telepic.domain.media.GeotaggedMedia
import com.telepic.settings.MapCameraRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The map must hide archived/trashed media (consistency with Photos/Albums/Viewer) and must not show a
 * "no locations" state while the first scan is still running. Runs on Robolectric so the ViewModel's
 * Main-scoped stateIn and launch behave exactly as on a device once the main looper is idled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MapViewModelTest {

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After
    fun tearDown() = mainScope.cancel()

    private fun geo(id: Long) = GeotaggedMedia(id, GeoLocation(10.0 + id, 20.0 + id), "content://m/$id")

    private class FakeMapRepo(override val locations: Flow<List<GeotaggedMedia>>) : MapLocationRepository {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var rescanCalls = 0
        var failNext = false
        override suspend fun rescan() {
            rescanCalls++
            if (failNext) throw IllegalStateException("scan blew up")
            gate.await()
        }
    }

    /**
     * A camera store whose *read* is gated (a DataStore read is asynchronous on a device too) and
     * whose writes can be suspended or failed, so both halves of "publish only after the write
     * lands" are observable.
     */
    private class FakeCameraRepo(
        private val stored: SavedCamera? = null,
        private val failSave: Boolean = false,
    ) : MapCameraRepository {
        val readGate = CompletableDeferred<Unit>()
        override val camera: Flow<SavedCamera?> = flow {
            readGate.await()
            emit(stored)
        }

        val saved = mutableListOf<SavedCamera>()
        private val writeGate = CompletableDeferred<Unit>()

        override suspend fun save(camera: SavedCamera) {
            if (failSave) throw IllegalStateException("disk full")
            writeGate.await()
            saved += camera
        }

        override suspend fun clear() = Unit

        fun releaseRead() = readGate.complete(Unit)

        fun releaseWrite() = writeGate.complete(Unit)
    }

    private fun settle() = repeat(5) { shadowOf(android.os.Looper.getMainLooper()).idle() }

    @Test
    fun `hidden archived and trashed media is excluded from the map`() {
        val locations = MutableStateFlow(listOf(geo(1), geo(2), geo(3)))
        val hidden = MutableStateFlow(setOf(2L))
        val vm = MapViewModel(FakeMapRepo(locations), hiddenIds = hidden)

        // Subscribe so the WhileSubscribed stateIn flows materialize, then idle the main looper.
        mainScope.launch { vm.locationCount.collect {} }
        mainScope.launch { vm.locations.collect {} }
        settle()
        assertEquals(2, vm.locationCount.value)

        // Un-hide id 2 — the count follows immediately.
        hidden.value = emptySet()
        settle()
        assertEquals(3, vm.locationCount.value)

        // The raw visible list the view clusters must not carry the hidden items.
        hidden.value = setOf(1L, 3L)
        settle()
        assertEquals(listOf(2L), vm.locations.value.map { it.mediaId })
    }

    @Test
    fun `reports scanning during a rescan and clears when it finishes`() {
        val repo = FakeMapRepo(MutableStateFlow(emptyList()))
        val vm = MapViewModel(repo)

        // Not scanning before a refresh is requested.
        assertFalse(vm.isScanning.value)

        vm.refresh()
        settle()
        assertTrue("expected scanning to be true while rescan is suspended", vm.isScanning.value)
        assertEquals(1, repo.rescanCalls)

        // Let the scan complete; the flag must clear.
        repo.gate.complete(Unit)
        settle()
        assertFalse(vm.isScanning.value)
    }

    @Test
    fun `locations default to empty and reflect the first emission`() = kotlinx.coroutines.runBlocking {
        val vm = MapViewModel(FakeMapRepo(MutableStateFlow(listOf(geo(5)))), hiddenIds = MutableStateFlow(emptySet()))
        mainScope.launch { vm.locations.collect {} }
        mainScope.launch { vm.locationCount.collect {} }
        settle()
        assertEquals(1, vm.locationCount.value)
        assertEquals(1, vm.locations.value.size)
        // The raw visible item — clustering into pins is now the view's zoom-adaptive concern.
        assertEquals(5L, vm.locations.value.first().mediaId)
    }

    // ------------------------------------------------------- remembered camera (BUG-2)

    @Test
    fun `the camera resolves once from the store and then reports ready`() {
        val stored = SavedCamera(1.0, 2.0, 7.5)
        val repo = FakeCameraRepo(stored = stored)
        val vm = MapViewModel(
            FakeMapRepo(MutableStateFlow(emptyList())),
            cameraRepository = repo,
        )

        // The screen waits for this so the map opens already framed.
        assertFalse(vm.cameraResolved.value)
        repo.releaseRead()
        settle()
        assertTrue(vm.cameraResolved.value)
        assertEquals(stored, vm.savedCamera.value)
    }

    @Test
    fun `cameraResolved is immediate when no store is supplied`() {
        val vm = MapViewModel(FakeMapRepo(MutableStateFlow(emptyList())))
        assertTrue(vm.cameraResolved.value)
        assertNull(vm.savedCamera.value)
    }

    @Test
    fun `a camera is reported as remembered only after the write lands`() {
        val repo = FakeCameraRepo()
        val vm = MapViewModel(FakeMapRepo(MutableStateFlow(emptyList())), cameraRepository = repo)
        repo.releaseRead()
        val moved = SavedCamera(48.2, 16.3, 11.0)

        vm.rememberCamera(moved)
        settle()
        // The write is still in flight: the ViewModel must not claim the position is remembered.
        assertNull(vm.savedCamera.value)

        repo.releaseWrite()
        settle()
        assertEquals(moved, vm.savedCamera.value)
        assertEquals(listOf(moved), repo.saved)
    }

    @Test
    fun `a failed write leaves the last remembered camera standing`() {
        val persisted = SavedCamera(10.0, 20.0, 5.0)
        val repo = FakeCameraRepo(stored = persisted, failSave = true)
        val vm = MapViewModel(FakeMapRepo(MutableStateFlow(emptyList())), cameraRepository = repo)
        repo.releaseRead()
        settle()
        assertEquals(persisted, vm.savedCamera.value)

        vm.rememberCamera(SavedCamera(0.0, 0.0, 1.0))
        settle()

        // Not the position that failed to write: the state must never assert a lie.
        assertEquals(persisted, vm.savedCamera.value)
        assertTrue(repo.saved.isEmpty())
    }

    // ------------------------------------------------------------------ scan robustness

    @Test
    fun `a rescan failure is contained and still clears the scanning flag`() {
        val repo = FakeMapRepo(MutableStateFlow(listOf(geo(1)))).apply { failNext = true }
        val vm = MapViewModel(repo)
        mainScope.launch { vm.locations.collect {} }
        settle()

        vm.refresh()
        settle()

        assertFalse(vm.isScanning.value)
        assertEquals(1, repo.rescanCalls)
        // The already-known locations stay on screen — the empty state is not a lie either.
        assertEquals(listOf(1L), vm.locations.value.map { it.mediaId })
        // …and the failure is now reportable instead of being indistinguishable from an empty library.
        assertTrue(vm.scanFailed.value)
    }

    @Test
    fun `a failed scan is reported and a later success clears it`() {
        val repo = FakeMapRepo(MutableStateFlow(emptyList())).apply { failNext = true }
        val vm = MapViewModel(repo)

        vm.refresh()
        settle()
        assertTrue(vm.scanFailed.value)

        repo.failNext = false
        vm.refresh()
        settle()
        repo.gate.complete(Unit)
        settle()

        assertFalse(vm.scanFailed.value)
        assertEquals(2, repo.rescanCalls)
    }

    @Test
    fun `a rescan requested while one runs is ignored`() {
        val repo = FakeMapRepo(MutableStateFlow(emptyList()))
        val vm = MapViewModel(repo)

        vm.refresh()
        vm.refresh()
        vm.refresh()
        settle()

        assertEquals(1, repo.rescanCalls)
    }
}
