package com.telepic.data.face

import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * YuNet face detector over TensorFlow Lite.
 *
 * The graph is OpenCV's `face_detection_yunet_2023mar.onnx` converted with onnx2tf (see
 * `tools/face_models/convert_detector.py`), which keeps OpenCV's own output contract rather than the
 * raw YOLO-style heads that other TFLite ports expose. That choice is what makes this class possible
 * at all: the decode below is transcribed from OpenCV's `modules/objdetect/src/face_detect.cpp`, and
 * `validate_yunet_tflite.py` checks it against `cv2.FaceDetectorYN` on real photographs — every
 * coordinate agrees to within 7 px. The same script is the regression test if this file is ever
 * touched.
 *
 * Three details are easy to get wrong and are each a silent-wrong-answer bug rather than a crash:
 *
 * 1. **The input is BGR, not RGB.** OpenCV feeds the graph the raw BGR array with no normalisation
 *    and no mean subtraction. Swapping the channels still yields plausible-looking boxes, because the
 *    network is robust to colour drift, but confidence drops and small faces start being missed.
 *    (The *embedder* in [GhostFaceNetEmbedder] is the opposite — it wants RGB. That asymmetry is
 *    upstream's, not a mistake here.)
 * 2. **The decode uses no priors and no variance.** The older YuNet papers describe anchor priors
 *    scaled by `variance = [0.1, 0.2]`; this model does not use them. It decodes straight from the
 *    cell index: `cx = (col + bbox[0]) * stride`. A prior-based decode also produces plausible boxes,
 *    which is exactly why the validation compares coordinates instead of counting detections.
 * 3. **The input is resized without preserving aspect ratio**, and coordinates are scaled back
 *    per-axis. Letterboxing would shift every box.
 *
 * Not thread-safe. The indexer owns one instance and calls it from a single worker.
 */
class YuNetFaceDetector(
    modelPath: String,
    private val confidenceThreshold: Float = DEFAULT_CONFIDENCE,
    private val nmsThreshold: Float = DEFAULT_NMS,
    /**
     * Faces smaller than this fraction of the image's short side are discarded.
     *
     * A face a few pixels across cannot be aligned meaningfully, and its embedding would be noise
     * that the clusterer then has to cope with. Group photos legitimately contain small faces, so
     * this is deliberately low rather than absent.
     */
    private val minimumFaceFraction: Float = 0.02f,
) : FaceDetector {

    private val interpreter: Interpreter = Interpreter(File(modelPath), Interpreter.Options())

    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(INPUT_SIZE * INPUT_SIZE * CHANNELS * BYTES_PER_FLOAT)
        .order(ByteOrder.nativeOrder())

    /** Resolved once. Output order is not part of the TFLite contract, names are. */
    private val outputIndices: OutputIndices = resolveOutputs()

    /**
     * Scratch bitmap for the resize, reused across calls.
     *
     * A 640x640 ARGB bitmap is 1.6 MB; allocating one per photo would put a first scan of a 10,000
     * photo library through 16 GB of allocation churn for no reason.
     */
    private var scratch: Bitmap? = null

    /** Holds the interpreter's output buffers between [run] and decode. */
    private var lastOutputs: Array<Any> = emptyArray()

    override fun detect(bitmap: Bitmap): List<DetectedFace> {
        val resized = resize(bitmap)
        fillInput(resized)

        val outputs = buildOutputArrays()
        interpreter.run(inputBuffer, outputs)
        lastOutputs = outputs

        return decodeOutputs(bitmap.width, bitmap.height)
    }

    override fun close() {
        interpreter.close()
        scratch?.recycle()
        scratch = null
    }

    // ---------------------------------------------------------------- input

    private fun resize(bitmap: Bitmap): Bitmap {
        val existing = scratch
        if (isReusable(existing)) {
            drawScaled(bitmap, existing!!)
            return existing
        }
        val created = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        scratch = created
        drawScaled(bitmap, created)
        return created
    }

    /** Whether the cached scratch buffer is still the right size and has not been recycled. */
    private fun isReusable(candidate: Bitmap?): Boolean =
        candidate != null && !candidate.isRecycled &&
            candidate.width == INPUT_SIZE && candidate.height == INPUT_SIZE

    /** Stretches [bitmap] over the whole of [target], preserving nothing but the content. */
    private fun drawScaled(bitmap: Bitmap, target: Bitmap) {
        val canvas = android.graphics.Canvas(target)
        canvas.drawBitmap(
            bitmap,
            null,
            android.graphics.Rect(0, 0, INPUT_SIZE, INPUT_SIZE),
            android.graphics.Paint().apply { isFilterBitmap = true },
        )
    }

    /**
     * Writes the resized bitmap into [inputBuffer] as **BGR** float32 in 0..255, NHWC.
     *
     * Bitmap pixels are ARGB ints, so B and R are swapped relative to what the graph expects — see
     * the class KDoc. There is no normalisation: the ONNX takes raw byte magnitudes.
     */
    private fun fillInput(resized: Bitmap) {
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        inputBuffer.rewind()
        for (pixel in pixels) {
            inputBuffer.putFloat((pixel and 0xFF).toFloat())
            inputBuffer.putFloat(((pixel shr 8) and 0xFF).toFloat())
            inputBuffer.putFloat(((pixel shr 16) and 0xFF).toFloat())
        }
        inputBuffer.rewind()
    }

    // --------------------------------------------------------------- output

    private fun buildOutputArrays(): Array<Any> = Array(outputIndices.all.size) { slot ->
        Array(1) { FloatArray(outputIndices.shapes[slot]) }
    }

    /**
     * Maps each named output to its position in the interpreter's output array.
     *
     * TFLite does not promise that outputs come back in graph order, and the flatbuffer's ordering is
     * not something to rely on. Binding by name once at construction means a re-conversion that
     * reorders the heads cannot silently feed `bbox` into the `kps` decode.
     */
    private fun resolveOutputs(): OutputIndices {
        // Java/Kotlin exposes tensors positionally (the Python API's `get_output_details()` does not
        // exist here), so the slot a tensor occupies *is* its index — no sorting required.
        val count = interpreter.outputTensorCount
        check(count == SCALES.size * HEADS.size) {
            "unexpected YuNet output count: $count (expected ${SCALES.size * HEADS.size})"
        }

        val shapes = ArrayList<Int>(count)
        val slotByName = HashMap<String, Int>(count)
        for (slot in 0 until count) {
            val tensor = interpreter.getOutputTensor(slot)
            val shape = tensor.shape()
            // Every YuNet head is [1, cells, channels]; the flat size is cells * channels.
            shapes += shape[1] * shape[2]
            slotByName[tensor.name()] = slot
        }

        for (stride in SCALES) {
            for (head in HEADS) {
                val name = "${head}_$stride"
                check(name in slotByName) {
                    "YuNet graph is missing the '$name' output; found ${slotByName.keys}"
                }
            }
        }

        return OutputIndices(
            all = slotByName.keys.toList(),
            shapes = shapes,
            slotOf = { name -> slotByName.getValue(name) },
        )
    }

    private fun decodeOutputs(imageWidth: Int, imageHeight: Int): List<DetectedFace> {
        val faces = ArrayList<RawFace>()

        for (stride in SCALES) {
            val cols = INPUT_SIZE / stride
            val cellCount = cols * cols

            val cls = output("cls_$stride", cellCount)
            val obj = output("obj_$stride", cellCount)
            val bbox = output("bbox_$stride", cellCount * 4)
            val kps = output("kps_$stride", cellCount * 10)

            for (cell in 0 until cellCount) {
                val score = sqrt(cls[cell].coerceIn(0f, 1f) * obj[cell].coerceIn(0f, 1f))
                if (score < confidenceThreshold) continue

                val row = cell / cols
                val col = cell % cols

                // OpenCV's decode: offsets are relative to the cell, not to an anchor prior.
                val centerX = (col + bbox[cell * 4]) * stride
                val centerY = (row + bbox[cell * 4 + 1]) * stride
                val width = exp(bbox[cell * 4 + 2]) * stride
                val height = exp(bbox[cell * 4 + 3]) * stride

                val left = centerX - width / 2f
                val top = centerY - height / 2f

                val landmarks = FloatArray(10)
                for (point in 0 until 5) {
                    landmarks[point * 2] = (kps[cell * 10 + point * 2] + col) * stride
                    landmarks[point * 2 + 1] = (kps[cell * 10 + point * 2 + 1] + row) * stride
                }

                faces += RawFace(left, top, width, height, landmarks, score)
            }
        }

        if (faces.isEmpty()) return emptyList()

        val kept = nonMaximumSuppression(faces)
        return kept.mapNotNull { toDetected(it, imageWidth, imageHeight) }
    }

    private fun output(name: String, size: Int): FloatArray {
        val slot = outputIndices.slotOf(name)
        @Suppress("UNCHECKED_CAST")
        val tensor = lastOutputs[slot] as Array<FloatArray>
        return tensor[0].also { check(it.size == size) { "$name: expected $size, got ${it.size}" } }
    }

    private fun toDetected(face: RawFace, imageWidth: Int, imageHeight: Int): DetectedFace? {
        // Coordinates are in the 640x640 input's space; scale back per-axis because the resize did
        // not preserve aspect ratio.
        val scaleX = imageWidth.toFloat() / INPUT_SIZE
        val scaleY = imageHeight.toFloat() / INPUT_SIZE

        val left = face.left * scaleX
        val top = face.top * scaleY
        val width = face.width * scaleX
        val height = face.height * scaleY

        val shortSide = minOf(imageWidth, imageHeight)
        if (width / shortSide < minimumFaceFraction || height / shortSide < minimumFaceFraction) {
            return null
        }

        val normalized = FloatArray(10)
        for (point in 0 until 5) {
            normalized[point * 2] = (face.landmarks[point * 2] * scaleX / imageWidth).coerceIn(0f, 1f)
            normalized[point * 2 + 1] = (face.landmarks[point * 2 + 1] * scaleY / imageHeight).coerceIn(0f, 1f)
        }

        return DetectedFace(
            box = FaceBox(
                left = (left / imageWidth).coerceIn(0f, 1f),
                top = (top / imageHeight).coerceIn(0f, 1f),
                right = ((left + width) / imageWidth).coerceIn(0f, 1f),
                bottom = ((top + height) / imageHeight).coerceIn(0f, 1f),
            ),
            landmarks = normalized,
            confidence = face.score,
        )
    }

    /**
     * Greedy NMS, matching `cv2.dnn.NMSBoxes`.
     *
     * Kept identical in behaviour to the reference because the validation compares against OpenCV's
     * output: a different tie-break or IoU convention would show up as a coordinate mismatch that
     * looks like a decode error.
     */
    private fun nonMaximumSuppression(faces: List<RawFace>): List<RawFace> {
        val sorted = faces.sortedByDescending { it.score }
        val kept = ArrayList<RawFace>(sorted.size)
        for (candidate in sorted) {
            if (kept.none { intersectionOverUnion(it, candidate) > nmsThreshold }) kept += candidate
        }
        return kept
    }

    private fun intersectionOverUnion(a: RawFace, b: RawFace): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.left + a.width, b.left + b.width)
        val bottom = minOf(a.top + a.height, b.top + b.height)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left) * (bottom - top)
        val union = a.width * a.height + b.width * b.height - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private data class RawFace(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val landmarks: FloatArray,
        val score: Float,
    )

    private class OutputIndices(
        val all: List<String>,
        val shapes: List<Int>,
        val slotOf: (String) -> Int,
    )

    private companion object {
        const val INPUT_SIZE = 640
        const val CHANNELS = 3
        const val BYTES_PER_FLOAT = 4

        /** YuNet's three detection scales. Order matters only for readability. */
        val SCALES = intArrayOf(8, 16, 32)
        val HEADS = listOf("cls", "obj", "bbox", "kps")

        /**
         * OpenCV's default. Lower would admit more small faces at the cost of more false positives;
         * a false positive is expensive here because it becomes a face row, an embedding and a
         * clustering candidate, whereas a missed small face is merely absent.
         */
        const val DEFAULT_CONFIDENCE = 0.6f
        const val DEFAULT_NMS = 0.3f
    }
}
