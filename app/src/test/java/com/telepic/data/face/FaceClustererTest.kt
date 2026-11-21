package com.telepic.data.face

import com.telepic.data.face.db.FaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clustering rules, tested on hand-built embeddings rather than model output.
 *
 * The point of using synthetic vectors is that the *similarity* is known exactly: two identical
 * vectors are 1.0, two orthogonal ones are 0.0, and nothing in between is guessed. That makes the
 * threshold tests assertions about arithmetic instead of about how a particular photograph happened to
 * come out, which is the only way these can be stable.
 */
class FaceClustererTest {

    /** A unit vector with a single non-zero component, so any two distinct probes are orthogonal. */
    private fun basis(index: Int, scale: Float = 1f): FloatArray =
        FloatArray(FaceEmbeddingCodec.DIMENSIONS).also { it[index] = scale }

    /** A unit vector `cos(θ)·e0 + sin(θ)·e1`, so its cosine against [basis]`(0)` is `cos(θ)`. */
    private fun angled(cosine: Float): FloatArray = FloatArray(FaceEmbeddingCodec.DIMENSIONS).also {
        it[0] = cosine
        it[1] = kotlin.math.sqrt((1f - cosine * cosine).coerceAtLeast(0f))
    }

    private fun face(id: Long, embedding: FloatArray) = FaceEntity(
        faceId = id,
        localMediaId = id * 10,
        left = 0.1f,
        top = 0.1f,
        right = 0.5f,
        bottom = 0.5f,
        embedding = FaceEmbeddingCodec.encode(embedding),
        personId = null,
        confidence = 0.9f,
        detectedAt = 1L,
    )

    @Test
    fun `identical embeddings form one group`() {
        val groups = FaceClusterer().cluster(
            listOf(face(1, basis(0)), face(2, basis(0)), face(3, basis(0))),
        )

        assertEquals(1, groups.size)
        assertEquals(listOf(1L, 2L, 3L), groups.single().memberFaceIds)
    }

    @Test
    fun `orthogonal embeddings never merge`() {
        val groups = FaceClusterer().cluster(
            listOf(face(1, basis(0)), face(2, basis(1)), face(3, basis(2))),
        )

        assertEquals(3, groups.size)
        assertEquals(listOf(1L), groups[0].memberFaceIds)
        assertEquals(listOf(2L), groups[1].memberFaceIds)
        assertEquals(listOf(3L), groups[2].memberFaceIds)
    }

    @Test
    fun `a face just above the threshold joins, one just below starts a group`() {
        // STRICT is 0.60. The probes straddle it by a margin wide enough that float accumulation
        // cannot move either across the line.
        val strict = FaceSimilarityPreset.STRICT

        val above = FaceClusterer().cluster(
            listOf(face(1, basis(0)), face(2, angled(strict.threshold + 0.05f))),
        )
        assertEquals(1, above.size)

        val below = FaceClusterer().cluster(
            listOf(face(1, basis(0)), face(2, angled(strict.threshold - 0.05f))),
        )
        assertEquals(2, below.size)
    }

    @Test
    fun `a looser preset merges what a stricter one splits`() {
        // 0.50 clears BALANCED and misses STRICT, so the same input produces different groupings —
        // which is exactly what the user is choosing between in the grouping dialog.
        val faces = listOf(face(1, basis(0)), face(2, angled(0.55f)))

        assertEquals(2, FaceClusterer().cluster(faces, FaceSimilarityPreset.STRICT).size)
        assertEquals(1, FaceClusterer().cluster(faces, FaceSimilarityPreset.BALANCED).size)
        assertEquals(1, FaceClusterer().cluster(faces, FaceSimilarityPreset.LOOSE).size)
    }

    @Test
    fun `an unplaceable face lands alone rather than poisoning a group`() {
        // A zero vector has no direction: its cosine against anything is 0, so it must not be dragged
        // into a group by the "closest exemplar" rule.
        val groups = FaceClusterer().cluster(
            listOf(face(1, basis(0)), face(2, FloatArray(FaceEmbeddingCodec.DIMENSIONS)), face(3, basis(0))),
        )

        assertEquals(2, groups.size)
        assertEquals(listOf(1L, 3L), groups.first { 1L in it.memberFaceIds }.memberFaceIds)
        assertEquals(listOf(2L), groups.first { 2L in it.memberFaceIds }.memberFaceIds)
    }

    @Test
    fun `the merge pass joins groups an incremental scan had to split`() {
        // Two groups whose members are only connected through a face each of them can reach: exactly
        // the shape a scan produces when the bridging photo is seen last.
        val left = ProvisionalGroup(memberFaceIds = listOf(1L), exemplars = listOf(basis(0)))
        val right = ProvisionalGroup(memberFaceIds = listOf(2L), exemplars = listOf(basis(0)))

        val remap = FaceClusterer().mergePass(listOf(left, right), FaceSimilarityPreset.STRICT)

        assertEquals(remap[0], remap[1])
    }

    @Test
    fun `the merge pass leaves unrelated groups alone`() {
        val groups = listOf(
            ProvisionalGroup(listOf(1L), listOf(basis(0))),
            ProvisionalGroup(listOf(2L), listOf(basis(1))),
            ProvisionalGroup(listOf(3L), listOf(basis(2))),
        )

        val remap = FaceClusterer().mergePass(groups, FaceSimilarityPreset.STRICT)

        assertEquals(listOf(0, 1, 2), remap.toList())
    }

    @Test
    fun `exemplars stay bounded no matter how large a group grows`() {
        // 40 distinct directions all within threshold of the group's first exemplar would grow an
        // unbounded exemplar list; the cap is what keeps every later comparison eight dots.
        val faces = ArrayList<FaceEntity>()
        for (index in 0 until 40) {
            val embedding = basis(0).copyOf()
            embedding[1] = 0.5f
            embedding[2] = index * 0.001f
            // Renormalise, or the vectors stop being unit length and the similarity stops being a
            // cosine.
            FaceEmbeddingCodec.normaliseInPlace(embedding)
            faces += face(index + 1L, embedding)
        }

        val group = FaceClusterer().cluster(faces).maxByOrNull { it.memberFaceIds.size }

        assertNotNull(group)
        assertTrue(group!!.exemplars.size <= FaceClusterer.MAX_EXEMPLARS)
        assertEquals(40, group.memberFaceIds.size)
    }

    @Test
    fun `the same input produces the same grouping twice`() {
        // Determinism matters because the user compares two scans; a tie that broke differently would
        // look like the app changing its mind about the same photos.
        val faces = listOf(face(1, basis(0)), face(2, angled(0.7f)), face(3, basis(1)), face(4, basis(0)))

        val first = FaceClusterer().cluster(faces, FaceSimilarityPreset.BALANCED).map { it.memberFaceIds }
        val second = FaceClusterer().cluster(faces, FaceSimilarityPreset.BALANCED).map { it.memberFaceIds }

        assertEquals(first, second)
    }

    @Test
    fun `preset names round-trip and unknown names fall back rather than throw`() {
        FaceSimilarityPreset.entries.forEach { preset ->
            assertEquals(preset, FaceSimilarityPreset.fromName(preset.name))
            // Case-insensitive: the value comes from DataStore, where a hand-edited or migrated value
            // may not match the enum's casing.
            assertEquals(preset, FaceSimilarityPreset.fromName(preset.name.lowercase()))
        }
        assertEquals(FaceSimilarityPreset.DEFAULT, FaceSimilarityPreset.fromName("nonsense"))
        assertEquals(FaceSimilarityPreset.DEFAULT, FaceSimilarityPreset.fromName(null))
    }

    @Test
    fun `the default preset is the conservative one`() {
        // Pinned: the whole design rests on preferring an over-split, because a merge is one tap and
        // an over-merge is not. Changing this default should require changing this test.
        assertEquals(FaceSimilarityPreset.STRICT, FaceSimilarityPreset.DEFAULT)
        assertTrue(FaceSimilarityPreset.STRICT.threshold > FaceSimilarityPreset.BALANCED.threshold)
        assertTrue(FaceSimilarityPreset.BALANCED.threshold > FaceSimilarityPreset.LOOSE.threshold)
    }
}
