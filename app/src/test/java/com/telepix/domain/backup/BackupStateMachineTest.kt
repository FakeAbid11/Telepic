package com.telepix.domain.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every legal transition of the backup state machine, and the invariant that BACKED_UP is only
 * reachable from UPLOADING — the single guarantee that a file is never marked done without upload. */
class BackupStateMachineTest {

    @Test
    fun `happy path transitions are allowed`() {
        assertTrue(BackupStateMachine.canTransition(BackupState.NOT_BACKED_UP, BackupState.QUEUED))
        assertTrue(BackupStateMachine.canTransition(BackupState.QUEUED, BackupState.PREPARING))
        assertTrue(BackupStateMachine.canTransition(BackupState.PREPARING, BackupState.UPLOADING))
        assertTrue(BackupStateMachine.canTransition(BackupState.UPLOADING, BackupState.BACKED_UP))
    }

    @Test
    fun `only uploading may reach backed up`() {
        val reachable = BackupState.entries.filter { BackupStateMachine.canTransition(it, BackupState.BACKED_UP) }
        assertEquals(listOf(BackupState.UPLOADING), reachable)
    }

    @Test
    fun `terminal states never leave`() {
        for (target in BackupState.entries) {
            assertFalse(BackupStateMachine.canTransition(BackupState.BACKED_UP, target))
            assertFalse(BackupStateMachine.canTransition(BackupState.CANCELLED, target))
        }
    }

    @Test
    fun `waiting states are non-terminal and return to queued`() {
        assertTrue(BackupStateMachine.canTransition(BackupState.QUEUED, BackupState.WAITING_FOR_NETWORK))
        assertTrue(BackupStateMachine.canTransition(BackupState.WAITING_FOR_NETWORK, BackupState.QUEUED))
        assertTrue(BackupStateMachine.canTransition(BackupState.UPLOADING, BackupState.WAITING_FOR_AUTH))
        assertTrue(BackupStateMachine.canTransition(BackupState.WAITING_FOR_AUTH, BackupState.QUEUED))
    }

    @Test
    fun `failed can be retried or cancelled but never jumped straight to done`() {
        assertTrue(BackupStateMachine.canTransition(BackupState.FAILED, BackupState.QUEUED))
        assertTrue(BackupStateMachine.canTransition(BackupState.FAILED, BackupState.CANCELLED))
        assertFalse(BackupStateMachine.canTransition(BackupState.FAILED, BackupState.BACKED_UP))
    }

    @Test
    fun `illegal transitions are ignored by transitionOr`() {
        assertEquals(
            BackupState.QUEUED,
            BackupStateMachine.transitionOr(BackupState.QUEUED, BackupState.BACKED_UP),
        )
        assertEquals(
            BackupState.BACKED_UP,
            BackupStateMachine.transitionOr(BackupState.UPLOADING, BackupState.BACKED_UP),
        )
    }

    @Test
    fun `queued and waiting are actionable, done states are terminal`() {
        assertTrue(BackupState.QUEUED.isActionable)
        assertTrue(BackupState.WAITING_FOR_NETWORK.isActionable)
        assertFalse(BackupState.BACKED_UP.isActionable)
        assertTrue(BackupState.BACKED_UP.isTerminal)
        assertFalse(BackupState.UPLOADING.isTerminal)
    }
}
