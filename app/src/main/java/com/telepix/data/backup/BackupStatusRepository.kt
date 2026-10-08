package com.telepix.data.backup

import com.telepix.data.backup.db.BackupQueueDao
import com.telepix.domain.backup.BackupState
import com.telepix.domain.backup.MediaBackupVisualState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The boundary Photos consumes to know each local item's backup status. It maps the persistent
 * backup queue (Room) into a `localMediaId → visual state` map in **one batched flow** — the UI
 * reads from this snapshot, never running a query per tile, and never derives status itself.
 *
 * Because the queue is the sole source of `BACKED_UP` (written only after Telegram confirms a real
 * remote identity), this boundary cannot report false success.
 */
interface BackupStatusRepository {
    val visualStates: Flow<Map<String, MediaBackupVisualState>>
}

class DefaultBackupStatusRepository(
    private val dao: BackupQueueDao,
) : BackupStatusRepository {

    override val visualStates: Flow<Map<String, MediaBackupVisualState>> =
        dao.observeStatusRows()
            .map { rows ->
                buildMap(rows.size) {
                    for (row in rows) {
                        put(row.localMediaId, MediaBackupVisualState.from(BackupState.fromName(row.state)))
                    }
                }
            }
            .distinctUntilChanged()
}
