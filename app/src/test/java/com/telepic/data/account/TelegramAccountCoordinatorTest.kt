package com.telepic.data.account

import com.telepic.data.backup.BackupProcessSummary
import com.telepic.data.backup.BackupRepository
import com.telepic.data.cloud.CloudRepository
import com.telepic.data.restore.RestoreIdentity
import com.telepic.data.restore.RestoreLedger
import com.telepic.domain.backup.BackupItem
import com.telepic.domain.backup.BackupQueueStats
import com.telepic.domain.cloud.CloudMedia
import com.telepic.domain.cloud.CloudPreview
import com.telepic.domain.cloud.CloudStatus
import com.telepic.domain.cloud.CloudUploadProgress
import com.telepic.domain.cloud.CloudUploadRequest
import com.telepic.domain.cloud.CloudUploadResult
import com.telepic.domain.cloud.LocalDownloadedMedia
import com.telepic.domain.cloud.TelepicCloudDestination
import com.telepic.domain.media.LocalMedia
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramAccountCoordinatorTest {

    private class FakeStore(initial: Long? = null) : TelegramAccountStore {
        val id = MutableStateFlow(initial)
        override val accountId: Flow<Long?> = id
        override suspend fun setAccountId(accountId: Long) {
            id.value = accountId
        }
    }

    private class FakeCloud(var clears: Boolean = false) : CloudRepository {
        var reconciledWith: Long? = null
        override val status: StateFlow<CloudStatus> = MutableStateFlow(CloudStatus.Initializing)
        override val media: Flow<List<CloudMedia>> = flowOf(emptyList())
        override val destinationTitle: StateFlow<String?> = MutableStateFlow(null)
        override suspend fun reconcileAccount(accountId: Long): Boolean {
            reconciledWith = accountId
            return clears
        }
        override suspend fun prepare() = Unit
        override suspend fun refresh() = Unit
        override suspend fun ensureDestination(): TelepicCloudDestination? = null
        override suspend fun confirmDestination(): TelepicCloudDestination? = null
        override suspend fun createNewDestination(): TelepicCloudDestination? = null
        override suspend fun getPreview(media: CloudMedia): CloudPreview? = null
        override suspend fun downloadOriginal(media: CloudMedia): LocalDownloadedMedia? = null
        override suspend fun uploadMedia(
            request: CloudUploadRequest,
            onProgress: (CloudUploadProgress) -> Unit,
            onSent: suspend (Long, Long) -> Unit,
        ): CloudUploadResult? = error("not used")
        override suspend fun confirmUpload(
            chatId: Long,
            messageId: Long,
            mediaType: com.telepic.domain.cloud.CloudMediaType,
            contentHash: String?,
            contentSizeBytes: Long?,
        ): CloudUploadResult = error("not used")
    }

    private class FakeBackup : BackupRepository {
        var resets = 0
        override fun observeQueue(): Flow<List<BackupItem>> = flowOf(emptyList())
        override fun observeStats(): Flow<BackupQueueStats> = flowOf(BackupQueueStats())
        override suspend fun enqueue(media: LocalMedia): Boolean = false
        override suspend fun enqueueAll(media: List<LocalMedia>): Int = 0
        override suspend fun retry(itemId: Long) = Unit
        override suspend fun cancel(itemId: Long) = Unit
        override suspend fun recoverInterruptedWork() = Unit
        override suspend fun resetForAccountSwitch() {
            resets++
        }
        override suspend fun processPendingWork(maxItems: Int): BackupProcessSummary = BackupProcessSummary()
    }

    private class FakeLedger : RestoreLedger {
        var clears = 0
        override suspend fun publishedUri(identity: RestoreIdentity): String? = null
        override suspend fun record(identity: RestoreIdentity, uri: String) = Unit
        override suspend fun forget(identity: RestoreIdentity) = Unit
        override suspend fun clear() {
            clears++
        }
    }

    private fun coordinator(
        store: FakeStore,
        cloud: FakeCloud = FakeCloud(),
        backup: FakeBackup = FakeBackup(),
        ledger: FakeLedger = FakeLedger(),
    ) = TelegramAccountCoordinator(store, cloud, backup, ledger)

    @Test
    fun `first authorization records the account without clearing anything`() = runTest {
        val store = FakeStore()
        val cloud = FakeCloud()
        val backup = FakeBackup()
        val ledger = FakeLedger()

        coordinator(store, cloud, backup, ledger).onAuthorized(7L)

        assertEquals(7L, store.id.value)
        assertEquals(7L, cloud.reconciledWith)
        assertEquals(0, backup.resets)
        assertEquals(0, ledger.clears)
    }

    @Test
    fun `same-account re-login keeps destination queue and ledger`() = runTest {
        val store = FakeStore(7L)
        val cloud = FakeCloud()
        val backup = FakeBackup()
        val ledger = FakeLedger()

        coordinator(store, cloud, backup, ledger).onAuthorized(7L)

        assertEquals(0, backup.resets)
        assertEquals(0, ledger.clears)
    }

    @Test
    fun `a different account resets queue and restore ledger`() = runTest {
        val store = FakeStore(7L)
        val cloud = FakeCloud(clears = true)
        val backup = FakeBackup()
        val ledger = FakeLedger()

        coordinator(store, cloud, backup, ledger).onAuthorized(8L)

        assertEquals(8L, store.id.value)
        assertEquals(8L, cloud.reconciledWith)
        assertEquals(1, backup.resets)
        assertEquals(1, ledger.clears)
    }

    @Test
    fun `a destination cleared by the cloud layer resets dependent state even for the same id`() = runTest {
        val store = FakeStore(7L)
        val cloud = FakeCloud(clears = true)
        val backup = FakeBackup()
        val ledger = FakeLedger()

        coordinator(store, cloud, backup, ledger).onAuthorized(7L)

        assertEquals(1, backup.resets)
        assertEquals(1, ledger.clears)
        assertEquals(7L, store.id.value)
    }

    @Test
    fun `a failed reconciliation does not record the new account`() = runTest {
        val store = FakeStore(7L)
        val cloud = FakeCloud()
        val coordinator = TelegramAccountCoordinator(
            accountStore = store,
            cloudRepository = object : CloudRepository by cloud {
                override suspend fun reconcileAccount(accountId: Long): Boolean = error("database unavailable")
            },
            backupRepository = FakeBackup(),
            restoreLedger = FakeLedger(),
        )

        val failed = runCatching { coordinator.onAuthorized(8L) }.isFailure

        assertTrue("the reconciliation failure must propagate", failed)
        assertEquals(7L, store.id.value)
    }
}
