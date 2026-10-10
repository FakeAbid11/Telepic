package com.telepic.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Test

/** The queue's internal state machine collapses to a compact, honest UI state (§15/§86). */
class MediaBackupVisualStateTest {

    @Test
    fun `backed up only when the queue says BACKED_UP`() {
        assertEquals(MediaBackupVisualState.BACKED_UP, MediaBackupVisualState.from(BackupState.BACKED_UP))
    }

    @Test
    fun `preparing and uploading collapse to uploading`() {
        assertEquals(MediaBackupVisualState.UPLOADING, MediaBackupVisualState.from(BackupState.PREPARING))
        assertEquals(MediaBackupVisualState.UPLOADING, MediaBackupVisualState.from(BackupState.UPLOADING))
    }

    @Test
    fun `queued and in-budget waiting collapse to queued`() {
        assertEquals(MediaBackupVisualState.QUEUED, MediaBackupVisualState.from(BackupState.QUEUED))
        assertEquals(MediaBackupVisualState.QUEUED, MediaBackupVisualState.from(BackupState.WAITING_FOR_NETWORK))
        assertEquals(MediaBackupVisualState.QUEUED, MediaBackupVisualState.from(BackupState.WAITING_FOR_NETWORK, retryCount = 3, maxRetries = 5))
        assertEquals(MediaBackupVisualState.QUEUED, MediaBackupVisualState.from(BackupState.WAITING_FOR_AUTH))
    }

    @Test
    fun `a network-waiting row past its retry budget is STALLED, not queued`() {
        // Nothing will automatically pick it up again — showing "queued" would fake progress.
        assertEquals(MediaBackupVisualState.STALLED, MediaBackupVisualState.from(BackupState.WAITING_FOR_NETWORK, retryCount = 5, maxRetries = 5))
        assertEquals(MediaBackupVisualState.STALLED, MediaBackupVisualState.from(BackupState.WAITING_FOR_NETWORK, retryCount = 9, maxRetries = 5))
        // Auth-waiting is a different cause (sign-in needed) and stays QUEUED regardless of count.
        assertEquals(MediaBackupVisualState.QUEUED, MediaBackupVisualState.from(BackupState.WAITING_FOR_AUTH, retryCount = 9, maxRetries = 5))
    }

    @Test
    fun `failed maps to failed`() {
        assertEquals(MediaBackupVisualState.FAILED, MediaBackupVisualState.from(BackupState.FAILED))
    }

    @Test
    fun `not backed up and cancelled show no indicator`() {
        assertEquals(MediaBackupVisualState.NONE, MediaBackupVisualState.from(BackupState.NOT_BACKED_UP))
        assertEquals(MediaBackupVisualState.NONE, MediaBackupVisualState.from(BackupState.CANCELLED))
        assertEquals(MediaBackupVisualState.NONE, MediaBackupVisualState.from(null))
    }
}
