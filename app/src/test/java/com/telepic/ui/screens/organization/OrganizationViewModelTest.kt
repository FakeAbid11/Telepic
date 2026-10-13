package com.telepic.ui.screens.organization

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import com.telepic.data.media.LocalMediaLookup
import com.telepic.data.media.NeighborDirection
import com.telepic.data.organization.DeleteRequest
import com.telepic.data.organization.LocalMediaDeleter
import com.telepic.data.organization.MediaOrganizationRepository
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.MediaType
import com.telepic.navigation.OrganizationKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The collection ViewModel maps favorite/archive/trash id-sets to media, and its bulk undo / consented
 * delete actions drive the repository correctly. Fakes only — the permanent-delete system consent is
 * device-gated and is exercised here only through the [LocalMediaDeleter] seam.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OrganizationViewModelTest {

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun media(id: Long) = LocalMedia(
        id = id, contentUri = Uri.parse("content://m/$id"), type = MediaType.PHOTO, mimeType = "image/jpeg",
        displayName = "f$id", dateMillis = 1L, durationMillis = null, width = 1, height = 1, sizeBytes = 1L,
        bucketId = null, bucketName = null, relativePath = null,
    )

    /** A real IntentSender, because the consent path hands one back to the screen. */
    private fun intentSender(): IntentSender {
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(context, Activity::class.java)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE).intentSender
    }

    private class FakeRepo : MediaOrganizationRepository {
        val favs = MutableStateFlow<Set<Long>>(emptySet())
        val arch = MutableStateFlow<Set<Long>>(emptySet())
        val trash = MutableStateFlow<Set<Long>>(emptySet())
        val removed = mutableListOf<Long>()
        val deletedFromStore = mutableListOf<Long>()
        val restored = mutableListOf<Long>()

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
            restored += id
            trash.value = trash.value - id
        }

        override suspend fun markDeletedFromStore(id: Long) { deletedFromStore += id }
        override suspend fun remove(id: Long) { removed += id }
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
        var fail = true
        val store = mutableMapOf<Long, LocalMedia>()
        override suspend fun byId(id: Long): LocalMedia? = store[id]
        override suspend fun neighborId(id: Long, direction: NeighborDirection, bucketId: Long?): Long? = null
        override suspend fun byIdList(ids: Collection<Long>): List<LocalMedia> {
            if (fail) throw IllegalStateException("provider refused")
            return ids.toList().mapNotNull { store[it] }
        }
    }

    /** Records the batch it was handed, so a bulk delete can be checked to be one request, not N. */
    private class FakeDeleter(private val outcome: DeleteRequest) : LocalMediaDeleter {
        var requested: List<LocalMedia> = emptyList()
        var calls = 0
        override suspend fun requestDelete(media: LocalMedia): DeleteRequest = requestDeleteAll(listOf(media))
        override suspend fun requestDeleteAll(media: List<LocalMedia>): DeleteRequest {
            calls++
            requested = media
            return outcome
        }
    }

    private fun readyIds(vm: OrganizationViewModel): Set<Long> =
        (vm.items.value as OrgItemsState.Ready).items.map { it.id }.toSet()

    @Test
    fun `favorites collection resolves the favorited ids to media`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRepo().apply { favs.value = setOf(1L, 2L) }
        val lookup = FakeLookup().apply {
            store[1L] = media(1L)
            store[2L] = media(2L)
        }
        val vm = OrganizationViewModel(OrganizationKind.FAVORITES, repo, lookup, FakeDeleter(DeleteRequest.Failed))
        val state = vm.items.first { it is OrgItemsState.Ready }
        assertEquals(setOf(1L, 2L), (state as OrgItemsState.Ready).items.map { it.id }.toSet())
    }

    @Test
    fun `a failed read is the error state, never an empty collection`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRepo().apply { trash.value = setOf(1L) }
        val vm = OrganizationViewModel(OrganizationKind.TRASH, repo, FlakyLookup(), FakeDeleter(DeleteRequest.Failed))
        // The bug this replaces: `byIdList` swallowed the throwable, so this rendered as "Trash is
        // empty" — a statement about the user's data, over a query that never answered.
        val state = vm.items.first { it !is OrgItemsState.Loading }
        assertEquals(OrgItemsState.Error, state)
    }

    @Test
    fun `refresh recovers from a failed read`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val lookup = FlakyLookup().apply { store[1L] = media(1L) }
        val repo = FakeRepo().apply { favs.value = setOf(1L) }
        val vm = OrganizationViewModel(OrganizationKind.FAVORITES, repo, lookup, FakeDeleter(DeleteRequest.Failed))
        val job = launch { vm.items.collect { } }
        runCurrent()
        assertEquals(OrgItemsState.Error, vm.items.value)

        // The retry the error state offers. Nothing about the id-set changed, so only a real re-read
        // can produce a Ready state here.
        lookup.fail = false
        vm.refresh()
        runCurrent()
        assertEquals(setOf(1L), readyIds(vm))
        assertFalse(vm.isRefreshing.value)
        job.cancel()
    }

    @Test
    fun `items start as loading until the collection is observed`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRepo().apply { favs.value = setOf(1L) }
        val lookup = FakeLookup().apply { store[1L] = media(1L) }
        val vm = OrganizationViewModel(OrganizationKind.FAVORITES, repo, lookup, FakeDeleter(DeleteRequest.Failed))
        // WhileSubscribed with no observer keeps the initial value, so a slow load is never mistaken
        // for an empty collection on screen.
        assertEquals(OrgItemsState.Loading, vm.items.value)
    }

    // --- Selection ------------------------------------------------------------------------------

    @Test
    fun `long-press begins selection and tapping toggles by stable id`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val vm = OrganizationViewModel(OrganizationKind.TRASH, FakeRepo(), FakeLookup(), FakeDeleter(DeleteRequest.Failed))
        assertTrue(vm.selectedItems.value.isEmpty())

        vm.beginSelection(media(1))
        vm.toggleSelection(media(2))
        assertEquals(setOf(1L, 2L), vm.selectedItems.value.keys)

        // Re-tapping an already-selected id removes only it; beginning again is idempotent.
        vm.beginSelection(media(1))
        assertEquals(setOf(1L, 2L), vm.selectedItems.value.keys)
        vm.toggleSelection(media(1))
        assertEquals(setOf(2L), vm.selectedItems.value.keys)
    }

    @Test
    fun `select all takes the whole collection`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val vm = OrganizationViewModel(OrganizationKind.FAVORITES, FakeRepo(), FakeLookup(), FakeDeleter(DeleteRequest.Failed))
        vm.beginSelection(media(1))
        vm.selectAll(listOf(media(1), media(2), media(3)))
        assertEquals(setOf(1L, 2L, 3L), vm.selectedItems.value.keys)
        // An empty collection selects nothing rather than clearing a selection that is already empty.
        vm.clearSelection()
        vm.selectAll(emptyList())
        assertTrue(vm.selectedItems.value.isEmpty())
    }

    // --- Bulk undo ------------------------------------------------------------------------------

    @Test
    fun `undo removes every selected item from favorites`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRepo().apply { favs.value = setOf(5L, 6L, 7L) }
        val vm = OrganizationViewModel(OrganizationKind.FAVORITES, repo, FakeLookup(), FakeDeleter(DeleteRequest.Failed))
        vm.beginSelection(media(5))
        vm.toggleSelection(media(6))
        vm.undoSelected()
        runCurrent()
        assertTrue(repo.removed.isEmpty())
        assertEquals(setOf(7L), repo.favs.value)
        // The selection is consumed by the action, so the bar does not linger over a done job.
        assertTrue(vm.selectedItems.value.isEmpty())
        assertFalse(vm.isRestoring.value)
        // And the write is reported. Silence used to be the only outcome here: the grid changed and
        // nothing said the two items had actually left the collection.
        assertEquals(OrgMessage.MembershipChanged(2), vm.message.value)
    }

    @Test
    fun `archive undo unarchives every selected item`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRepo().apply { arch.value = setOf(3L, 4L) }
        val vm = OrganizationViewModel(OrganizationKind.ARCHIVE, repo, FakeLookup(), FakeDeleter(DeleteRequest.Failed))
        vm.selectAll(listOf(media(3), media(4)))
        vm.undoSelected()
        runCurrent()
        assertEquals(emptySet<Long>(), repo.arch.value)
    }

    @Test
    fun `trash undo restores every selected item`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRepo().apply { trash.value = setOf(4L, 8L) }
        val vm = OrganizationViewModel(OrganizationKind.TRASH, repo, FakeLookup(), FakeDeleter(DeleteRequest.Failed))
        vm.selectAll(listOf(media(4), media(8)))
        vm.undoSelected()
        runCurrent()
        assertEquals(listOf(4L, 8L), repo.restored)
    }

    // --- Permanent delete -----------------------------------------------------------------------

    @Test
    fun `a direct delete outcome forgets the items and clears the busy state`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRepo()
        val deleter = FakeDeleter(DeleteRequest.Deleted)
        val vm = OrganizationViewModel(OrganizationKind.TRASH, repo, FakeLookup(), deleter)
        vm.selectAll(listOf(media(8), media(9)))
        vm.requestDeleteSelected { org.junit.Assert.fail("Deleted outcome needs no consent") }
        runCurrent()

        // One request for the whole selection, never one per item.
        assertEquals(1, deleter.calls)
        assertEquals(setOf(8L, 9L), deleter.requested.map { it.id }.toSet())
        assertEquals(setOf(8L, 9L), repo.removed.toSet())
        assertFalse(vm.isDeleting.value)
        assertTrue(vm.selectedItems.value.isEmpty())
        // Reported as a permanent deletion, not as a membership change: the two mean different things
        // to the user and only one of them can be undone.
        assertEquals(OrgMessage.DeletedFromDevice(2), vm.message.value)
    }

    @Test
    fun `a refused delete reports the count and keeps the selection`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRepo()
        val vm = OrganizationViewModel(OrganizationKind.TRASH, repo, FakeLookup(), FakeDeleter(DeleteRequest.Failed))
        vm.selectAll(listOf(media(1), media(2), media(3)))
        var consentRequested = false
        vm.requestDeleteSelected { consentRequested = true }
        runCurrent()

        // The Failed outcome never needs consent, so the callback must not have fired…
        assertFalse(consentRequested)
        assertTrue(repo.removed.isEmpty())
        assertFalse(vm.isDeleting.value)
        // …the selection survives so the user can retry…
        assertEquals(setOf(1L, 2L, 3L), vm.selectedItems.value.keys)
        // …and the message names how many items the refusal actually covered.
        assertEquals(OrgMessage.DeleteFailed(3), vm.message.value)
    }

    @Test
    fun `delete with an empty selection does not reach the deleter`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val deleter = FakeDeleter(DeleteRequest.Deleted)
        val vm = OrganizationViewModel(OrganizationKind.TRASH, FakeRepo(), FakeLookup(), deleter)
        vm.requestDeleteSelected { org.junit.Assert.fail("nothing was selected") }
        runCurrent()
        assertEquals(0, deleter.calls)
        assertFalse(vm.isDeleting.value)
    }

    /** A Trash collection whose permanent delete always goes through the system consent prompt. */
    private fun consentTrashViewModel(repo: FakeRepo): OrganizationViewModel = OrganizationViewModel(
        OrganizationKind.TRASH,
        repo,
        FakeLookup(),
        FakeDeleter(DeleteRequest.NeedsConsent(intentSender())),
    )

    @Test
    fun `a cancelled consent forgets nothing and keeps the selection`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRepo()
        val vm = consentTrashViewModel(repo)
        vm.selectAll(listOf(media(8), media(9)))
        var consentLaunched = false
        vm.requestDeleteSelected { consentLaunched = true }
        runCurrent()

        assertTrue(consentLaunched)
        // Still busy while the system dialog is up: the action is not finished until it answers.
        assertTrue(vm.isDeleting.value)

        assertFalse(vm.finishConsent(success = false))
        assertTrue(repo.removed.isEmpty())
        assertFalse(vm.isDeleting.value)
        // The selection survives, so the user can simply try again.
        assertEquals(setOf(8L, 9L), vm.selectedItems.value.keys)
    }

    @Test
    fun `a consented success forgets exactly the items the prompt covered`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRepo()
        val vm = consentTrashViewModel(repo)
        vm.selectAll(listOf(media(8), media(9)))
        vm.requestDeleteSelected { }
        runCurrent()

        assertTrue(vm.finishConsent(success = true))
        runCurrent()
        assertEquals(setOf(8L, 9L), repo.removed.toSet())
        assertTrue(vm.selectedItems.value.isEmpty())
        assertFalse(vm.isDeleting.value)
    }

    @Test
    fun `a consent result is consumed once, so a repeat cannot delete twice`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRepo()
        val vm = consentTrashViewModel(repo)
        vm.selectAll(listOf(media(8), media(9)))
        vm.requestDeleteSelected { }
        runCurrent()

        assertTrue(vm.finishConsent(success = true))
        runCurrent()
        assertEquals(setOf(8L, 9L), repo.removed.toSet())

        // One prompt covers one request; a second result has no pending set to act on, so it must not
        // forget anything on its own.
        repo.removed.clear()
        assertTrue(vm.finishConsent(success = true))
        runCurrent()
        assertTrue(repo.removed.isEmpty())
    }

    @Test
    fun `a consent result with no pending request forgets nothing`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRepo()
        val vm = OrganizationViewModel(OrganizationKind.TRASH, repo, FakeLookup(), FakeDeleter(DeleteRequest.Failed))
        // E.g. the process was killed while the system dialog was up: an unverifiable delete is never
        // claimed, so the result is a no-op rather than a mass removal.
        vm.finishConsent(success = true)
        runCurrent()
        assertTrue(repo.removed.isEmpty())
        assertFalse(vm.isDeleting.value)
    }
}
