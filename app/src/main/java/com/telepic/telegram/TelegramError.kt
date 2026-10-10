package com.telepic.telegram

/**
 * A user-facing Telegram error. Carries a safe, displayable [message] only — never raw TDLib
 * internals or sensitive input. [retryAfterSeconds] is set for flood waits so the UI can ask the
 * user to wait instead of retrying aggressively.
 */
sealed class TelegramError(
    val message: String,
    val retryAfterSeconds: Int? = null,
) {
    class Network(message: String) : TelegramError(message)
    class InvalidPhoneNumber(message: String) : TelegramError(message)
    class InvalidCode(message: String) : TelegramError(message)
    class InvalidPassword(message: String) : TelegramError(message)
    class FloodWait(seconds: Int) :
        TelegramError("Too many attempts. Please wait before trying again.", seconds)

    class Unauthorized(message: String) : TelegramError(message)
    class Initialization(message: String) : TelegramError(message)
    class Unknown(message: String) : TelegramError(message)

    /** The build was compiled without Telegram API credentials, so sign-in cannot proceed. */
    class NotConfigured : TelegramError(
        "Telegram sign-in isn't configured on this build. Provide the Telegram API credentials to enable it.",
    )

    companion object {
        /**
         * Maps a TDLib error (HTTP-ish [code] + [message]) to a safe [TelegramError]. Pure, so
         * it is unit-testable without a live client. [hintStage] disambiguates otherwise-generic
         * 400 errors (which field the user was submitting).
         */
        fun classify(
            code: Int,
            message: String,
            hintStage: AuthStage = AuthStage.None,
        ): TelegramError {
            val lower = message.lowercase()
            return when {
                code == 420 -> FloodWait(extractSeconds(message) ?: 60)
                "network" in lower || "connection" in lower || code == 499 ->
                    Network(sanitize(message))
                code == 401 -> Unauthorized(sanitize(message))
                code == 400 -> when (hintStage) {
                    AuthStage.PhoneNumber -> InvalidPhoneNumber(sanitize(message))
                    AuthStage.Code -> InvalidCode(sanitize(message))
                    AuthStage.Password -> InvalidPassword(sanitize(message))
                    AuthStage.None -> Unknown(sanitize(message))
                }
                else -> Unknown(sanitize(message))
            }
        }

        private fun extractSeconds(message: String): Int? =
            Regex("\\d+").find(message)?.value?.toIntOrNull()

        // Never surface raw provider text verbatim; keep it short and free of control chars.
        private fun sanitize(message: String): String =
            message.trim().replace('\n', ' ').take(160).ifBlank { "Telegram reported a problem." }
    }

    enum class AuthStage { None, PhoneNumber, Code, Password }
}
