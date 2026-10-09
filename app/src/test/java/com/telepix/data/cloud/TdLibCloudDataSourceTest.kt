package com.telepix.data.cloud

import com.telepix.domain.cloud.CloudMedia
import com.telepix.domain.cloud.CloudMediaType
import com.telepix.domain.cloud.CloudUploadRequest
import com.telepix.domain.cloud.CloudUploadProgress
import com.telepix.telegram.TdLibClientGateway
import com.telepix.telegram.TelegramAuthState
import com.telepix.telegram.TelegramUser
import java.io.File
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Drives the real [TdLibCloudDataSource] through a fake [TdLibClientGateway]: it verifies that the
 * correct TDLib requests are issued and their responses mapped. This is a JVM unit test with a fake
 * transport — NOT a live Telegram integration test (that is device-gated).
 */
class TdLibCloudDataSourceTest {

    private val authorized = MutableStateFlow<TelegramAuthState>(
        TelegramAuthState.Authorized(TelegramUser(99L, "T", null, null)),
    )

    private class FakeGateway(
        private val responder: (TdApi.Function<*>) -> TdApi.Object,
    ) : TdLibClientGateway {
        val sent = mutableListOf<TdApi.Function<*>>()
        override val updates: SharedFlow<TdApi.Object> = MutableSharedFlow(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        override val isOpen: Boolean = true
        override fun open() = Unit
        override suspend fun request(request: TdApi.Function<*>): TdApi.Object {
            sent += request
            return responder(request)
        }
        override fun close() = Unit
    }

    private fun source(responder: (TdApi.Function<*>) -> TdApi.Object): Pair<TdLibCloudDataSource, FakeGateway> {
        val gateway = FakeGateway(responder)
        return TdLibCloudDataSource(gateway, authorized, sendPollDelayMs = 0L, maxSendAttempts = 3) to gateway
    }

    private fun photoContent(previewId: Int, originalId: Int) = TdApi.MessagePhoto().apply {
        photo = TdApi.Photo().apply {
            sizes = arrayOf(
                TdApi.PhotoSize().apply { photo = TdApi.File().apply { id = previewId }; width = 90; height = 90 },
                TdApi.PhotoSize().apply { photo = TdApi.File().apply { id = originalId }; width = 900; height = 900 },
            )
        }
    }

    private fun message(id: Long, content: TdApi.MessageContent) = TdApi.Message().apply {
        this.id = id; chatId = 100L; date = 1_700_000_000; this.content = content
    }

    @Test
    fun `unauthenticated search throws an honest network error, never an empty success`() = runBlocking {
        val unauthorized = MutableStateFlow<TelegramAuthState>(TelegramAuthState.NotConnected)
        val gateway = FakeGateway { TdApi.Error(400, "x") }
        val ds = TdLibCloudDataSource(gateway, unauthorized)
        try {
            ds.searchDestinationCandidates()
            fail("expected CloudNetworkException")
        } catch (e: CloudNetworkException) {
            assertTrue(e.message!!.contains("Not authenticated"))
        }
    }

    @Test
    fun `discovery searches channels, resolves each chat and derives posting rights from membership`() = runBlocking {
        val channel = TdApi.Chat().apply { id = 10L; title = "Telepix Backup"; type = TdApi.ChatTypeSupergroup().apply { isChannel = true } }
        val responder: (TdApi.Function<*>) -> TdApi.Object = { req ->
            when (req) {
                is TdApi.SearchChatsOnServer -> {
                    assertTrue(req.query == "Telepix Backup")
                    assertTrue(req.typeFilter is TdApi.SearchChatTypeFilterChannel)
                    TdApi.Chats(1, longArrayOf(10L))
                }
                is TdApi.GetChat -> channel
                is TdApi.GetMe -> TdApi.User().apply { id = 99L }
                is TdApi.GetChatMember -> TdApi.ChatMember().apply { status = TdApi.ChatMemberStatusCreator() }
                else -> TdApi.Error(400, "unhandled")
            }
        }
        val (ds, gateway) = source(responder)
        val candidates = ds.searchDestinationCandidates()
        assertEquals(1, candidates.size)
        assertEquals(10L, candidates[0].chatId)
        assertTrue(candidates[0].isChannel)
        assertTrue(candidates[0].canPostMessages)
        assertTrue(gateway.sent.any { it is TdApi.GetChatMember })
    }

    @Test
    fun `a candidate the account cannot post to is reported honestly`() = runBlocking {
        val channel = TdApi.Chat().apply { id = 11L; title = "Telepix Backup"; type = TdApi.ChatTypeSupergroup().apply { isChannel = true } }
        val (ds, _) = source { req ->
            when (req) {
                is TdApi.SearchChatsOnServer -> TdApi.Chats(1, longArrayOf(11L))
                is TdApi.GetChat -> channel
                is TdApi.GetMe -> TdApi.User().apply { id = 99L }
                is TdApi.GetChatMember -> TdApi.ChatMember().apply { status = TdApi.ChatMemberStatusMember() }
                else -> TdApi.Error(400, "unhandled")
            }
        }
        val candidates = ds.searchDestinationCandidates()
        assertEquals(1, candidates.size)
        assertEquals(false, candidates[0].canPostMessages)
    }

    @Test
    fun `history is paged newest-first and unsupported messages are skipped`() = runBlocking {
        val page1 = TdApi.Messages(3, arrayOf(
            message(30L, photoContent(1, 2)),
            message(29L, TdApi.MessageText()),
            message(28L, photoContent(3, 4)),
        ))
        val (ds, _) = source { req ->
            when (req) {
                is TdApi.GetChatHistory -> {
                    // second page requested with the oldest id from the first page
                    if (req.fromMessageId == 0L) page1 else TdApi.Messages(0, emptyArray())
                }
                else -> TdApi.Error(400, "unhandled")
            }
        }
        val media = ds.loadNewestMedia(100L, limit = 10)
        assertEquals(2, media.size)
        assertEquals(30L, media[0].messageId)
        assertEquals(28L, media[1].messageId)
    }

    @Test
    fun `original download yields a verified local path and rejects an incomplete file`() = runBlocking {
        val tmp = File.createTempFile("telepix_dl", ".bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        tmp.deleteOnExit()
        val media = CloudMedia(
            messageId = 1L, chatId = 100L, mediaType = CloudMediaType.IMAGE, mimeType = "image/jpeg",
            fileName = null, sizeBytes = 10L, width = 10, height = 10, durationMs = null,
            dateEpochSec = 1L, previewFileId = 41, originalFileId = 42,
        )
        val (ds, _) = source { req ->
            when (req) {
                is TdApi.DownloadFile -> TdApi.File().apply {
                    local = TdApi.LocalFile().apply { path = tmp.absolutePath; isDownloadingCompleted = true }
                }
                else -> TdApi.Error(400, "unhandled")
            }
        }
        val downloaded = ds.downloadOriginal(media)
        assertNotNull(downloaded)
        assertEquals(tmp.absolutePath, downloaded!!.localPath)

        // Incomplete download -> honest null.
        val (ds2, _) = source { _ ->
            TdApi.File().apply { local = TdApi.LocalFile().apply { path = tmp.absolutePath; isDownloadingCompleted = false } }
        }
        assertNull(ds2.downloadOriginal(media))
    }

    @Test
    fun `upload returns remote identity only after the send clears its pending state`() = runBlocking {
        val pending = message(500L, photoContent(1, 2)).apply { sendingState = TdApi.MessageSendingStatePending() }
        val confirmed = message(500L, photoContent(1, 2)).apply { sendingState = null }
        var getCalled = false
        val (ds, gateway) = source { req ->
            when (req) {
                is TdApi.SendMessage -> pending
                is TdApi.GetMessage -> { getCalled = true; confirmed }
                else -> TdApi.Error(400, "unhandled")
            }
        }
        val request = CloudUploadRequest(
            stagedPath = "/tmp/orig.bin", fileName = "orig.jpg", mimeType = "image/jpeg",
            mediaType = CloudMediaType.IMAGE, sizeBytes = 4242L, width = 100, height = 100,
            durationMs = null, dateEpochSec = 1L,
        )
        var progressSamples = 0
        val result = ds.upload(chatId = 100L, request = request, onProgress = { _: CloudUploadProgress -> progressSamples++ })
        assertEquals(100L, result.chatId)
        assertEquals(500L, result.messageId)
        assertEquals(2, result.telegramFileId)
        assertTrue(getCalled)
        assertEquals(1, progressSamples)
        assertTrue(gateway.sent.any { it is TdApi.SendMessage })
    }

    @Test
    fun `a rejected send throws a permanent exception so the queue will not mark backed-up`() = runBlocking {
        val (ds, _) = source { req ->
            when (req) {
                is TdApi.SendMessage -> TdApi.Error(400, "image could not be processed")
                else -> TdApi.Error(400, "unhandled")
            }
        }
        val request = CloudUploadRequest("/tmp/x", "x", "image/jpeg", CloudMediaType.IMAGE, 10L, 1, 1, null, 1L)
        try {
            ds.upload(100L, request) {}
            fail("expected rejection")
        } catch (e: CloudUploadRejectedException) {
            assertTrue(e.message!!.contains("rejected"))
        }
    }

    @Test
    fun `a flood-wait send error is treated as transient for retry`() = runBlocking {
        val (ds, _) = source { _ -> TdApi.Error(429, "retry after") }
        val request = CloudUploadRequest("/tmp/x", "x", "image/jpeg", CloudMediaType.IMAGE, 10L, 1, 1, null, 1L)
        try {
            ds.upload(100L, request) {}
            fail("expected transient")
        } catch (e: CloudNetworkException) {
            assertTrue(e.message!!.contains("429"))
        }
    }

    @Test
    fun `confirmation that never arrives times out as transient`() = runBlocking {
        val (ds, _) = source { req ->
            when (req) {
                is TdApi.SendMessage -> message(600L, photoContent(1, 2)).apply { sendingState = TdApi.MessageSendingStatePending() }
                is TdApi.GetMessage -> message(600L, photoContent(1, 2)).apply { sendingState = TdApi.MessageSendingStatePending() }
                else -> TdApi.Error(400, "unhandled")
            }
        }
        val request = CloudUploadRequest("/tmp/x", "x", "image/jpeg", CloudMediaType.IMAGE, 10L, 1, 1, null, 1L)
        try {
            ds.upload(100L, request) {}
            fail("expected timeout")
        } catch (e: CloudNetworkException) {
            assertTrue(e.message!!.contains("timed out"))
        }
    }
}
