package com.telepic.data.face.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.telepic.diagnostics.TelepicLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Names of the two People-tab work streams. Unique, so repeated requests never stack. */
const val FACE_MODEL_WORK_NAME = "telepic_face_models"
const val FACE_INDEX_WORK_NAME = "telepic_face_index"

/**
 * What the model-download work stream is doing.
 *
 * Three outcomes, not two, because "not running" and "ran and failed" need opposite responses: the
 * first is the user never having asked, the second is a connection problem they should be told about.
 * Collapsing them left the tab sitting on "Downloading…" indefinitely after a failure, which reads as
 * a hang.
 */
enum class FaceDownloadState {
    /** Never started, or finished successfully. */
    IDLE,

    /** A download is enqueued or executing. */
    RUNNING,

    /** The last run finished without producing the models. */
    FAILED,
}

/**
 * Schedules the People tab's two background jobs.
 *
 * They are deliberately separate streams. Downloading models needs a network and takes seconds;
 * indexing needs no network at all and can take minutes. Coupling them would make the index wait for
 * connectivity it does not use, and would make a failed download cancel a scan that was already
 * running fine.
 *
 * Neither is scheduled automatically. Both are triggered by an explicit user action (downloading the
 * models, starting a scan), because the feature is opt-in end to end — a user who never opens the
 * People tab must never see a network request or a CPU-heavy background job from it.
 */
interface FaceWorkScheduler {

    /** Starts (or continues) downloading the models. Requires connectivity. */
    fun scheduleModelDownload()

    /** Starts (or continues) indexing. Requires no network. */
    fun scheduleIndexing()

    /** Whether an indexing run is executing right now. */
    suspend fun isIndexing(): Boolean

    /** The model download's current outcome, observed so the tab can stop claiming it is downloading. */
    fun observeDownloadState(): Flow<FaceDownloadState>
}

class WorkManagerFaceWorkScheduler(private val context: Context) : FaceWorkScheduler {

    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    override fun scheduleModelDownload() {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    // `CONNECTED`, not `UNMETERED`: the user explicitly asked for these ~4.7 MB, and
                    // making them wait for Wi-Fi would leave the tab stuck on "downloading" for
                    // someone who tapped the button on a train.
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        // REPLACE rather than KEEP: a retry is the common case here (a flaky connection), and KEEP
        // would drop the new request while the previous, failed spec is still recorded as finished.
        workManager.enqueueUniqueWork(FACE_MODEL_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    override fun scheduleIndexing() {
        val request = OneTimeWorkRequestBuilder<FaceIndexWorker>().build()
        // APPEND_OR_REPLACE while a scan is running: a request made during a scan (the user tapped
        // "continue") queues behind it rather than cancelling work that is already making progress.
        val running = try {
            workManager.getWorkInfosForUniqueWork(FACE_INDEX_WORK_NAME).get()
                .any { it.state == WorkInfo.State.RUNNING }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            TelepicLog.warn("FaceScheduler", "could not read the face index state", throwable)
            // Fails closed: assume a scan is running and queue behind it.
            true
        }
        workManager.enqueueUniqueWork(
            FACE_INDEX_WORK_NAME,
            if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    override suspend fun isIndexing(): Boolean = try {
        workManager.getWorkInfosForUniqueWorkFlow(FACE_INDEX_WORK_NAME).first()
            .any { it.state == WorkInfo.State.RUNNING }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        TelepicLog.warn("FaceScheduler", "WorkManager state unreadable; assuming a scan is running", throwable)
        true
    }

    override fun observeDownloadState(): Flow<FaceDownloadState> =
        workManager.getWorkInfosForUniqueWorkFlow(FACE_MODEL_WORK_NAME).map { infos ->
            // One name, so at most one request of interest — but APPEND_OR_REPLACE can queue a retry,
            // and taking the *last* keeps the answer about the most recent attempt.
            when (infos.lastOrNull()?.state) {
                null -> FaceDownloadState.IDLE
                WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED ->
                    FaceDownloadState.RUNNING
                WorkInfo.State.SUCCEEDED -> FaceDownloadState.IDLE
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> FaceDownloadState.FAILED
            }
        }.catch { throwable ->
            // An unreadable WorkManager must not take the tab down; "idle" is the state the screen
            // already knows how to render.
            TelepicLog.warn("FaceScheduler", "could not observe the model download state", throwable)
            emit(FaceDownloadState.IDLE)
        }
}
