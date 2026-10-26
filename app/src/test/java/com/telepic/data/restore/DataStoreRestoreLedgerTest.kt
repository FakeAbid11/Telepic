package com.telepic.data.restore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The restore ledger — the app-owned (remote identity -> published uri) record that lets a repeat
 * restore reuse the earlier copy without a second MediaStore insert.
 */
class DataStoreRestoreLedgerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newLedger(name: String): Pair<DataStoreRestoreLedger, DataStore<Preferences>> {
        val dataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.root, "$name.preferences_pb") },
        )
        return DataStoreRestoreLedger(dataStore) to dataStore
    }

    @Test
    fun `records and reads back a published uri`() = runTest {
        val (ledger, _) = newLedger("record")
        val identity = RestoreIdentity(chatId = -100123L, messageId = 55L)
        assertNull(ledger.publishedUri(identity))

        ledger.record(identity, "content://media/external/images/media/42")
        assertEquals("content://media/external/images/media/42", ledger.publishedUri(identity))
    }

    @Test
    fun `distinct identities do not collide`() = runTest {
        val (ledger, _) = newLedger("distinct")
        val a = RestoreIdentity(100L, 7L)
        val b = RestoreIdentity(100L, 8L)

        ledger.record(a, "content://media/1")
        ledger.record(b, "content://media/2")

        assertEquals("content://media/1", ledger.publishedUri(a))
        assertEquals("content://media/2", ledger.publishedUri(b))
    }

    @Test
    fun `forget removes only the given identity`() = runTest {
        val (ledger, _) = newLedger("forget")
        val a = RestoreIdentity(1L, 1L)
        val b = RestoreIdentity(1L, 2L)
        ledger.record(a, "content://media/1")
        ledger.record(b, "content://media/2")

        ledger.forget(a)

        assertNull(ledger.publishedUri(a))
        assertEquals("content://media/2", ledger.publishedUri(b))
    }

    @Test
    fun `clear drops every mapping across accounts`() = runTest {
        val (ledger, _) = newLedger("clear")
        val a = RestoreIdentity(1L, 1L)
        val b = RestoreIdentity(2L, 2L)
        ledger.record(a, "content://media/1")
        ledger.record(b, "content://media/2")

        ledger.clear()

        assertNull(ledger.publishedUri(a))
        assertNull(ledger.publishedUri(b))
    }

    @Test
    fun `a corrupt value degrades to no record instead of throwing`() = runTest {
        val (ledger, dataStore) = newLedger("corrupt")
        val identity = RestoreIdentity(5L, 5L)
        dataStore.edit { it[stringPreferencesKey("restore_ledger")] = "garbage-without-a-tab" }

        assertNull(ledger.publishedUri(identity))

        // A later record still succeeds and overwrites the garbage.
        ledger.record(identity, "content://media/ok")
        assertEquals("content://media/ok", ledger.publishedUri(identity))
    }
}
