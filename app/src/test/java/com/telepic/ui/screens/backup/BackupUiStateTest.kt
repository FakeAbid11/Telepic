package com.telepic.ui.screens.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Nothing queued" and "not read yet" must not look the same. The Backup Center renders its empty
 * state off [BackupUiState.isEmpty], and the ViewModel's `stateIn` initial value is an *unread*
 * state with no items — so before [BackupUiState.isLoaded] existed the screen asserted "no backups
 * yet", a claim about the user's data, over a queue that already had rows in it.
 */
class BackupUiStateTest {

    @Test
    fun `an unread queue is not an empty queue`() {
        assertFalse(BackupUiState().isEmpty)
    }

    @Test
    fun `a read queue with no rows is empty`() {
        assertTrue(BackupUiState(isLoaded = true).isEmpty)
    }
}
