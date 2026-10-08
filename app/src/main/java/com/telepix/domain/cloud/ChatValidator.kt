package com.telepix.domain.cloud

/**
 * A chat candidate reduced to the facts Telepix needs to validate a backup destination.
 *
 * Deliberately free of TdApi so validation is pure and unit-testable; the TDLib data source maps
 * a raw chat into this shape.
 */
data class ChatCandidate(
    val chatId: Long,
    val title: String,
    val isChannel: Boolean,
    val canPostMessages: Boolean,
    val isAccessible: Boolean,
)

/** Why a candidate is (or isn't) a usable Telepix backup destination. */
sealed interface DestinationVerdict {
    data object Valid : DestinationVerdict
    data class Invalid(val reason: String) : DestinationVerdict
}

/**
 * Validates that a Telegram chat is genuinely suitable as the Telepix Backup destination — never
 * from the title alone. A destination must be an accessible channel the account can post to and
 * that carries the expected Telepix title, so a same-name chat owned by someone else (or a private
 * chat / non-postable group) is rejected.
 */
object ChatValidator {

    fun validate(candidate: ChatCandidate, expectedTitle: String = TelepixDestinationTitle): DestinationVerdict = when {
        !candidate.isAccessible -> DestinationVerdict.Invalid("The destination is not accessible.")
        !candidate.isChannel -> DestinationVerdict.Invalid("Not a Telegram channel.")
        !candidate.canPostMessages -> DestinationVerdict.Invalid("Telepix cannot post to this channel.")
        !candidate.title.equals(expectedTitle, ignoreCase = true) ->
            DestinationVerdict.Invalid("Name does not match '$expectedTitle'.")
        else -> DestinationVerdict.Valid
    }

    const val TelepixDestinationTitle = "Telepix Backup"
}
