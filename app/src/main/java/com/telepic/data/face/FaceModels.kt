package com.telepic.data.face

/**
 * The face models the People tab runs, and the exact files this build accepts.
 *
 * Both models are downloaded on demand rather than bundled (see `ModelDownloadWorker`), so their
 * integrity has to be checked before they are ever loaded. A truncated download is not the only risk:
 * a proxy or a bad mirror can serve a *different* model with a valid-looking body, and a swapped
 * embedder would not crash — it would quietly produce embeddings in an incompatible space, and every
 * person group would be wrong. The checksum is what makes that impossible rather than unlikely.
 *
 * Every digest below was produced by the conversion tools in `tools/face_models/` and verified
 * against a reference implementation before being committed:
 *
 * - `yunet.tflite` — converted from OpenCV's `face_detection_yunet_2023mar.onnx` via onnx2tf.
 *   Validated against `cv2.FaceDetectorYN` on real photographs; every coordinate agrees within 7 px
 *   (`validate_yunet_tflite.py`). 640x640 NHWC float32 input, 12 named outputs.
 * - `ghostfacenet.tflite` — converted from GhostFaceNets V1 W1.3-S1 (MS1MV3, MIT) via
 *   `convert_model.py`, dynamic-range int8. 112x112 float32 input, 512-D output.
 *
 * Why dynamic-range int8 and not float16
 * --------------------------------------
 * The two candidates were compared on labelled same/different face sets rather than on random noise
 * (`compare_quantisation.py`), because the property that matters is *relative geometry*: two photos of
 * one person must stay closer together than photos of two people.
 *
 *     file        size      same-person cos   diff-person cos   margin
 *     float16     8.22 MB   0.8682 (min .657) 0.0445 (max .095) +0.5616
 *     int8        4.49 MB   0.8647 (min .654) 0.0501 (max .102) +0.5519
 *
 * int8 costs ~1.7% of the separation margin and halves the download. The margin is what the threshold
 * is chosen against, and that threshold is calibrated on *this* file — so the shift relative to the
 * fp32 reference is irrelevant, while 3.7 MB less to download is not.
 *
 * Note the counter-intuitive part: int8 (4.49 MB) is *smaller* than float16 (8.22 MB). GhostFaceNet is
 * depthwise-convolution heavy, and float16 stores every depthwise kernel at 2 bytes with nothing to
 * compress, so the option that sounds cheaper is the bigger file.
 */
object FaceModels {

    /** The face detector. YuNet, 640x640. */
    val DETECTOR = ModelFile(
        name = "yunet.tflite",
        sizeBytes = 236_540,
        sha256 = "3ef5a03cf2408f0038d130d286b58844dec005e1c37c82ead0ea03539c59d84b",
    )

    /** The face embedder. GhostFaceNet V1 W1.3-S1, 512-D, dynamic-range int8. */
    val EMBEDDER = ModelFile(
        name = "ghostfacenet.tflite",
        sizeBytes = 4_494_616,
        sha256 = "a6d3ad8ab2098f64e303afdd5eda83d98282ac306b18f33d7345009a2c836aba",
    )

    val ALL: List<ModelFile> = listOf(DETECTOR, EMBEDDER)

    /** Combined download size, for the opt-in copy. */
    val TOTAL_BYTES: Long = ALL.sumOf { it.sizeBytes }

    /**
     * Where the files are fetched from.
     *
     * A release asset on the project's own repository rather than a third-party host: the URL is
     * stable, the bytes are versioned with the app that pins their digests, and nothing about the
     * People tab depends on an external service staying up.
     */
    const val DOWNLOAD_BASE_URL =
        "https://github.com/FakeAbid11/Telepic/releases/download/models-v1"

    /** One model file and the digest that identifies it. */
    data class ModelFile(
        val name: String,
        val sizeBytes: Long,
        val sha256: String,
    ) {
        val downloadUrl: String get() = "$DOWNLOAD_BASE_URL/$name"
    }
}
