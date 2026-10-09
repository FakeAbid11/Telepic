package com.telepix.domain.cloud

/**
 * A chat candidate reduced to the facts Telepic needs to validate a backup destination.
 *
 * Deliberately free of TdApi so validation is pure and unit-testable; the TDLib data source maps
 * a raw chat into this shape. [isOwnedByAccount] is true only when the current account CREATED /
 * owns the channel (TDLib membership status Creator), never inferred from the title or mere posting
 * rights — an admin of someone else's same-name channel is not an owner.
 */
data class ChatCandidate(
    val chatId: Long,
    val title: String,
    val isChannel: Boolean,
    val canPostMessages: Boolean,
    val isAccessible: Boolean,
    val isOwnedByAccount: Boolean,
)

/** Why a candidate is (or isn't) a usable Telepic backup destination. */
sealed interface DestinationVerdict {
    data object Valid : DestinationVerdict
    data class Invalid(val reason: String) : DestinationVerdict
    /** A same-name channel the account can post to but does not own: usable only with explicit consent. */
    data class NeedsConfirmation(val chatId: Long, val reason: String) : DestinationVerdict
}

/**
 * Validates that a Telegram chat is genuinely suitable as the Telepic Backup destination — never
 * from the title alone. A destination must be an accessible channel the account can post to, carries
 * the expected Telepic title, AND is owned by the account, so a same-name channel owned by someone
 * else is not used automatically. A postable-but-not-owned same-name channel yields
 * [DestinationVerdict.NeedsConfirmation] (never an implicit auto-backup); anything else is invalid.
 */
object ChatValidator {

    fun validate(candidate: ChatCandidate, expectedTitle: String = TelepixDestinationTitle): DestinationVerdict = when {
        !candidate.isAccessible -> DestinationVerdict.Invalid("The destination is not accessible.")
        !candidate.isChannel -> DestinationVerdict.Invalid("Not a Telegram channel.")
        !candidate.canPostMessages -> DestinationVerdict.Invalid("Telepic cannot post to this channel.")
        !candidate.title.equals(expectedTitle, ignoreCase = true) ->
            DestinationVerdict.Invalid("Name does not match '$expectedTitle'.")
        candidate.isOwnedByAccount -> DestinationVerdict.Valid
        else -> DestinationVerdict.NeedsConfirmation(
            candidate.chatId,
            "A matching channel exists but is not owned by this account; explicit confirmation required.",
        )
    }

    const val TelepixDestinationTitle = "Telepic Backup"
}
