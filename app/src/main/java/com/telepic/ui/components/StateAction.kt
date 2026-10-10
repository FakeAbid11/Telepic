package com.telepic.ui.components

/**
 * A single tappable action shown by the reusable state components
 * ([EmptyState] and [ErrorState]).
 */
data class StateAction(
    val label: String,
    val onClick: () -> Unit,
)
