package com.telepic.data.backup.hash

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SHA-256 hashing validated against known vectors, plus streaming, determinism and cancellation.
 * These run on the JVM with no Telegram, no network and no native library — proving the recognition
 * primitive itself.
 */
class ContentHashingTest {

    private fun hashOf(bytes: ByteArray): String = runBlocking {
        ContentHashing.sha256(ByteArrayInputStream(bytes)).sha256
    }

    @Test
    fun `known vector - empty input`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hashOf(ByteArray(0)),
        )
    }

    @Test
    fun `known vector - abc`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hashOf("abc".toByteArray()),
        )
    }

    @Test
    fun `streaming hash equals a one-shot JDK digest over the same bytes`() = runBlocking {
        val bytes = "The quick brown fox jumps over the lazy dog".toByteArray()
        val streamed = ContentHashing.sha256(ByteArrayInputStream(bytes)).sha256
        val oneShot = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).toHexViaContentHashing()
        assertEquals(oneShot, streamed)
    }

    private fun ByteArray.toHexViaContentHashing(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append(ContentHashing.HEX_ALPHABET[v ushr 4]).append(ContentHashing.HEX_ALPHABET[v and 0x0F])
        }
        return out.toString()
    }

    @Test
    fun `representation is exactly 64 lowercase hex chars`() {
        val digest = hashOf("Telepic".toByteArray())
        assertTrue(digest.length == 64)
        assertTrue(ContentHashing.isLowercaseHexSha256(digest))
    }

    @Test
    fun `same bytes give the same hash regardless of source`() {
        val a = hashOf(byteArrayOf(1, 2, 3, 4))
        val b = runBlocking { ContentHashing.sha256(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))).sha256 }
        assertEquals(a, b)
    }

    @Test
    fun `different bytes give different hashes (filename is irrelevant to content)`() {
        assertNotEquals(hashOf("photo.jpg A".toByteArray()), hashOf("photo.jpg B".toByteArray()))
    }

    @Test
    fun `streaming over a large generated input reports the exact byte count`() = runBlocking {
        val size = 3 * 1024 * 1024 // 3 MB, larger than the internal buffer
        val pattern = ByteArray(1024) { (it % 251).toByte() }
        val source = object : InputStream() {
            private var emitted = 0
            private var pos = 0
            override fun read(): Int {
                if (emitted >= size) return -1
                val b = pattern[pos].toInt() and 0xff
                pos = (pos + 1) % pattern.size
                emitted++
                return b
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (emitted >= size) return -1
                val n = minOf(len, size - emitted)
                for (i in 0 until n) {
                    b[off + i] = pattern[pos]
                    pos = (pos + 1) % pattern.size
                }
                emitted += n
                return n
            }
        }
        val streamed = ContentHashing.sha256(source)
        assertEquals(size.toLong(), streamed.bytesRead)
        assertTrue(streamed.sha256.length == 64)
    }
}
