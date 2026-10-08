package com.telepix.telegram

import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction the UI consumes for Telegram authorization. Phase 4 provides a TDLib-backed
 * implementation; Phase 2 provides [UnavailableTelegramAuthController] so the onboarding
 * screen can render an honest "not connected" state without any fake client.
 */
interface TelegramAuthController {
    /** Current authorization state. */
    val state: StateFlow<TelegramAuthState>

    /**
     * Whether a real Telegram backend is wired up. `false` in Phase 2, which lets the UI show
     * a clean disabled/"being prepared" state instead of pretending authentication exists.
     */
    val isBackendAvailable: Boolean

    /** Begin authentication. No-op until the TDLib backend exists (Phase 4). */
    fun connect()

    /** Cancel an in-progress authentication. No-op until Phase 4. */
    fun cancel()
}
