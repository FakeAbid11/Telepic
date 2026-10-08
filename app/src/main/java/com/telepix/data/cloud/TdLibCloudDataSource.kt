package com.telepix.data.cloud

import com.telepix.domain.cloud.ChatCandidate
import com.telepix.domain.cloud.CloudMedia
import com.telepix.domain.cloud.CloudPreview
import com.telepix.domain.cloud.CloudUploadProgress
import com.telepix.domain.cloud.CloudUploadRequest
import com.telepix.domain.cloud.CloudUploadResult
import com.telepix.domain.cloud.LocalDownloadedMedia
import com.telepix.telegram.TelegramAuthState
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * TDLib-backed [CloudDataSource].
 *
 * Phase 5 status (honest): the concrete Telegram cloud calls — chat discovery
 * (`searchChatsOnServer`/`getChats`), destination creation (`createNewSupergroupChat`), history
 * paging (`getChatHistory`) and file download (`download` + local `getFile` path) — depend on
 * TDLib's Java `TdApi`, whose generated surface is produced at AAR build time and has churned
 * across 1.8.x. Exact field/method names must be finalized against the bundled schema **on a real
 * ARM device with an authenticated Telegram session** (see README). Until that bring-up, every
 * call below is defensive and returns an honest "not available" result — it NEVER fabricates a
 * destination, media list, preview, or download.
 *
 * Everything downstream (validation, persistence, manifest, Cloud state machine, UI, repository
 * orchestration) is implemented and unit/UI-tested against fakes, so completing only the request
 * shapes above activates the live cloud with no further changes.
 *
 * Phase 6 adds the upload path. For the same reason it is deliberately **not** guessed: until the
 * `sendMessage` + `InputFile` request shapes are finalized on a real device, [upload] throws an
 * honest transient [CloudNetworkException] so the engine keeps items QUEUED rather than falsely
 * marking them BACKED_UP. The repository/queue/WorkManager layers are built against the [CloudDataSource]
 * seam and are fully tested with a fake that does succeed.
 */
class TdLibCloudDataSource(
    private val filesDir: File,
    private val authState: StateFlow<TelegramAuthState>,
) : CloudDataSource {

    private val isReady: Boolean get() = authState.value is TelegramAuthState.Authorized

    override suspend fun searchDestinationCandidates(): List<ChatCandidate> {
        // TODO(Phase 5 device bring-up): searchChatsOnServer -> map each Chat(chatId, title,
        // ChatTypeSupergroup.isChannel, postability, accessibility) into a ChatCandidate.
        return emptyList()
    }

    override suspend fun createDestination(): ChatCandidate? {
        // TODO(Phase 5 device bring-up): createNewSupergroupChat(title = "Telepix Backup",
        // isChannel = true) -> map the resulting Chat. Persisted only after validation.
        return null
    }

    override suspend fun loadNewestMedia(chatId: Long, limit: Int): List<CloudMedia> {
        // TODO(Phase 5 device bring-up): getChatHistory(chatId, returnLastMessages) newest-first;
        // map MessagePhoto/Video/Animation via CloudMediaKind; ignore unsupported messages.
        return emptyList()
    }

    override suspend fun downloadPreview(media: CloudMedia): CloudPreview? {
        // TODO(Phase 5 device bring-up): download the smallest preview file into [filesDir].
        return null
    }

    override suspend fun downloadOriginal(media: CloudMedia): LocalDownloadedMedia? {
        if (!isReady) throw CloudNetworkException("Not authenticated")
        // TODO(Phase 5 device bring-up): explicitly download the original into [filesDir].
        return null
    }

    override suspend fun upload(
        chatId: Long,
        request: CloudUploadRequest,
        onProgress: (CloudUploadProgress) -> Unit,
    ): CloudUploadResult {
        if (!isReady) throw CloudNetworkException("Not authenticated")
        // TODO(Phase 6 device bring-up): map [request] to a Telegram InputFile + sendMessage
        // (Photo/Video/Animation vs Document for byte-preserving upload) against the bundled TdApi,
        // await the sent Message, and return its real (chatId, messageId, fileId). Until then this
        // is an honest, transient "not available": the upload never succeeds and the queue never
        // becomes BACKED_UP — no fabricated remote identity.
        throw CloudNetworkException("Telegram upload is not yet available on this build")
    }
}
