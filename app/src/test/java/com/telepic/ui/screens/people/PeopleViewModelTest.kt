package com.telepic.ui.screens.people

import com.telepic.data.face.FaceGroupingSettings
import com.telepic.data.face.FaceModelStore
import com.telepic.data.face.FaceRepository
import com.telepic.data.face.FaceSimilarityPreset
import com.telepic.data.face.work.FaceDownloadState
import com.telepic.data.face.work.FaceWorkScheduler
import com.telepic.domain.face.PeopleState
import com.telepic.domain.face.Person
import com.telepic.domain.face.PersonFace
import com.telepic.domain.face.ScanProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The People tab's state machine, exercised through the real `combine`.
 *
 * The screen is a `when` over [PeopleState], so a wrong precedence here is not a cosmetic bug: it
 * decides whether a user sees a download offer, a scan offer, a progress bar, or their own people.
 * Each test pins exactly one rule of that precedence.
 *
 * Robolectric because a failed curation operation logs through `TelepicLog`, which reaches
 * `android.util.Log` — the point of that test is that the failure is *swallowed and logged*, so it
 * cannot avoid touching the logger.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class PeopleViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeModels(var ready: Boolean) : FaceModelStore {
        var deleteCalls = 0
        override fun isReady(): Boolean = ready
        override fun detectorPath(): String = "yunet.tflite"
        override fun embedderPath(): String = "ghostfacenet.tflite"
        override suspend fun ensureDownloaded(onProgress: (Long, Long) -> Unit) = Unit
        override fun deleteAll() { deleteCalls++ }
    }

    private class FakeScheduler : FaceWorkScheduler {
        var modelCalls = 0
        var indexCalls = 0
        override fun scheduleModelDownload() { modelCalls++ }
        override fun scheduleIndexing() { indexCalls++ }
        override suspend fun isIndexing(): Boolean = false
        override fun observeDownloadState(): Flow<FaceDownloadState> = downloadState
        val downloadState = MutableStateFlow(FaceDownloadState.IDLE)
    }

    private class FakeGrouping(
        initial: FaceSimilarityPreset = FaceSimilarityPreset.DEFAULT,
    ) : FaceGroupingSettings {
        val stored = MutableStateFlow(initial)
        override val preset: Flow<FaceSimilarityPreset> = stored
        override suspend fun setPreset(preset: FaceSimilarityPreset) { stored.value = preset }
    }

    private open class FakeRepository : FaceRepository {
        val people = MutableStateFlow<List<Person>>(emptyList())
        val faceCount = MutableStateFlow(0)
        private val progress = MutableStateFlow<ScanProgress?>(null)

        var rebuiltWith: FaceSimilarityPreset? = null
        var removeAllCalls = 0
        var renames = mutableListOf<Pair<Long, String?>>()
        var merges = mutableListOf<Pair<Long, Long>>()
        var hidden = mutableListOf<Pair<Long, Boolean>>()

        override fun observePeople(minimumGroupSize: Int): Flow<List<Person>> = people
        override fun observePerson(personId: Long): Flow<Person?> = flowOf(null)
        override fun observeFaces(personId: Long): Flow<List<PersonFace>> = flowOf(emptyList())
        override fun observeFaceCount(): Flow<Int> = faceCount
        override val scanProgress: StateFlow<ScanProgress?> = progress.asStateFlow()
        override fun reportScanProgress(progress: ScanProgress?) { this.progress.value = progress }

        override suspend fun rebuildClusters(preset: FaceSimilarityPreset) { rebuiltWith = preset }
        override suspend fun rename(personId: Long, name: String?) { renames += personId to name }
        override suspend fun merge(targetPersonId: Long, sourcePersonId: Long) {
            merges += targetPersonId to sourcePersonId
        }
        override suspend fun split(faceIds: List<Long>): Long = 1L
        override suspend fun setHidden(personId: Long, hidden: Boolean) { this.hidden += personId to hidden }
        override suspend fun setCover(personId: Long, faceId: Long?) = Unit
        override suspend fun removeFace(faceId: Long) = Unit
        override suspend fun removeAll() { removeAllCalls++ }
    }

    private fun viewModel(
        models: FakeModels = FakeModels(ready = false),
        scheduler: FakeScheduler = FakeScheduler(),
        repository: FakeRepository = FakeRepository(),
        grouping: FakeGrouping = FakeGrouping(),
    ) = PeopleViewModel(
        repository = repository,
        modelStore = models,
        scheduler = scheduler,
        grouping = grouping,
    )

    @Test
    fun `the tab opens on the model offer and does not start anything`() = runTest {
        val scheduler = FakeScheduler()
        val viewModel = viewModel(models = FakeModels(ready = false), scheduler = scheduler)

        assertEquals(PeopleState.ModelsAbsent, viewModel.state.first())

        // The whole feature is opt-in: opening the tab must not download or scan.
        assertEquals(0, scheduler.modelCalls)
        assertEquals(0, scheduler.indexCalls)
    }

    @Test
    fun `downloading is shown immediately and starts the worker`() = runTest {
        val scheduler = FakeScheduler()
        val viewModel = viewModel(models = FakeModels(ready = false), scheduler = scheduler)

        viewModel.downloadModels()

        // Optimistic: a button that appears to do nothing for a second reads as broken.
        assertTrue(viewModel.state.first() is PeopleState.Downloading)
        assertEquals(1, scheduler.modelCalls)
    }

    @Test
    fun `a finished download turns into the scan offer once the state is refreshed`() = runTest {
        val models = FakeModels(ready = false)
        val viewModel = viewModel(models = models)

        viewModel.downloadModels()
        assertTrue(viewModel.state.first() is PeopleState.Downloading)

        // The file is written by a worker, so nothing in this process observes it. Resuming the tab is
        // what has to pick it up.
        models.ready = true
        viewModel.refreshModelState()

        assertEquals(PeopleState.NotIndexed, viewModel.state.first())
    }

    @Test
    fun `models present but no faces offers a scan`() = runTest {
        val viewModel = viewModel(models = FakeModels(ready = true))

        assertEquals(PeopleState.NotIndexed, viewModel.state.first())
    }

    @Test
    fun `a running scan outranks the grid`() = runTest {
        // Otherwise the grid looks frozen while faces are still arriving, and the user cannot tell
        // whether the scan is working.
        val repository = FakeRepository()
        val viewModel = viewModel(models = FakeModels(ready = true), repository = repository)
        repository.people.value = listOf(person(1, count = 5))
        repository.faceCount.value = 5
        assertTrue(viewModel.state.first() is PeopleState.Ready)

        repository.reportScanProgress(ScanProgress(photosExamined = 800, facesFound = 412))

        val indexing = viewModel.state.first()
        assertTrue(indexing is PeopleState.Indexing)
        indexing as PeopleState.Indexing
        assertEquals(800, indexing.photosExamined)
        assertEquals(412, indexing.facesFound)
    }

    @Test
    fun `clearing progress returns to the grid`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(models = FakeModels(ready = true), repository = repository)
        repository.people.value = listOf(person(1, count = 5))
        repository.faceCount.value = 5
        repository.reportScanProgress(ScanProgress(100, 200))
        assertTrue(viewModel.state.first() is PeopleState.Indexing)

        repository.reportScanProgress(null)

        assertTrue(viewModel.state.first() is PeopleState.Ready)
    }

    @Test
    fun `faces with no group big enough is Ready with no people, not NotIndexed`() = runTest {
        // The difference matters: "nothing found yet" and "found some faces but no groups" are
        // different messages, and only the second one can point at the grouping setting.
        val repository = FakeRepository()
        val viewModel = viewModel(models = FakeModels(ready = true), repository = repository)

        repository.faceCount.value = 7

        val state = viewModel.state.first()
        assertTrue(state is PeopleState.Ready)
        state as PeopleState.Ready
        assertTrue(state.people.isEmpty())
        assertEquals(7, state.totalFaces)
    }

    @Test
    fun `starting a scan schedules indexing and nothing else`() = runTest {
        val scheduler = FakeScheduler()
        val viewModel = viewModel(models = FakeModels(ready = true), scheduler = scheduler)

        viewModel.startScanning()

        assertEquals(1, scheduler.indexCalls)
        assertEquals(0, scheduler.modelCalls)
    }

    @Test
    fun `a rebuild uses the persisted preset`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(
            models = FakeModels(ready = true),
            repository = repository,
            grouping = FakeGrouping(FaceSimilarityPreset.LOOSE),
        )

        viewModel.rebuildGrouping()

        // The stored preset is the user's choice from the grouping dialog; rebuilding with the
        // hard-coded default would silently ignore it.
        assertEquals(FaceSimilarityPreset.LOOSE, repository.rebuiltWith)
    }

    @Test
    fun `applying a grouping stores it and rebuilds with it`() = runTest {
        val repository = FakeRepository()
        val grouping = FakeGrouping(FaceSimilarityPreset.STRICT)
        val viewModel = viewModel(
            models = FakeModels(ready = true),
            repository = repository,
            grouping = grouping,
        )

        viewModel.applyGrouping(FaceSimilarityPreset.BALANCED)

        // Persisted first: the next scan and the next rebuild have to honour the choice even if this
        // rebuild fails.
        assertEquals(FaceSimilarityPreset.BALANCED, grouping.stored.value)
        assertEquals(FaceSimilarityPreset.BALANCED, repository.rebuiltWith)
    }

    @Test
    fun `the dialog's preset follows the stored value`() = runTest {
        val grouping = FakeGrouping(FaceSimilarityPreset.BALANCED)
        val viewModel = viewModel(models = FakeModels(ready = true), grouping = grouping)

        assertEquals(FaceSimilarityPreset.BALANCED, viewModel.preset.first())

        grouping.stored.value = FaceSimilarityPreset.LOOSE

        assertEquals(FaceSimilarityPreset.LOOSE, viewModel.preset.first())
    }

    @Test
    fun `a failed download is its own state, not the offer again`() = runTest {
        // Otherwise a user who tapped "Download" sees the same button with no explanation.
        val scheduler = FakeScheduler()
        val viewModel = viewModel(models = FakeModels(ready = false), scheduler = scheduler)

        viewModel.downloadModels()
        scheduler.downloadState.value = FaceDownloadState.FAILED

        assertEquals(PeopleState.DownloadFailed, viewModel.state.first())
    }

    @Test
    fun `a retry after a failure goes back to downloading`() = runTest {
        val scheduler = FakeScheduler()
        val viewModel = viewModel(models = FakeModels(ready = false), scheduler = scheduler)
        viewModel.downloadModels()
        scheduler.downloadState.value = FaceDownloadState.FAILED
        assertEquals(PeopleState.DownloadFailed, viewModel.state.first())

        viewModel.downloadModels()
        scheduler.downloadState.value = FaceDownloadState.RUNNING

        assertTrue(viewModel.state.first() is PeopleState.Downloading)
    }

    @Test
    fun `the models on disk outrank a failed download`() = runTest {
        // A stale FAILED from an earlier run must not hide a library that is already usable.
        val scheduler = FakeScheduler()
        val viewModel = viewModel(models = FakeModels(ready = true), scheduler = scheduler)

        scheduler.downloadState.value = FaceDownloadState.FAILED

        assertEquals(PeopleState.NotIndexed, viewModel.state.first())
    }

    @Test
    fun `renaming maps straight through, including clearing`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(models = FakeModels(ready = true), repository = repository)

        viewModel.rename(3L, "Ada")
        viewModel.rename(3L, null)

        assertEquals(listOf(3L to "Ada", 3L to null), repository.renames)
    }

    @Test
    fun `merge passes target and source in the order the repository expects`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(models = FakeModels(ready = true), repository = repository)

        viewModel.merge(targetPersonId = 1L, sourcePersonId = 2L)

        // Reversing these would delete the group the user wanted to keep.
        assertEquals(listOf(1L to 2L), repository.merges)
    }

    @Test
    fun `removing all data clears the index and the models`() = runTest {
        // Leaving 4.7 MB of model behind after the user asked for their face data to be removed is not
        // what they asked for.
        val models = FakeModels(ready = true)
        val repository = FakeRepository()
        val viewModel = viewModel(models = models, repository = repository)

        viewModel.removeAllFaceData()

        assertEquals(1, repository.removeAllCalls)
        assertEquals(1, models.deleteCalls)
    }

    @Test
    fun `a failing curation operation does not take the ViewModel down`() = runTest {
        // These are edits to derived data; a failure should leave the previous grouping on screen.
        val repository = object : FakeRepository() {
            override suspend fun rename(personId: Long, name: String?) = throw IllegalStateException("gone")
        }
        val viewModel = viewModel(models = FakeModels(ready = true), repository = repository)

        viewModel.rename(9L, "Ada")

        assertEquals(PeopleState.NotIndexed, viewModel.state.first())
    }

    @Test
    fun `the person screen observes its own person and faces`() = runTest {
        val repository = FakeRepository()
        val viewModel = PersonViewModel(personId = 4L, repository = repository)

        // No person yet and no faces is the "this group no longer exists" branch the screen renders,
        // rather than an error.
        assertNull(viewModel.person.first())
        assertTrue(viewModel.faces.first().isEmpty())

        viewModel.rename("Ada")
        viewModel.setCover(11L)
        viewModel.setHidden(true)

        assertEquals(listOf(4L to "Ada"), repository.renames)
        assertEquals(listOf(4L to true), repository.hidden)
    }

    /** A person with the fields the grid reads, so assertions are about state and not about data. */
    private fun person(id: Long, count: Int) = Person(
        personId = id,
        name = null,
        coverFaceId = null,
        faceCount = count,
        isHidden = false,
        createdAt = 1L,
    )
}
