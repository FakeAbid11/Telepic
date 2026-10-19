package com.telepic.ui.components

import com.telepic.data.organization.MediaOrganizationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bulk favourite / trash actions, which the Photos timeline and album contents share.
 *
 * The two things worth pinning are the **one-way** favourite (a toggle would be ambiguous over a
 * mixed selection) and the **undo** (trashing is reversible, and the Undo has to restore exactly
 * the items the action moved — no more, no fewer).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BulkOrganizationControllerTest {

    private class FakeOrganization : MediaOrganizationRepository {
        private val favorites = MutableStateFlow<Set<Long>>(emptySet())
        private val archived = MutableStateFlow<Set<Long>>(emptySet())
        private val trashed = MutableStateFlow<Set<Long>>(emptySet())

        /** Every id written, per verb, in order. */
        val favoritedWrites = mutableListOf<Long>()
        val trashedWrites = mutableListOf<Long>()
        val restoredWrites = mutableListOf<Long>()

        var failNextWrite = false

        override val favoriteIds: Flow<Set<Long>> = favorites.asStateFlow()
        override val archivedIds: Flow<Set<Long>> = archived.asStateFlow()
        override val trashedIds: Flow<Set<Long>> = trashed.asStateFlow()

        override suspend fun hiddenIds(): Set<Long> = favorites.value + archived.value + trashed.value
        override suspend fun isFavorite(id: Long): Boolean = id in favorites.value
        override suspend fun isArchived(id: Long): Boolean = id in archived.value
        override suspend fun isTrashed(id: Long): Boolean = id in trashed.value

        override suspend fun setFavorite(id: Long, favorite: Boolean) {
            if (failNextWrite) throw IllegalStateException("write refused")
            favoritedWrites += id
            favorites.value = if (favorite) favorites.value + id else favorites.value - id
        }

        override suspend fun setArchived(id: Long, archived: Boolean) {
            this.archived.value = if (archived) this.archived.value + id else this.archived.value - id
        }

        override suspend fun moveToTrash(id: Long) {
            if (failNextWrite) throw IllegalStateException("write refused")
            trashedWrites += id
            trashed.value = trashed.value + id
        }

        override suspend fun restoreFromTrash(id: Long) {
            restoredWrites += id
            trashed.value = trashed.value - id
        }

        override suspend fun markDeletedFromStore(id: Long) = Unit
        override suspend fun remove(id: Long) = Unit
    }

    @Before
    fun setUp() {
        // An unconfined scope, so the controller's writes run to completion inside the call: the
        // assertions below are about what the write *did*, not about when it lands.
        scope = CoroutineScope(UnconfinedTestDispatcher())
    }

    private lateinit var scope: CoroutineScope

    @Test
    fun `favouriting writes every selected item and reports the split`() = runTest {
        val repo = FakeOrganization()
        val controller = BulkOrganizationController(repo, scope)
        var landed = false

        controller.favorite(listOf(1L, 2L, 3L), alreadyFavorites = setOf(2L)) { landed = true }

        // The one that was already a favourite is not written again: re-writing it would reset the
        // moment it was favourited for no gain.
        assertEquals(listOf(1L, 3L), repo.favoritedWrites)
        assertTrue("the caller must be told the write landed", landed)

        val outcome = controller.report.value?.outcome
        assertTrue(outcome is BulkOrganizationReport.Outcome.Favorited)
        outcome as BulkOrganizationReport.Outcome.Favorited
        assertEquals(2, outcome.added)
        assertEquals(1, outcome.already)
    }

    @Test
    fun `a failed favourite reports the failure and does not claim a write`() = runTest {
        val repo = FakeOrganization().apply { failNextWrite = true }
        val controller = BulkOrganizationController(repo, scope)
        var landed = false

        controller.favorite(listOf(1L), alreadyFavorites = emptySet()) { landed = true }

        assertEquals(
            BulkOrganizationReport.Outcome.Failed,
            controller.report.value?.outcome,
        )
        assertTrue("a failed write must not tell the caller it landed", !landed)
    }

    @Test
    fun `trashing moves every selected item and undo restores exactly those`() = runTest {
        val repo = FakeOrganization()
        val controller = BulkOrganizationController(repo, scope)

        controller.trash(listOf(7L, 8L, 9L)) { }
        assertEquals(listOf(7L, 8L, 9L), repo.trashedWrites)

        val outcome = controller.report.value?.outcome
        assertTrue(outcome is BulkOrganizationReport.Outcome.Trashed)

        controller.undoTrash()
        assertEquals(listOf(7L, 8L, 9L), repo.restoredWrites)
        assertTrue("the bar disappears once the restore lands", controller.report.value == null)
    }

    @Test
    fun `a failed trash is never reported as a success`() = runTest {
        val repo = FakeOrganization().apply { failNextWrite = true }
        val controller = BulkOrganizationController(repo, scope)

        controller.trash(listOf(1L)) { }

        assertEquals(
            BulkOrganizationReport.Outcome.Failed,
            controller.report.value?.outcome,
        )
    }

    @Test
    fun `undo does nothing when nothing was trashed`() = runTest {
        val repo = FakeOrganization()
        val controller = BulkOrganizationController(repo, scope)

        controller.undoTrash()

        assertTrue("there is nothing to restore", repo.restoredWrites.isEmpty())
    }
}
