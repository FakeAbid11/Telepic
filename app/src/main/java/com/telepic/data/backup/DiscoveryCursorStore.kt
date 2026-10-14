package com.telepic.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.telepic.diagnostics.TelepicLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Remembers how far a discovery pass reached through one loader.
 *
 * Discovery is capped per pass ([DefaultBackupCoordinator.discoveryMaxItems]) and measured from the
 * newest item. Without a persisted cursor that cap was a *permanent ceiling*: every pass restarted at
 * offset 0, so a library larger than the cap only ever had its newest slice examined and the older
 * tail never converged no matter how many 12h ticks ran.
 *
 * With a cursor, a capped or interrupted pass resumes where it stopped, and a pass that *reached the
 * end* resets to 0 — so newly added media is still discovered on the next tick.
 */
interface DiscoveryCursorStore {
    /** The offset the next pass for [key] should start from. */
    suspend fun offset(key: String): Int

    /** Record where the next pass for [key] should start (0 = start again from the newest item). */
    suspend fun save(key: String, offset: Int)

    /**
     * Persists nothing and always reports 0 — the pre-cursor behaviour. Used by tests, and available
     * for a wiring that has no preference store. Deliberately **not** a default for the coordinator's
     * constructor argument: a silent no-op in production would recreate the ceiling this exists to
     * remove, so the dependency is required and mis-wiring fails to compile.
     */
    object NoOp : DiscoveryCursorStore {
        override suspend fun offset(key: String): Int = 0

        override suspend fun save(key: String, offset: Int) = Unit
    }
}

/** [DiscoveryCursorStore] over the app's shared preferences DataStore. */
class PreferencesDiscoveryCursorStore(
    private val dataStore: DataStore<Preferences>,
) : DiscoveryCursorStore {

    override suspend fun offset(key: String): Int =
        try {
            dataStore.data.first()[preferencesKey(key)] ?: 0
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            // A cursor is an optimisation: losing it costs one re-scan, never correctness.
            TelepicLog.warn("BackupDiscovery", "could not read the discovery cursor for '$key'", throwable)
            0
        }

    override suspend fun save(key: String, offset: Int) {
        dataStore.edit { preferences -> preferences[preferencesKey(key)] = offset }
    }

    private fun preferencesKey(key: String) = intPreferencesKey("discovery_offset_$key")
}
