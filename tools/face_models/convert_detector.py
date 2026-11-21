"""Convert OpenCV's YuNet ONNX detector into TFLite.

Why the official ONNX and not a prebuilt TFLite port
----------------------------------------------------
Two TFLite YuNet exports circulate and both were evaluated before this file was written:

1. `litert-community/YuNet-Face-LiteRT` — a 640x640 raw head with 12 outputs and an NCHW input.
   Its anchor/offset decode is undocumented, and its `cls` outputs sit in a narrow band (0.48-0.83)
   on a real face, so it cannot be a plain sigmoid output. Reverse-engineering it is guesswork.
2. `vinimuchulski/yunet-face-det-tflite-int8` — keeps OpenCV's [N, 15] contract, but the graph is
   frozen at a 160x120 input (resizing the tensor leaves the prior count at 1076, so the anchors no
   longer match the feature maps). At that size it misses a face occupying a large part of a 548x342
   photograph, which is exactly the group-photo case the People tab needs.

Converting the official ONNX avoids both problems: it is the same graph OpenCV itself runs, at
640x640, and its decode is read directly from `modules/objdetect/src/face_detect.cpp` rather than
inferred. The decode that this produces was validated against `cv2.FaceDetectorYN` — see
`validate_detector.py` — matching every coordinate to within ~7 px on two photographs.

The ONNX itself must be fetched from `media.githubusercontent.com`, not `raw.githubusercontent.com`:
the file is stored in Git LFS and the raw URL returns a 131-byte pointer.

Usage
-----
    python convert_detector.py --onnx yunet.onnx --out yunet.tflite
"""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--onnx", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--workdir", type=Path, default=Path("onnx2tf_out"))
    args = parser.parse_args()

    if args.onnx.stat().st_size < 10_000:
        raise SystemExit(
            f"{args.onnx} is {args.onnx.stat().st_size} bytes — that is a Git LFS pointer, not the "
            "model. Fetch it from media.githubusercontent.com instead."
        )

    # onnx2tf emits NHWC tensors and a SavedModel tree, then a float32 TFLite flatbuffer. The
    # `-kat` flags keep the input/output names readable so the Kotlin side can bind by name rather
    # than by positional index.
    cmd = [
        sys.executable,
        "-m",
        "onnx2tf",
        "-i",
        str(args.onnx),
        "-o",
        str(args.workdir),
        "-osd",
        # YuNet's three detection scales have different spatial sizes; without this onnx2tf prints
        # a shape-override prompt and blocks waiting for input.
        "-cotof",
        "-nuo",
    ]
    print("running:", " ".join(cmd))
    result = subprocess.run(cmd, check=False)
    if result.returncode != 0:
        raise SystemExit(f"onnx2tf failed with exit code {result.returncode}")

    candidates = sorted(args.workdir.glob("*_float32.tflite")) or sorted(
        args.workdir.glob("*.tflite")
    )
    if not candidates:
        raise SystemExit(f"no .tflite produced under {args.workdir}")

    produced = candidates[0]
    payload = produced.read_bytes()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(payload)

    digest = hashlib.sha256(payload).hexdigest()
    print(f"\nwrote {args.out} ({len(payload) / (1024 * 1024):.2f} MB)")
    print(f"sha256 {digest}")
    print(f"\nPin this in FaceModelStore:\n  {args.out.name}  {digest}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
