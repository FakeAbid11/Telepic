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

class KeystoreTdLibKeyProvider(context: Context) : SecureTdLibKeyProvider {

    private val appContext = context.applicationContext
    private val keyFile = File(appContext.filesDir, WRAPPED_FILE)

    override suspend fun getOrCreate(): ByteArray = withContext(Dispatchers.IO) {
        if (keyFile.exists()) {
            runCatching { unwrap(keyFile.readBytes()) }.getOrElse { throwable ->
                // Wrapped blob unreadable — fall through and (re)generate. If the database already
                // exists with a different key TDLib will report it; surfaced as an init error. Logged
                // because this is the exact fork that loses an existing session: without the line, a
                // corrupt blob and a first-ever run are indistinguishable from the outside.
                TelepicLog.warn("TelegramKey", "the stored TDLib database key was unreadable; regenerating", throwable)
                generateAndStore()
            }
        } else {
            generateAndStore()
        }
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
        // IndexOutOfBoundsException, which the caller's runCatching would treat as "unreadable" and
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
