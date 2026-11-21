package com.telepic.domain.face

import com.telepic.data.face.db.FaceEntity
import com.telepic.data.face.db.PersonCover
import com.telepic.data.face.db.PersonEntity

/**
 * A person as the UI shows them: their identity, how much evidence backs the group, and which face
 * represents them.
 *
 * [faceCount] is the group's size, which the UI needs to (a) hide groups below the minimum size and
 * (b) tell the user how many photos a person appears in. It is carried here rather than queried per
 * card so a grid of 200 people does not issue 200 count queries.
 */
data class Person(
    val personId: Long,
    val name: String?,
    val coverFaceId: Long?,
    val faceCount: Int,
    val isHidden: Boolean,
    val createdAt: Long,
    /**
     * The cover face itself, resolved from [coverFaceId], or null when there is nothing to show.
     *
     * A [FaceCrop] rather than a [PersonFace] because a card needs less than the detail grid does —
     * a box and a photo to crop, no confidence — and because the two are resolved differently: the
     * detail grid resolves every face's photo in one batch, while a card needs exactly one.
     */
    val cover: FaceCrop? = null,
)

/**
 * One face inside a person's detail screen, with the photo it came from.
 *
 * [contentUri] is stored alongside rather than looked up per face: the detail grid shows the *photo*
 * a face was found in, and resolving that from MediaStore per tile would be a provider query per
 * frame of a scrolling grid.
 */
data class PersonFace(
    val faceId: Long,
    val localMediaId: Long,
    val contentUri: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
)

/**
 * The photo region a person's card should show: the cover face, its photo, and where the face sits.
 *
 * [contentUri] is null only while it is being resolved — a card with no URI draws its empty
 * background rather than a placeholder image, which is the same rule the album tiles follow.
 */
data class FaceCrop(
    val faceId: Long,
    val localMediaId: Long,
    val contentUri: String?,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * What the People tab needs to decide what to render.
 *
 * Modelled as one state rather than several booleans because the cases are mutually exclusive and
 * every combination of "models present / scan running / index empty" that is not listed is a bug, not
 * a variant. A single sealed type makes that impossible to represent.
 */
sealed interface PeopleState {
    /** The models have not been downloaded. The tab offers to fetch them. */
    data object ModelsAbsent : PeopleState

    /** A model download is running. [progress] is 0..1, or null when the total is unknown. */
    data class Downloading(val progress: Float?) : PeopleState

    /**
     * The download worker ran and did not produce the models.
     *
     * Its own state rather than a return to [ModelsAbsent], because a silent return to the offer would
     * leave a user who tapped "Download" looking at the same button with no explanation — the most
     * common cause is a flaky connection, which retrying fixes.
     */
    data object DownloadFailed : PeopleState

    /** Models are present but nothing has been scanned yet. The tab offers to start. */
    data object NotIndexed : PeopleState

    /** A scan is running. [photosExamined] and [facesFound] are running totals. */
    data class Indexing(val photosExamined: Int, val facesFound: Int) : PeopleState

    /**
     * An index exists.
     *
     * @param people groups above the minimum size, ready to render.
     * @param totalFaces every stored face, including those in groups too small to show. The
     *   difference between this and the people list is what tells the UI "you have faces but no
     *   groups yet", which is a different message from "nothing found".
     */
    data class Ready(val people: List<Person>, val totalFaces: Int) : PeopleState
}

/** Thrown when a person operation refers to a group that no longer exists. */
class PersonNotFoundException(personId: Long) : IllegalStateException("person $personId no longer exists")

/**
 * Live progress of a running scan.
 *
 * Deliberately not persisted: it describes *this process's* work, and a value restored after a restart
 * would claim a scan was running when it is not. The durable progress is the committed row count.
 */
data class ScanProgress(
    val photosExamined: Int,
    val facesFound: Int,
)

/** Exposed for the UI: the stored entities, without interpretation. */
internal fun PersonEntity.toDomain(faceCount: Int, cover: FaceCrop? = null) = Person(
    personId = personId,
    name = name,
    coverFaceId = coverFaceId,
    faceCount = faceCount,
    isHidden = isHidden,
    createdAt = createdAt,
    cover = cover,
)

/** Exposed for the UI: the cover projection, before its photo has been resolved. */
internal fun PersonCover.toFaceCrop() = FaceCrop(
    faceId = faceId,
    localMediaId = localMediaId,
    contentUri = null,
    left = left,
    top = top,
    right = right,
    bottom = bottom,
)

/** Exposed for the UI: the stored entity, mapped to the tile model. */
internal fun FaceEntity.toDomainFace(contentUri: String) = PersonFace(
    faceId = faceId,
    localMediaId = localMediaId,
    contentUri = contentUri,
    left = left,
    top = top,
    right = right,
    bottom = bottom,
    confidence = confidence,
)
