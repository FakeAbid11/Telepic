package com.telepix.ui.screens.map

import com.telepix.data.media.MapLocationRepository
import com.telepix.domain.media.GeoLocation
import com.telepix.domain.media.GeotaggedMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        override suspend fun rescan() {
            rescanCalls++
            gate.await()
        }
    }

    private fun settle() = repeat(5) { shadowOf(android.os.Looper.getMainLooper()).idle() }

    @Test
    fun `hidden archived and trashed media is excluded from the map`() {
        val locations = MutableStateFlow(listOf(geo(1), geo(2), geo(3)))
        val hidden = MutableStateFlow(setOf(2L))
        val vm = MapViewModel(FakeMapRepo(locations), hiddenIds = hidden)

        // Subscribe so the WhileSubscribed stateIn flows materialize, then idle the main looper.
        mainScope.launch { vm.locationCount.collect {} }
        mainScope.launch { vm.pins.collect {} }
        settle()
        assertEquals(2, vm.locationCount.value)

        // Un-hide id 2 — the count follows immediately.
        hidden.value = emptySet()
        settle()
        assertEquals(3, vm.locationCount.value)

        // Pins must not carry a pin for the hidden item.
        hidden.value = setOf(1L, 3L)
        settle()
        val pinnedIds = vm.pins.value.mapNotNull { (it as? com.telepix.data.media.MapClusterer.MapPin.Single)?.mediaId }
        assertEquals(listOf(2L), pinnedIds)
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
    fun `pins default to empty and reflect the first emission`() = kotlinx.coroutines.runBlocking {
        val vm = MapViewModel(FakeMapRepo(MutableStateFlow(listOf(geo(5)))), hiddenIds = MutableStateFlow(emptySet()))
        mainScope.launch { vm.pins.collect {} }
        mainScope.launch { vm.locationCount.collect {} }
        settle()
        assertEquals(1, vm.locationCount.value)
        // A single geotagged item clusters to exactly one pin.
        assertEquals(1, vm.pins.value.size)
        assertTrue(vm.pins.first { it.isNotEmpty() }.isNotEmpty())
    }
}
