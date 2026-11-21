"""Decide between int8 and float16 quantisation using real faces, not noise.

The conversion script's numerical check uses random noise, which is a deliberately harsh probe but
says nothing about the property the People tab depends on. What matters is **relative geometry**: two
photographs of the same person must stay closer together than photographs of two different people. A
quantisation that shifts every embedding by the same amount is harmless; one that reorders neighbours
is fatal, and only the second kind shows up as a clustering failure.

This script therefore builds a small labelled set — real faces plus photometric and geometric
augmentations standing in for "the same person on a different day" — and checks the ranking survives
in each candidate model. It reports the actual margin, so the threshold choice is made against data
rather than a guess.

Usage
-----
    python compare_quantisation.py --weights gfn.h5 --image a.jpg --image b.jpg
"""

from __future__ import annotations

import os

os.environ.setdefault("TF_USE_LEGACY_KERAS", "1")
os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import argparse
import itertools
import sys
from pathlib import Path

import numpy as np

INPUT_SIZE = 112
PIXEL_MEAN = 127.5
PIXEL_SCALE = 0.0078125


def align_face(image_bgr, box, landmarks):
    """Similarity-transform a detection onto the canonical 112x112 ArcFace template.

    The published benchmarks for GhostFaceNet assume 5-point alignment, not a rectangular crop. A raw
    crop leaves the eyes at whatever angle and scale the photo happened to have, which costs more
    accuracy than any quantisation choice — so this is part of the pipeline, not a refinement.
    """
    import cv2

    # Canonical 112x112 five-point template (left eye, right eye, nose, left mouth, right mouth).
    template = np.array(
        [
            [38.2946, 51.6963],
            [73.5318, 51.5014],
            [56.0252, 71.7366],
            [41.5493, 92.3655],
            [70.7299, 92.2041],
        ],
        dtype=np.float32,
    )
    src = np.asarray(landmarks, dtype=np.float32).reshape(5, 2)
    matrix, _ = cv2.estimateAffinePartial2D(src, template, method=cv2.LMEDS)
    if matrix is None:
        x, y, w, h = box
        crop = image_bgr[int(y) : int(y + h), int(x) : int(x + w)]
        return cv2.resize(crop, (INPUT_SIZE, INPUT_SIZE))
    return cv2.warpAffine(image_bgr, matrix, (INPUT_SIZE, INPUT_SIZE), borderValue=0.0)


def augment(image_bgr, variant: int):
    """Photometric/geometric variants standing in for 'same person, different photo'."""
    import cv2

    if variant == 0:
        return image_bgr
    if variant == 1:
        return np.clip(image_bgr.astype(np.float32) * 1.25, 0, 255).astype(np.uint8)
    if variant == 2:
        return np.clip(image_bgr.astype(np.float32) * 0.75, 0, 255).astype(np.uint8)
    if variant == 3:
        return cv2.GaussianBlur(image_bgr, (5, 5), 0)
    if variant == 4:
        h, w = image_bgr.shape[:2]
        m = cv2.getRotationMatrix2D((w / 2, h / 2), 8, 1.0)
        return cv2.warpAffine(image_bgr, m, (w, h), borderMode=cv2.BORDER_REPLICATE)
    if variant == 5:
        h, w = image_bgr.shape[:2]
        m = cv2.getRotationMatrix2D((w / 2, h / 2), -8, 1.0)
        return cv2.warpAffine(image_bgr, m, (w, h), borderMode=cv2.BORDER_REPLICATE)
    raise ValueError(variant)


def embed_tflite(model_path, faces):
    import tensorflow as tf

    interpreter = tf.lite.Interpreter(model_path=model_path)
    interpreter.allocate_tensors()
    inp = interpreter.get_input_details()[0]
    out = interpreter.get_output_details()[0]

    results = []
    for face in faces:
        x = (face.astype(np.float32) - PIXEL_MEAN) * PIXEL_SCALE
        interpreter.set_tensor(inp["index"], x[np.newaxis, ...].astype(inp["dtype"]))
        interpreter.invoke()
        vector = interpreter.get_tensor(out["index"])[0].astype(np.float32)
        norm = np.linalg.norm(vector)
        results.append(vector / norm if norm > 0 else vector)
    return np.asarray(results)


def embed_keras(model, faces):
    processed = (faces.astype(np.float32) - PIXEL_MEAN) * PIXEL_SCALE
    out = model(processed, training=False).numpy()
    norms = np.linalg.norm(out, axis=1, keepdims=True)
    return out / np.maximum(norms, 1e-12)


def report(label: str, embeddings: np.ndarray, labels: list[int]) -> float:
    """Print same-vs-different separation and return the margin."""
    sim = embeddings @ embeddings.T
    n = len(labels)
    same, diff = [], []
    for i, j in itertools.combinations(range(n), 2):
        (same if labels[i] == labels[j] else diff).append(float(sim[i, j]))

    same_min = min(same)
    diff_max = max(diff)
    margin = same_min - diff_max
    status = "SEPARABLE" if margin > 0 else "OVERLAP"
    print(
        f"  {label:<12} same: min {same_min:.4f} mean {np.mean(same):.4f} | "
        f"diff: max {diff_max:.4f} mean {np.mean(diff):.4f} | "
        f"margin {margin:+.4f}  {status}"
    )
    return margin


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--weights", type=Path, required=True)
    parser.add_argument("--onnx", type=Path, required=True, help="YuNet ONNX used for alignment")
    parser.add_argument("--tflite", action="append", required=True)
    parser.add_argument("--image", action="append", required=True)
    args = parser.parse_args()

    import cv2

    detector = cv2.FaceDetectorYN.create(str(args.onnx), "", (640, 640), 0.6, 0.3, 5000)

    faces, labels = [], []
    for identity, path in enumerate(args.image):
        image = cv2.imread(path)
        if image is None:
            raise SystemExit(f"could not read {path}")
        for variant in range(6):
            augmented = augment(image, variant)
            # Detect on the *augmented* image. Reusing landmarks from the original would misalign
            # every rotated variant, and a misaligned crop is not a fair test of the embedding —
            # it measures the aligner's input error rather than the model's quantisation.
            detector.setInputSize((augmented.shape[1], augmented.shape[0]))
            _, detections = detector.detect(augmented)
            if detections is None or len(detections) == 0:
                print(f"  (no face in {path} variant {variant} — skipped)")
                continue
            face = detections[0]
            faces.append(align_face(augmented, face[:4], face[4:14]))
            labels.append(identity)

    if len(set(labels)) < 2:
        raise SystemExit("need at least two identities with detections to measure separation")

    faces_arr = np.asarray(faces, dtype=np.float32)
    print(f"identities {len(args.image)}, samples {len(faces)} "
          f"({len(faces) // len(args.image)} per identity)\n")

    import tf_keras

    reference_model = tf_keras.models.load_model(str(args.weights), compile=False)
    margins = {}
    margins["keras-f16"] = report("keras-f16", embed_keras(reference_model, faces_arr), labels)
    for path in args.tflite:
        name = Path(path).stem
        margins[name] = report(name, embed_tflite(path, faces_arr), labels)

    print()
    worst = min(margins.values())
    if worst <= 0:
        print("FAIL: at least one model cannot separate the identities at all")
        return 1

    baseline = margins["keras-f16"]
    for name, margin in margins.items():
        if name == "keras-f16":
            continue
        # A candidate is acceptable when it keeps a clear separation, even if the absolute margin
        # shrinks — the threshold is calibrated against whichever file ships.
        if margin < baseline * 0.5:
            print(f"WARN: {name} margin {margin:+.4f} is less than half the reference {baseline:+.4f}")
        else:
            print(f"OK:   {name} margin {margin:+.4f} (reference {baseline:+.4f})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
