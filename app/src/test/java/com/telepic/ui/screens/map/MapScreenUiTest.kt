package com.telepic.ui.screens.map

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.telepic.data.media.MapLocationRepository
import com.telepic.data.media.SavedCamera
import com.telepic.domain.media.GeoLocation
import com.telepic.domain.media.GeotaggedMedia
import com.telepic.navigation.MapFocusRequest
import com.telepic.permissions.MediaPermissionState
import com.telepic.settings.MapCameraRepository
import com.telepic.ui.theme.TelepicTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric coverage of the photo map's *state machine*: permission, "still scanning" vs
 * "genuinely empty", and the honest locations count. The osmdroid surface itself is device-bound
 * (its factory builds a real `MapView` and starts tile requests), so these assertions deliberately
 * stay on the branches that render before the surface exists — which is exactly the part that can
 * regress silently, because the map logic it guards lives behind the surface.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp")
class MapScreenUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun geo(id: Long) = GeotaggedMedia(id, GeoLocation(10.0 + id, 20.0 + id), "content://m/$id")

    private class FakeMapRepo : MapLocationRepository {
        private val items = MutableStateFlow<List<GeotaggedMedia>>(emptyList())
        override val locations: Flow<List<GeotaggedMedia>> = items
        var rescanCalls = 0
        val scanGate = CompletableDeferred<Unit>()

        fun emit(items: List<GeotaggedMedia>) {
            this.items.value = items
        }

        fun finishScan() = scanGate.complete(Unit)

        override suspend fun rescan() {
            rescanCalls++
            scanGate.await()
        }
    }

    /**
     * A camera store whose *read* is gated: a DataStore read is asynchronous on a device too, and
     * without the gate the ViewModel would resolve `cameraResolved` synchronously inside its
     * constructor — making the "still reading the remembered camera" branch untestable.
     */
    private class FakeCameraRepo(
        private val stored: SavedCamera? = null,
    ) : MapCameraRepository {
        val readGate = CompletableDeferred<Unit>()
        override val camera: Flow<SavedCamera?> = flow {
            readGate.await()
            emit(stored)
        }

        override suspend fun save(camera: SavedCamera) = Unit
        override suspend fun clear() = Unit
    }

    private fun settle() = repeat(4) { shadowOf(android.os.Looper.getMainLooper()).idle() }

    /**
     * These tests deliberately stay on the branches that render *before* the osmdroid surface exists,
     * so the scan grace is pinned far beyond anything a test can advance. The loading branch is what is
     * under test here; letting the window lapse would build a real `MapView`, which is device-bound.
     * The lapse itself is covered at the ViewModel level in `MapViewModelTest`.
     */
    private fun showMap(
        repo: FakeMapRepo,
        permission: MediaPermissionState = MediaPermissionState.Granted,
        cameraRepository: MapCameraRepository? = null,
    ) {
        composeRule.setContent {
            TelepicTheme {
                MapScreen(
                    viewModel = MapViewModel(
                        repo,
                        hiddenIds = flowOf(emptySet()),
                        cameraRepository = cameraRepository,
                        scanGraceMillis = 60_000L,
                    ),
                    onOpenMedia = {},
                    focusRequest = MapFocusRequest(),
                    permissionStateOverride = permission,
                )
            }
        }
    }

    @Test
    fun `no media access asks for it and does not fake an empty map`() {
        val repo = FakeMapRepo()
        repo.emit(List(3) { geo(it.toLong()) })
        showMap(repo, permission = MediaPermissionState.Denied)
        settle()

        composeRule.onNodeWithText("See your photo locations").assertIsDisplayed()
        composeRule.onNodeWithText("Allow access").assertIsDisplayed()
        // Three media DO have coordinates — the count is not hidden behind the permission wall.
        composeRule.onNodeWithText("3 locations").assertIsDisplayed()
        // The empty state must not answer a permission problem: it would say "you have no photos".
        composeRule.onAllNodesWithText("No locations yet").assertCountEquals(0)
    }

    @Test
    fun `a permanently denied permission routes to settings instead of re-asking`() {
        val repo = FakeMapRepo()
        repo.finishScan()
        showMap(repo, permission = MediaPermissionState.PermanentlyDenied)
        settle()

        composeRule.onNodeWithText("Open settings").assertIsDisplayed()
    }

    @Test
    fun `the map waits for the remembered camera before it is created`() {
        val repo = FakeMapRepo()
        showMap(repo, cameraRepository = FakeCameraRepo(SavedCamera(48.2, 16.3, 11.0)))
        settle()

        // "Still loading", not "no locations": the empty state must never flash before the first
        // markers land, and the surface must not be built at the wrong camera position.
        composeRule.onNodeWithContentDescription("Loading").assertIsDisplayed()
    }

    @Test
    fun `a finished scan with no coordinates reports an honest empty state`() {
        val repo = FakeMapRepo()
        repo.finishScan()
        showMap(repo)
        settle()

        composeRule.onNodeWithText("No locations yet").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Photos and videos that contain GPS coordinates appear on the map. " +
                "Telepic never invents a location for media without coordinates.",
        ).assertIsDisplayed()
        // The count affordance stays hidden when there is nothing to count.
        composeRule.onAllNodesWithText("0 locations").assertCountEquals(0)
    }

    @Test
    fun `opening the map with access rescans the location cache exactly once`() {
        val repo = FakeMapRepo()
        showMap(repo)
        settle()
        assertEquals(1, repo.rescanCalls)

        // Recomposition alone must not start a second scan.
        composeRule.waitForIdle()
        settle()
        assertEquals(1, repo.rescanCalls)
        // The scan is still in flight — nothing has resolved it.
        assertTrue(repo.scanGate.isActive)
    }
}
