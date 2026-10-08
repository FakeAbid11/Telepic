package com.telepix.domain.backup

/**
 * The single authority on which [BackupState] transitions are legal. Encoding them here (rather
 * than scattering `if`s through the engine) makes the invariant testable and impossible to violate
 * by accident: nothing may enter [BackupState.BACKED_UP] except from [BackupState.UPLOADING], and
 * terminal states never leave.
 */
object BackupStateMachine {

    private val allowed: Map<BackupState, Set<BackupState>> = mapOf(
        BackupState.NOT_BACKED_UP to setOf(BackupState.QUEUED),
        BackupState.QUEUED to setOf(
            BackupState.PREPARING,
            BackupState.WAITING_FOR_AUTH,
            BackupState.WAITING_FOR_NETWORK,
            BackupState.CANCELLED,
        ),
        BackupState.PREPARING to setOf(
            BackupState.UPLOADING,
            BackupState.WAITING_FOR_AUTH,
            BackupState.WAITING_FOR_NETWORK,
            BackupState.FAILED,
            BackupState.CANCELLED,
            BackupState.QUEUED, // crash recovery
        ),
        BackupState.UPLOADING to setOf(
            BackupState.BACKED_UP,
            BackupState.FAILED,
            BackupState.WAITING_FOR_AUTH,
            BackupState.WAITING_FOR_NETWORK,
            BackupState.CANCELLED,
            BackupState.QUEUED, // crash recovery
        ),
        BackupState.WAITING_FOR_AUTH to setOf(
            BackupState.QUEUED,
            BackupState.CANCELLED,
        ),
        BackupState.WAITING_FOR_NETWORK to setOf(
            BackupState.QUEUED,
            BackupState.CANCELLED,
        ),
        BackupState.FAILED to setOf(
            BackupState.QUEUED, // retry
            BackupState.CANCELLED,
        ),
        BackupState.BACKED_UP to emptySet(),
        BackupState.CANCELLED to emptySet(),
    )

    fun canTransition(from: BackupState, to: BackupState): Boolean =
        allowed[from]?.contains(to) == true

    /** Returns [to] when legal, otherwise [from] unchanged — invalid transitions are ignored. */
    fun transitionOr(from: BackupState, to: BackupState): BackupState =
        if (canTransition(from, to)) to else from
}
