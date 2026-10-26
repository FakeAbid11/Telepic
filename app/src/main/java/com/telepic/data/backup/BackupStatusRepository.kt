package com.telepic.data.backup

import com.telepic.data.backup.db.BackupQueueDao
import com.telepic.domain.backup.BackupState
import com.telepic.domain.backup.MediaBackupVisualState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * The boundary Photos consumes to know each local item's backup status. It maps the persistent
 * backup queue (Room) into a `localMediaId → visual state` map in **one batched flow** — the UI
 * reads from this snapshot, never running a query per tile, and never derives status itself.
 *
 * Because the queue is the sole source of `BACKED_UP` (written only after Telegram confirms a real
 * remote identity), this boundary cannot report false success.
 */
interface BackupStatusRepository {
    /** Keyed by the numeric MediaStore id, the same identity the UI uses everywhere else. */
    val visualStates: Flow<Map<Long, MediaBackupVisualState>>
}

class DefaultBackupStatusRepository(
    private val dao: BackupQueueDao,
    private val maxRetries: Int = DefaultBackupRepository.DEFAULT_MAX_RETRIES,
) : BackupStatusRepository {

    override val visualStates: Flow<Map<Long, MediaBackupVisualState>> =
        dao.observeStatusRows()
            .map { rows ->
                buildMap(rows.size) {
                    for (row in rows) {
                        val id = row.localMediaId.toLongOrNull() ?: continue
                        put(
                            id,
                            MediaBackupVisualState.from(
                                state = BackupState.fromName(row.state),
                                retryCount = row.retryCount,
                                maxRetries = maxRetries,
                            ),
                        )
                    }
                }
            }
            .distinctUntilChanged()
            // The queue can be large and every upload write invalidates this flow. Build and compare
            // the snapshot off the main thread; consumers receive exactly the same StateFlow updates.
            .flowOn(Dispatchers.Default)
}
