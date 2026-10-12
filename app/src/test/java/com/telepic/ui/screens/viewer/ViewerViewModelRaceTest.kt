package com.telepic.ui.screens.viewer

import android.net.Uri
import com.telepic.data.cloud.CloudRepository
import com.telepic.data.media.LocalMediaLookup
import com.telepic.data.media.NeighborDirection
import com.telepic.data.organization.MediaOrganizationRepository
import com.telepic.domain.cloud.CloudMedia
import com.telepic.domain.cloud.CloudMediaType
import com.telepic.domain.cloud.CloudPreview
import com.telepic.domain.cloud.CloudStatus
import com.telepic.domain.cloud.LocalDownloadedMedia
import com.telepic.domain.cloud.TelepicCloudDestination
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.MediaType
import com.telepic.navigation.MediaSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Viewer's stale-write invariants under navigation: a slow item load or a cloud download that
 * finishes *after* the user swiped on must never resurrect the earlier item's state. Every late
 * write is dropped by source identity (or by the cancelled load job).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ViewerViewModelRaceTest {

    private fun local(id: Long) = LocalMedia(
        id = id, contentUri = Uri.parse("content://media/$id"), type = MediaType.PHOTO, mimeType = "image/jpeg",
        displayName = "f$id", dateMillis = 1_000L, durationMillis = null, width = 1, height = 1,
        sizeBytes = 1L, bucketId = null, bucketName = null, relativePath = null,
    )

    private fun cloudItem(messageId: Long) = CloudMedia(
        messageId = messageId, chatId = 100L, mediaType = CloudMediaType.IMAGE, mimeType = "image/jpeg",
        fileName = null, sizeBytes = null, width = null, height = null, durationMs = null,
        dateEpochSec = 2L, previewFileId = 9, originalFileId = 9,
    )

    /** byId(id) for gated ids suspends until the test releases the matching gate. */
    private class GatedLookup(
        private val items: Map<Long, LocalMedia>,
        private val gates: Map<Long, CompletableDeferred<Unit>> = emptyMap(),
    ) : LocalMediaLookup {
        override suspend fun byId(id: Long): LocalMedia? {
            gates[id]?.await()
            return items[id]
        }
        override suspend fun neighborId(id: Long, direction: NeighborDirection, bucketId: Long?): Long? = null
        override suspend fun byIdList(ids: Collection<Long>): List<LocalMedia> = ids.mapNotNull { items[it] }
    }

    private class GatedCloud(
        private val manifest: List<CloudMedia>,
        private val downloadGate: CompletableDeferred<Unit>,
        private val downloadResult: LocalDownloadedMedia? = LocalDownloadedMedia("/tmp/original.jpg", 100L, 7L),
    ) : CloudRepository {
        override val status: StateFlow<CloudStatus> = MutableStateFlow(CloudStatus.Ready).asStateFlow()
        override val media: Flow<List<CloudMedia>> = flowOf(manifest)
        override val destinationTitle: StateFlow<String?> = MutableStateFlow<String?>("Telepic Backup").asStateFlow()
        override suspend fun prepare() = Unit
        override suspend fun refresh() = Unit
        override suspend fun ensureDestination(): TelepicCloudDestination? = null
        override suspend fun confirmDestination(): TelepicCloudDestination? = null
        override suspend fun createNewDestination(): TelepicCloudDestination? = null
        override suspend fun getPreview(media: CloudMedia): CloudPreview? = CloudPreview("/tmp/prev_${media.messageId}", null, null)
        override suspend fun downloadOriginal(media: CloudMedia): LocalDownloadedMedia? {
            downloadGate.await()
            return downloadResult
        }
        override suspend fun uploadMedia(
            request: com.telepic.domain.cloud.CloudUploadRequest,
            onProgress: (com.telepic.domain.cloud.CloudUploadProgress) -> Unit,
            onSent: suspend (Long, Long) -> Unit,
        ) = null
        override suspend fun confirmUpload(
            chatId: Long,
            messageId: Long,
            mediaType: com.telepic.domain.cloud.CloudMediaType,
            contentHash: String?,
            contentSizeBytes: Long?,
        ): com.telepic.domain.cloud.CloudUploadResult = throw NotImplementedError()
    }

    /**
     * Runs [duringWrite] from *inside* the trash write, which is how the race is made deterministic:
     * the swipe is guaranteed to happen before the write returns, with no reliance on dispatcher
     * timing or on when a suspended coroutine happens to resume.
     */
    private class SwipingOrg(private val duringWrite: suspend () -> Unit) : MediaOrganizationRepository {
        override val favoriteIds: Flow<Set<Long>> = flowOf(emptySet())
        override val archivedIds: Flow<Set<Long>> = flowOf(emptySet())
        override val trashedIds: Flow<Set<Long>> = flowOf(emptySet())
        override suspend fun hiddenIds(): Set<Long> = emptySet()
        override suspend fun isFavorite(id: Long): Boolean = false
        override suspend fun isArchived(id: Long): Boolean = false
        override suspend fun isTrashed(id: Long): Boolean = false
        override suspend fun setFavorite(id: Long, favorite: Boolean) = Unit
        override suspend fun setArchived(id: Long, archived: Boolean) = Unit
        override suspend fun moveToTrash(id: Long) { duringWrite() }
        override suspend fun restoreFromTrash(id: Long) = Unit
        override suspend fun markDeletedFromStore(id: Long) = Unit
        override suspend fun remove(id: Long) = Unit
    }

    private fun completedGate(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().apply { complete(Unit) }

    @Test
    fun `a slow initial load never lands after navigation cancels it`() = runBlocking {
        val gate5 = CompletableDeferred<Unit>()
        val vm = ViewerViewModel(
            initialSource = MediaSource.Local(5),
            localLookup = GatedLookup(mapOf(5L to local(5), 6L to local(6)), gates = mapOf(5L to gate5)),
            cloudRepository = GatedCloud(emptyList(), completedGate()),
        )
        // Load of item 5 is parked inside byId(5); navigate to the fast item 6.
        vm.open(MediaSource.Local(6))
        val ready = vm.uiState.first { it.source == MediaSource.Local(6) && it.status == ViewerStatus.READY }
        assertEquals(6L, (ready.item as ViewerItem.Local).media.id)
        // Releasing the stale gate must not overwrite the current item.
        gate5.complete(Unit)
        assertEquals(MediaSource.Local(6), vm.uiState.value.source)
        assertEquals(ViewerStatus.READY, vm.uiState.value.status)
    }

    @Test
    fun `a download that finishes after navigation does not resurrect the old item`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val cloud = GatedCloud(listOf(cloudItem(7), cloudItem(8)), gate)
        val vm = ViewerViewModel(
            initialSource = MediaSource.Cloud(100, 7),
            localLookup = GatedLookup(emptyMap()),
            cloudRepository = cloud,
        )
        vm.uiState.first { it.source == MediaSource.Cloud(100, 7) && it.status == ViewerStatus.READY }
        vm.downloadOriginal()
        assertEquals(CloudDownload.Downloading, vm.uiState.value.download)
        // Swipe on while the download is still in flight, then let it finish for the old item.
        vm.open(MediaSource.Cloud(100, 8))
        vm.uiState.first { it.source == MediaSource.Cloud(100, 8) && it.status == ViewerStatus.READY }
        gate.complete(Unit)
        val state = vm.uiState.value
        assertEquals(MediaSource.Cloud(100, 8), state.source)
        assertEquals(CloudDownload.Idle, state.download)
        val item = state.item as ViewerItem.Cloud
        assertNull(item.originalPath)
    }

    @Test
    fun `a trash write that lands after the user swipes does not publish its confirmation`() = runBlocking {
        lateinit var vm: ViewerViewModel
        // The repository swipes to item 9 from inside the write for item 5, so the ordering is
        // fixed rather than dependent on when a suspended coroutine resumes.
        val org = SwipingOrg { vm.open(MediaSource.Local(9)) }
        vm = ViewerViewModel(
            initialSource = MediaSource.Local(5),
            localLookup = GatedLookup(mapOf(5L to local(5), 9L to local(9))),
            cloudRepository = GatedCloud(emptyList(), completedGate()),
            organizationRepository = org,
        )
        vm.uiState.first { it.status == ViewerStatus.READY }

        vm.moveToTrash()

        // The swipe really did happen before the guard ran...
        assertEquals(MediaSource.Local(9), vm.uiState.value.source)
        // ...so item 5's write was dropped, and "Moved to trash" must not appear over item 9.
        // Publishing it there reports an outcome for something the user is no longer looking at, and
        // the Undo it offers would act on item 9 rather than restoring item 5.
        assertNull(vm.message.value)
    }
}
