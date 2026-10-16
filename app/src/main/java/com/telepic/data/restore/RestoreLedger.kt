package com.telepic.data.restore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.telepic.diagnostics.TelepicLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * The stable remote identity of a cloud item: Telegram `chatId` + `messageId`.
 *
 * This is the only restoration identity Telepic considers trustworthy. Display name, byte size and
 * MIME type are NOT identity — two unrelated files can share all three, so matching on them reported
 * a *different* existing file as the restored target. The remote identity comes from Telegram itself
 * and is stable across restores.
 */
data class RestoreIdentity(val chatId: Long, val messageId: Long) {
    /** Compact, opaque key persisted with the record. Neither id contains `:` or a newline. */
    fun token(): String = "$chatId:$messageId"
}

/**
 * App-owned record of which remote items have already been published into public media storage and
 * to which URI, so restoring the same item twice does not create a second copy.
 *
 * This is deliberately *not* a heuristic over provider columns. A persisted `identity -> uri` map is
 * reliable in a way that "same name + size + MIME" can never be, and it is independent of Telegram's
 * re-encoding behaviour. The recorded URI is re-verified against the provider before it is trusted,
 * so a copy the user deleted from the gallery does not make a later restore claim a save it never did.
 */
interface RestoreLedger {
    /** The published URI previously recorded for [identity], or null when there is no record. */
    suspend fun publishedUri(identity: RestoreIdentity): String?

    /** Records that [identity] was published at [uri]. */
    suspend fun record(identity: RestoreIdentity, uri: String)

    /** Drops the mapping for [identity] — used when the recorded row no longer exists. */
    suspend fun forget(identity: RestoreIdentity)
}

/**
 * [RestoreLedger] over Jetpack DataStore (Preferences), persisting a single `token -> uri` map as
 * newline-separated `token\turi` lines. Reads are failure-tolerant: a corrupt or unreadable value
 * degrades to "no record", which re-publishes rather than falsely reporting a save.
 */
class DataStoreRestoreLedger(
    private val dataStore: DataStore<Preferences>,
) : RestoreLedger {

    override suspend fun publishedUri(identity: RestoreIdentity): String? {
        val raw = readRaw() ?: return null
        return decode(raw)[identity.token()]
    }

    override suspend fun record(identity: RestoreIdentity, uri: String) {
        dataStore.edit { preferences ->
            val map = decode(preferences[KEY_LEDGER].orEmpty()).toMutableMap()
            map[identity.token()] = uri
            preferences[KEY_LEDGER] = encode(map)
        }
    }

    override suspend fun forget(identity: RestoreIdentity) {
        dataStore.edit { preferences ->
            val map = decode(preferences[KEY_LEDGER].orEmpty()).toMutableMap()
            if (map.remove(identity.token()) != null) {
                preferences[KEY_LEDGER] = encode(map)
            }
        }
    }

    private suspend fun readRaw(): String? = try {
        dataStore.data.first()[KEY_LEDGER]
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        // A read failure must not be mistaken for "already restored": returning null makes the caller
        // publish (a duplicate at worst) rather than skip a real save. Recorded, not swallowed.
        TelepicLog.warn("RestoreLedger", "the restore ledger could not be read; treating as empty", throwable)
        null
    }

    private fun encode(map: Map<String, String>): String =
        map.entries.joinToString(separator = "\n") { (token, uri) -> "$token\t$uri" }

    private fun decode(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        return raw.lineSequence()
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1)
            }
            .toMap()
    }

    private companion object {
        val KEY_LEDGER = stringPreferencesKey("restore_ledger")
    }
}
