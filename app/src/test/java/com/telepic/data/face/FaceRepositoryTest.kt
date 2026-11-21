package com.telepic.data.face

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.telepic.data.cloud.db.TelepicDatabase
import com.telepic.data.face.db.FaceEntity
import com.telepic.data.face.db.PersonEntity
import com.telepic.data.media.LocalMediaLookup
import com.telepic.data.media.NeighborDirection
import com.telepic.domain.face.PersonNotFoundException
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.MediaType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The People data layer against a real (in-memory) Room database.
 *
 * These tests exist for the operations where a plausible bug is silent rather than loud: a rebuild
 * that renumbers people and loses their names, a merge that strands faces on a deleted id, a split
 * that leaves the source group's cover pointing at a face that walked away. All of them "work" in the
 * sense of not throwing; they just leave the user with data they did not ask for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FaceRepositoryTest {

    private lateinit var db: TelepicDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TelepicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * A unit vector with one significant component, so "similar" and "different" are exact.
     *
     * Face `n` is its own direction unless two faces are given the same [direction], which is how a
     * test says "these are the same person".
     */
    private fun face(
        id: Long,
        direction: Int,
        personId: Long? = null,
        confidence: Float = 0.9f,
        left: Float = 0.1f,
        top: Float = 0.1f,
        right: Float = 0.5f,
        bottom: Float = 0.5f,
    ): FaceEntity {
        val embedding = FloatArray(FaceEmbeddingCodec.DIMENSIONS).also { it[direction] = 1f }
        return FaceEntity(
            faceId = id,
            localMediaId = id,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            embedding = FaceEmbeddingCodec.encode(embedding),
            personId = personId,
            confidence = confidence,
            detectedAt = 1L,
        )
    }

    private fun media(id: Long) = LocalMedia(
        id = id,
        contentUri = Uri.parse("content://media/$id"),
        type = MediaType.PHOTO,
        mimeType = "image/jpeg",
        displayName = "p$id.jpg",
        dateMillis = 1_000L,
        durationMillis = null,
        width = 1,
        height = 1,
        sizeBytes = 1L,
        bucketId = null,
        bucketName = null,
        relativePath = null,
    )

    private class FakeLookup(private val items: Map<Long, LocalMedia>) : LocalMediaLookup {
        override suspend fun byId(id: Long): LocalMedia? = items[id]
        override suspend fun neighborId(id: Long, direction: NeighborDirection, bucketId: Long?): Long? = null
        override suspend fun byIdList(ids: Collection<Long>): List<LocalMedia> = ids.mapNotNull { items[it] }
    }

    private fun repository(faceIds: List<Long> = (1L..300L).toList()) =
        DefaultFaceRepository(
            faceDao = db.faceDao(),
            personDao = db.personDao(),
            lookup = FakeLookup(faceIds.associateWith { media(it) }),
            transaction = FaceTransaction.room(db),
            clock = { 5_000L },
        )

    private suspend fun people(repo: DefaultFaceRepository, minimum: Int = 1) =
        repo.observePeople(minimum).first()

    @Test
    fun `a rebuild groups identical faces and leaves different people apart`() = runBlocking {
        val repo = repository()
        db.faceDao().insertAll(
            listOf(
                face(1, direction = 0),
                face(2, direction = 0),
                face(3, direction = 0),
                face(4, direction = 1),
                face(5, direction = 1),
                face(6, direction = 1),
            ),
        )

        repo.rebuildClusters()

        val groups = people(repo)
        assertEquals(2, groups.size)
        assertEquals(listOf(3, 3), groups.map { it.faceCount }.sorted())
        // Every face is assigned: an unassigned face would be invisible everywhere.
        assertTrue(db.faceDao().unassignedFaces().isEmpty())
    }

    @Test
    fun `a rebuild preserves the names the user typed`() = runBlocking {
        val repo = repository()
        db.faceDao().insertAll(listOf(face(1, 0), face(2, 0), face(3, 1), face(4, 1)))
        repo.rebuildClusters()

        val before = people(repo).sortedBy { it.faceCount }
        val adas = before.first { it.faceCount == 2 && it.personId == before.first().personId }
        repo.rename(adas.personId, "Ada")

        // A second rebuild rewrites every person row from scratch and assigns fresh ids, so the name
        // can only survive if the grouping is matched back by membership.
        repo.rebuildClusters()

        val after = people(repo).sortedBy { it.faceCount }
        assertEquals("Ada", after.first { it.faceCount == 2 }.name)
    }

    @Test
    fun `a rebuild does not resurrect a hidden person`() = runBlocking {
        // Hidden state is deliberately *not* carried through a rebuild: the ids mean nothing once the
        // grouping is rewritten, and inheriting the flag by majority vote would hide a person the user
        // never hid.
        val repo = repository()
        db.faceDao().insertAll(listOf(face(1, 0), face(2, 0), face(3, 0)))
        repo.rebuildClusters()

        val person = people(repo).single()
        repo.setHidden(person.personId, true)
        assertTrue(people(repo).isEmpty())

        repo.rebuildClusters()

        val rebuilt = people(repo).single()
        assertTrue(!rebuilt.isHidden)
        assertNull(rebuilt.name)
    }

    @Test
    fun `renaming to blank clears the name rather than storing spaces`() = runBlocking {
        val repo = repository()
        db.faceDao().insertAll(listOf(face(1, 0), face(2, 0), face(3, 0)))
        repo.rebuildClusters()

        val person = people(repo).single()
        repo.rename(person.personId, "  Ada  ")
        assertEquals("Ada", people(repo).single().name)

        // Whitespace is not a name: it would render as an empty label, which looks broken.
        repo.rename(person.personId, "   ")
        assertNull(people(repo).single().name)

        repo.rename(person.personId, "Ada")
        repo.rename(person.personId, null)
        assertNull(people(repo).single().name)
    }

    @Test
    fun `merging moves every face and retires the source`() = runBlocking {
        val repo = repository()
        db.faceDao().insertAll(listOf(face(1, 0), face(2, 0), face(3, 0), face(4, 1), face(5, 1), face(6, 1)))
        repo.rebuildClusters()

        val groups = people(repo)
        assertEquals(2, groups.size)
        val target = groups[0]
        val source = groups[1]
        repo.rename(target.personId, "Ada")
        repo.rename(source.personId, "Ada again")

        repo.merge(target.personId, source.personId)

        val merged = people(repo).single()
        assertEquals(6, merged.faceCount)
        // The target keeps its name; the source's name is only adopted when the target has none.
        assertEquals("Ada", merged.name)
        // Nothing points at the retired id, which no longer exists.
        assertTrue(db.faceDao().all().none { it.personId == source.personId })
    }

    @Test
    fun `merging into an unnamed person adopts the source name`() = runBlocking {
        val repo = repository()
        db.faceDao().insertAll(listOf(face(1, 0), face(2, 0), face(3, 0), face(4, 1), face(5, 1), face(6, 1)))
        repo.rebuildClusters()

        val groups = people(repo)
        val unnamed = groups.first { it.name == null }
        val named = groups.first { it.personId != unnamed.personId }
        repo.rename(named.personId, "Grace")

        repo.merge(unnamed.personId, named.personId)

        // The only name anyone typed is not thrown away just because the survivor had none.
        assertEquals("Grace", people(repo).single().name)
    }

    @Test
    fun `splitting moves the selected faces and recomputes the source cover`() = runBlocking {
        val repo = repository()
        // Face 1 is the biggest, so it is the natural cover of the group; moving it away must not leave
        // the source pointing at a face that left.
        db.faceDao().insertAll(
            listOf(
                face(1, direction = 0, right = 0.9f, bottom = 0.9f),
                face(2, 0),
                face(3, 0),
            ),
        )
        repo.rebuildClusters()

        val source = people(repo).single()
        assertEquals(1L, source.coverFaceId)

        val newPersonId = repo.split(listOf(1L, 2L))

        val groups = people(repo).sortedBy { it.faceCount }
        assertEquals(1, groups[0].faceCount)
        assertEquals(2, groups[1].faceCount)
        assertEquals(newPersonId, groups[1].personId)
        assertEquals(listOf(1L, 2L), db.faceDao().facesOf(newPersonId).map { it.faceId }.sorted())

        val shrunk = groups.first { it.personId == source.personId }
        assertEquals(3L, shrunk.coverFaceId)
    }

    @Test
    fun `groups below the minimum size are hidden from the grid but kept in the database`() = runBlocking {
        val repo = repository()
        db.faceDao().insertAll(listOf(face(1, 0), face(2, 0), face(3, 0), face(4, 1), face(5, 1)))
        repo.rebuildClusters()

        // Two faces is not a person; three is. Hiding it from the grid must not delete the evidence,
        // or a later scan could never promote the group.
        assertEquals(listOf(3), repo.observePeople().first().map { it.faceCount })
        assertEquals(2, people(repo, minimum = 1).size)
        assertEquals(5, db.faceDao().count())
    }

    @Test
    fun `an empty index rebuilds to no people at all`() = runBlocking {
        val repo = repository()
        repo.rebuildClusters()

        assertTrue(people(repo).isEmpty())
        assertTrue(db.personDao().all().isEmpty())
    }

    @Test
    fun `removing a face that was the cover promotes another`() = runBlocking {
        val repo = repository()
        db.faceDao().insertAll(
            listOf(
                face(1, direction = 0, right = 0.9f, bottom = 0.9f),
                face(2, 0),
                face(3, 0),
            ),
        )
        repo.rebuildClusters()

        val person = people(repo, minimum = 3).single()
        assertEquals(1L, person.coverFaceId)

        repo.removeFace(1L)

        val after = people(repo, minimum = 2).single()
        assertEquals(2, after.faceCount)
        assertEquals(2L, after.coverFaceId)
        // The removed face must not be re-clustered on the next rebuild — that is what "remove this
        // false detection" has to mean.
        assertNull(db.faceDao().byId(1L))
    }

    @Test
    fun `removing the last face of a group removes the group`() = runBlocking {
        val repo = repository()
        db.faceDao().insertAll(listOf(face(1, 0)))
        repo.rebuildClusters()

        repo.removeFace(1L)

        assertTrue(db.personDao().all().isEmpty())
    }

    @Test
    fun `removeAll clears faces, scans and people`() = runBlocking {
        val repo = repository()
        db.faceDao().insertAll(listOf(face(1, 0), face(2, 0), face(3, 0)))
        db.faceDao().markScanned(listOf(com.telepic.data.face.db.FaceScanEntity(1L, 1L), com.telepic.data.face.db.FaceScanEntity(2L, 1L)))
        repo.rebuildClusters()
        assertEquals(3, db.faceDao().count())

        repo.removeAll()

        assertEquals(0, db.faceDao().count())
        assertTrue(db.faceDao().scannedIds().isEmpty())
        assertTrue(db.personDao().all().isEmpty())
        assertEquals(0, repo.observeFaceCount().first())
    }

    @Test
    fun `operations on a person that no longer exists are refused`() = runBlocking {
        val repo = repository()

        // A rebuild can retire a group while its screen is open. Silently creating a row for an id
        // nobody is looking at would be worse than an error the caller can log.
        val rename = runCatching { repo.rename(404L, "Ada") }
        assertTrue(rename.exceptionOrNull() is PersonNotFoundException)

        val hide = runCatching { repo.setHidden(404L, true) }
        assertTrue(hide.exceptionOrNull() is PersonNotFoundException)

        val merge = runCatching { repo.merge(404L, 405L) }
        assertTrue(merge.exceptionOrNull() is PersonNotFoundException)
    }

    @Test
    fun `merging a person into themselves is refused`() = runBlocking {
        val repo = repository()
        db.faceDao().insertAll(listOf(face(1, 0), face(2, 0), face(3, 0)))
        repo.rebuildClusters()
        val person = people(repo).single()

        val result = runCatching { repo.merge(person.personId, person.personId) }

        // Would otherwise delete the group and strand its faces.
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(3, db.faceDao().count())
    }

    @Test
    fun `splitting an empty selection is refused`() = runBlocking {
        val repo = repository()

        // An empty selection would otherwise create an empty person, which the grid then has to
        // explain away.
        val result = runCatching { repo.split(emptyList()) }

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(db.personDao().all().isEmpty())
    }

    @Test
    fun `a person's cover resolves to the photo it came from`() = runBlocking {
        val repo = repository()
        db.faceDao().insertAll(listOf(face(1, 0), face(2, 0), face(3, 0)))
        repo.rebuildClusters()

        val person = people(repo).single()
        val cover = person.cover

        assertNotNull(cover)
        assertEquals("content://media/${cover!!.localMediaId}", cover.contentUri)
        // The box travels with the cover: without it the card could not crop to the face.
        assertTrue(cover.right > cover.left)
        assertEquals(person.coverFaceId, cover.faceId)
    }

    @Test
    fun `a cover photo that has disappeared leaves the box but no uri`() = runBlocking {
        // The store can lose a photo between the scan and the render. The card then draws its empty
        // background instead of inventing an image, and the grouping is unaffected.
        val repo = DefaultFaceRepository(
            faceDao = db.faceDao(),
            personDao = db.personDao(),
            lookup = FakeLookup(emptyMap()),
            transaction = FaceTransaction.room(db),
        )
        db.faceDao().insertAll(listOf(face(1, 0), face(2, 0), face(3, 0)))
        repo.rebuildClusters()

        val cover = people(repo).single().cover

        assertNotNull(cover)
        assertNull(cover!!.contentUri)
    }

    @Test
    fun `a person's face list carries the photo uris`() = runBlocking {
        val repo = repository()
        db.faceDao().insertAll(listOf(face(1, 0), face(2, 0), face(3, 0)))
        repo.rebuildClusters()

        val person = people(repo).single()
        val faces = repo.observeFaces(person.personId).first()

        assertEquals(3, faces.size)
        assertTrue(faces.all { it.contentUri.startsWith("content://media/") })
        // Ordered by face id, so the detail grid does not reshuffle between emissions.
        assertEquals(listOf(1L, 2L, 3L), faces.map { it.faceId })
    }

    @Test
    fun `scan progress is published and cleared`() = runBlocking {
        val repo = repository()
        assertNull(repo.scanProgress.value)

        repo.reportScanProgress(com.telepic.domain.face.ScanProgress(photosExamined = 120, facesFound = 200))

        assertEquals(120, repo.scanProgress.value?.photosExamined)
        assertEquals(200, repo.scanProgress.value?.facesFound)

        repo.reportScanProgress(null)
        assertNull(repo.scanProgress.value)
    }

    @Test
    fun `a person inserted directly is observable by id`() = runBlocking {
        // The detail screen resolves the header from the observed row rather than from the route, so
        // a null here would render the fallback title forever.
        val personId = db.personDao().insert(PersonEntity(name = "Ada", createdAt = 1L))

        val observed = repository().observePerson(personId).first()

        assertNotNull(observed)
        assertEquals("Ada", observed!!.name)
        assertEquals(0, observed.faceCount)
    }
}
