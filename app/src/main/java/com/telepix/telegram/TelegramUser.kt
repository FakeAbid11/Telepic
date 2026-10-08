package com.telepix.telegram

/**
 * A minimal, non-sensitive view of the authenticated Telegram account.
 *
 * Only display identity is carried to the UI. The numeric [id] identifies the session internally;
 * usernames are never treated as the primary identity. No secret material lives here.
 */
data class TelegramUser(
    val id: Long,
    val firstName: String?,
    val lastName: String?,
    val username: String?,
) {
    val displayName: String
        get() = listOfNotNull(firstName?.takeIf { it.isNotBlank() }, lastName?.takeIf { it.isNotBlank() })
            .joinToString(" ")
            .ifBlank { username?.takeIf { it.isNotBlank() } ?: "Telegram account" }
}
