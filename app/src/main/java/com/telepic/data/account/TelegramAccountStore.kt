package com.telepic.data.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.telepic.diagnostics.TelepicLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * The last Telegram account whose session owned the persisted cloud state.
 *
 * Kept separately from the destination row so a sign-out can preserve same-account state while the
 * next different account is detected reliably. A read failure is treated as "unknown" (null), which
 * makes the next authenticated account adopt existing state rather than deleting it.
 */
interface TelegramAccountStore {
    val accountId: Flow<Long?>
    suspend fun setAccountId(accountId: Long)
}

class DataStoreTelegramAccountStore(
    private val dataStore: DataStore<Preferences>,
) : TelegramAccountStore {

    override val accountId: Flow<Long?> = dataStore.data
        .map { preferences -> preferences[KEY_ACCOUNT_ID] }
        .catch { throwable ->
            TelepicLog.warn("TelegramAccount", "the account identity could not be read", throwable)
            emit(null)
        }

    override suspend fun setAccountId(accountId: Long) {
        dataStore.edit { preferences -> preferences[KEY_ACCOUNT_ID] = accountId }
    }

    private companion object {
        val KEY_ACCOUNT_ID = longPreferencesKey("telegram_account_id")
    }
}
