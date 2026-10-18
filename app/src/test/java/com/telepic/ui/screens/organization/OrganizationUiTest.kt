package com.telepic.ui.screens.organization

import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.telepic.data.media.LocalMediaLookup
import com.telepic.data.media.NeighborDirection
import com.telepic.data.organization.DeleteRequest
import com.telepic.data.organization.LocalMediaDeleter
import com.telepic.data.organization.MediaOrganizationRepository
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.MediaType
import com.telepic.navigation.OrganizationKind
import com.telepic.ui.theme.TelepicTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose-level coverage for the collections screen: the honest empty state, a populated grid
 * whose tiles open the media, and — the distinction the ViewModel guards — a failed provider read
 * surfacing as an error rather than as "no favorites". Fakes mirror [OrganizationViewModelTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class OrganizationUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun media(id: Long) = LocalMedia(
        id = id, contentUri = Uri.parse("content://m/$id"), type = MediaType.PHOTO, mimeType = "image/jpeg",
        displayName = "f$id", dateMillis = 1L, durationMillis = null, width = 1, height = 1, sizeBytes = 1L,
        bucketId = null, bucketName = null, relativePath = null,
    )

    private class FakeRepo : MediaOrganizationRepository {
        val favs = MutableStateFlow<Set<Long>>(emptySet())
        val arch = MutableStateFlow<Set<Long>>(emptySet())
        val trash = MutableStateFlow<Set<Long>>(emptySet())

        override val favoriteIds: Flow<Set<Long>> = favs.asStateFlow()
        override val archivedIds: Flow<Set<Long>> = arch.asStateFlow()
        override val trashedIds: Flow<Set<Long>> = trash.asStateFlow()
        override suspend fun hiddenIds(): Set<Long> = arch.value + trash.value
        override suspend fun isFavorite(id: Long) = id in favs.value
        override suspend fun isArchived(id: Long) = id in arch.value
        override suspend fun isTrashed(id: Long) = id in trash.value

        override suspend fun setFavorite(id: Long, favorite: Boolean) {
            favs.value = if (favorite) favs.value + id else favs.value - id
        }

        override suspend fun setArchived(id: Long, archived: Boolean) {
            arch.value = if (archived) arch.value + id else arch.value - id
        }

        override suspend fun moveToTrash(id: Long) { trash.value = trash.value + id }

        override suspend fun restoreFromTrash(id: Long) {
            trash.value = trash.value - id
        }

        override suspend fun markDeletedFromStore(id: Long) = Unit
        override suspend fun remove(id: Long) = Unit
    }

    private class FakeLookup : LocalMediaLookup {
        val store = mutableMapOf<Long, LocalMedia>()
        override suspend fun byId(id: Long): LocalMedia? = store[id]
        override suspend fun neighborId(id: Long, direction: NeighborDirection, bucketId: Long?): Long? = null
        override suspend fun byIdList(ids: Collection<Long>): List<LocalMedia> =
            ids.toList().mapNotNull { store[it] }
    }

    /** A provider that refuses to answer, so "the read failed" can be told from "nothing is here". */
    private class FlakyLookup : LocalMediaLookup {
        val store = mutableMapOf<Long, LocalMedia>()
        override suspend fun byId(id: Long): LocalMedia? = store[id]
        override suspend fun neighborId(id: Long, direction: NeighborDirection, bucketId: Long?): Long? = null
        override suspend fun byIdList(ids: Collection<Long>): List<LocalMedia> =
            throw IllegalStateException("provider refused")
    }

    private class FakeDeleter : LocalMediaDeleter {
        override suspend fun requestDelete(media: LocalMedia): DeleteRequest = DeleteRequest.Failed
        override suspend fun requestDeleteAll(media: List<LocalMedia>): DeleteRequest = DeleteRequest.Failed
    }

    private fun viewModel(
        kind: OrganizationKind = OrganizationKind.FAVORITES,
        repo: FakeRepo = FakeRepo(),
        lookup: LocalMediaLookup = FakeLookup(),
    ) = OrganizationViewModel(kind, repo, lookup, FakeDeleter())

    private fun render(
        vm: OrganizationViewModel,
        onBack: () -> Unit = {},
        onOpenMedia: (Long) -> Unit = {},
    ) {
        composeRule.setContent {
            TelepicTheme(darkTheme = true) {
                OrganizationScreen(viewModel = vm, onBack = onBack, onOpenMedia = onOpenMedia)
            }
        }
    }

    private fun waitUntilVisible(text: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `empty favorites shows its empty state`() {
        render(viewModel())
        waitUntilVisible("No favorites yet")
        composeRule.onAllNodesWithText("Favorites").onFirst().assertExists()
        composeRule.onAllNodesWithText("No favorites yet").onFirst().assertExists()
    }

    @Test
    fun `favorited items render as tiles`() {
        val repo = FakeRepo().apply { favs.value = setOf(1L, 2L) }
        val lookup = FakeLookup().apply {
            store[1L] = media(1L)
            store[2L] = media(2L)
        }
        render(viewModel(repo = repo, lookup = lookup))
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription("Photo", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(
            2,
            composeRule.onAllNodesWithContentDescription("Photo", substring = true)
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `a failed read is the error state, never an empty collection`() {
        val repo = FakeRepo().apply { favs.value = setOf(1L) }
        render(viewModel(repo = repo, lookup = FlakyLookup()))
        waitUntilVisible("Couldn't read this collection")
    }

    @Test
    fun `tapping a tile opens that media`() {
        var opened: Long? = null
        val repo = FakeRepo().apply { favs.value = setOf(1L) }
        val lookup = FakeLookup().apply { store[1L] = media(1L) }
        render(viewModel(repo = repo, lookup = lookup), onOpenMedia = { opened = it })
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription("Photo", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithContentDescription("Photo", substring = true)
            .onFirst().performClick()
        assertEquals(1L, opened)
    }
}
