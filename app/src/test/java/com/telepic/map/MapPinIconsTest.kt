package com.telepic.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The marker image ids: the one link between a feature's `icon` property and the bitmap that was
 * drawn for it.
 *
 * Worth a test because the link is a string, and a mismatch has no failure mode except a blank map —
 * MapLibre simply finds no image for the id and draws nothing, with no error anywhere.
 */
class MapPinIconsTest {

    @Test
    fun `the single pin has one fixed id`() {
        assertEquals("telepic-pin", MapPinIcons.SINGLE)
    }

    @Test
    fun `a cluster id carries its count`() {
        assertEquals("telepic-cluster-12", MapPinIcons.cluster(12))
        assertEquals("telepic-cluster-1", MapPinIcons.cluster(1))
        assertEquals("telepic-cluster-987", MapPinIcons.cluster(987))
    }

    @Test
    fun `different counts never share an image`() {
        // Each bubble is drawn with its own number on it, so two sizes cannot share one bitmap.
        val ids = listOf(2, 3, 10, 11, 100).map { MapPinIcons.cluster(it) }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `a cluster id can never collide with the single pin`() {
        // A prefix that could be produced by `cluster(…)` would let a bubble overwrite the pin.
        val clusterIds = (1..200).map { MapPinIcons.cluster(it) }
        assertTrue(MapPinIcons.SINGLE !in clusterIds)
        clusterIds.forEach { assertNotEquals(MapPinIcons.SINGLE, it) }
    }
}
