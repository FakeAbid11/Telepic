package com.telepic.data.face

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.telepic.data.face.db.FaceDao
import com.telepic.data.face.db.FaceEntity
import com.telepic.data.face.db.PersonDao
import com.telepic.data.face.db.PersonEntity
import com.telepic.data.media.LocalMediaLookup
import com.telepic.diagnostics.TelepicLog
import com.telepic.domain.face.Person
import com.telepic.domain.face.PersonFace
import com.telepic.domain.face.PersonNotFoundException
import com.telepic.domain.face.ScanProgress
import com.telepic.domain.face.toDomain
import com.telepic.domain.face.toDomainFace
import com.telepic.domain.face.toFaceCrop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The People tab's data layer: clustering, person curation, and the read models the UI renders.
 *
 * The split of responsibility is deliberate. [FaceIndexer] produces faces with no opinion about who
 * they belong to; this class turns that flat list into people, and owns every operation that changes
 * a grouping. Keeping them apart means the expensive, model-dependent part (indexing) can be re-run
 * without touching curation, and curation can be tested without a model.
 */
interface FaceRepository {

    /** Every person above [minimumGroupSize] faces, ordered for the grid. */
    fun observePeople(minimumGroupSize: Int = MINIMUM_GROUP_SIZE): Flow<List<Person>>

    /** One person, or null if they no longer exist. */
    fun observePerson(personId: Long): Flow<Person?>

    /** The faces in one person's group, best cover first. */
    fun observeFaces(personId: Long): Flow<List<PersonFace>>

    /** Total faces stored, including unassigned ones and groups below the minimum size. */
    fun observeFaceCount(): Flow<Int>

    /**
     * Live progress of a running scan, or null when no scan is in flight.
     *
     * Published by the indexing worker rather than derived from the database, because a scan that has
     * examined 800 photos and found no faces looks identical to one that has not started — and the
     * screen has to tell those apart to show anything truthful.
     */
    val scanProgress: StateFlow<ScanProgress?>

    /** Publishes [progress], or clears it when null. Called by `FaceIndexWorker`. */
    fun reportScanProgress(progress: ScanProgress?)

    /**
     * Rebuilds every grouping from the stored embeddings and persists the result.
     *
     * This is the only place groups are written, and it is a **full rebuild** rather than an
     * incremental update. That is a deliberate trade: a rebuild is `O(faces × groups × exemplars)`,
     * which for a 30,000-face library is a few seconds of background work, while keeping groupings
     * incrementally correct across merge, split, rename and re-scan is a source of bugs that are far
     * more expensive than a few seconds. User-assigned names are preserved across a rebuild by
     * matching on member overlap — see `persistClusters`.
     *
     * @param preset the similarity threshold to cluster with.
     */
    suspend fun rebuildClusters(preset: FaceSimilarityPreset = FaceSimilarityPreset.DEFAULT)

    /** Gives a person a name, or clears it when [name] is blank. */
    suspend fun rename(personId: Long, name: String?)

    /** Merges [sourcePersonId] into [targetPersonId]; the source group ceases to exist. */
    suspend fun merge(targetPersonId: Long, sourcePersonId: Long)

    /**
     * Moves [faceIds] out of their current person and into a new one.
     *
     * This is the split operation. It always creates a *new* group rather than reusing the source, so
     * the user's action is unambiguous: the faces they selected become a person, and whatever remains
     * stays where it was.
     *
     * @return the id of the group the faces were moved into.
     */
    suspend fun split(faceIds: List<Long>): Long

    /** Hides a person from the grid without deleting their faces. */
    suspend fun setHidden(personId: Long, hidden: Boolean)

    /** Sets which face represents a person. */
    suspend fun setCover(personId: Long, faceId: Long?)

    /**
     * Removes one face from the index entirely.
     *
     * Used when a face is a false detection. Deleting the row rather than unassigning it means it
     * cannot be re-clustered into a group on the next rebuild, which is what the user asked for.
     */
    suspend fun removeFace(faceId: Long)

    /**
     * Deletes every face, person and scan record.
     *
     * The user-facing "remove face data" action. Models are removed separately by the caller (see
     * `FaceModelStore.deleteAll`) so this class stays free of file-system concerns.
     */
    suspend fun removeAll()

    companion object {
        /**
         * Groups smaller than this are not shown.
         *
         * Three is the point at which a group stops being plausible coincidence: two similar faces can
         * be twins, siblings or the same person on two bad days, but a cluster of three rarely is. The
         * cost of showing size-2 groups is a grid full of noise that buries the real people.
         */
        const val MINIMUM_GROUP_SIZE = 3
    }
}

/**
 * Runs a block atomically.
 *
 * An interface with a generic method rather than a function-typed property: Kotlin cannot express a
 * generic lambda as a `val`, and the operations that need it ([DefaultFaceRepository.split] among
 * them) return a value while the others do not.
 */
interface FaceTransaction {
    suspend fun <T> run(block: suspend () -> T): T

    companion object {
        /** No real transaction — for unit tests that construct the repository without a database. */
        val Passthrough: FaceTransaction = object : FaceTransaction {
            override suspend fun <T> run(block: suspend () -> T): T = block()
        }

        /** The real thing, backed by a Room database. */
        fun room(database: RoomDatabase): FaceTransaction = object : FaceTransaction {
            override suspend fun <T> run(block: suspend () -> T): T =
                database.withTransaction { block() }
        }
    }
}

class DefaultFaceRepository(
    private val faceDao: FaceDao,
    private val personDao: PersonDao,
    private val lookup: LocalMediaLookup,
    private val clusterer: FaceClusterer = FaceClusterer(),
    /**
     * Makes each multi-row write atomic.
     *
     * Load-bearing for observation, not just for durability. Room emits per transaction, so a rebuild
     * that cleared the person table and then inserted the new groups *outside* one would publish an
     * intermediate state where every person is gone — the People grid would blink empty and back
     * during every rescan. Wrapping the write makes observers see only the finished grouping.
     *
     * Defaults to a passthrough so the repository can be constructed in a unit test without a
     * database; `AppContainer` supplies the real one.
     */
    private val transaction: FaceTransaction = FaceTransaction.Passthrough,
    private val clock: () -> Long = System::currentTimeMillis,
) : FaceRepository {

    override fun observePeople(minimumGroupSize: Int): Flow<List<Person>> =
        combine(
            personDao.observeVisible(),
            faceCountsByPerson(),
            faceDao.observeCovers(),
        ) { persons, counts, covers ->
            Triple(persons, counts, covers)
        }.map { (persons, counts, covers) ->
            val coversByPerson = covers.associateBy { it.personId }
            persons
                .map { person ->
                    person.toDomain(
                        faceCount = counts[person.personId] ?: 0,
                        cover = coversByPerson[person.personId]?.toFaceCrop(),
                    )
                }
                .filter { it.faceCount >= minimumGroupSize }
                // The grid draws one cover per person, so this resolves one photo per card — once per
                // emission, in a single batched provider query.
                .withResolvedCovers()
        }

    override fun observePerson(personId: Long): Flow<Person?> =
        combine(
            personDao.observeById(personId),
            faceCountsByPerson(),
            faceDao.observeCovers(),
        ) { person, counts, covers ->
            Triple(person, counts, covers)
        }.map { (person, counts, covers) ->
            if (person == null) {
                null
            } else {
                listOf(
                    person.toDomain(
                        faceCount = counts[personId] ?: 0,
                        cover = covers.firstOrNull { it.personId == personId }?.toFaceCrop(),
                    ),
                ).withResolvedCovers().first()
            }
        }

    /**
     * Fills in each cover's `contentUri`, which the database cannot supply.
     *
     * The cover's photo URI comes from MediaStore, so it is resolved here — once per emission, for
     * every visible person in **one** batched lookup rather than one per card. A failed lookup
     * degrades to cards with no cover instead of an empty tab: the grouping is still correct and still
     * the thing the user came for.
     */
    private suspend fun List<Person>.withResolvedCovers(): List<Person> {
        val mediaIds = mapNotNull { it.cover?.localMediaId }.toSet()
        if (mediaIds.isEmpty()) return this

        val uris = try {
            lookup.byIdList(mediaIds).associate { it.id to it.contentUri.toString() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (io: IOException) {
            TelepicLog.warn("FaceRepository", "could not resolve cover photos", io)
            emptyMap()
        }

        // A person whose cover photo is gone keeps their `cover` with a null URI: the box is still
        // valid, and the card draws its empty background rather than an invented image.
        return map { person ->
            val cover = person.cover ?: return@map person
            person.copy(cover = cover.copy(contentUri = uris[cover.localMediaId]))
        }
    }

    override fun observeFaces(personId: Long): Flow<List<PersonFace>> =
        faceDao.observeFacesOf(personId).map { faces ->
            if (faces.isEmpty()) return@map emptyList()

            // One lookup for the whole group rather than one per face: a person with 400 photos would
            // otherwise issue 400 provider queries every time the detail screen scrolled.
            val ids = faces.map { it.localMediaId }.toSet()
            val uris = try {
                lookup.byIdList(ids).associate { it.id to it.contentUri.toString() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (io: IOException) {
                TelepicLog.warn("FaceRepository", "could not resolve photo uris for person $personId", io)
                emptyMap()
            }

            faces.mapNotNull { face ->
                val uri = uris[face.localMediaId] ?: return@mapNotNull null
                face.toDomainFace(uri)
            }
        }

    override fun observeFaceCount(): Flow<Int> = faceDao.observeCount()

    private val _scanProgress = MutableStateFlow<ScanProgress?>(null)

    override val scanProgress: StateFlow<ScanProgress?> = _scanProgress.asStateFlow()

    override fun reportScanProgress(progress: ScanProgress?) {
        _scanProgress.value = progress
    }

    /**
     * Per-person face counts, as one grouped query rather than a count per card.
     *
     * The grid renders up to a few hundred people; issuing a `COUNT(*)` per person would be a few
     * hundred queries per emission.
     */
    private fun faceCountsByPerson(): Flow<Map<Long, Int>> =
        faceDao.observeCountsByPerson().map { rows -> rows.associate { it.personId to it.count } }

    override suspend fun rebuildClusters(preset: FaceSimilarityPreset) = withContext(Dispatchers.IO) {
        val faces = faceDao.all()
        if (faces.isEmpty()) {
            personDao.clear()
            return@withContext
        }

        val groups = clusterer.cluster(faces, preset)

        // One merge pass now that every face is present: during a scan a face could only link to what
        // had already been seen, so pairs that needed a later face to connect them were split.
        val remap = clusterer.mergePass(groups, preset)
        val merged = LinkedHashMap<Int, MutableList<ProvisionalGroup>>()
        for (index in groups.indices) {
            merged.getOrPut(remap[index]) { ArrayList() } += groups[index]
        }

        val finalGroups = merged.values.map { constituents ->
            ProvisionalGroup(
                memberFaceIds = constituents.flatMap { it.memberFaceIds },
                exemplars = constituents.flatMap { it.exemplars },
            )
        }

        persistClusters(finalGroups, faces)
    }

    /**
     * Writes the new grouping, preserving the names the user assigned.
     *
     * Names are the one piece of user intent in this table, and a rebuild that dropped them would make
     * every rescan start from "Person 1, Person 2…" again — the single most annoying thing this
     * feature could do.
     *
     * Matching is by **majority membership**: for each new group, the existing person who shares the
     * most faces with it keeps their name. That survives the ordinary case (a rescan produces roughly
     * the same groups, with a few faces moved) without requiring stable ids. A group with no overlap
     * with any named person is a genuinely new group and starts unnamed, which is correct.
     *
     * Hidden state is deliberately **not** carried over. A hide is a decision about a specific group;
     * once the grouping is rebuilt from scratch the ids mean nothing, and inheriting `isHidden` by
     * majority vote would quietly hide a person the user never hid.
     */
    private suspend fun persistClusters(groups: List<ProvisionalGroup>, faces: List<FaceEntity>) {
        val previousPeople = personDao.all().associateBy { it.personId }
        val previousPersonOfFace = faces.associate { it.faceId to it.personId }
        val now = clock()

        val newPersonIds = ArrayList<Long>(groups.size)
        val assignments = ArrayList<Pair<Long, Long>>(faces.size)

        transaction.run {
            // The old rows go first. Inserting before clearing would leave the old people in the
            // table alongside the new ones until the clear removed *both* — a rebuild that produced
            // empty people, which is exactly what the old order did.
            personDao.clear()

            for (group in groups) {
                val previousOwners = group.memberFaceIds
                    .mapNotNull { previousPersonOfFace[it] }
                    .groupingBy { it }
                    .eachCount()

                val majorityOwner = previousOwners.maxByOrNull { it.value }?.key
                val previous = majorityOwner?.let { previousPeople[it] }

                val personId = personDao.insert(
                    PersonEntity(
                        // The id is assigned by the database; `0` tells Room to generate one.
                        personId = 0,
                        name = previous?.name,
                        coverFaceId = null,
                        isHidden = false,
                        createdAt = previous?.createdAt ?: now,
                    ),
                )
                newPersonIds += personId
                group.memberFaceIds.forEach { assignments += it to personId }
            }

            // Assign in one batched pass. A failure between the clear and here leaves faces
            // unassigned rather than pointing at ids that no longer exist, which is the recoverable
            // side to fail on.
            faceDao.assignAll(assignments)

            // Covers are chosen after assignment, from the now-correct membership.
            assignCovers(newPersonIds)
        }
    }

    private suspend fun assignCovers(personIds: List<Long>) {
        for (personId in personIds) {
            val faces = faceDao.facesOf(personId)
            val cover = FaceCoverSelector.select(faces) ?: continue
            personDao.setCover(personId, cover)
        }
    }

    override suspend fun rename(personId: Long, name: String?) = withContext(Dispatchers.IO) {
        requirePerson(personId)
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() }
        personDao.rename(personId, trimmed)
    }

    override suspend fun merge(targetPersonId: Long, sourcePersonId: Long) = withContext(Dispatchers.IO) {
        require(targetPersonId != sourcePersonId) { "cannot merge a person into themselves" }
        val target = requirePerson(targetPersonId)
        val source = requirePerson(sourcePersonId)

        transaction.run {
            // Reassign first: deleting the source while faces still point at it would, without a
            // foreign key, leave them dangling on an id that will never resolve.
            val faces = faceDao.facesOf(sourcePersonId)
            if (faces.isNotEmpty()) {
                faceDao.assignAll(faces.map { it.faceId to targetPersonId })
            }
            personDao.delete(sourcePersonId)

            // The target keeps its name unless it has none, in which case the source's is the only
            // information available. Silently preferring the target would lose the only name the user
            // typed.
            if (target.name == null && source.name != null) {
                personDao.rename(targetPersonId, source.name)
            }
            // Hidden if *either* was hidden: the user hid one of these groups, and a merge should
            // not resurrect it into the grid.
            if (target.isHidden || source.isHidden) {
                personDao.setHidden(targetPersonId, true)
            }

            // The cover may have been the source's face, and the group just grew — recompute it.
            personDao.setCover(targetPersonId, FaceCoverSelector.select(faceDao.facesOf(targetPersonId)))
        }
    }

    override suspend fun split(faceIds: List<Long>): Long = withContext(Dispatchers.IO) {
        require(faceIds.isNotEmpty()) { "cannot split an empty selection" }

        // Read the source groups *before* the reassignment below: afterwards every face points at the
        // new person and the set of groups that shrank is empty.
        val affected = faceIds
            .mapNotNull { faceId -> faceDao.byId(faceId)?.personId }
            .toSet()

        transaction.run {
            val newPersonId = personDao.insert(
                PersonEntity(name = null, coverFaceId = null, isHidden = false, createdAt = clock()),
            )
            faceDao.assignAll(faceIds.map { it to newPersonId })
            personDao.setCover(newPersonId, FaceCoverSelector.select(faceDao.facesOf(newPersonId)))

            // The group the faces came from has shrunk, so its cover may now be a face that left.
            for (personId in affected) {
                personDao.setCover(personId, FaceCoverSelector.select(faceDao.facesOf(personId)))
            }

            newPersonId
        }
    }

    override suspend fun setHidden(personId: Long, hidden: Boolean) = withContext(Dispatchers.IO) {
        requirePerson(personId)
        personDao.setHidden(personId, hidden)
    }

    override suspend fun setCover(personId: Long, faceId: Long?) = withContext(Dispatchers.IO) {
        requirePerson(personId)
        personDao.setCover(personId, faceId)
    }

    override suspend fun removeFace(faceId: Long) {
        withContext(Dispatchers.IO) {
            val face = faceDao.byId(faceId)
            if (face == null) {
                Unit
            } else {
                transaction.run {
                    faceDao.delete(faceId)

                    // If the removed face was the cover, the card would render a dangling id.
                    face.personId?.let { personId ->
                        val person = personDao.byId(personId)
                        if (person != null && person.coverFaceId == faceId) {
                            personDao.setCover(
                                personId,
                                FaceCoverSelector.select(faceDao.facesOf(personId)),
                            )
                        }
                        // A group that no longer has any faces is not a group.
                        if (faceDao.countFor(personId) == 0) personDao.delete(personId)
                    }
                }
            }
        }
    }

    override suspend fun removeAll() = withContext(Dispatchers.IO) {
        transaction.run {
            // Faces before people: the reverse order would briefly leave face rows pointing at ids
            // that are gone, and any observer reading in that window would show phantom groups.
            faceDao.clearFaces()
            faceDao.clearScans()
            personDao.clear()
        }
    }

    private suspend fun requirePerson(personId: Long): PersonEntity =
        personDao.byId(personId) ?: throw PersonNotFoundException(personId)
}
