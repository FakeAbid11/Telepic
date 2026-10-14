package com.telepic.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.telepic.diagnostics.TelepicLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Remembers how far a bounded scan reached through one loader, so the next scan of that loader can
 * resume instead of restarting.
 *
 * Two callers, one shape. Backup discovery is capped per pass (25,000 items), so without a cursor that
 * cap is a *permanent ceiling*: every pass restarted at offset 0, so a larger library's older tail was
 * never examined no matter how many ticks ran. The map sweep has the same defect behind its page cap
 * (2,000 pages ≈ 240,000 items) — its own KDoc admits "every sweep restarts at offset 0, so a small cap
 * would bound coverage at maxPages × pageSize however often the screen is reopened".
 *
 * In both cases a scan that reaches the end rewinds the cursor to 0, which is what keeps newly added
 * media discoverable, while a capped or interrupted one resumes where it stopped. The key is
 * per-loader, so independent loaders never share progress.
 */
interface ScanCursorStore {
    /** The offset the next scan of [key] should start from. */
    suspend fun offset(key: String): Int

    /** Record where the next scan of [key] should start (0 = start again from the newest item). */
    suspend fun save(key: String, offset: Int)

    /**
     * Persists nothing and always reports 0 — the pre-cursor behaviour. Used by tests, and available
     * for a wiring that has no preference store. Deliberately **not** a default for any constructor
     * argument: a silent no-op in production would recreate exactly the ceiling this exists to remove.
     */
    object NoOp : ScanCursorStore {
        override suspend fun offset(key: String): Int = 0

        override suspend fun save(key: String, offset: Int) = Unit
    }
}

/** [ScanCursorStore] over the app's shared preferences DataStore. */
class PreferencesScanCursorStore(
    private val dataStore: DataStore<Preferences>,
) : ScanCursorStore {

    override suspend fun offset(key: String): Int =
        try {
            dataStore.data.first()[preferencesKey(key)] ?: 0
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            // A cursor is an optimisation: losing it costs one re-scan, never correctness.
            TelepicLog.warn("ScanCursor", "could not read the scan cursor for '$key'", throwable)
            0
        }

    override suspend fun save(key: String, offset: Int) {
        dataStore.edit { preferences -> preferences[preferencesKey(key)] = offset }
    }

    private fun preferencesKey(key: String) = intPreferencesKey("scan_offset_$key")
}
