package com.telepix.ui.screens.backup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.performClick
import com.telepix.data.backup.BackupProcessSummary
import com.telepix.data.backup.BackupCoordinator
import com.telepix.data.backup.BackupRepository
import com.telepix.domain.backup.BackupItem
import com.telepix.domain.backup.BackupQueueStats
import com.telepix.domain.backup.BackupState
import com.telepix.domain.media.LocalMedia
import com.telepix.telegram.TelegramAuthState
import com.telepix.telegram.TelegramUser
import com.telepix.ui.theme.TelepixTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class BackupCenterUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeRepo(
        items: List<BackupItem>,
        stats: BackupQueueStats,
    ) : BackupRepository {
        var retried = -1L
        var cancelled = -1L
        override fun observeQueue(): Flow<List<BackupItem>> = flowOf(items)
        override fun observeStats(): Flow<BackupQueueStats> = flowOf(stats)
        override suspend fun enqueue(media: LocalMedia) = true
        override suspend fun enqueueAll(media: List<LocalMedia>) = 0
        override suspend fun retry(itemId: Long) { retried = itemId }
        override suspend fun cancel(itemId: Long) { cancelled = itemId }
        override suspend fun recoverInterruptedWork() = Unit
        override suspend fun processPendingWork(maxItems: Int) = BackupProcessSummary()
    }

    private class FakeCoordinator(override val repository: BackupRepository) : BackupCoordinator {
        var started = false
        override suspend fun backup(media: LocalMedia) = Unit
        override suspend fun backupAll(media: List<LocalMedia>) = Unit
        override suspend fun retry(itemId: Long) = repository.retry(itemId)
        override suspend fun cancel(itemId: Long) = repository.cancel(itemId)
        override suspend fun syncFromPreference() = Unit
        override suspend fun startPendingBackup() { started = true }
    }

    private val authorized: StateFlow<TelegramAuthState> =
        MutableStateFlow<TelegramAuthState>(TelegramAuthState.Authorized(TelegramUser(1, "A", "B", null))).asStateFlow()

    private fun item(id: Long, state: BackupState) = BackupItem(
        id = id, localMediaId = id.toString(), contentUri = "content://m/$id", mediaType = "IMAGE",
        mimeType = "image/jpeg", fileName = "p$id.jpg", sizeBytes = 10L, modifiedTimeSeconds = 1L,
        state = state, retryCount = 0, lastError = null, telegramChatId = null, telegramMessageId = null,
        createdAt = 1L, updatedAt = 1L, startedAt = null, completedAt = null,
    )

    private fun render(repo: FakeRepo): FakeCoordinator {
        val coordinator = FakeCoordinator(repo)
        composeRule.setContent {
            TelepixTheme(darkTheme = true) {
                BackupCenterScreen(viewModel = BackupViewModel(coordinator, authorized))
            }
        }
        return coordinator
    }

    @Test
    fun `empty queue shows the honest empty state`() {
        render(FakeRepo(emptyList(), BackupQueueStats()))
        composeRule.onNodeWithText("Nothing to back up yet").assertIsDisplayed()
    }

    @Test
    fun `populated queue shows counts and per-item states`() {
        render(
            FakeRepo(
                items = listOf(item(1, BackupState.QUEUED), item(2, BackupState.BACKED_UP), item(3, BackupState.FAILED)),
                stats = BackupQueueStats(queued = 1, uploading = 0, completed = 1, failed = 1),
            ),
        )
        composeRule.onNodeWithText("p1.jpg").assertIsDisplayed()
        composeRule.onNodeWithText("p3.jpg").assertIsDisplayed()
        composeRule.onNodeWithText("Backed up").assertIsDisplayed()
        composeRule.onNodeWithText("Completed").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun `waiting-for-auth surfaces the Telegram guidance state`() {
        render(FakeRepo(listOf(item(1, BackupState.WAITING_FOR_AUTH)), BackupQueueStats(queued = 1)))
        composeRule.onNodeWithText("Backup is ready").assertDoesNotExist()
        composeRule.onNodeWithText("Waiting for Telegram…").assertIsDisplayed()
    }

    @Test
    fun `start action is wired to the coordinator`() {
        val repo = FakeRepo(listOf(item(1, BackupState.QUEUED)), BackupQueueStats(queued = 1))
        val coordinator = render(repo)
        composeRule.onNodeWithText("Start backup").performClick()
        assertTrue(coordinator.started)
    }
}
