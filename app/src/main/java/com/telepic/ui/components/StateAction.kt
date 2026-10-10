package com.telepic.ui.components

/**
 * A single tappable action shown by the reusable state components
 * ([EmptyState] and [ErrorState]). [enabled] gates it honestly when the action would be a
 * duplicate or is temporarily meaningless (e.g. "Start backup" while uploads are already running).
 */
data class StateAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
)
