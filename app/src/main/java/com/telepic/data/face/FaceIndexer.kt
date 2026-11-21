package com.telepic.data.face

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.telepic.data.ScanCursorStore
import com.telepic.data.face.db.FaceDao
import com.telepic.data.face.db.FaceEntity
import com.telepic.data.face.db.FaceScanEntity
import com.telepic.data.media.MediaPageLoader
import com.telepic.diagnostics.TelepicLog
import com.telepic.domain.media.LocalMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Builds the on-device face index: walks the photo library, detects faces, aligns them, embeds them
 * and stores the result.
 *
 * Three constraints shape this class, and each has a visible cost if ignored.
 *
 * **Videos are skipped entirely.** A frame-accurate face scan of video needs a decoder pass per file
 * and would dominate the whole scan's cost for a small share of the library's faces. v1 covers photos
 * only, and the filter is applied here rather than in the page loader so the *cursor* still advances
 * across video rows — filtering upstream would make a library that is mostly video look shorter than
 * it is and silently skip the photos after it.
 *
 * **Work is chunked, not per-photo.** Decoding, detecting and embedding are all tens of milliseconds
 * per photo; a scan of 10,000 photos is minutes of continuous CPU. Writing one row per photo would
 * mean 15,000 transactions, and — more importantly — nothing would be visible until the very end.
 * Chunks make progress observable and writes batched. The first chunk is deliberately smaller than
 * the rest so the People tab has something to show almost immediately.
 *
 * **Progress resumes across process death.** The cursor is the *number of photos examined*, not a
 * page offset, because photos are skipped (videos, undecodable files) and a page offset would drift
 * from what was actually processed. `face_scan` is the authority on what has been examined; the
 * cursor only decides where to start looking.
 */
interface FaceIndexer {

    /**
     * Examines photos until the library ends or [maxPhotos] have been examined.
     *
     * @param maxPhotos a safety bound for one run. The worker passes its own budget so a scan can be
     *   handed to a fresh run instead of running for an unbounded time in the background.
     * @param onChunk called after each chunk is committed, with the running total of photos examined
     *   and faces found in that chunk. Invoked on the calling coroutine's context.
     * @return a summary of what this run did.
     */
    suspend fun index(
        maxPhotos: Int = DEFAULT_MAX_PHOTOS,
        onChunk: (ChunkResult) -> Unit = {},
    ): IndexResult

    companion object {
        /**
         * Default budget for one run. At roughly 40 ms per photo this is a couple of minutes of work —
         * long enough to make real progress, short enough that a background run finishes.
         */
        const val DEFAULT_MAX_PHOTOS = 3_000
    }

    /**
     * Forgets everything: faces, scan memory and the cursor.
     *
     * Necessary after the models change. An embedding produced by one model is not comparable with an
     * embedding produced by another — the vectors live in different spaces — so "already scanned"
     * stops meaning "already embedded in the current space" the moment a model file is replaced.
     * Keeping the old rows would silently mix incompatible embeddings and produce groups that are
     * wrong in a way no amount of threshold tuning could fix.
     */
    suspend fun reset()
}

/** What one committed chunk contained. */
data class ChunkResult(
    val photosExamined: Int,
    val facesFound: Int,
    val runningPhotoTotal: Int,
    val runningFaceTotal: Int,
)

/** What a whole [FaceIndexer.index] run did. */
data class IndexResult(
    val photosExamined: Int,
    val facesFound: Int,
    /** True when the sweep reached the end of the library rather than stopping at its budget. */
    val reachedEnd: Boolean,
)

class DefaultFaceIndexer(
    private val contentResolver: ContentResolver,
    private val pageLoader: MediaPageLoader,
    private val faceDao: FaceDao,
    private val cursorStore: ScanCursorStore,
    /**
     * Factories rather than instances: the models are large native resources, and an indexer that
     * lives as long as the container must not hold two interpreters open between scans. Each run
     * opens them and closes them in a `finally`.
     */
    private val detectorFactory: () -> FaceDetector,
    private val embedderFactory: () -> FaceEmbedder,
    private val aligner: FaceAligner = ArcFaceAligner(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Longest side of the decoded bitmap. See [decode]. */
    private val decodeMaxSide: Int = DEFAULT_DECODE_SIDE,
) : FaceIndexer {

    override suspend fun index(maxPhotos: Int, onChunk: (ChunkResult) -> Unit): IndexResult =
        withContext(Dispatchers.IO) {
            val alreadyScanned = faceDao.scannedIds().toHashSet()
            var cursor = cursorStore.offset(CURSOR_KEY).coerceAtLeast(0)

            var photosExamined = 0
            var facesFound = 0
            var reachedEnd = false

            val detector = detectorFactory()
            val embedder = embedderFactory()
            try {
                var chunkSize = FIRST_CHUNK_SIZE
                while (photosExamined < maxPhotos) {
                    val remaining = maxPhotos - photosExamined
                    val pageSize = minOf(chunkSize, remaining)
                    val page = pageLoader.load(cursor, pageSize)
                    if (page.isEmpty()) {
                        reachedEnd = true
                        break
                    }

                    val chunkFaces = processPage(page, alreadyScanned, detector, embedder)
                    photosExamined += page.size
                    facesFound += chunkFaces
                    cursor += page.size

                    // Persist the cursor before the callback: a crash between the two must not replay
                    // a chunk that was already committed, and losing the callback is only a UI blip.
                    persistCursor(cursor)

                    onChunk(
                        ChunkResult(
                            photosExamined = page.size,
                            facesFound = chunkFaces,
                            runningPhotoTotal = photosExamined,
                            runningFaceTotal = facesFound,
                        ),
                    )

                    if (page.size < pageSize) {
                        reachedEnd = true
                        break
                    }
                    chunkSize = SUBSEQUENT_CHUNK_SIZE
                }
            } finally {
                detector.close()
                embedder.close()
            }

            // Reaching the end rewinds the cursor so newly added photos are found by the next run;
            // a budget-limited run keeps it, which is what lets a large library converge over
            // successive runs instead of re-examining the same prefix forever.
            if (reachedEnd) persistCursor(0)

            IndexResult(photosExamined, facesFound, reachedEnd)
        }

    /**
     * Processes one page, writing faces and scan records in one batched pair of calls.
     *
     * @return the number of faces stored for this page.
     */
    private suspend fun processPage(
        page: List<LocalMedia>,
        alreadyScanned: MutableSet<Long>,
        detector: FaceDetector,
        embedder: FaceEmbedder,
    ): Int {
        val now = clock()
        val faces = ArrayList<FaceEntity>(page.size)
        val scans = ArrayList<FaceScanEntity>(page.size)

        for (item in page) {
            if (item.id in alreadyScanned) continue

            // Videos are recorded as examined so a later scan does not re-decode them, even though
            // they contribute no faces. Without this a library of videos would be re-walked forever.
            if (item.isVideo) {
                scans += FaceScanEntity(item.id, now)
                alreadyScanned += item.id
                continue
            }

            val bitmap = decode(item.contentUri)
            if (bitmap == null) {
                // Unreadable is not the same as "has no faces", but both mean "nothing more to learn
                // from this file". Recording it as examined stops a corrupt file from failing every
                // future scan.
                scans += FaceScanEntity(item.id, now)
                alreadyScanned += item.id
                continue
            }

            try {
                val detections = detector.detect(bitmap)
                for (detection in detections) {
                    val aligned = aligner.align(bitmap, detection.landmarks) ?: continue
                    try {
                        val embedding = embedder.embed(aligned)
                        faces += FaceEntity(
                            localMediaId = item.id,
                            left = detection.box.left,
                            top = detection.box.top,
                            right = detection.box.right,
                            bottom = detection.box.bottom,
                            embedding = FaceEmbeddingCodec.encode(embedding),
                            personId = null,
                            confidence = detection.confidence,
                            detectedAt = now,
                        )
                    } finally {
                        aligned.recycle()
                    }
                }
                scans += FaceScanEntity(item.id, now)
                alreadyScanned += item.id
            } finally {
                bitmap.recycle()
            }
        }

        // Faces first: if the process dies between the two writes, the scan record is absent and the
        // photo is re-examined, which is recoverable. The reverse order would leave a photo marked as
        // examined with its faces missing, and nothing would ever go back for them.
        if (faces.isNotEmpty()) faceDao.insertAll(faces)
        if (scans.isNotEmpty()) faceDao.markScanned(scans)
        return faces.size
    }

    /**
     * Decodes a photo at a bounded size.
     *
     * A modern phone photo is 4000x3000 and decodes to ~48 MB as ARGB_8888. Doing that per photo while
     * holding a detector and an embedder would put a mid-range device under real memory pressure. The
     * detector downsamples to 640x640 regardless, so anything above that is discarded work — but the
     * *decode* is what costs the memory, hence the `inSampleSize` pre-pass.
     *
     * [decodeMaxSide] is larger than 640 deliberately: small faces in a group photo survive a 1280px
     * decode and vanish at 640px, and the detector benefits from the extra pixels.
     */
    private fun decode(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            } ?: return null
        } catch (io: IOException) {
            TelepicLog.warn("FaceIndexer", "could not read the bounds of $uri", io)
            return null
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= decodeMaxSide) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (io: IOException) {
            TelepicLog.warn("FaceIndexer", "could not decode $uri", io)
            null
        }
    }

    override suspend fun reset() {
        withContext(Dispatchers.IO) {
            // Faces before scans, and both before the cursor: an interrupted reset must leave the
            // indexer believing work remains, never believing it is done.
            faceDao.clearFaces()
            faceDao.clearScans()
            persistCursor(0)
        }
    }

    /** Best-effort: a cursor that cannot be written costs a re-scan, never correctness. */
    private suspend fun persistCursor(offset: Int) {
        try {
            cursorStore.save(CURSOR_KEY, offset)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            TelepicLog.warn("FaceIndexer", "could not persist the face scan cursor", throwable)
        }
    }

    companion object {
        /**
         * Independent of the map sweep's cursor, so the two scans never share progress. Sharing one
         * key would make each scan believe it had covered a library it had not touched.
         */
        const val CURSOR_KEY = "face_index"

        /** Small, so the People tab has something to show after the first few seconds. */
        const val FIRST_CHUNK_SIZE = 100

        /** Then larger, because per-chunk overhead is amortised and progress is already visible. */
        const val SUBSEQUENT_CHUNK_SIZE = 250

        /** Longest decoded side. See [decode] for why this is above the detector's 640. */
        const val DEFAULT_DECODE_SIDE = 1_280
    }
}
