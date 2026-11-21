"""Verify the YuNet TFLite port detects faces, and pin down the exact decode.

Why this exists
---------------
Two different YuNet TFLite exports circulate. The `litert-community` one is a raw 3-scale YOLO head
(12 outputs, NCHW input) whose decode is undocumented; the `vinimuchulski` one keeps OpenCV's
[N, 15] contract (1076 priors x {2 conf, 14 loc, 1 iou}, NHWC uint8 input). Only the second is
usable without reverse-engineering a graph, so this script proves it works on real photographs before
any Kotlin is written against it.

It also settles the things the Kotlin implementation must get exactly right:
  - prior generation (min_sizes / steps / variance)
  - the sqrt(cls * iou) score
  - the letterbox-free resize + per-axis ratio back to original coordinates
  - NMS

Run:
    python validate_detector.py --model yunet_v.tflite --image lena.jpg
"""

from __future__ import annotations

import argparse
import sys
from itertools import product

import numpy as np


class YuNet:
    """Minimal reference implementation. The Kotlin `YuNetFaceDetector` mirrors this exactly."""

    MIN_SIZES = [[10, 16, 24], [32, 48], [64, 96], [128, 192, 256]]
    STEPS = [8, 16, 32, 64]
    VARIANCE = [0.1, 0.2]

    def __init__(self, model_path: str, input_hw=(120, 160), conf_threshold=0.6, nms_threshold=0.3):
        import tensorflow as tf

        self.input_hw = input_hw
        self.conf_threshold = conf_threshold
        self.nms_threshold = nms_threshold

        self.interpreter = tf.lite.Interpreter(model_path=model_path)
        self.interpreter.allocate_tensors()
        self.input_detail = self.interpreter.get_input_details()[0]
        self.output_details = self.interpreter.get_output_details()

        self.output_map = {}
        for i, detail in enumerate(self.output_details):
            last = detail["shape"][-1]
            if last == 14:
                self.output_map["loc"] = i
            elif last == 2:
                self.output_map["conf"] = i
            elif last == 1:
                self.output_map["iou"] = i
        if len(self.output_map) != 3:
            raise SystemExit(f"unexpected output layout: {[d['shape'] for d in self.output_details]}")

        self.priors = self._generate_priors()

    def _generate_priors(self) -> np.ndarray:
        h, w = self.input_hw
        feature_maps = [
            [int(h / 8), int(w / 8)],
            [int(h / 16), int(w / 16)],
            [int(h / 32), int(w / 32)],
            [int(h / 64), int(w / 64)],
        ]
        priors = []
        for k, (fh, fw) in enumerate(feature_maps):
            for i, j in product(range(fh), range(fw)):
                cx = (j + 0.5) * self.STEPS[k] / w
                cy = (i + 0.5) * self.STEPS[k] / h
                for min_size in self.MIN_SIZES[k]:
                    priors.append([cx, cy, min_size / w, min_size / h])
        return np.array(priors, dtype=np.float32)

    def _dequantise(self, index: int) -> np.ndarray:
        detail = self.output_details[index]
        tensor = self.interpreter.get_tensor(detail["index"])
        if detail["dtype"] in (np.int8, np.uint8):
            scale, zero_point = detail["quantization"]
            tensor = (tensor.astype(np.float32) - zero_point) * scale
        return tensor.astype(np.float32)

    def detect(self, image_bgr: np.ndarray):
        import cv2

        original_h, original_w = image_bgr.shape[:2]
        resized = cv2.resize(image_bgr, (self.input_hw[1], self.input_hw[0]))
        rgb = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB).astype(np.uint8)

        self.interpreter.set_tensor(self.input_detail["index"], rgb[np.newaxis, ...])
        self.interpreter.invoke()

        loc = self._dequantise(self.output_map["loc"])
        conf = self._dequantise(self.output_map["conf"])
        iou = self._dequantise(self.output_map["iou"])

        cls_scores = np.clip(conf[:, 1], 0.0, 1.0)
        iou_scores = np.clip(iou[:, 0], 0.0, 1.0)
        scores = np.sqrt(cls_scores * iou_scores)

        mask = scores > self.conf_threshold
        if not np.any(mask):
            return np.empty((0, 4)), np.empty((0, 5, 2)), np.empty((0,))

        loc, scores, priors = loc[mask], scores[mask], self.priors[mask]
        # (x, y, w, h) order, so this must be [width, height] — not the (height, width) that
        # `input_hw` is stored as. Getting this backwards scales x by the height and y by the width,
        # which still *looks* like a detection but produces a stretched box and misaligned landmarks.
        scale = np.array([self.input_hw[1], self.input_hw[0]], dtype=np.float32)

        boxes = np.hstack(
            (
                (priors[:, 0:2] + loc[:, 0:2] * self.VARIANCE[0] * priors[:, 2:4]) * scale,
                (priors[:, 2:4] * np.exp(loc[:, 2:4] * self.VARIANCE)) * scale,
            )
        )
        boxes[:, 0:2] -= boxes[:, 2:4] / 2

        landmarks = np.hstack(
            [
                (priors[:, 0:2] + loc[:, 4 + i * 2 : 6 + i * 2] * self.VARIANCE[0] * priors[:, 2:4])
                * scale
                for i in range(5)
            ]
        )

        h_ratio = original_h / self.input_hw[0]
        w_ratio = original_w / self.input_hw[1]
        boxes[:, [0, 2]] *= w_ratio
        boxes[:, [1, 3]] *= h_ratio
        landmarks[:, 0::2] *= w_ratio
        landmarks[:, 1::2] *= h_ratio

        indices = cv2.dnn.NMSBoxes(
            boxes.tolist(), scores.tolist(), self.conf_threshold, self.nms_threshold
        )
        if len(indices) == 0:
            return np.empty((0, 4)), np.empty((0, 5, 2)), np.empty((0,))

        indices = np.asarray(indices).flatten()
        return boxes[indices], landmarks[indices].reshape(-1, 5, 2), scores[indices]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True)
    parser.add_argument("--image", required=True)
    args = parser.parse_args()

    import cv2

    image = cv2.imread(args.image)
    if image is None:
        raise SystemExit(f"could not read {args.image}")

    detector = YuNet(args.model)
    boxes, landmarks, scores = detector.detect(image)

    print(f"image  : {args.image} ({image.shape[1]}x{image.shape[0]})")
    print(f"priors : {len(detector.priors)}")
    print(f"faces  : {len(scores)}")
    for i, (box, score) in enumerate(zip(boxes, scores)):
        x, y, w, h = box
        print(f"  [{i}] box=({x:.1f}, {y:.1f}, {w:.1f}, {h:.1f}) score={score:.4f}")

    if len(scores) == 0:
        print("\nFAIL: no faces detected in a photo that contains one")
        return 1

    # Every landmark must land inside its own box, otherwise the decode has a sign or scale error
    # that would silently ruin alignment downstream.
    for i, (box, points) in enumerate(zip(boxes, landmarks)):
        x, y, w, h = box
        inside = (
            (points[:, 0] >= x - w * 0.15)
            & (points[:, 0] <= x + w * 1.15)
            & (points[:, 1] >= y - h * 0.15)
            & (points[:, 1] <= y + h * 1.15)
        )
        if not inside.all():
            print(f"\nFAIL: face {i} has landmarks outside its box:\n{points}")
            return 1

    print("\nPASS: faces detected, landmarks consistent with boxes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
