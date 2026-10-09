package com.telepix.domain.cloud

import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies a destination is validated on real capability AND account ownership, never the title. */
class ChatValidatorTest {

    private fun candidate(
        isChannel: Boolean = true,
        canPost: Boolean = true,
        accessible: Boolean = true,
        owned: Boolean = true,
        title: String = "Telepic Backup",
    ) = ChatCandidate(
        chatId = 1L,
        title = title,
        isChannel = isChannel,
        canPostMessages = canPost,
        isAccessible = accessible,
        isOwnedByAccount = owned,
    )

    @Test
    fun `an owned postable accessible channel with the right title is valid`() {
        assertTrue(ChatValidator.validate(candidate()) is DestinationVerdict.Valid)
    }

    @Test
    fun `inaccessible chat is invalid`() {
        assertTrue(ChatValidator.validate(candidate(accessible = false)) is DestinationVerdict.Invalid)
    }

    @Test
    fun `non-channel chat is invalid`() {
        assertTrue(ChatValidator.validate(candidate(isChannel = false)) is DestinationVerdict.Invalid)
    }

    @Test
    fun `channel the account cannot post to is invalid`() {
        assertTrue(ChatValidator.validate(candidate(canPost = false)) is DestinationVerdict.Invalid)
    }

    @Test
    fun `a same-name channel the account merely administers is never auto-selected`() {
        // Right title, a channel, postable — but NOT owned by the account (e.g. admin of someone
        // else's channel). Must require explicit confirmation, never an implicit auto-backup target.
        val verdict = ChatValidator.validate(candidate(owned = false))
        assertTrue(verdict is DestinationVerdict.NeedsConfirmation)
    }

    @Test
    fun `same-name non-channel impersonation is invalid`() {
        val imposter = candidate(isChannel = false, title = "Telepic Backup")
        assertTrue(ChatValidator.validate(imposter) is DestinationVerdict.Invalid)
    }

    @Test
    fun `wrong title is invalid even if otherwise postable and owned`() {
        assertTrue(ChatValidator.validate(candidate(title = "Random Channel")) is DestinationVerdict.Invalid)
    }
}
