package com.telepic.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Verifies the failure-tolerant parsing used when reading a persisted backup preference. */
class BackupPreferenceTest {

    @Test
    fun `round-trips every preference name`() {
        BackupPreference.entries.forEach { preference ->
            assertSame(preference, BackupPreference.fromKey(preference.name))
        }
    }

    @Test
    fun `parse is case-insensitive`() {
        assertSame(BackupPreference.BACKUP_ALL, BackupPreference.fromKey("backup_all"))
        assertSame(BackupPreference.NOT_NOW, BackupPreference.fromKey("NoT_NoW"))
    }

    @Test
    fun `unknown or missing keys parse to null`() {
        assertNull(BackupPreference.fromKey(null))
        assertNull(BackupPreference.fromKey(""))
        assertNull(BackupPreference.fromKey("bogus"))
    }

    @Test
    fun `exactly the three PRD choices exist`() {
        assertEquals(3, BackupPreference.entries.size)
    }
}
