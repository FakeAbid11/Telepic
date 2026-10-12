package com.telepic.ui.screens.viewer

import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.telepic.data.cloud.CloudRepository
import com.telepic.data.media.LocalMediaLookup
import com.telepic.data.media.NeighborDirection
import com.telepic.data.organization.MediaOrganizationRepository
import com.telepic.domain.cloud.CloudMedia
import com.telepic.domain.cloud.CloudPreview
import com.telepic.domain.cloud.CloudStatus
import com.telepic.domain.cloud.LocalDownloadedMedia
import com.telepic.domain.cloud.TelepicCloudDestination
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.MediaType
import com.telepic.navigation.MediaSource
import com.telepic.ui.theme.TelepicTheme
import com.telepic.ui.theme.TelepicTokens
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Robolectric Compose coverage of the Viewer chrome and states (photo path; video is device-only). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class ViewerUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun localPhoto(id: Long) = LocalMedia(
        id = id, contentUri = Uri.parse("content://media/$id"), type = MediaType.PHOTO, mimeType = "image/jpeg",
        displayName = "f$id", dateMillis = 1_700_000_000_000L, durationMillis = null, width = 10, height = 10,
        sizeBytes = 10L, bucketId = null, bucketName = null, relativePath = null,
    )

    private class Lookup(private val items: Map<Long, LocalMedia>, private val older: Long? = null) : LocalMediaLookup {
        override suspend fun byId(id: Long): LocalMedia? = items[id]
        override suspend fun neighborId(id: Long, direction: NeighborDirection, bucketId: Long?): Long? =
            if (direction == NeighborDirection.OLDER) older else null
        override suspend fun byIdList(ids: Collection<Long>): List<LocalMedia> = ids.toList().mapNotNull { items[it] }
    }

    private object NoCloud : CloudRepository {
        override val status: StateFlow<CloudStatus> = MutableStateFlow(CloudStatus.Ready).asStateFlow()
        override val media: Flow<List<CloudMedia>> = flowOf(emptyList())
        override val destinationTitle: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()
        override suspend fun prepare() = Unit
        override suspend fun refresh() = Unit
        override suspend fun ensureDestination(): TelepicCloudDestination? = null
        override suspend fun confirmDestination(): TelepicCloudDestination? = null
        override suspend fun createNewDestination(): TelepicCloudDestination? = null
        override suspend fun getPreview(media: CloudMedia): CloudPreview? = null
        override suspend fun downloadOriginal(media: CloudMedia): LocalDownloadedMedia? = null
        override suspend fun uploadMedia(request: com.telepic.domain.cloud.CloudUploadRequest, onProgress: (com.telepic.domain.cloud.CloudUploadProgress) -> Unit, onSent: suspend (Long, Long) -> Unit) = null
        override suspend fun confirmUpload(
            chatId: Long,
            messageId: Long,
            mediaType: com.telepic.domain.cloud.CloudMediaType,
            contentHash: String?,
            contentSizeBytes: Long?,
        ): com.telepic.domain.cloud.CloudUploadResult = throw NotImplementedError()
    }

    /**
     * An organization repository that holds no state. Needed because the Viewer's organization
     * actions no-op when the repository is unwired — a test of their feedback has to wire one.
     */
    private object NoOrg : MediaOrganizationRepository {
        override val favoriteIds: Flow<Set<Long>> = flowOf(emptySet())
        override val archivedIds: Flow<Set<Long>> = flowOf(emptySet())
        override val trashedIds: Flow<Set<Long>> = flowOf(emptySet())
        override suspend fun hiddenIds(): Set<Long> = emptySet()
        override suspend fun isFavorite(id: Long): Boolean = false
        override suspend fun isArchived(id: Long): Boolean = false
        override suspend fun isTrashed(id: Long): Boolean = false
        override suspend fun setFavorite(id: Long, favorite: Boolean) = Unit
        override suspend fun setArchived(id: Long, archived: Boolean) = Unit
        override suspend fun moveToTrash(id: Long) = Unit
        override suspend fun restoreFromTrash(id: Long) = Unit
        override suspend fun markDeletedFromStore(id: Long) = Unit
        override suspend fun remove(id: Long) = Unit
    }

    @Test
    fun `a local photo shows the source label and a working back action`() {
        var backed = false
        val vm = ViewerViewModel(MediaSource.Local(5), Lookup(mapOf(5L to localPhoto(5), 9L to localPhoto(9)), older = 9L), NoCloud)
        composeRule.setContent { TelepicTheme { ViewerScreen(vm, onBack = { backed = true }) } }

        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        composeRule.onNodeWithText("On this device").assertIsDisplayed()
        // A next item exists → the forward control is present and back returns to the origin.
        composeRule.onNodeWithContentDescription("Back").performClick()
        assertTrue(backed)
    }

    @Test
    fun `a missing item shows an honest empty state`() {
        val vm = ViewerViewModel(MediaSource.Local(404), Lookup(emptyMap()), NoCloud)
        composeRule.setContent { TelepicTheme { ViewerScreen(vm, onBack = {}) } }
        composeRule.onNodeWithText("This media is no longer available").assertIsDisplayed()
    }

    @Test
    fun `the item date is exposed as a heading so the chrome has a landmark`() {
        val vm = ViewerViewModel(MediaSource.Local(5), Lookup(mapOf(5L to localPhoto(5))), NoCloud)
        composeRule.setContent { TelepicTheme { ViewerScreen(vm, onBack = {}) } }

        // Exactly one: the date. Asserted by key rather than by its text, which is locale-formatted.
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertCountEquals(1)
    }

    @Test
    fun `archive, trash and details stay reachable behind the overflow control`() {
        val vm = ViewerViewModel(MediaSource.Local(5), Lookup(mapOf(5L to localPhoto(5))), NoCloud)
        composeRule.setContent { TelepicTheme { ViewerScreen(vm, onBack = {}) } }

        // These three used to be their own 48dp buttons, which starved the date column. They are now
        // menu items, so the contract worth pinning is that none of them became unreachable.
        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.onNodeWithText("Archive").assertExists()
        composeRule.onNodeWithText("Move to trash").assertExists()
        composeRule.onNodeWithText("Details").assertExists()
    }

    /**
     * `VideoViewer` insets the video surface by exactly `TelepicSpacing.chromeBarHeight` at each end,
     * and the Compose transport that replaced media3's stock controller is that tall too
     * (`VideoTransportUiTest` pins that side). The three are coupled by one value, so this pins the
     * photo chrome against it: the bars build their height from `sm` + `touchTarget` + `sm`, and the
     * reservation is the same sum.
     *
     * Equality rather than a minimum, because the failure mode runs the *other* way — a bar that
     * grows taller than the reservation is what would push controls back under the chrome.
     */
    @Test
    fun `the chrome bars are exactly as tall as the band the video surface reserves`() {
        var topBarExpected = 0.dp
        var bottomBarExpected = 0.dp
        val vm = ViewerViewModel(
            MediaSource.Local(5),
            Lookup(mapOf(5L to localPhoto(5), 9L to localPhoto(9)), older = 9L),
            NoCloud,
        )
        composeRule.setContent {
            TelepicTheme {
                val density = LocalDensity.current
                val band = TelepicTokens.spacing.chromeBarHeight
                // Read back the very insets the bars apply, so the expectation holds on whatever the
                // host reports instead of hard-coding a 24dp system bar.
                topBarExpected = with(density) { WindowInsets.statusBars.getTop(this).toDp() } + band
                bottomBarExpected = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() } + band
                ViewerScreen(vm, onBack = {})
            }
        }
        composeRule.waitForIdle()

        // `older = 9L` gives the item a neighbour, so the navigation row is the bar that renders.
        composeRule.onNodeWithTag(TAG_VIEWER_TOP_BAR).assertHeightIsEqualTo(topBarExpected)
        composeRule.onNodeWithTag(TAG_VIEWER_BOTTOM_BAR).assertHeightIsEqualTo(bottomBarExpected)
    }

    /**
     * The reported defect: trashing wrote to Room correctly but said nothing, so from the user's seat
     * the button did nothing. The Viewer deliberately stays on the item (popping before the write hid
     * failures), and the item keeps rendering unchanged — so the confirmation bar is the only
     * evidence the action happened.
     */
    @Test
    fun `trashing from the viewer reports the outcome and offers an undo`() {
        val vm = ViewerViewModel(
            MediaSource.Local(5),
            Lookup(mapOf(5L to localPhoto(5))),
            NoCloud,
            organizationRepository = NoOrg,
        )
        composeRule.setContent { TelepicTheme { ViewerScreen(vm, onBack = {}) } }

        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.onNodeWithText("Move to trash").performClick()
        // The dialog's confirm button carries the same label as the menu item that opened it, so while
        // the menu is still animating out, `onNodeWithText("Move to trash")` matches two nodes and
        // throws. Poll until exactly one remains rather than assuming a fixed wait retires the menu.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Move to trash").fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithText("Move to trash").performClick()

        // The write is async and the bar is on an auto-dismiss timer, so poll for it to appear.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Moved to trash").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Moved to trash").assertExists()
        // Undo is the whole point: Trash is not reachable from the Viewer, so the confirmation has to
        // carry the reversal itself.
        composeRule.onNodeWithText("Undo").assertExists()
    }
}
