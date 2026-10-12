package com.telepic.ui.screens.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telepic.data.backup.BackupStatusRepository
import com.telepic.data.cloud.CloudRepository
import com.telepic.data.media.LocalMediaLookup
import com.telepic.data.media.MediaMetadata
import com.telepic.data.media.MediaMetadataReader
import com.telepic.data.media.NeighborDirection
import com.telepic.data.organization.MediaOrganizationRepository
import com.telepic.data.restore.RestoreFailure
import com.telepic.data.restore.RestoreRepository
import com.telepic.data.restore.RestoreResult
import com.telepic.domain.backup.MediaBackupVisualState
import com.telepic.domain.cloud.CloudMedia
import com.telepic.domain.cloud.CloudMediaType
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.MediaType
import com.telepic.navigation.MediaSource
import com.telepic.navigation.OrganizationKind
import com.telepic.navigation.ViewerScope
import com.telepic.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The kind of media the Viewer is showing (a GIF stays distinct from a static photo). */
enum class ViewerKind { PHOTO, VIDEO, GIF }

enum class ViewerStatus { LOADING, READY, MISSING }

/** The download lifecycle for a cloud original — success only with a verified local path. */
sealed interface CloudDownload {
    data object Idle : CloudDownload
    data object Downloading : CloudDownload
    data class Available(val localPath: String) : CloudDownload
    data object Unavailable : CloudDownload // honest: unsupported on this build / failed
}

/** A resolved media item for display: a local content URI, or a cloud preview + optional original. */
sealed interface ViewerItem {
    data class Local(val media: LocalMedia) : ViewerItem
    data class Cloud(
        val media: CloudMedia,
        val previewPath: String?,
        val originalPath: String?,
    ) : ViewerItem
}

/** Organization flags for the current local item (Favorites / Archive / Trash). Independent of backup. */
data class ViewerFlags(
    val isFavorite: Boolean = false,
    val isArchived: Boolean = false,
    val isTrashed: Boolean = false,
)

/**
 * A transient outcome the Viewer must report, because the action leaves no other trace on screen.
 *
 * Trashing is the case that forced this: the Viewer deliberately stays on the item (popping before
 * the write hid failures and yanked the user back), and the item keeps rendering exactly as before —
 * so without a message the user taps "Move to trash", the dialog closes, and *nothing* appears to
 * happen. A type rather than a magic string, so the screen owns the wording.
 */
sealed interface ViewerMessage {
    /** The item was written to Trash. Posted only after the write returned — never optimistically. */
    data object MovedToTrash : ViewerMessage
}

data class ViewerUiState(
    val source: MediaSource,
    val status: ViewerStatus = ViewerStatus.LOADING,
    val kind: ViewerKind = ViewerKind.PHOTO,
    val item: ViewerItem? = null,
    val previous: MediaSource? = null,
    val next: MediaSource? = null,
    val download: CloudDownload = CloudDownload.Idle,
    val dateMillis: Long? = null,
) {
    /**
     * True when this item is a video with a file that can actually be played right now. The state in
     * which [VideoViewer] owns the bottom bar: its Compose transport carries prev/next plus the cloud
     * save action, so the generic [ViewerBottomBar] must *not* render as well — that stacking is the
     * double-bar defect the transport replaced.
     *
     * A cloud video whose original has not been downloaded is not playable (it shows the honest
     * download step instead), so the generic chrome keeps owning navigation there.
     */
    val isPlayableVideo: Boolean
        get() = kind == ViewerKind.VIDEO && when (item) {
            is ViewerItem.Local -> true
            is ViewerItem.Cloud -> item.originalPath != null
            null -> false
        }
}

/**
 * Presentation metadata for the Viewer's Details sheet. Every field is null when it cannot be
 * obtained reliably — the UI omits null rows rather than showing a fabricated value. [captureMillis]
 * comes only from EXIF (never the file-modified time), and [latitude]/[longitude] only from EXIF GPS.
 *
 * The sheet covers **both** sources, which is why the cloud fields exist: a cloud item has no local
 * file to open, so previously `loadDetails()` returned early for it and the sheet sat on its spinner
 * forever. Its details come from the manifest entry instead, and [isCloud] tells the sheet which set
 * of rows is meaningful.
 *
 * [backupState] is null when the backup queue holds no row for the item, and the sheet then omits the
 * row entirely. That is deliberate: the queue is the only source of `BACKED_UP`, but an *absent* row
 * is not proof of "not backed up" — after a reinstall the queue is empty while the cloud still holds
 * every item, so rendering "Not backed up" there would be a false claim about the user's data.
 */
data class MediaDetails(
    val fileName: String?,
    val mimeType: String?,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val captureMillis: Long?,
    val libraryDateMillis: Long,
    val libraryAddedMillis: Long?,
    val cameraMake: String?,
    val cameraModel: String?,
    val latitude: Double?,
    val longitude: Double?,
    /** Photo / video / GIF, when known. */
    val mediaType: MediaType? = null,
    /** Playback duration for a video; null for photos, GIFs and cloud items without one. */
    val durationMillis: Long? = null,
    /** The MediaStore bucket (folder) name the item lives in. Local items only. */
    val bucketName: String? = null,
    /** The item's path relative to the storage root, e.g. `DCIM/Camera/`. Local items only. */
    val relativePath: String? = null,
    /** The backup queue's state for this item, or null when the queue has no row for it. */
    val backupState: MediaBackupVisualState? = null,
    /** True for a Telegram cloud item — the sheet then reports remote identity instead of local paths. */
    val isCloud: Boolean = false,
    val cloudChatId: Long? = null,
    val cloudMessageId: Long? = null,
    /** Whether the cloud item's original has been downloaded to this device. */
    val cloudOriginalDownloaded: Boolean = false,
    /** EXIF exposure set. All null when the file carries no such metadata. */
    val iso: Int? = null,
    val fNumber: Double? = null,
    val exposureTimeSeconds: Double? = null,
    val focalLengthMm: Double? = null,
)

/**
 * One Viewer session. Resolves a [MediaSource] strictly by its own identity — a local MediaStore id
 * via [LocalMediaLookup], a cloud (chatId, messageId) via the [CloudRepository] manifest — and
 * derives adjacent sources for next/previous without loading whole libraries. A missing item surfaces
 * [ViewerStatus.MISSING] rather than substituting another. Cloud originals download only on explicit
 * request, and success is reported only with a verified local path (never fabricated).
 */
class ViewerViewModel(
    initialSource: MediaSource,
    private val localLookup: LocalMediaLookup,
    private val cloudRepository: CloudRepository,
    private val organizationRepository: MediaOrganizationRepository? = null,
    private val restoreRepository: RestoreRepository? = null,
    private val scope: ViewerScope = ViewerScope.Global,
    private val metadataReader: MediaMetadataReader? = null,
    private val backupStatusRepository: BackupStatusRepository? = null,
    private val settingsRepository: SettingsRepository? = null,
) : ViewModel() {

    /**
     * Whether a video should open with sound. Read once per session and handed to [VideoViewer] as
     * its *initial* mute state — the setting means "start muted", not "force muted", so the user's
     * own unmute for the clip they are watching is never overridden.
     */
    val startVideosMuted: StateFlow<Boolean> =
        (settingsRepository?.startVideosMuted ?: flowOf(true))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _uiState = MutableStateFlow(ViewerUiState(initialSource))
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    /** Organization flags for the current local item; all-false for cloud items or when unwired. */
    private val _flags = MutableStateFlow(ViewerFlags())
    val flags: StateFlow<ViewerFlags> = _flags.asStateFlow()

    /** Transient outcome to report for the current item, or null. Cleared on navigation. */
    private val _message = MutableStateFlow<ViewerMessage?>(null)
    val message: StateFlow<ViewerMessage?> = _message.asStateFlow()

    /** Details for the current LOCAL item, or null when not requested/available. Reset on navigation. */
    private val _details = MutableStateFlow<MediaDetails?>(null)
    val details: StateFlow<MediaDetails?> = _details.asStateFlow()

    /** Restore-to-device state for the current cloud item (idle unless a restore is requested). */
    private val _restore = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val restore: StateFlow<RestoreState> = _restore.asStateFlow()

    /** The in-flight item load; cancelled on every navigation so a slow resolve never lands stale. */
    private var loadJob: Job? = null

    init {
        load(initialSource)
    }

    fun showNext() {
        _uiState.value.next?.let(::open)
    }

    fun showPrevious() {
        _uiState.value.previous?.let(::open)
    }

    /** Navigate to another source (used by next/previous and by the swipe gesture). */
    fun open(newSource: MediaSource) {
        loadJob?.cancel()
        // A confirmation belongs to the item it describes: carrying "Moved to trash" onto the next
        // photo would report an outcome for something the user is no longer looking at.
        _message.value = null
        _uiState.value = ViewerUiState(newSource, ViewerStatus.LOADING)
        load(newSource)
    }

    /**
     * The Coil display model for a (possibly adjacent) source, so the screen can warm the image
     * cache for the swipe targets. Cloud sources return null: pre-resolving a preview would fire a
     * TDLib file request for every neighbor.
     */
    suspend fun displayModelFor(source: MediaSource): Any? = when (source) {
        is MediaSource.Local -> localLookup.byId(source.mediaId)?.contentUri
        is MediaSource.Cloud -> null
    }

    /**
     * Load Details for the current item on demand.
     *
     * Local items read EXIF off the main thread via [metadataReader] and merge it with the MediaStore
     * fields already on the item. Cloud items have no local file to read, so their details come from
     * the manifest entry — which is what makes the sheet work for them at all: this used to return
     * early on a cloud item, leaving the sheet on its spinner forever with no way out.
     */
    fun loadDetails() {
        val state = _uiState.value
        val requestedSource = state.source
        when (val item = state.item) {
            is ViewerItem.Local -> loadLocalDetails(item.media, requestedSource)
            is ViewerItem.Cloud -> loadCloudDetails(item.media, requestedSource)
            null -> return
        }
    }

    private fun loadLocalDetails(media: LocalMedia, requestedSource: MediaSource) {
        viewModelScope.launch {
            val metadata = try {
                metadataReader?.read(media.contentUri) ?: MediaMetadata()
            } catch (cancellation: CancellationException) {
                // A cancelled read must not publish: the identity guard below cannot tell a cancelled
                // scope from a live one, so swallowing this would set details for a dead session.
                throw cancellation
            } catch (_: Throwable) {
                // Corrupt file, unsupported format, or a stream we cannot open → MediaStore fields only.
                MediaMetadata()
            }
            val backup = backupStateOf(media.id)
            // Identity-guarded: a slow EXIF read must never land on an item the user already left.
            if (_uiState.value.source != requestedSource) return@launch
            _details.value = MediaDetails(
                fileName = media.displayName,
                mimeType = media.mimeType,
                sizeBytes = media.sizeBytes,
                width = media.width,
                height = media.height,
                captureMillis = metadata.captureMillis,
                libraryDateMillis = media.dateMillis,
                libraryAddedMillis = metadata.libraryAddedMillis,
                cameraMake = metadata.cameraMake,
                cameraModel = metadata.cameraModel,
                latitude = metadata.location?.latitude,
                longitude = metadata.location?.longitude,
                mediaType = media.type,
                durationMillis = media.durationMillis,
                bucketName = media.bucketName,
                relativePath = media.relativePath,
                backupState = backup,
                isCloud = false,
                iso = metadata.iso,
                fNumber = metadata.fNumber,
                exposureTimeSeconds = metadata.exposureTimeSeconds,
                focalLengthMm = metadata.focalLengthMm,
            )
        }
    }

    /**
     * A cloud item's details come entirely from its manifest entry — there is no local file to read,
     * and nothing is invented to fill the gap: the EXIF fields stay null and the sheet omits them.
     *
     * [MediaDetails.backupState] is reported as [MediaBackupVisualState.BACKED_UP] rather than looked
     * up in the local queue. A manifest row exists only because Telegram confirmed the message, so a
     * cloud item *is* backed up by definition; any local queue row would belong to a restored copy.
     */
    private fun loadCloudDetails(media: CloudMedia, requestedSource: MediaSource) {
        viewModelScope.launch {
            if (_uiState.value.source != requestedSource) return@launch
            _details.value = MediaDetails(
                fileName = media.fileName,
                mimeType = media.mimeType,
                sizeBytes = media.sizeBytes ?: 0L,
                width = media.width ?: 0,
                height = media.height ?: 0,
                captureMillis = null,
                libraryDateMillis = media.dateEpochSec?.times(1000L) ?: 0L,
                libraryAddedMillis = null,
                cameraMake = null,
                cameraModel = null,
                latitude = null,
                longitude = null,
                mediaType = media.mediaType.toDomainMediaType(),
                durationMillis = media.durationMs,
                bucketName = null,
                relativePath = null,
                backupState = MediaBackupVisualState.BACKED_UP,
                isCloud = true,
                cloudChatId = media.chatId,
                cloudMessageId = media.messageId,
                cloudOriginalDownloaded = media.isDownloaded,
            )
        }
    }

    /**
     * The item's backup state, or null when the queue holds no row for it.
     *
     * Null is deliberately *not* reported as "not backed up" — see [MediaDetails.backupState] — so a
     * failed lookup and a genuinely absent row both make the sheet omit the row rather than assert a
     * fact about the user's data that nothing verified.
     */
    private suspend fun backupStateOf(localMediaId: Long): MediaBackupVisualState? {
        val repository = backupStatusRepository ?: return null
        return try {
            repository.visualStates.first()[localMediaId.toString()]
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
    }

    fun clearDetails() {
        _details.value = null
    }

    // --- Phase 10 organization actions (local items only) ------------------------------------

    fun toggleFavorite() = mutateFlags { repo, id, current ->
        val next = !current.isFavorite
        repo.setFavorite(id, next)
        current.copy(isFavorite = next)
    }

    fun toggleArchive() = mutateFlags { repo, id, current ->
        val next = !current.isArchived
        repo.setArchived(id, next)
        current.copy(isArchived = next)
    }

    /** Move the current local item to Trash (app-level; the file is preserved until a delete). */
    fun moveToTrash() = mutateFlags(
        // Posted after the write returns, so the confirmation can never report a write that did not
        // land, and inside the identity guard, so it can never land on an item the user has swiped
        // to in the meantime. This is the only feedback the action gets: the Viewer stays on the
        // item and it keeps rendering unchanged.
        onWriteLanded = { _message.value = ViewerMessage.MovedToTrash },
    ) { repo, id, current ->
        repo.moveToTrash(id)
        current.copy(isTrashed = true)
    }

    /**
     * Undo of [moveToTrash] — the action the confirmation offers. Restores the item and drops the
     * message, so the bar disappears on the same signal that says the restore landed.
     */
    fun restoreFromTrash() = mutateFlags(
        onWriteLanded = { _message.value = null },
    ) { repo, id, current ->
        repo.restoreFromTrash(id)
        current.copy(isTrashed = false)
    }

    /** The user dismissed the confirmation without acting on it. */
    fun consumeMessage() {
        _message.value = null
    }

    private fun mutateFlags(
        /**
         * Runs only after the write has landed **and** the Viewer is still on the source the write
         * was raised for. The transient message belongs here rather than inside [action]: `action`
         * runs *before* the identity check below, so a message set there is published even when the
         * user has already swiped to another item — reporting an outcome for something they are no
         * longer looking at, and offering an Undo that acts on the new item instead.
         */
        onWriteLanded: (() -> Unit)? = null,
        action: suspend (MediaOrganizationRepository, Long, ViewerFlags) -> ViewerFlags,
    ) {
        val repo = organizationRepository ?: return
        val id = (_uiState.value.item as? ViewerItem.Local)?.media?.id ?: return
        val requestedSource = _uiState.value.source
        viewModelScope.launch {
            val updated = try {
                action(repo, id, _flags.value)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // A database failure must not escape `viewModelScope`: that is an uncaught exception on
                // the main thread and the app closes itself with no message. Nothing is claimed on
                // failure either — the flags stay as they were, which is the truth, and the next load
                // re-reads them from the repository.
                return@launch
            }
            // Identity-guarded: the write targets the captured id (always the correct item), but
            // the UI state must never be overwritten for a source the user has already left.
            if (_uiState.value.source == requestedSource) {
                _flags.value = updated
                onWriteLanded?.invoke()
            }
        }
    }

    private suspend fun loadFlags(id: Long) {
        val repo = organizationRepository
        _flags.value = if (repo == null) {
            ViewerFlags()
        } else {
            ViewerFlags(
                isFavorite = repo.isFavorite(id),
                isArchived = repo.isArchived(id),
                isTrashed = repo.isTrashed(id),
            )
        }
    }

    /** Explicitly fetch a cloud original. Never auto-invoked by merely opening the Viewer. */
    fun downloadOriginal() {
        val current = _uiState.value
        val cloud = current.item as? ViewerItem.Cloud ?: return
        if (current.download is CloudDownload.Available) return
        val requestedSource = current.source
        _uiState.value = current.copy(download = CloudDownload.Downloading)
        viewModelScope.launch {
            val local = runCatching { cloudRepository.downloadOriginal(cloud.media) }.getOrNull()
            // Identity-guarded: navigating away mid-download must never resurrect the old item's state.
            _uiState.update { state ->
                if (state.source != requestedSource) return@update state
                if (local != null) {
                    val existing = state.item as? ViewerItem.Cloud ?: return@update state
                    state.copy(
                        download = CloudDownload.Available(local.localPath),
                        item = existing.copy(originalPath = local.localPath),
                    )
                } else {
                    state.copy(download = CloudDownload.Unavailable)
                }
            }
        }
    }

    /**
     * Restore the current cloud original into the device's public media storage — download, verify,
     * publish. Explicit user action only; success is reported only after a verified publication. A
     * device-gated or failed step leaves an honest failure, never a false "saved" state.
     */
    fun restoreToLocal() {
        val repo = restoreRepository ?: return
        val cloud = (_uiState.value.item as? ViewerItem.Cloud) ?: return
        if (_restore.value is RestoreState.Restoring) return
        val requestedSource = _uiState.value.source
        _restore.value = RestoreState.Restoring
        viewModelScope.launch {
            val result = runCatching { repo.restore(cloud.media) }.getOrElse {
                RestoreResult.Failed(RestoreFailure.DOWNLOAD_UNAVAILABLE)
            }
            // Guard: a restore that finishes after navigation must not claim the new item was saved.
            if (_uiState.value.source == requestedSource) {
                _restore.value = when (result) {
                    is RestoreResult.Restored -> RestoreState.Restored(result.localUri)
                    is RestoreResult.Failed -> RestoreState.Failed(result.reason)
                }
            }
        }
    }

    private fun load(target: MediaSource) {
        loadJob = viewModelScope.launch {
            _restore.value = RestoreState.Idle
            _details.value = null // a different item never leaks the previous item's details
            val resolved = when (target) {
                is MediaSource.Local -> resolveLocal(target)
                is MediaSource.Cloud -> resolveCloud(target)
            }
            // Cloud items have no organization flags; reset so the flag bar never leaks across items.
            if (resolved.item is ViewerItem.Local) {
                loadFlags((resolved.item as ViewerItem.Local).media.id)
            } else {
                _flags.value = ViewerFlags()
            }
            _uiState.update { if (it.source == resolved.source) resolved else it }
        }
    }

    private suspend fun resolveLocal(s: MediaSource.Local): ViewerUiState {
        val media = localLookup.byId(s.mediaId) ?: return ViewerUiState(s, ViewerStatus.MISSING)
        val (newer, older) = localNeighbors(s.mediaId)
        return ViewerUiState(
            source = s,
            status = ViewerStatus.READY,
            kind = media.type.toViewerKind(),
            item = ViewerItem.Local(media),
            previous = newer?.let { MediaSource.Local(it) },
            next = older?.let { MediaSource.Local(it) },
            dateMillis = media.dateMillis,
        )
    }

    /**
     * Resolves the adjacent local ids honoring the originating [scope]: the album's bucket, a curated
     * collection's own (newest-first) order, or the visible global timeline. Returns (previous, next)
     * as ids, where previous is the newer item and next the older one.
     */
    private suspend fun localNeighbors(id: Long): Pair<Long?, Long?> = when (scope) {
        ViewerScope.Global ->
            localLookup.neighborId(id, NeighborDirection.NEWER) to localLookup.neighborId(id, NeighborDirection.OLDER)
        is ViewerScope.Bucket ->
            localLookup.neighborId(id, NeighborDirection.NEWER, scope.bucketId) to
                localLookup.neighborId(id, NeighborDirection.OLDER, scope.bucketId)
        is ViewerScope.Collection -> collectionNeighbors(id, scope.kind)
    }

    // The collection's ordered id list is exactly what the originating screen shows (the Room id-set
    // resolved newest-first through the same bounded lookup), so next/previous stay inside it.
    private suspend fun collectionNeighbors(id: Long, kind: OrganizationKind): Pair<Long?, Long?> {
        val repo = organizationRepository
            ?: return localLookup.neighborId(id, NeighborDirection.NEWER) to localLookup.neighborId(id, NeighborDirection.OLDER)
        val ids = when (kind) {
            OrganizationKind.FAVORITES -> repo.favoriteIds
            OrganizationKind.ARCHIVE -> repo.archivedIds
            OrganizationKind.TRASH -> repo.trashedIds
        }.first()
        val ordered = localLookup.byIdList(ids).map { it.id } // newest-first, existing items only
        val index = ordered.indexOf(id)
        if (index < 0) return null to null
        return ordered.getOrNull(index - 1) to ordered.getOrNull(index + 1)
    }

    private suspend fun resolveCloud(s: MediaSource.Cloud): ViewerUiState {
        val manifest = cloudRepository.media.first()
        val index = manifest.indexOfFirst { it.chatId == s.chatId && it.messageId == s.messageId }
        if (index < 0) return ViewerUiState(s, ViewerStatus.MISSING)
        val media = manifest[index]
        val preview = runCatching { cloudRepository.getPreview(media) }.getOrNull()
        return ViewerUiState(
            source = s,
            status = ViewerStatus.READY,
            kind = media.mediaType.toViewerKind(),
            item = ViewerItem.Cloud(media, previewPath = preview?.localPath, originalPath = null),
            previous = manifest.getOrNull(index - 1)?.let { MediaSource.Cloud(it.chatId, it.messageId) },
            next = manifest.getOrNull(index + 1)?.let { MediaSource.Cloud(it.chatId, it.messageId) },
            download = CloudDownload.Idle,
            dateMillis = media.dateEpochSec?.times(1000L),
        )
    }
}

private fun MediaType.toViewerKind(): ViewerKind = when (this) {
    MediaType.PHOTO -> ViewerKind.PHOTO
    MediaType.VIDEO -> ViewerKind.VIDEO
    MediaType.GIF -> ViewerKind.GIF
}

private fun CloudMediaType.toViewerKind(): ViewerKind = when (this) {
    CloudMediaType.IMAGE -> ViewerKind.PHOTO
    CloudMediaType.VIDEO -> ViewerKind.VIDEO
    CloudMediaType.GIF -> ViewerKind.GIF
}

/** The domain kind for a cloud item, so the Details sheet can label it with the same wording as a local one. */
private fun CloudMediaType.toDomainMediaType(): MediaType = when (this) {
    CloudMediaType.IMAGE -> MediaType.PHOTO
    CloudMediaType.VIDEO -> MediaType.VIDEO
    CloudMediaType.GIF -> MediaType.GIF
}

/** Restore-to-device state for the current cloud item. */
sealed interface RestoreState {
    data object Idle : RestoreState
    data object Restoring : RestoreState
    data class Restored(val localUri: String) : RestoreState

    /** Carries the concrete reason so the UI can explain *why* a save failed, not just that it did. */
    data class Failed(val reason: RestoreFailure) : RestoreState
}
