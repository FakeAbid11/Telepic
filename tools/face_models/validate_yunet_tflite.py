"""Validate a converted YuNet TFLite against OpenCV's own detector.

This is the gate for `convert_detector.py`. An ONNX→TFLite conversion can reorder axes, fuse ops or
silently drop a head; the file still loads and still produces numbers, so "it runs" proves nothing.
The only meaningful check is that the converted graph finds the same faces in the same places as the
reference implementation running the same source ONNX.

The decode is taken from OpenCV's `modules/objdetect/src/face_detect.cpp`, which does **not** use
priors or variance the way the older YuNet papers describe. It is simply:

    cx = (col + bbox[idx, 0]) * stride
    cy = (row + bbox[idx, 1]) * stride
    w  = exp(bbox[idx, 2]) * stride
    h  = exp(bbox[idx, 3]) * stride
    landmark = (kps[idx, j] + col_or_row) * stride
    score    = sqrt(clamp(cls) * clamp(obj))

Getting this wrong is subtle: a prior-based decode also produces plausible-looking boxes, which is
why this script compares coordinates against OpenCV rather than just counting detections.

Usage
-----
    python validate_yunet_tflite.py --tflite yunet.tflite --onnx yunet.onnx --image face.jpg
"""

from __future__ import annotations

import argparse
import sys

import numpy as np

STRIDES = (8, 16, 32)
INPUT_SIZE = 640
COORD_TOLERANCE_PX = 12.0


def decode_scale(cls, obj, bbox, kps, stride, input_size, threshold):
    """OpenCV's per-scale decode. Returns a list of 15-float rows in input-image pixels."""
    cols = input_size // stride
    rows = input_size // stride
    scores = np.sqrt(np.clip(cls, 0.0, 1.0) * np.clip(obj, 0.0, 1.0))

    rows_out = []
    for idx in np.nonzero(scores >= threshold)[0]:
        row, col = divmod(int(idx), cols)
        cx = (col + bbox[idx, 0]) * stride
        cy = (row + bbox[idx, 1]) * stride
        w = np.exp(bbox[idx, 2]) * stride
        h = np.exp(bbox[idx, 3]) * stride
        face = [cx - w / 2.0, cy - h / 2.0, w, h]
        for n in range(5):
            face.append((kps[idx, 2 * n] + col) * stride)
            face.append((kps[idx, 2 * n + 1] + row) * stride)
        face.append(float(scores[idx]))
        rows_out.append(face)
    return rows_out


def detect_tflite(model_path, image_bgr, threshold=0.6, nms_threshold=0.3):
    import tensorflow as tf

    interpreter = tf.lite.Interpreter(model_path=model_path)
    interpreter.allocate_tensors()
    inp = interpreter.get_input_details()[0]
    outputs = {d["name"]: d for d in interpreter.get_output_details()}

    original_h, original_w = image_bgr.shape[:2]
    import cv2

    resized = cv2.resize(image_bgr, (INPUT_SIZE, INPUT_SIZE))
    # OpenCV feeds raw BGR, 0..255, no normalisation, and the converted graph is NHWC float32.
    blob = resized.astype(np.float32)[np.newaxis, ...]
    interpreter.set_tensor(inp["index"], blob)
    interpreter.invoke()

    def get(name):
        return interpreter.get_tensor(outputs[name]["index"])[0]

    rows = []
    for stride in STRIDES:
        rows += decode_scale(
            get(f"cls_{stride}")[:, 0],
            get(f"obj_{stride}")[:, 0],
            get(f"bbox_{stride}"),
            get(f"kps_{stride}"),
            stride,
            INPUT_SIZE,
            threshold,
        )

    if not rows:
        return np.empty((0, 15), dtype=np.float32)

    faces = np.asarray(rows, dtype=np.float32)
    sx, sy = original_w / INPUT_SIZE, original_h / INPUT_SIZE
    faces[:, [0, 2, 4, 6, 8, 10, 12]] *= sx
    faces[:, [1, 3, 5, 7, 9, 11, 13]] *= sy

    keep = cv2.dnn.NMSBoxes(
        faces[:, :4].tolist(), faces[:, 14].tolist(), threshold, nms_threshold
    )
    if len(keep) == 0:
        return np.empty((0, 15), dtype=np.float32)
    return faces[np.asarray(keep).flatten()]


def detect_opencv(onnx_path, image_bgr):
    import cv2

    detector = cv2.FaceDetectorYN.create(onnx_path, "", (INPUT_SIZE, INPUT_SIZE), 0.6, 0.3, 5000)
    detector.setInputSize((image_bgr.shape[1], image_bgr.shape[0]))
    _, faces = detector.detect(image_bgr)
    return np.empty((0, 15), dtype=np.float32) if faces is None else faces


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tflite", required=True)
    parser.add_argument("--onnx", required=True)
    parser.add_argument("--image", action="append", required=True)
    args = parser.parse_args()

    import cv2

    failures = 0
    for path in args.image:
        image = cv2.imread(path)
        if image is None:
            print(f"{path}: could not read")
            failures += 1
            continue

        ours = detect_tflite(args.tflite, image)
        reference = detect_opencv(args.onnx, image)

        print(f"\n{path}  ({image.shape[1]}x{image.shape[0]})")
        print(f"  tflite {len(ours)} face(s) | opencv {len(reference)} face(s)")

        if len(ours) != len(reference):
            print("  FAIL: detection count differs")
            failures += 1
            continue
        if len(ours) == 0:
            print("  (no faces in either — nothing to compare)")
            continue

        worst = 0.0
        for i in range(len(ours)):
            diff = float(np.abs(ours[i][:14] - reference[i][:14]).max())
            worst = max(worst, diff)
            print(f"  face {i}: max coord diff {diff:.2f} px")
        if worst > COORD_TOLERANCE_PX:
            print(f"  FAIL: {worst:.2f} px exceeds {COORD_TOLERANCE_PX} px tolerance")
            failures += 1
        else:
            print(f"  PASS (worst {worst:.2f} px <= {COORD_TOLERANCE_PX} px)")

    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
