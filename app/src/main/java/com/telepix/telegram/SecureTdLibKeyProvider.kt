package com.telepix.telegram

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
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
            runCatching { unwrap(keyFile.readBytes()) }.getOrElse {
                // Wrapped blob unreadable — fall through and (re)generate. If the database already
                // exists with a different key TDLib will report it; surfaced as an init error.
                generateAndStore()
            }
        } else {
            generateAndStore()
        }
    }

    private fun generateAndStore(): ByteArray {
        val plaintext = ByteArray(KEY_LENGTH_BYTES).also { SECURE_RANDOM.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, keystoreKey())
        }
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        keyFile.writeBytes(iv + ciphertext)
        return plaintext
    }

    private fun unwrap(blob: ByteArray): ByteArray {
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
        const val ALIAS = "telepix_tdlib_db_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val WRAPPED_FILE = ".tdlib_db_key.v1"
        const val KEY_LENGTH_BYTES = 32
        const val IV_LENGTH_BYTES = 12
        const val TAG_LENGTH_BITS = 128
        val SECURE_RANDOM = java.security.SecureRandom()
    }
}
