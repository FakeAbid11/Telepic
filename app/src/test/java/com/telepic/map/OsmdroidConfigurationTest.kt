package com.telepic.map

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tile User-Agent is load-bearing: OpenStreetMap rejects a request whose agent is blank or a
 * library default, which presents to the user as a permanently blank map. These pin the two ways
 * that could silently regress.
 */
class OsmdroidConfigurationTest {

    @Test
    fun `user agent identifies the app with its version and id`() {
        val ua = OsmdroidConfiguration.userAgent("0.1.0", "com.telepic")
        assertTrue(ua.contains("Telepic"))
        assertTrue(ua.contains("0.1.0"))
        assertTrue(ua.contains("com.telepic"))
    }

    @Test
    fun `user agent is never blank and never the osmdroid default`() {
        val ua = OsmdroidConfiguration.userAgent("", "")
        assertFalse(ua.isBlank())
        assertFalse(ua == "osmdroid")
        assertEquals(ua.trim(), ua)
    }

    @Test
    fun `tile cache root is the osmdroid tiles directory under filesDir`() {
        val filesDir = File("files")
        assertEquals(
            File(File(filesDir, "osmdroid"), "tiles"),
            OsmdroidConfiguration.tileCacheRoot(filesDir),
        )
    }

    /**
     * The CARTO key migration renamed the tile sources. The old directories are never read again, but
     * deleting them reclaims the space; the current V2 caches (keyed and keyless) must survive.
     */
    @Test
    fun `retired tile caches are deleted while V2 caches survive`() {
        val root = Files.createTempDirectory("telepic-tiles").toFile()
        try {
            File(root, "TelepicLight/5/10/20.png").apply { parentFile.mkdirs(); writeText("old") }
            File(root, "TelepicDark/5/10/20.png").apply { parentFile.mkdirs(); writeText("old") }
            val keyed = File(root, "TelepicLightV2/5/10/20.png").apply {
                parentFile.mkdirs()
                writeText("new")
            }
            val keyless = File(root, "TelepicDarkV2NoKey/5/10").apply { mkdirs() }

            OsmdroidConfiguration.deleteRetiredTileCaches(root)

            assertFalse(File(root, "TelepicLight").exists())
            assertFalse(File(root, "TelepicDark").exists())
            assertTrue(keyed.isFile)
            assertTrue(keyless.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }
}
