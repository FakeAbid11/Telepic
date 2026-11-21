package com.telepic.data.face

import com.telepic.data.face.db.FaceEntity
import kotlin.math.sqrt

/**
 * How similar two faces must be to be called the same person.
 *
 * Exposed as named presets rather than a raw float because the number is not meaningful to a user and
 * is not stable across embedders. The values come from `tools/face_models/compare_quantisation.py`:
 * on a labelled same/different set the same-person cosine bottoms out around 0.72 and the
 * different-person cosine peaks around 0.10, so the gap is wide and a threshold anywhere inside it
 * separates cleanly. These presets sit in the conservative half of that gap on purpose — see
 * [DEFAULT].
 */
enum class FaceSimilarityPreset(
    /** Minimum cosine similarity for two faces to be considered one person. */
    val threshold: Float,
) {
    /**
     * Deliberately conservative: 0.60 is well below the measured same-person floor of ~0.72, so it
     * splits people who are genuinely the same rather than merging people who are not.
     *
     * That asymmetry is the whole design. A split is *recoverable* — the user merges two cards and
     * the fix is permanent. A wrong merge is not: the two people's faces are now one group, the
     * evidence that distinguished them is buried, and the user has to untangle it by hand. Since the
     * app cannot know which mistake a given library will provoke, it should prefer the one the user
     * can undo in one tap.
     */
    STRICT(0.60f),

    /** The middle setting: between the measured same-person floor and the different-person ceiling. */
    BALANCED(0.50f),

    /** Most permissive. For libraries of one person photographed in very varied conditions. */
    LOOSE(0.42f),
    ;

    companion object {
        /**
         * [STRICT]. Over-splitting is recoverable with a merge; over-merging is not.
         */
        val DEFAULT = STRICT

        /** The preset for a persisted name, falling back to [DEFAULT] rather than throwing. */
        fun fromName(name: String?): FaceSimilarityPreset =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * One provisional group produced by [FaceClusterer.cluster].
 *
 * [memberFaceIds] is the group's evidence; [exemplars] is the small set the clusterer actually
 * compares against, chosen to span the group's appearance rather than to average it (see
 * [FaceClusterer]).
 */
data class ProvisionalGroup(
    val memberFaceIds: List<Long>,
    val exemplars: List<FloatArray>,
)

/**
 * Groups face embeddings into people.
 *
 * **Why exemplars and not a centroid.** The obvious approach — average a group's embeddings into one
 * vector and compare new faces to it — fails in a specific, predictable way. A person's photos vary:
 * one is frontal and evenly lit, another is in profile, another is in dim light. Their mean is a
 * vector that resembles *none* of them, so a genuinely new photo of that person sits closer to some
 * other person's centroid than to their own. The group then stops growing exactly when it should not.
 *
 * Comparing against several representative members instead means a new face only has to resemble
 * *one* of the ways this person appears. [MAX_EXEMPLARS] bounds the cost, and the exemplars are chosen
 * to be mutually dissimilar — the most diverse members, not the first ones — so the group's spread is
 * covered rather than its densest cluster.
 *
 * **Order dependence.** This is an incremental, greedy clusterer: a face is assigned to the best
 * existing group that clears the threshold, or starts a new one. That makes the result depend on the
 * order faces arrive in, which is inherent to any online approach. It is acceptable here because the
 * correction mechanism (merge/split) exists and is cheap; a batch clustering pass that got the order
 * right would still need it for the cases the embeddings simply cannot separate.
 *
 * Not thread-safe; the indexer runs one instance at a time.
 */
class FaceClusterer(
    private val preset: FaceSimilarityPreset = FaceSimilarityPreset.DEFAULT,
) {

    /**
     * Clusters [faces] into groups.
     *
     * @param faces every face to place. Their [FaceEntity.personId] is ignored — this is a full
     *   rebuild, not an incremental assignment.
     * @param preset overrides this instance's threshold. Supplied per call because the user can
     *   change the strictness between scans, and the clusterer itself is otherwise stateless.
     * @return groups in no particular order. Faces whose embedding is a zero vector land alone, which
     *   is the honest outcome for a vector carrying no identity information.
     */
    fun cluster(faces: List<FaceEntity>, preset: FaceSimilarityPreset = this.preset): List<ProvisionalGroup> {
        val groups = ArrayList<MutableGroup>()

        for (face in faces) {
            val embedding = FaceEmbeddingCodec.decode(face.embedding)
            val best = bestGroup(embedding, groups, preset)
            if (best == null) {
                groups += MutableGroup(face.faceId, embedding)
            } else {
                best.add(face.faceId, embedding)
            }
        }

        return groups.map { it.toProvisional() }
    }

    /**
     * The single merge pass run after a scan completes.
     *
     * During a scan the groups are provisional by necessity: a face that arrives early starts a group,
     * and a second photo of that person arriving later may only link to the first through a face that
     * has not been seen yet. Running one pass at the end, with every face present, catches the pairs
     * the incremental pass had to split.
     *
     * Only the groups' exemplars are compared, so the pass is `O(groups² × exemplars²)` rather than
     * `O(faces²)` — with a few hundred groups that is a few hundred thousand dot products, not
     * billions.
     *
     * @return a remapping from each group index to the index of the group it merged into. Groups that
     *   did not merge map to themselves.
     */
    fun mergePass(groups: List<ProvisionalGroup>, preset: FaceSimilarityPreset = this.preset): IntArray {
        val parent = IntArray(groups.size) { it }

        fun find(index: Int): Int {
            var current = index
            while (parent[current] != current) {
                parent[current] = parent[parent[current]]
                current = parent[current]
            }
            return current
        }

        for (i in groups.indices) {
            for (j in i + 1 until groups.size) {
                val rootI = find(i)
                val rootJ = find(j)
                if (rootI == rootJ) continue
                if (anyExemplarMatches(groups[rootI].exemplars, groups[rootJ].exemplars, preset)) {
                    parent[rootJ] = rootI
                }
            }
        }

        return IntArray(groups.size) { find(it) }
    }

    private fun anyExemplarMatches(
        left: List<FloatArray>,
        right: List<FloatArray>,
        preset: FaceSimilarityPreset,
    ): Boolean {
        for (a in left) {
            for (b in right) {
                if (FaceEmbeddingCodec.similarity(a, b) >= preset.threshold) return true
            }
        }
        return false
    }

    /** The group whose *closest* exemplar is most similar, or null when none clears the threshold. */
    private fun bestGroup(
        embedding: FloatArray,
        groups: List<MutableGroup>,
        preset: FaceSimilarityPreset,
    ): MutableGroup? {
        var best: MutableGroup? = null
        var bestSimilarity = preset.threshold

        for (group in groups) {
            val similarity = group.bestSimilarity(embedding)
            // Strictly greater, so ties go to the earlier group — deterministic, which matters for a
            // result the user can compare between two scans.
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity
                best = group
            }
        }
        return best
    }

    private inner class MutableGroup(firstFaceId: Long, firstEmbedding: FloatArray) {
        private val faceIds = ArrayList<Long>(INITIAL_CAPACITY)
        private val exemplars = ArrayList<FloatArray>(MAX_EXEMPLARS)

        init {
            faceIds += firstFaceId
            exemplars += firstEmbedding
        }

        fun bestSimilarity(embedding: FloatArray): Float {
            var best = -1f
            for (exemplar in exemplars) {
                val similarity = FaceEmbeddingCodec.similarity(exemplar, embedding)
                if (similarity > best) best = similarity
            }
            return best
        }

        fun add(faceId: Long, embedding: FloatArray) {
            faceIds += faceId
            // Only a face that is *dissimilar* to everything already held becomes an exemplar: it is
            // the only kind that adds new information. A near-duplicate would occupy a slot and make
            // the group look narrower than it is.
            if (exemplars.size < MAX_EXEMPLARS) {
                if (bestSimilarity(embedding) < EXEMPLAR_DIVERSITY) exemplars += embedding
            } else {
                replaceIfMoreDistinct(embedding)
            }
        }

        /**
         * Keeps the exemplar set spanning the group rather than frozen at its first members.
         *
         * The incoming face is measured against every existing exemplar; if its *maximum* similarity
         * is lower than the most redundant exemplar's, it is a better representative of an
         * under-covered part of the group and replaces that one.
         */
        private fun replaceIfMoreDistinct(embedding: FloatArray) {
            var mostRedundantIndex = -1
            var mostRedundantSimilarity = -1f
            for (index in exemplars.indices) {
                var peak = -1f
                for (other in exemplars.indices) {
                    if (other == index) continue
                    val similarity = FaceEmbeddingCodec.similarity(exemplars[index], exemplars[other])
                    if (similarity > peak) peak = similarity
                }
                if (peak > mostRedundantSimilarity) {
                    mostRedundantSimilarity = peak
                    mostRedundantIndex = index
                }
            }

            if (mostRedundantIndex >= 0 && mostRedundantSimilarity > bestSimilarity(embedding)) {
                exemplars[mostRedundantIndex] = embedding
            }
        }

        fun toProvisional() = ProvisionalGroup(
            memberFaceIds = faceIds.toList(),
            exemplars = exemplars.toList(),
        )
    }

    companion object {
        /**
         * Enough representatives to cover the ways one person appears (frontal, profile, different
         * light, different age) without making every comparison eight times more expensive than it
         * needs to be. Measured against the alternative of one centroid, this is what stops a group
         * from going stale as it grows.
         */
        const val MAX_EXEMPLARS = 8

        /** A face more similar than this to an existing exemplar adds nothing to the group's span. */
        private const val EXEMPLAR_DIVERSITY = 0.92f

        private const val INITIAL_CAPACITY = 8
    }
}

/**
 * Chooses which face should represent a person on their card.
 *
 * Deliberately not "the first" or "the newest". A cover is the one thing the user sees for every
 * person, and a card showing a blurry profile shot makes the whole tab look broken. The heuristics
 * are: bigger faces are more detailed, higher confidence means the detector was sure it saw a face,
 * and a face near the centre of its photo is usually the subject rather than a bystander.
 *
 * All three are cheap, computed from data already stored, and none requires re-reading an image.
 */
object FaceCoverSelector {

    /** @return the id of the best cover face, or null if [faces] is empty. */
    fun select(faces: List<FaceEntity>): Long? {
        if (faces.isEmpty()) return null
        return faces.maxByOrNull { score(it) }?.faceId
    }

    /**
     * Higher is better.
     *
     * The terms are multiplicative rather than additive so a face that is tiny *or* low-confidence
     * cannot be rescued by doing well on the other axis — a 4%-of-the-image face is a poor cover no
     * matter how certain the detector was.
     */
    fun score(face: FaceEntity): Float {
        val area = ((face.right - face.left) * (face.bottom - face.top)).coerceAtLeast(0f)
        // Square-rooted so the ranking is on linear size rather than area: a face twice as wide is
        // better, but not four times better.
        val size = sqrt(area)
        return size * face.confidence
    }
}
