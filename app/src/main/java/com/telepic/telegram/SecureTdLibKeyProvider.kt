package com.telepic.telegram

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.telepic.diagnostics.TelepicLog
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Supplies the persistent TDLib database encryption key.
 *
 * The key is generated once, wrapped with a non-exportable Android Keystore AES key, and stored
 * as ciphertext in app-private storage. It is reused across launches (so the TDLib database can
 * reopen) and is never logged or stored in plaintext. Abstracted so tests inject a fake.
 */
interface SecureTdLibKeyProvider {
    suspend fun getOrCreate(): ByteArray
}

class KeystoreTdLibKeyProvider(
    context: Context,
    /**
     * Where TDLib keeps its local session cache.
     *
     * Needed to answer one question the provider cannot answer alone: is the key *missing*, or was
     * it merely unreadable? A key that is lost while a session cache exists is fatal (TDLib would
     * refuse to open a database encrypted with a different key), and the cache has to be discarded
     * so the user can sign in again. A key that is lost while no cache exists costs nothing.
     */
    private val databaseDir: File = TelegramPaths.databaseDir(context),
) : SecureTdLibKeyProvider {

    private val appContext = context.applicationContext

    /**
     * The wrapped key blob.
     *
     * `noBackupFilesDir`, not `filesDir`: the Android backup and device-to-device transfer include
     * `filesDir`, so a blob written there was uploaded to the user's cloud backup — where it is
     * useless (the Keystore key that unwraps it is never backed up) and merely a liability. The
     * TDLib database it protects already lives in `noBackupFilesDir`; the key belongs beside it.
     */
    private val keyFile = File(appContext.noBackupFilesDir, WRAPPED_FILE)

    override suspend fun getOrCreate(): ByteArray = withContext(Dispatchers.IO) {
        if (keyFile.exists()) {
            try {
                return@withContext unwrap(keyFile.readBytes())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                // The blob could not be unwrapped. The most common cause is the Android Keystore
                // having invalidated the wrapping key (a screen-lock change, a secure-hardware
                // reset); the second is a corrupted blob.
                //
                // The old behaviour regenerated the key in every case, which produced a *different*
                // key for a database still encrypted with the old one — TDLib then refused to open
                // it and the user's Telegram session was destroyed, with only a generic
                // "could not be securely initialised" to show for it.
                //
                // What is actually lost is a *cache* of server-side data, not the account, so the
                // local cache is discarded and a fresh key generated. The user signs in again; the
                // alternative is a permanently broken app.
                TelepicLog.error(
                    "TelegramKey",
                    "the stored TDLib database key could not be read; the local session cache is being reset",
                    throwable,
                )
                discardSessionCache()
                return@withContext generateAndStore()
            }
        }

        // No blob at all. If a session cache exists anyway, the key is simply gone (the app data
        // was partially wiped, or the blob was deleted) and the same recovery applies — a fresh key
        // over a database encrypted with the old one would never open.
        if (hasSessionCache()) {
            TelepicLog.error(
                "TelegramKey",
                "the TDLib database key is missing but a local session cache exists; the cache is being reset",
                null,
            )
            discardSessionCache()
        }
        generateAndStore()
    }

    /**
     * Writes the wrapped key **atomically**: to a sibling temp file, then renamed over the target.
     *
     * The previous single `writeBytes` was not atomic, so a crash or a kill mid-write left a
     * truncated blob. The next launch then failed to unwrap it, fell through to [generateAndStore],
     * and produced a *different* key for a TDLib database already encrypted with the old one — which
     * surfaces as an init error and loses the user's Telegram session. A rename is atomic on the same
     * filesystem, so the target is either the old blob or the new one, never a partial write.
     *
     * `suspend` and pinned to [Dispatchers.IO] because it performs Keystore crypto (`Cipher`, key
     * generation) that blocks for tens to hundreds of milliseconds: a caller that reached it from the
     * main thread would ANR. Making the dispatcher part of the signature means a future caller cannot
     * get that wrong.
     */
    private suspend fun generateAndStore(): ByteArray = withContext(Dispatchers.IO) {
        val plaintext = ByteArray(KEY_LENGTH_BYTES).also { SECURE_RANDOM.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, keystoreKey())
        }
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        keyFile.parentFile?.mkdirs()
        val tmp = File(keyFile.parentFile, "${keyFile.name}.tmp")
        tmp.writeBytes(iv + ciphertext)
        if (!tmp.renameTo(keyFile)) {
            // Some filesystems refuse to rename over an existing file; clear the way and retry once.
            keyFile.delete()
            if (!tmp.renameTo(keyFile)) {
                tmp.delete()
                throw IOException("Could not persist the TDLib database key")
            }
        }
        plaintext
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        // Checked rather than sliced blindly: copyOfRange on a short blob throws
        // IndexOutOfBoundsException, which the caller's catch would treat as "unreadable" and
        // answer by regenerating the key. Failing here with the real reason keeps that decision
        // deliberate instead of accidental.
        if (blob.size <= IV_LENGTH_BYTES) throw IOException("Wrapped TDLib key blob is truncated")
        val iv = blob.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = blob.copyOfRange(IV_LENGTH_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        }
        return cipher.doFinal(ciphertext)
    }

    /**
     * Whether TDLib's local session cache holds anything.
     *
     * "Holds anything" rather than "the directory exists": the session manager creates the directory
     * before it asks for the key, so an empty directory proves nothing about whether a database was
     * ever written.
     */
    private fun hasSessionCache(): Boolean {
        if (!databaseDir.isDirectory) return false
        val entries = databaseDir.listFiles() ?: return false
        return entries.any { it.isFile || (it.isDirectory && it.list()?.isNotEmpty() == true) }
    }

    /**
     * Delete the local TDLib cache so a fresh key can open a fresh database.
     *
     * This is a *cache* of server-side data (the message index, downloaded files), not the account:
     * the account lives on Telegram's servers and the session is re-established by signing in
     * again. Deleting it is the only way out of "the database is encrypted with a key that no
     * longer exists" — the alternative is an app that can never sign in again.
     */
    private fun discardSessionCache() {
        if (!databaseDir.exists()) return
        val deleted = runCatching { databaseDir.deleteRecursively() }.getOrDefault(false)
        if (!deleted) {
            TelepicLog.warn("TelegramKey", "the local Telegram session cache could not be removed")
        }
    }

    private fun keystoreKey(): java.security.Key {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.getKey(ALIAS, null)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "telepic_tdlib_db_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val WRAPPED_FILE = ".tdlib_db_key.v1"
        const val KEY_LENGTH_BYTES = 32
        const val IV_LENGTH_BYTES = 12
        const val TAG_LENGTH_BITS = 128
        val SECURE_RANDOM = java.security.SecureRandom()
    }
}
