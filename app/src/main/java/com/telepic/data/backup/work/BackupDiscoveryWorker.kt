package com.telepic.data.backup.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.telepic.data.backup.BackupCoordinator
import kotlinx.coroutines.CancellationException

/**
 * The recurring discovery pass (installed by [BackupWorkScheduler.schedulePeriodic]). It only runs
 * the preference-gated discovery → recognition → enqueue flow — `syncFromPreference` itself hands
 * any genuinely new work to the network-constrained one-time [BackupWorker]. Upload policy, retries
 * and backoff therefore stay in exactly one place; this worker never uploads anything itself, and
 * it re-checks the user's preference on every tick (NOT_NOW makes the run a cheap no-op).
 */
class BackupDiscoveryWorker(
    appContext: Context,
    params: WorkerParameters,
    private val coordinator: BackupCoordinator,
) : CoroutineWorker(appContext, params) {

    /**
     * Reflective constructor mirroring [BackupWorker]'s dependency seam; the factory path is
     * preferred, this only fires if WorkManager builds the worker before the container installs it.
     */
    constructor(appContext: Context, params: WorkerParameters) : this(
        appContext,
        params,
        requireNotNull(BackupWorkerDependencies.coordinator) {
            "BackupCoordinator not installed; TelepicWorkerFactory must create BackupDiscoveryWorker"
        },
    )

    override suspend fun doWork(): Result =
        try {
            coordinator.syncFromPreference()
            Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation // cooperative stop (constraints/stop) is not a failure to retry-blame
        } catch (_: Exception) {
            // Transient by doctrine: a failed discovery pass simply re-runs on the next period.
            Result.retry()
        }
}
