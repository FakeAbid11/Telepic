package com.telepic.ui.screens.viewer

import android.content.Context
import android.net.Uri
import androidx.paging.PagingData
import androidx.test.core.app.ApplicationProvider
import com.telepic.data.cloud.CloudRepository
import com.telepic.data.media.CustomAlbumRepository
import com.telepic.data.media.LocalMediaLookup
import com.telepic.data.media.NeighborDirection
import com.telepic.domain.backup.MediaBackupVisualState
import com.telepic.domain.cloud.CloudMedia
import com.telepic.domain.cloud.CloudMediaType
import com.telepic.domain.cloud.CloudPreview
import com.telepic.domain.cloud.CloudStatus
import com.telepic.domain.cloud.LocalDownloadedMedia
import com.telepic.domain.cloud.TelepicCloudDestination
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.MediaType
import com.telepic.navigation.MediaSource
import com.telepic.navigation.OrganizationKind
import com.telepic.navigation.ViewerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Viewer resolution by *typed source*: a local id via the lookup, a cloud (chatId, messageId) via the
 * manifest — each strictly in its own identity space, with missing → MISSING (never a substitute).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ViewerViewModelTest {

    private fun local(id: Long, type: MediaType = MediaType.PHOTO) = LocalMedia(
        id = id, contentUri = Uri.parse("content://media/$id"), type = type, mimeType = "image/jpeg",
        displayName = "f$id", dateMillis = 1_000L, durationMillis = null, width = 1, height = 1,
        sizeBytes = 1L, bucketId = null, bucketName = null, relativePath = null,
    )

    private class FakeLookup(
        private val items: Map<Long, LocalMedia>,
        private val newer: Map<Long, Long?> = emptyMap(),
        private val older: Map<Long, Long?> = emptyMap(),
        private val order: List<Long> = emptyList(),
    ) : LocalMediaLookup {
        val bucketSeen = mutableListOf<Long?>()
        override suspend fun byId(id: Long): LocalMedia? = items[id]
        override suspend fun neighborId(id: Long, direction: NeighborDirection, bucketId: Long?): Long? {
            bucketSeen += bucketId
            return if (direction == NeighborDirection.NEWER) newer[id] else older[id]
        }
        // Mirrors the real newest-first contract: return the requested ids in [order] when one is set,
        // so collection-scope neighbors can be exercised deterministically.
        override suspend fun byIdList(ids: Collection<Long>): List<LocalMedia> {
            val requested = ids.toSet()
            val seq = if (order.isNotEmpty()) order.filter { it in requested } else ids.toList()
            return seq.mapNotNull { items[it] }
        }
    }

    private class FakeOrg(
        val favs: Set<Long>,
        val arch: Set<Long>,
        val trash: Set<Long>,
    ) : com.telepic.data.organization.MediaOrganizationRepository {
        /** Records every trash write so a test can prove the action reached the repository. */
        val trashWrites = mutableListOf<Long>()
        /** Records every restore, so the confirmation's Undo can be proven to reach the repository. */
        val restoreWrites = mutableListOf<Long>()
        override val favoriteIds: Flow<Set<Long>> = flowOf(favs)
        override val archivedIds: Flow<Set<Long>> = flowOf(arch)
        override val trashedIds: Flow<Set<Long>> = flowOf(trash)
        override suspend fun hiddenIds(): Set<Long> = arch + trash
        override suspend fun isFavorite(id: Long) = id in favs
        override suspend fun isArchived(id: Long) = id in arch
        override suspend fun isTrashed(id: Long) = id in trash
        override suspend fun setFavorite(id: Long, favorite: Boolean) = Unit
        override suspend fun setArchived(id: Long, archived: Boolean) = Unit
        override suspend fun moveToTrash(id: Long) { trashWrites += id }
        override suspend fun restoreFromTrash(id: Long) { restoreWrites += id }
        override suspend fun markDeletedFromStore(id: Long) = Unit
        override suspend fun remove(id: Long) = Unit
    }

    /**
     * A user-created album holding a fixed set of member ids. Only membership matters to the Viewer, so
     * every write is a no-op — the point is that [observeMediaIds] drives the neighbour order.
     */
    private class FakeCustomAlbums(private val memberIds: List<Long>) : CustomAlbumRepository {
        override val albums: Flow<List<CustomAlbum>> = flowOf(emptyList())
        override suspend fun create(name: String): Long? = null
        override suspend fun rename(albumId: Long, name: String) = Unit
        override suspend fun delete(albumId: Long) = Unit
        override suspend fun addMedia(albumId: Long, mediaIds: List<Long>): Int = 0
        override suspend fun removeMedia(albumId: Long, localMediaId: Long) = Unit
        override fun observeName(albumId: Long): Flow<String?> = flowOf(null)
        override fun observeMediaIds(albumId: Long): Flow<List<Long>> = flowOf(memberIds)
        override suspend fun mediaIds(albumId: Long): List<Long> = memberIds
        override fun mediaInAlbum(albumId: Long): Flow<PagingData<PhotosItem>> = flowOf(PagingData.empty())
        override fun refresh() = Unit
    }

    private class FakeCloud(private val manifest: List<CloudMedia>) : CloudRepository {
        override val status: StateFlow<CloudStatus> = MutableStateFlow(CloudStatus.Ready).asStateFlow()
        override val media: Flow<List<CloudMedia>> = flowOf(manifest)
        override val destinationTitle: StateFlow<String?> = MutableStateFlow<String?>("Telepic Backup").asStateFlow()
        override suspend fun prepare() = Unit
        override suspend fun refresh() = Unit
        override suspend fun ensureDestination(): TelepicCloudDestination? = null
        override suspend fun confirmDestination(): TelepicCloudDestination? = null
        override suspend fun createNewDestination(): TelepicCloudDestination? = null
        override suspend fun getPreview(media: CloudMedia): CloudPreview? = CloudPreview("/tmp/prev_${media.messageId}", null, null)
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

    private fun cloudItem(messageId: Long) = CloudMedia(
        messageId = messageId, chatId = 100L, mediaType = CloudMediaType.IMAGE, mimeType = "image/jpeg",
        fileName = null, sizeBytes = null, width = null, height = null, durationMs = null,
        dateEpochSec = 2L, previewFileId = 9, originalFileId = 9,
    )

    @Test
    fun `resolves a local photo with neighbors and no download`() = runBlocking {
        val vm = ViewerViewModel(
            initialSource = MediaSource.Local(5),
            localLookup = FakeLookup(mapOf(5L to local(5)), newer = mapOf(5L to 6L), older = mapOf(5L to 4L)),
            cloudRepository = FakeCloud(emptyList()),
        )
        val state = vm.uiState.first { it.status != ViewerStatus.LOADING }
        assertEquals(ViewerStatus.READY, state.status)
        assertEquals(ViewerKind.PHOTO, state.kind)
        assertEquals(MediaSource.Local(6), state.previous)
        assertEquals(MediaSource.Local(4), state.next)
        assertTrue(state.item is ViewerItem.Local)
    }

    @Test
    fun `a missing local item is MISSING, not another item`() = runBlocking {
        val vm = ViewerViewModel(MediaSource.Local(99), FakeLookup(emptyMap()), FakeCloud(emptyList()))
        val state = vm.uiState.first { it.status != ViewerStatus.LOADING }
        assertEquals(ViewerStatus.MISSING, state.status)
        assertNull(state.item)
    }

    @Test
    fun `a cloud item resolves by remote identity with a preview`() = runBlocking {
        val vm = ViewerViewModel(
            initialSource = MediaSource.Cloud(100, 7),
            localLookup = FakeLookup(emptyMap()),
            cloudRepository = FakeCloud(listOf(cloudItem(6), cloudItem(7), cloudItem(8))),
        )
        val state = vm.uiState.first { it.status != ViewerStatus.LOADING }
        assertEquals(ViewerStatus.READY, state.status)
        val cloud = state.item as ViewerItem.Cloud
        assertEquals(7L, cloud.media.messageId)
        assertEquals("/tmp/prev_7", cloud.previewPath)
        // cloud neighbors keep the same chat id and advance the message id
        assertEquals(MediaSource.Cloud(100, 6), state.previous)
        assertEquals(MediaSource.Cloud(100, 8), state.next)
    }

    @Test
    fun `a cloud original downloads only on request and reports unavailable honestly`() = runBlocking {
        val vm = ViewerViewModel(
            MediaSource.Cloud(100, 7),
            FakeLookup(emptyMap()),
            FakeCloud(listOf(cloudItem(7))),
        )
        vm.uiState.first { it.status != ViewerStatus.LOADING }
        // No download until asked.
        assertEquals(CloudDownload.Idle, vm.uiState.value.download)
        vm.downloadOriginal()
        val after = vm.uiState.first { it.download != CloudDownload.Idle }
        // Defensive TDLib returns null → honest Unavailable, never a fabricated success.
        assertEquals(CloudDownload.Unavailable, after.download)
    }

    @Test
    fun `a cloud item absent from the manifest is MISSING`() = runBlocking {
        val vm = ViewerViewModel(MediaSource.Cloud(100, 999), FakeLookup(emptyMap()), FakeCloud(listOf(cloudItem(7))))
        val state = vm.uiState.first { it.status != ViewerStatus.LOADING }
        assertEquals(ViewerStatus.MISSING, state.status)
    }

    @Test
    fun `global scope walks the timeline and passes no bucket`() = runBlocking {
        val lookup = FakeLookup(
            mapOf(5L to local(5)),
            newer = mapOf(5L to 6L),
            older = mapOf(5L to 4L),
        )
        val vm = ViewerViewModel(MediaSource.Local(5), lookup, FakeCloud(emptyList()), scope = ViewerScope.Global)
        val state = vm.uiState.first { it.status != ViewerStatus.LOADING }
        assertEquals(MediaSource.Local(6), state.previous)
        assertEquals(MediaSource.Local(4), state.next)
        // The global timeline lookup is bucket-agnostic on both sides.
        assertEquals(listOf<Long?>(null, null), lookup.bucketSeen)
    }

    @Test
    fun `bucket scope confines next and previous to the album bucket`() = runBlocking {
        val lookup = FakeLookup(
            mapOf(5L to local(5)),
            newer = mapOf(5L to 9L),
            older = mapOf(5L to 2L),
        )
        val vm = ViewerViewModel(MediaSource.Local(5), lookup, FakeCloud(emptyList()), scope = ViewerScope.Bucket(7L))
        val state = vm.uiState.first { it.status != ViewerStatus.LOADING }
        assertEquals(MediaSource.Local(9), state.previous)
        assertEquals(MediaSource.Local(2), state.next)
        // Both neighbor queries carried the originating bucket, so they stay inside the album.
        assertEquals(listOf<Long?>(7L, 7L), lookup.bucketSeen)
    }

    @Test
    fun `collection scope follows the curated set's own order, not the timeline`() = runBlocking {
        val items = mapOf(1L to local(1), 2L to local(2), 3L to local(3))
        // Newest-first order of the Favorites set: 3, 1, 2. Opening the middle item (1).
        val lookup = FakeLookup(items, order = listOf(3L, 1L, 2L))
        val vm = ViewerViewModel(
            initialSource = MediaSource.Local(1),
            localLookup = lookup,
            cloudRepository = FakeCloud(emptyList()),
            organizationRepository = FakeOrg(favs = setOf(1L, 2L, 3L), arch = emptySet(), trash = emptySet()),
            scope = ViewerScope.Collection(OrganizationKind.FAVORITES),
        )
        val state = vm.uiState.first { it.status != ViewerStatus.LOADING }
        // Previous is the newer favorite (3), next the older (2) — the collection order, not _ID.
        assertEquals(MediaSource.Local(3), state.previous)
        assertEquals(MediaSource.Local(2), state.next)
    }

    @Test
    fun `collection scope at the newest item has no previous and one next`() = runBlocking {
        val items = mapOf(1L to local(1), 2L to local(2), 3L to local(3))
        val lookup = FakeLookup(items, order = listOf(3L, 1L, 2L))
        val vm = ViewerViewModel(
            initialSource = MediaSource.Local(3),
            localLookup = lookup,
            cloudRepository = FakeCloud(emptyList()),
            organizationRepository = FakeOrg(favs = setOf(1L, 2L, 3L), arch = emptySet(), trash = emptySet()),
            scope = ViewerScope.Collection(OrganizationKind.FAVORITES),
        )
        val state = vm.uiState.first { it.status != ViewerStatus.LOADING }
        assertNull(state.previous) // newest in the collection
        assertEquals(MediaSource.Local(1), state.next)
    }

    @Test
    fun `album scope confines next and previous to the user-created album`() = runBlocking {
        val items = mapOf(1L to local(1), 2L to local(2), 3L to local(3), 9L to local(9))
        // The album holds 1, 2 and 3 — not 9. Its newest-first order is 3, 1, 2, so opening 1 must
        // step to 3 and 2 rather than to whatever the global timeline happens to neighbour.
        val lookup = FakeLookup(items, order = listOf(3L, 1L, 2L))
        val vm = ViewerViewModel(
            initialSource = MediaSource.Local(1),
            localLookup = lookup,
            cloudRepository = FakeCloud(emptyList()),
            scope = ViewerScope.Album(albumId = 7L),
            customAlbumRepository = FakeCustomAlbums(memberIds = listOf(1L, 2L, 3L)),
        )

        val state = vm.uiState.first { it.status != ViewerStatus.LOADING }

        assertEquals(MediaSource.Local(3), state.previous)
        assertEquals(MediaSource.Local(2), state.next)
    }

    @Test
    fun `album scope without a repository falls back to the timeline rather than dead-ending`() = runBlocking {
        // The album repository is not wired, so there is no membership to walk. Falling back to the
        // global timeline keeps the Viewer navigable; returning null here would strand the user on one
        // photo with no way forward.
        val lookup = FakeLookup(mapOf(5L to local(5)), newer = mapOf(5L to 9L), older = mapOf(5L to 2L))
        val vm = ViewerViewModel(
            initialSource = MediaSource.Local(5),
            localLookup = lookup,
            cloudRepository = FakeCloud(emptyList()),
            scope = ViewerScope.Album(albumId = 7L),
        )

        val state = vm.uiState.first { it.status != ViewerStatus.LOADING }

        assertEquals(MediaSource.Local(9), state.previous)
        assertEquals(MediaSource.Local(2), state.next)
    }

    @Test
    fun `an id that is no longer in the album has no neighbours`() = runBlocking {
        // 9 exists in the library but is not a member of this album.
        val lookup = FakeLookup(mapOf(9L to local(9)), order = listOf(3L, 1L, 2L))
        val vm = ViewerViewModel(
            initialSource = MediaSource.Local(9),
            localLookup = lookup,
            cloudRepository = FakeCloud(emptyList()),
            scope = ViewerScope.Album(albumId = 7L),
            customAlbumRepository = FakeCustomAlbums(memberIds = listOf(1L, 2L, 3L)),
        )

        val state = vm.uiState.first { it.status != ViewerStatus.LOADING }

        assertNull(state.previous)
        assertNull(state.next)
    }

    /** A deterministic metadata reader for the Details flow tests. */
    private class FakeMetadataReader(private val metadata: com.telepic.data.media.MediaMetadata) :
        com.telepic.data.media.MediaMetadataReader {
        override suspend fun read(uri: Uri) = metadata
    }

    @Test
    fun `details merges media fields with EXIF capture, camera and gps`() = runBlocking {
        val reader = FakeMetadataReader(
            com.telepic.data.media.MediaMetadata(
                cameraMake = "Google",
                cameraModel = "Pixel 8",
                captureMillis = 5_000L,
                libraryAddedMillis = 7_000L,
                location = com.telepic.domain.media.GeoLocation(48.85, 2.35),
            ),
        )
        val vm = ViewerViewModel(
            initialSource = MediaSource.Local(5),
            localLookup = FakeLookup(mapOf(5L to local(5))),
            cloudRepository = FakeCloud(emptyList()),
            metadataReader = reader,
        )
        vm.uiState.first { it.status != ViewerStatus.LOADING }
        assertNull(vm.details.value) // not loaded until requested

        vm.loadDetails()
        val details = vm.details.first { it != null }!!
        assertEquals("f5", details.fileName)
        assertEquals(5_000L, details.captureMillis) // EXIF capture, not the library date
        assertEquals(7_000L, details.libraryAddedMillis) // MediaStore DATE_ADDED, shown separately
        assertEquals("Google", details.cameraMake)
        assertEquals(48.85, details.latitude!!, 0.0001)
        assertEquals(2.35, details.longitude!!, 0.0001)
    }

    @Test
    fun `details reports no capture time when the file has no EXIF datetime`() = runBlocking {
        val vm = ViewerViewModel(
            initialSource = MediaSource.Local(5),
            localLookup = FakeLookup(mapOf(5L to local(5))),
            cloudRepository = FakeCloud(emptyList()),
            metadataReader = FakeMetadataReader(com.telepic.data.media.MediaMetadata()), // all-null EXIF
        )
        vm.uiState.first { it.status != ViewerStatus.LOADING }
        vm.loadDetails()
        val details = vm.details.first { it != null }!!
        assertNull(details.captureMillis) // never fabricate; the library date is kept separate
        assertEquals(1_000L, details.libraryDateMillis)
        assertNull(details.latitude)
        assertNull(details.cameraMake)
    }

    @Test
    fun `navigating to another item clears the previous item's details`() = runBlocking {
        val vm = ViewerViewModel(
            initialSource = MediaSource.Local(5),
            localLookup = FakeLookup(mapOf(5L to local(5), 6L to local(6))),
            cloudRepository = FakeCloud(emptyList()),
            metadataReader = FakeMetadataReader(com.telepic.data.media.MediaMetadata(cameraMake = "X")),
        )
        vm.uiState.first { it.status != ViewerStatus.LOADING }
        vm.loadDetails()
        vm.details.first { it != null }
        vm.open(MediaSource.Local(6))
        vm.uiState.first { it.source == MediaSource.Local(6) && it.status != ViewerStatus.LOADING }
        assertNull(vm.details.value) // cleared on navigation
    }

    @Test
    fun `local details carry the kind, duration and folder`() = runBlocking {
        val video = local(5).copy(
            type = MediaType.VIDEO,
            durationMillis = 4_000L,
            bucketName = "Camera",
            relativePath = "DCIM/Camera/",
        )
        val vm = ViewerViewModel(
            initialSource = MediaSource.Local(5),
            localLookup = FakeLookup(mapOf(5L to video)),
            cloudRepository = FakeCloud(emptyList()),
            metadataReader = FakeMetadataReader(com.telepic.data.media.MediaMetadata()),
        )
        vm.uiState.first { it.status != ViewerStatus.LOADING }

        vm.loadDetails()
        val details = vm.details.first { it != null }!!

        assertEquals(MediaType.VIDEO, details.mediaType)
        assertEquals(4_000L, details.durationMillis)
        assertEquals("Camera", details.bucketName)
        assertEquals("DCIM/Camera/", details.relativePath)
        // No backup status repository is wired here, so the state stays unknown. It must not become
        // NONE: "the queue has no row" is not the same claim as "this is not backed up".
        assertNull(details.backupState)
    }

    @Test
    fun `details are produced for a cloud item from its manifest entry`() = runBlocking {
        val media = CloudMedia(
            messageId = 77L,
            chatId = 9L,
            mediaType = CloudMediaType.VIDEO,
            mimeType = "video/mp4",
            fileName = "clip.mp4",
            sizeBytes = 4_096L,
            width = 1920,
            height = 1080,
            durationMs = 12_000L,
            dateEpochSec = 1_700_000_000L,
            previewFileId = 1,
            originalFileId = 2,
            isDownloaded = false,
        )
        val vm = ViewerViewModel(
            initialSource = MediaSource.Cloud(9L, 77L),
            localLookup = FakeLookup(emptyMap()),
            cloudRepository = FakeCloud(listOf(media)),
        )
        vm.uiState.first { it.status != ViewerStatus.LOADING }

        vm.loadDetails()
        val details = vm.details.first { it != null }!!

        // A cloud item has no local file to open, so this used to return early and leave the sheet on
        // its spinner forever. Everything now comes from the manifest entry.
        assertTrue(details.isCloud)
        assertEquals("clip.mp4", details.fileName)
        assertEquals(MediaType.VIDEO, details.mediaType)
        assertEquals(12_000L, details.durationMillis)
        assertEquals(9L, details.cloudChatId)
        assertEquals(77L, details.cloudMessageId)
        assertFalse(details.cloudOriginalDownloaded)
        // A manifest row exists only because Telegram confirmed the message, so the item is backed up
        // by definition — and the local-only fields are absent rather than guessed at.
        assertEquals(MediaBackupVisualState.BACKED_UP, details.backupState)
        assertNull(details.cameraMake)
        assertNull(details.captureMillis)
        assertNull(details.latitude)
        assertNull(details.bucketName)
    }

    @Test
    fun `display model for a local source is its content uri and cloud sources prefetch nothing`() = runBlocking {
        val lookup = FakeLookup(mapOf(4L to local(4)))
        val vm = ViewerViewModel(MediaSource.Local(5), lookup, FakeCloud(listOf(cloudItem(7))))
        assertEquals(Uri.parse("content://media/4"), vm.displayModelFor(MediaSource.Local(4)))
        assertNull(vm.displayModelFor(MediaSource.Local(99))) // gone items warm nothing
        assertNull(vm.displayModelFor(MediaSource.Cloud(100, 7))) // no TDLib request per neighbor
    }

    @Test
    fun `move to trash reaches the repository and flips the viewer flag`() = runBlocking {
        val org = FakeOrg(favs = emptySet(), arch = emptySet(), trash = emptySet())
        val vm = ViewerViewModel(
            initialSource = MediaSource.Local(5),
            localLookup = FakeLookup(mapOf(5L to local(5))),
            cloudRepository = FakeCloud(emptyList()),
            organizationRepository = org,
        )
        vm.uiState.first { it.status != ViewerStatus.LOADING }

        vm.moveToTrash()

        // The write runs in viewModelScope, so await the flag it publishes rather than polling.
        assertTrue(vm.flags.first { it.isTrashed }.isTrashed)
        assertEquals(listOf(5L), org.trashWrites)
        // Flag-only, like favourite: the user stays on the item instead of being kicked out, and the
        // grids behind react to the Room flow on their own.
        assertEquals(ViewerStatus.READY, vm.uiState.value.status)
    }

    @Test
    fun `the trash flag mirrors the item's stored state when the viewer opens it`() = runBlocking {
        val vm = ViewerViewModel(
            initialSource = MediaSource.Local(5),
            localLookup = FakeLookup(mapOf(5L to local(5))),
            cloudRepository = FakeCloud(emptyList()),
            organizationRepository = FakeOrg(favs = emptySet(), arch = emptySet(), trash = setOf(5L)),
        )
        vm.uiState.first { it.status != ViewerStatus.LOADING }
        assertTrue(vm.flags.first { it.isTrashed }.isTrashed)
    }

    @Test
    fun `trashing confirms the outcome instead of succeeding silently`() = runBlocking {
        val org = FakeOrg(favs = emptySet(), arch = emptySet(), trash = emptySet())
        val vm = ViewerViewModel(
            initialSource = MediaSource.Local(5),
            localLookup = FakeLookup(mapOf(5L to local(5))),
            cloudRepository = FakeCloud(emptyList()),
            organizationRepository = org,
        )
        vm.uiState.first { it.status != ViewerStatus.LOADING }

        vm.moveToTrash()

        // The Viewer stays on the item and it keeps rendering unchanged, so this message is the only
        // evidence the action happened at all. Its absence was the reported "the button does
        // nothing": the write landed, and nothing on screen said so.
        assertEquals(ViewerMessage.MovedToTrash, vm.message.first { it != null })
        assertEquals(listOf(5L), org.trashWrites)
    }

    @Test
    fun `undo restores the item and drops the confirmation`() = runBlocking {
        val org = FakeOrg(favs = emptySet(), arch = emptySet(), trash = emptySet())
        val vm = ViewerViewModel(
            initialSource = MediaSource.Local(5),
            localLookup = FakeLookup(mapOf(5L to local(5))),
            cloudRepository = FakeCloud(emptyList()),
            organizationRepository = org,
        )
        vm.uiState.first { it.status != ViewerStatus.LOADING }

        vm.moveToTrash()
        // Await the trashed state before undoing, so both transitions are real and the second action
        // reads the flags the first one published.
        assertEquals(ViewerMessage.MovedToTrash, vm.message.first { it != null })
        assertTrue(vm.flags.first { it.isTrashed }.isTrashed)

        vm.restoreFromTrash()

        assertFalse(vm.flags.first { !it.isTrashed }.isTrashed)
        // The message is cleared by the action body *after* the repository call returns, so awaiting
        // its disappearance proves the restore landed rather than the bar merely being hidden.
        assertNull(vm.message.first { it == null })
        assertEquals(listOf(5L), org.restoreWrites)
    }

    @Test
    fun `the confirmation does not follow the user to the next item`() = runBlocking {
        val vm = ViewerViewModel(
            initialSource = MediaSource.Local(5),
            localLookup = FakeLookup(mapOf(5L to local(5), 9L to local(9))),
            cloudRepository = FakeCloud(emptyList()),
            organizationRepository = FakeOrg(favs = emptySet(), arch = emptySet(), trash = emptySet()),
        )
        vm.uiState.first { it.status != ViewerStatus.LOADING }
        vm.moveToTrash()
        assertEquals(ViewerMessage.MovedToTrash, vm.message.first { it != null })

        vm.open(MediaSource.Local(9))

        // "Moved to trash" describes item 5; leaving it up over item 9 would report an outcome for
        // something the user is no longer looking at.
        assertNull(vm.message.value)
    }
}
