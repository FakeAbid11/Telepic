"""Determine YuNet's prior layout empirically and validate the decode.

The official OpenCV YuNet ONNX (`face_detection_yunet_2023mar`) exposes 12 outputs — `cls_`, `obj_`,
`bbox_`, `kps_` at three strides — rather than the single `[N, 15]` tensor OpenCV's C++ API returns.
The decode is therefore something this project has to implement, and the anchor layout is not
documented in the model file: the outputs are 6400 / 1600 / 400 for a 640x640 input, which is one
prior per cell at strides 8 / 16 / 32, but the `min_sizes` used to scale the regression are not
recoverable from the shapes.

Rather than guess and ship a detector that returns stretched boxes, this script tries the plausible
layouts against a photograph whose face is known and reports which one actually lands on it.
"""

from __future__ import annotations

import argparse
import itertools
import sys

import numpy as np

STRIDES = [8, 16, 32]
VARIANCE = [0.1, 0.2]


def build_priors(input_hw: int, min_sizes_per_scale: list[list[float]]) -> np.ndarray:
    """YuNet priors in normalized (cx, cy, w, h) form, matching OpenCV's generator."""
    h = w = input_hw
    priors = []
    for k, stride in enumerate(STRIDES):
        fh, fw = h // stride, w // stride
        for i, j in itertools.product(range(fh), range(fw)):
            cx = (j + 0.5) * stride / w
            cy = (i + 0.5) * stride / h
            for min_size in min_sizes_per_scale[k]:
                priors.append([cx, cy, min_size / w, min_size / h])
    return np.array(priors, dtype=np.float32)


def decode(loc, cls, obj, priors, input_hw):
    """Standard YuNet decode: score = sqrt(cls*obj), boxes from prior + variance-scaled offsets."""
    scores = np.sqrt(np.clip(cls[:, 0], 0, 1) * np.clip(obj[:, 0], 0, 1))
    scale = np.array([input_hw, input_hw], dtype=np.float32)

    boxes = np.hstack(
        (
            (priors[:, 0:2] + loc[:, 0:2] * VARIANCE[0] * priors[:, 2:4]) * scale,
            (priors[:, 2:4] * np.exp(loc[:, 2:4] * VARIANCE[1])) * scale,
        )
    )
    boxes[:, 0:2] -= boxes[:, 2:4] / 2
    landmarks = np.hstack(
        [
            (priors[:, 0:2] + loc[:, 4 + i * 2 : 6 + i * 2] * VARIANCE[0] * priors[:, 2:4]) * scale
            for i in range(5)
        ]
    )
    return boxes, landmarks, scores


def run(session, image_bgr, input_hw, min_sizes_per_scale, conf_threshold=0.6):
    import cv2

    original_h, original_w = image_bgr.shape[:2]
    resized = cv2.resize(image_bgr, (input_hw, input_hw))

    # OpenCV feeds YuNet raw BGR 0..255, NCHW, no normalisation.
    blob = resized.astype(np.float32).transpose(2, 0, 1)[np.newaxis, ...]
    names = [o.name for o in session.get_outputs()]
    outputs = session.run(names, {session.get_inputs()[0].name: blob})
    by_name = dict(zip(names, outputs))

    cls = np.concatenate([by_name[f"cls_{s}"][0] for s in STRIDES], axis=0)
    obj = np.concatenate([by_name[f"obj_{s}"][0] for s in STRIDES], axis=0)
    loc = np.concatenate(
        [np.hstack([by_name[f"bbox_{s}"][0], by_name[f"kps_{s}"][0]]) for s in STRIDES], axis=0
    )

    priors = build_priors(input_hw, min_sizes_per_scale)
    if len(priors) != len(loc):
        return None, f"prior count {len(priors)} != anchor count {len(loc)}"

    boxes, landmarks, scores = decode(loc, cls, obj, priors, input_hw)

    keep = scores > conf_threshold
    if not np.any(keep):
        return np.empty((0, 4)), "no detections above threshold"

    boxes, landmarks, scores = boxes[keep], landmarks[keep], scores[keep]

    h_ratio, w_ratio = original_h / input_hw, original_w / input_hw
    boxes[:, [0, 2]] *= w_ratio
    boxes[:, [1, 3]] *= h_ratio
    landmarks[:, 0::2] *= w_ratio
    landmarks[:, 1::2] *= h_ratio

    indices = cv2.dnn.NMSBoxes(boxes.tolist(), scores.tolist(), conf_threshold, 0.3)
    if len(indices) == 0:
        return np.empty((0, 4)), "all detections suppressed by NMS"
    indices = np.asarray(indices).flatten()
    return boxes[indices], "ok"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--onnx", required=True)
    parser.add_argument("--image", required=True)
    args = parser.parse_args()

    import cv2
    import onnxruntime as ort

    session = ort.InferenceSession(args.onnx, providers=["CPUExecutionProvider"])
    image = cv2.imread(args.image)
    if image is None:
        raise SystemExit(f"could not read {args.image}")

    input_hw = session.get_inputs()[0].shape[2]

    # Candidate anchor layouts. The output shapes imply one prior per cell, so each scale needs a
    # single min_size; the question is which magnitude. Candidates bracket the stride and the values
    # OpenCV uses for its 320-input model.
    candidates = {
        "one-per-cell, min=2*stride": [[16.0], [32.0], [64.0]],
        "one-per-cell, min=4*stride": [[32.0], [64.0], [128.0]],
        "one-per-cell, min=1*stride": [[8.0], [16.0], [32.0]],
        "opencv-320 layout": [[10.0, 16.0, 24.0], [32.0, 48.0], [64.0, 96.0]],
    }

    print(f"image {args.image} ({image.shape[1]}x{image.shape[0]}), input {input_hw}x{input_hw}\n")
    for label, min_sizes in candidates.items():
        boxes, status = run(session, image, input_hw, min_sizes)
        if boxes is None:
            print(f"{label:<30} SKIP  {status}")
            continue
        print(f"{label:<30} {len(boxes)} face(s)  {status}")
        for box in boxes[:3]:
            x, y, w, h = box
            print(f"    box=({x:.0f}, {y:.0f}, {w:.0f}x{h:.0f})  aspect={w / max(h, 1):.2f}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
