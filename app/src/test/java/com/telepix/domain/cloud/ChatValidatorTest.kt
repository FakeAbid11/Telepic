package com.telepix.domain.cloud

import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies a destination is validated on real capability, never the title alone. */
class ChatValidatorTest {

    private fun candidate(
        isChannel: Boolean = true,
        canPost: Boolean = true,
        accessible: Boolean = true,
        title: String = "Telepix Backup",
    ) = ChatCandidate(
        chatId = 1L,
        title = title,
        isChannel = isChannel,
        canPostMessages = canPost,
        isAccessible = accessible,
    )

    @Test
    fun `a postable accessible channel with the right title is valid`() {
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
    fun `same-name channel owned by someone else is rejected on title only is not enough`() {
        // Correct title + channel but the validator also requires post rights/access; a normal
        // group that merely matches the title (not a channel) must fail.
        val imposter = candidate(isChannel = false, title = "Telepix Backup")
        assertTrue(ChatValidator.validate(imposter) is DestinationVerdict.Invalid)
    }

    @Test
    fun `wrong title is invalid even if otherwise postable`() {
        assertTrue(ChatValidator.validate(candidate(title = "Random Channel")) is DestinationVerdict.Invalid)
    }
}
