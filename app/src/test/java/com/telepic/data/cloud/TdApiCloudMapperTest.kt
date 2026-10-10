package com.telepic.data.cloud

import com.telepic.domain.cloud.CloudMediaType
import com.telepic.domain.cloud.CloudUploadRequest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.drinkless.tdlib.TdApi

/**
 * Pure mapping between the resolved TDLib 1.8.x `TdApi` value objects and Telepic's domain models.
 * These are JVM tests (no native library): they verify request/response *shapes* and are explicitly
 * NOT live integration tests — end-to-end behaviour is device-gated.
 */
class TdApiCloudMapperTest {

    private fun file(id: Int, size: Long = 100L) = TdApi.File().apply {
        this.id = id
        this.size = size
        this.expectedSize = size
    }

    private fun message(content: TdApi.MessageContent, sendingState: TdApi.MessageSendingState? = null): TdApi.Message =
        TdApi.Message().apply {
            id = 55L
            chatId = 100L
            date = 1_700_000_000
            this.content = content
            this.sendingState = sendingState
        }

    @Test
    fun `photo maps with smallest preview and largest original`() {
        val small = TdApi.PhotoSize().apply { type = "s"; photo = file(11, 5_000); width = 100; height = 100 }
        val large = TdApi.PhotoSize().apply { type = "x"; photo = file(12, 500_000); width = 1280; height = 720 }
        val photo = TdApi.Photo().apply { sizes = arrayOf(small, large) }
        val content = TdApi.MessagePhoto().apply { this.photo = photo }

        val media = TdApiCloudMapper.messageToCloudMedia(100L, message(content))!!
        assertEquals(CloudMediaType.IMAGE, media.mediaType)
        assertEquals(11, media.previewFileId)
        assertEquals(12, media.originalFileId)
        assertEquals(500_000L, media.sizeBytes)
        assertEquals(1280, media.width)
        assertEquals(720, media.height)
        assertNull(media.durationMs)
    }

    @Test
    fun `video maps duration ms and thumbnail preview`() {
        val thumbnail = TdApi.Thumbnail().apply { file = file(21) }
        val video = TdApi.Video().apply {
            duration = 12
            width = 640
            height = 480
            mimeType = "video/mp4"
            fileName = "clip.mp4"
            this.video = file(22, 900_000)
            this.thumbnail = thumbnail
        }
        val content = TdApi.MessageVideo().apply { this.video = video }

        val media = TdApiCloudMapper.messageToCloudMedia(100L, message(content))!!
        assertEquals(CloudMediaType.VIDEO, media.mediaType)
        assertEquals(21, media.previewFileId)
        assertEquals(22, media.originalFileId)
        assertEquals(12_000L, media.durationMs)
        assertEquals("clip.mp4", media.fileName)
    }

    @Test
    fun `animation is classified as GIF, not image`() {
        val animation = TdApi.Animation().apply {
            duration = 3
            width = 200
            height = 200
            mimeType = "image/gif"
            this.animation = file(31, 700_000)
        }
        val content = TdApi.MessageAnimation().apply { this.animation = animation }

        val media = TdApiCloudMapper.messageToCloudMedia(100L, message(content))!!
        assertEquals(CloudMediaType.GIF, media.mediaType)
        assertEquals(3_000L, media.durationMs)
    }

    @Test
    fun `document is only kept when it is really image or video media`() {
        val asVideo = TdApi.Document().apply { mimeType = "video/mp4"; document = file(41) }
        val supported = TdApiCloudMapper.messageToCloudMedia(
            100L,
            message(TdApi.MessageDocument().apply { document = asVideo }),
        )
        assertEquals(CloudMediaType.VIDEO, supported?.mediaType)

        val asPdf = TdApi.Document().apply { mimeType = "application/pdf"; document = file(42) }
        assertNull(
            TdApiCloudMapper.messageToCloudMedia(
                100L,
                message(TdApi.MessageDocument().apply { document = asPdf }),
            ),
        )
    }

    @Test
    fun `a failed send and an unsupported content are skipped`() {
        val photo = TdApi.Photo().apply { sizes = arrayOf(TdApi.PhotoSize().apply { photo = file(1) }) }
        val failed = message(
            TdApi.MessagePhoto().apply { this.photo = photo },
            sendingState = TdApi.MessageSendingStateFailed(),
        )
        assertNull(TdApiCloudMapper.messageToCloudMedia(100L, failed))

        val text = TdApi.MessageText()
        assertNull(TdApiCloudMapper.messageToCloudMedia(100L, message(text)))
        assertNull(TdApiCloudMapper.messageToCloudMedia(100L, null))
    }

    @Test
    fun `creator and post-privileged admin can post but a channel member cannot`() {
        val member = TdApi.ChatMember().apply { status = TdApi.ChatMemberStatusCreator() }
        assertTrue(TdApiCloudMapper.canPostFromMember(member))

        val adminPost = TdApi.ChatMember().apply {
            status = TdApi.ChatMemberStatusAdministrator().apply {
                rights = TdApi.ChatAdministratorRights().apply { canPostMessages = true }
            }
        }
        assertTrue(TdApiCloudMapper.canPostFromMember(adminPost))

        val adminNoPost = TdApi.ChatMember().apply {
            status = TdApi.ChatMemberStatusAdministrator().apply {
                rights = TdApi.ChatAdministratorRights().apply { canPostMessages = false }
            }
        }
        assertFalse(TdApiCloudMapper.canPostFromMember(adminNoPost))

        val plainMember = TdApi.ChatMember().apply { status = TdApi.ChatMemberStatusMember() }
        assertFalse(TdApiCloudMapper.canPostFromMember(plainMember))
        assertFalse(TdApiCloudMapper.canPostFromMember(null))
    }

    @Test
    fun `a channel supergroup maps to an accessible postable candidate`() {
        val chat = TdApi.Chat().apply {
            id = 7L
            title = "Telepic Backup"
            type = TdApi.ChatTypeSupergroup().apply { isChannel = true }
        }
        val candidate = TdApiCloudMapper.chatToCandidate(chat, canPost = true, isOwnedByAccount = true, accessible = true)
        assertEquals(7L, candidate.chatId)
        assertTrue(candidate.isChannel)
        assertTrue(candidate.canPostMessages)
        assertTrue(candidate.isOwnedByAccount)

        val group = TdApi.Chat().apply {
            id = 8L
            title = "Telepic Backup"
            type = TdApi.ChatTypeSupergroup().apply { isChannel = false }
        }
        assertFalse(TdApiCloudMapper.chatToCandidate(group, canPost = true, isOwnedByAccount = true, accessible = true).isChannel)
    }

    @Test
    fun `upload builds the media-appropriate input over the local staged file`() {
        fun request(type: CloudMediaType) = CloudUploadRequest(
            stagedPath = "/tmp/a",
            fileName = "a",
            mimeType = "image/jpeg",
            mediaType = type,
            sizeBytes = 10L,
            width = 40,
            height = 30,
            durationMs = 4000L,
            dateEpochSec = 1L,
        )

        val photo = TdApiCloudMapper.uploadContent(request(CloudMediaType.IMAGE)) as TdApi.InputMessagePhoto
        assertTrue(photo.photo?.photo is TdApi.InputFileLocal)
        assertEquals("/tmp/a", (photo.photo.photo as TdApi.InputFileLocal).path)
        assertEquals(40, photo.photo.width)

        val video = TdApiCloudMapper.uploadContent(request(CloudMediaType.VIDEO)) as TdApi.InputMessageVideo
        assertEquals(4, video.video.duration)

        val gif = TdApiCloudMapper.uploadContent(request(CloudMediaType.GIF)) as TdApi.InputMessageAnimation
        assertEquals(File("/tmp/a").path, "/tmp/a")
        assertEquals(4, gif.animation.duration)
    }

    @Test
    fun `uploaded file id is read back from the confirmed message`() {
        val photo = TdApi.Photo().apply {
            sizes = arrayOf(
                TdApi.PhotoSize().apply { photo = file(1) },
                TdApi.PhotoSize().apply { photo = file(9); width = 800; height = 800 },
            )
        }
        val msg = message(TdApi.MessagePhoto().apply { this.photo = photo })
        assertEquals(9, TdApiCloudMapper.uploadedFileId(msg))
    }
}
