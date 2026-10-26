package com.telepic.data.account

import com.telepic.data.backup.BackupRepository
import com.telepic.data.cloud.CloudRepository
import com.telepic.data.restore.RestoreLedger
import kotlinx.coroutines.flow.first

/**
 * Keeps persisted remote state tied to the Telegram account that produced it.
 *
 * The destination, cloud manifest, backup queue's remote identities and the restore ledger are all
 * scoped to one account. On a same-account re-login nothing is lost. When a different account signs
 * in, the cloud destination/manifest are cleared, queue rows that claimed a remote identity return to
 * QUEUED for the new destination, and the restore ledger is dropped. Local media, organization state
 * and user albums are untouched.
 */
class TelegramAccountCoordinator(
    private val accountStore: TelegramAccountStore,
    private val cloudRepository: CloudRepository,
    private val backupRepository: BackupRepository,
    private val restoreLedger: RestoreLedger,
) {

    suspend fun onAuthorized(accountId: Long) {
        val previous = accountStore.accountId.first()
        val cloudWasCleared = cloudRepository.reconcileAccount(accountId)
        if ((previous != null && previous != accountId) || cloudWasCleared) {
            backupRepository.resetForAccountSwitch()
            restoreLedger.clear()
        }
        // Only record the account after the reset succeeded: a failure must leave the old id in
        // place so the next authorized emission retries the reconciliation.
        accountStore.setAccountId(accountId)
    }
}
