package com.telepic.ui.screens.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telepic.data.face.FaceGroupingSettings
import com.telepic.data.face.FaceModelStore
import com.telepic.data.face.FaceRepository
import com.telepic.data.face.FaceSimilarityPreset
import com.telepic.data.face.work.FaceDownloadState
import com.telepic.data.face.work.FaceWorkScheduler
import com.telepic.diagnostics.TelepicLog
import com.telepic.domain.face.PeopleState
import com.telepic.domain.face.Person
import com.telepic.domain.face.PersonFace
import com.telepic.domain.face.ScanProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the People tab and the single-person screen that pushes from it.
 *
 * The tab has five genuinely different states — models missing, downloading, failed, scanned-nothing,
 * and a populated index — and they are modelled as one [PeopleState] rather than a set of booleans.
 * Every combination of flags that is *not* one of those five is a bug, and a sealed type makes those
 * combinations unrepresentable instead of merely unlikely.
 *
 * **Nothing starts automatically.** Both the download and the scan are explicit user actions, because
 * the whole feature is opt-in: a user who never opens this tab must never see a network request or a
 * background scan. That is why there is no `init { }` block here.
 */
class PeopleViewModel(
    private val repository: FaceRepository,
    private val modelStore: FaceModelStore,
    private val scheduler: FaceWorkScheduler,
    private val grouping: FaceGroupingSettings,
) : ViewModel() {

    /**
     * Whether the models are on disk.
     *
     * Kept in a flow rather than read once because it changes from outside this ViewModel — a worker
     * finishes a download — and the screen has to react without being recreated.
     */
    private val modelsReady = MutableStateFlow(modelStore.isReady())

    /** Set between tapping "download" and the attempt producing an answer. */
    private val downloadRequested = MutableStateFlow(false)

    /**
     * The last observed outcome of the download worker, so a failure is not mistaken for a hang.
     *
     * Collected eagerly, and clears [downloadRequested] the moment the attempt fails. That is what
     * makes a failure *visible*: the optimistic flag exists to cover the gap between the tap and the
     * worker starting, and if it outlived the answer it would keep claiming "downloading" forever —
     * exactly the hang the failed state exists to replace.
     */
    private val downloadState: StateFlow<FaceDownloadState> = scheduler.observeDownloadState()
        .onEach { state ->
            if (state == FaceDownloadState.FAILED) downloadRequested.value = false
        }
        .catch { throwable ->
            // Observing WorkManager must never be able to break the tab; the model presence check is
            // the authoritative signal and stands on its own.
            TelepicLog.warn("People", "could not observe the model download", throwable)
            emit(FaceDownloadState.IDLE)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, FaceDownloadState.IDLE)

    /**
     * Everything that decides *which* screen to show, kept apart from the index itself.
     *
     * `combine` has a typed overload for three flows and none for six, so grouping the model signals
     * separately is what keeps the whole thing typed instead of going through an `Array<*>` and a set
     * of casts.
     */
    private val models: Flow<ModelsPhase> = combine(
        modelsReady,
        downloadRequested,
        downloadState,
    ) { ready, requested, download ->
        when {
            // The files on disk are the only thing the rest of the screen cares about, so a present
            // model outranks every transient flag about how it got there.
            ready -> ModelsPhase.Ready
            requested || download == FaceDownloadState.RUNNING -> ModelsPhase.Downloading
            download == FaceDownloadState.FAILED -> ModelsPhase.Failed
            else -> ModelsPhase.Absent
        }
    }

    val state: StateFlow<PeopleState> = combine(
        models,
        repository.observePeople(),
        repository.observeFaceCount(),
        repository.scanProgress,
    ) { phase, people, faceCount, progress ->
        when (phase) {
            ModelsPhase.Absent -> PeopleState.ModelsAbsent
            ModelsPhase.Downloading -> PeopleState.Downloading(progress = null)
            ModelsPhase.Failed -> PeopleState.DownloadFailed
            ModelsPhase.Ready -> when {
                // A scan in flight outranks "Ready": the grid would otherwise look frozen while faces
                // are still arriving, and the user cannot tell whether it is working.
                progress != null -> PeopleState.Indexing(progress.photosExamined, progress.facesFound)
                faceCount > 0 -> PeopleState.Ready(people, faceCount)
                // Models are present but nothing has been found yet.
                else -> PeopleState.NotIndexed
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), PeopleState.ModelsAbsent)

    /** The model lifecycle collapsed to what the screen branches on. */
    private enum class ModelsPhase { Absent, Downloading, Failed, Ready }

    /**
     * Re-reads whether the models are present.
     *
     * Called when the screen resumes: the download runs in a worker, so the file it produced is not
     * something an in-process observer can see. Without this the tab would sit on "downloading" after
     * a successful download until it was recreated.
     */
    fun refreshModelState() {
        modelsReady.value = modelStore.isReady()
        if (modelsReady.value) downloadRequested.value = false
    }

    /**
     * Starts the model download, after the user has opted in.
     *
     * The state is flipped optimistically: the worker takes a moment to start, and a button that
     * appears to do nothing for a second reads as broken. A previous failure clears itself — the
     * scheduler replaces the failed request, so the observed state moves back through `RUNNING`.
     */
    fun downloadModels() {
        downloadRequested.value = true
        scheduler.scheduleModelDownload()
    }

    /** Starts (or continues) a scan. */
    fun startScanning() {
        scheduler.scheduleIndexing()
    }

    /** Renames a person. A blank name clears it, restoring the "Person N" label. */
    fun rename(personId: Long, name: String?) {
        viewModelScope.launch {
            runCatchingPersonOperation("rename person $personId") { repository.rename(personId, name) }
        }
    }

    /** Merges [sourcePersonId] into [targetPersonId]. */
    fun merge(targetPersonId: Long, sourcePersonId: Long) {
        viewModelScope.launch {
            runCatchingPersonOperation("merge $sourcePersonId into $targetPersonId") {
                repository.merge(targetPersonId, sourcePersonId)
            }
        }
    }

    /** Hides a person from the grid, keeping their faces. */
    fun setHidden(personId: Long, hidden: Boolean) {
        viewModelScope.launch {
            runCatchingPersonOperation("set hidden=$hidden on person $personId") {
                repository.setHidden(personId, hidden)
            }
        }
    }

    /**
     * Rebuilds every group with the user's chosen strictness.
     *
     * Needed after a threshold change, because the stored grouping was produced under the old one —
     * the setting only affects a *rebuild*, which is a deliberate cost the user opts into rather than
     * something that silently re-runs in the background.
     */
    fun rebuildGrouping() {
        viewModelScope.launch {
            runCatchingPersonOperation("rebuild grouping") {
                repository.rebuildClusters(grouping.current())
            }
        }
    }

    /**
     * Stores [preset] and rebuilds with it.
     *
     * The two steps are one action from the user's side: picking a strictness that is only applied on
     * the next rebuild would look like the choice did nothing. Persisting first means the next scan
     * and the next rebuild both honour it even if this rebuild fails.
     */
    fun applyGrouping(preset: FaceSimilarityPreset) {
        viewModelScope.launch {
            runCatchingPersonOperation("apply grouping $preset") {
                grouping.setPreset(preset)
                repository.rebuildClusters(preset)
            }
        }
    }

    /** The strictness the next rebuild will use, so the dialog opens on the current choice. */
    val preset: StateFlow<FaceSimilarityPreset> = grouping.preset
        .catch { throwable ->
            // An unreadable preference must not stop the dialog from opening; the default is what the
            // app would have used anyway.
            TelepicLog.warn("People", "could not read the grouping preset", throwable)
            emit(FaceSimilarityPreset.DEFAULT)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), FaceSimilarityPreset.DEFAULT)

    /**
     * Removes every face, person and model file.
     *
     * The model files go too: they are only useful with an index, and leaving 4.7 MB behind after the
     * user asked for their face data to be removed is not what they asked for.
     */
    fun removeAllFaceData() {
        viewModelScope.launch {
            runCatchingPersonOperation("remove all face data") {
                repository.removeAll()
                modelStore.deleteAll()
                modelsReady.value = false
                downloadRequested.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Nothing to release: the interpreters belong to the indexing worker, which closes them when
        // its run ends. Holding them here would keep two TFLite interpreters resident between scans.
    }

    private companion object {
        /** Long enough to survive a configuration change without re-reading the database. */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * Backs one person's photo grid.
 *
 * Separate from [PeopleViewModel] rather than a mode of it: the two screens have independent lifetimes
 * (the detail screen can be open while the tab behind it is re-clustered), and sharing one ViewModel
 * would make the detail screen's state depend on a screen the user is not looking at.
 */
class PersonViewModel(
    private val personId: Long,
    private val repository: FaceRepository,
) : ViewModel() {

    val person: StateFlow<Person?> = repository.observePerson(personId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    val faces: StateFlow<List<PersonFace>> = repository.observeFaces(personId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    /** Renames this person. A blank name clears it. */
    fun rename(name: String?) {
        viewModelScope.launch {
            try {
                repository.rename(personId, name)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                // The person can be retired by a rebuild while their screen is open. Logging rather
                // than crashing leaves the (now stale) grid on screen, which the observer then clears.
                TelepicLog.warn("Person", "could not rename person $personId", throwable)
            }
        }
    }

    /** Uses [faceId] as this person's card cover. */
    fun setCover(faceId: Long?) {
        viewModelScope.launch {
            try {
                repository.setCover(personId, faceId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                TelepicLog.warn("Person", "could not change the cover of person $personId", throwable)
            }
        }
    }

    /** Shows or hides this person in the People grid. */
    fun setHidden(hidden: Boolean) {
        viewModelScope.launch {
            try {
                repository.setHidden(personId, hidden)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                TelepicLog.warn("Person", "could not set hidden=$hidden on person $personId", throwable)
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * Runs a curation operation, logging rather than crashing.
 *
 * These are user-initiated edits to derived data. A failure (the person was removed in another tab,
 * the database was busy) should leave the previous grouping on screen, not tear the app down — and it
 * must not swallow cancellation, which is how the screen is left.
 */
private suspend fun runCatchingPersonOperation(what: String, block: suspend () -> Unit) {
    try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        TelepicLog.warn("People", "could not $what", throwable)
    }
}
