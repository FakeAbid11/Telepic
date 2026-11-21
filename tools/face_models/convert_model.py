"""Convert the GhostFaceNet .h5 into a TFLite embedder and prove it still works.

Why this is a separate offline tool and not part of the Gradle build
--------------------------------------------------------------------
The upstream release is a Keras 2 `.h5`. Turning it into a quantised `.tflite` needs TensorFlow, a
representative dataset and a numerical comparison against the original graph. None of that belongs in
a Gradle task, and CI must never depend on a 600 MB Python install. So the artefact is produced here,
once, published to a release, and pinned by SHA-256 in `FaceModelStore`.

Why TF_USE_LEGACY_KERAS
-----------------------
The weights were saved with Keras 2. Keras 3's loader rejects the legacy config outright
(`'str' object has no attribute 'quantization_mode'`), so the graph can only be opened through
`tf_keras`. But `TFLiteConverter.from_keras_model` inspects `tf.keras`, and on TF 2.21 that is Keras 3
— handing it a `tf_keras` model, or tracing one into a `tf.function` by hand, yields a graph whose
fused Conv2D/FusedBatchNormV3/PReLU ops the MLIR importer refuses ("neither a custom op nor a flex
op").

Setting `TF_USE_LEGACY_KERAS=1` *before* TensorFlow is imported makes `tf.keras` resolve to `tf_keras`.
The converter then sees exactly the Keras version the file was written with, and the normal
`from_keras_model` path works. The environment variable must be set before the import, which is why it
is at the top of this file rather than in a caller.

Quantisation choice
-------------------
Default is **dynamic-range int8** (weights int8, activations float32). For an embedding model this is
the right trade: it shrinks the file ~4x, needs no representative dataset (so nothing to get wrong),
and leaves activations at full precision — which is what keeps embedding distances stable. Full int8
with a representative dataset would be smaller and faster but shifts distances non-uniformly, and the
clustering threshold is calibrated against whatever this script produces. `--mode float16` is the
fallback if the numerical check below regresses.

Usage
-----
    python convert_model.py --weights gfn_w13s1.h5 --out ghostfacenet.tflite
    python convert_model.py --weights gfn_w13s1.h5 --out gf.tflite --mode float16
"""

from __future__ import annotations

import os

# Must precede the tensorflow import. See the module docstring.
os.environ.setdefault("TF_USE_LEGACY_KERAS", "1")
os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import argparse
import hashlib
import sys
from pathlib import Path

import numpy as np

INPUT_SHAPE = (1, 112, 112, 3)
EMBED_DIM = 512
# The training pipeline normalises pixels as (pixel - 127.5) * 0.0078125, i.e. /128.
PIXEL_MEAN = 127.5
PIXEL_SCALE = 0.0078125


def load_model(weights: Path):
    import tensorflow as tf

    model = tf.keras.models.load_model(str(weights), compile=False)
    out_dim = tuple(model.outputs[0].shape)[-1]
    if out_dim != EMBED_DIM:
        raise SystemExit(f"expected a {EMBED_DIM}-D output, got {model.outputs[0].shape}")
    return model


def to_float32(model):
    """Rebuild the graph with float32 layers.

    The published weights were trained under a `mixed_float16` policy, so the saved graph carries f16
    compute dtypes. The TFLite importer rejects that: it fails on `tf.FusedBatchNormV3`, `tf.Conv2D`,
    `tf.DepthwiseConv2dNative` and the PReLU `Neg`/`Relu`/`Mul`/`AddV2` chain, because those ops are
    "neither a custom op nor a flex op" once they are typed f16.

    Converting the *weights* to float32 and leaving quantisation to the converter's optimisation pass
    is the correct order: the graph then uses ordinary float ops the importer understands, and int8
    happens in the optimiser where it belongs. This mirrors `convert_mixed_float16_to_float32` in the
    upstream `GhostFaceNets.py`, which exists for precisely this reason.
    """
    import tensorflow as tf

    def rebuild(layer):
        if isinstance(layer, tf.keras.layers.InputLayer):
            return layer
        if isinstance(layer, tf.keras.layers.Activation) and layer.activation is tf.keras.activations.linear:
            return layer
        config = layer.get_config()
        config["dtype"] = "float32"
        rebuilt = layer.__class__.from_config(config)
        rebuilt.build(layer.input_shape)
        rebuilt.set_weights(layer.get_weights())
        return rebuilt

    inputs = tf.keras.layers.Input(model.input_shape[1:])
    return tf.keras.models.clone_model(model, input_tensors=inputs, clone_function=rebuild)


def convert(model, mode: str) -> bytes:
    import tensorflow as tf

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    if mode == "dynamic_int8":
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
    elif mode == "float16":
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_types = [tf.float16]
    elif mode == "none":
        pass
    else:
        raise SystemExit(f"unknown mode {mode!r}")
    return converter.convert()


def preprocess(samples: np.ndarray) -> np.ndarray:
    return (samples.astype(np.float32) - PIXEL_MEAN) * PIXEL_SCALE


def l2_normalise(vectors: np.ndarray) -> np.ndarray:
    norms = np.linalg.norm(vectors, axis=1, keepdims=True)
    return vectors / np.maximum(norms, 1e-12)


def reference_embeddings(model, samples: np.ndarray) -> np.ndarray:
    return l2_normalise(model(preprocess(samples), training=False).numpy())


def tflite_embeddings(tflite_bytes: bytes, samples: np.ndarray) -> np.ndarray:
    import tensorflow as tf

    interpreter = tf.lite.Interpreter(model_content=tflite_bytes)
    interpreter.allocate_tensors()
    inp = interpreter.get_input_details()[0]
    out = interpreter.get_output_details()[0]

    results = []
    for i in range(samples.shape[0]):
        x = preprocess(samples[i : i + 1]).astype(inp["dtype"])
        interpreter.set_tensor(inp["index"], x)
        interpreter.invoke()
        results.append(interpreter.get_tensor(out["index"])[0])

    return l2_normalise(np.asarray(results, dtype=np.float32))


def compare(reference: np.ndarray, candidate: np.ndarray) -> None:
    """Cosine agreement between the original graph and the converted file."""
    cosines = np.sum(reference * candidate, axis=1)
    print(f"\ncosine similarity vs original graph (n={len(cosines)}):")
    print(f"  min    {cosines.min():.6f}")
    print(f"  mean   {cosines.mean():.6f}")
    print(f"  max    {cosines.max():.6f}")

    # The embedder only has to preserve *relative* geometry: two crops of the same person must stay
    # closer than two crops of different people. A mean cosine below 0.99 means the quantisation has
    # moved the vectors enough that a threshold calibrated on the original would no longer transfer.
    if cosines.mean() < 0.99:
        raise SystemExit(
            f"FAIL: mean cosine {cosines.mean():.6f} < 0.99 — quantisation moved the embeddings too "
            "far. Retry with --mode float16."
        )
    print("  PASS (mean >= 0.99)")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--weights", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument(
        "--mode",
        choices=["dynamic_int8", "float16", "none"],
        default="dynamic_int8",
    )
    parser.add_argument("--seed", type=int, default=0)
    args = parser.parse_args()

    model = load_model(args.weights)
    # The published weights are mixed_float16, so the loaded graph is itself imprecise. Comparing a
    # quantised file against it would measure float16 noise as if it were quantisation error. The
    # rebuilt float32 graph is the honest baseline.
    reference_model = to_float32(model)
    tflite_bytes = convert(reference_model, args.mode)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(tflite_bytes)

    # Deterministic inputs. Random noise is a deliberately harsh probe: it sits nowhere near the
    # face manifold, so any numerical drift shows up amplified. Agreement here is a necessary
    # condition, not proof of accuracy — real-photo validation is a separate, manual step on device.
    rng = np.random.default_rng(args.seed)
    samples = rng.integers(0, 256, size=(8, 112, 112, 3), dtype=np.uint8)

    compare(
        reference_embeddings(reference_model, samples),
        tflite_embeddings(tflite_bytes, samples),
    )

    digest = hashlib.sha256(tflite_bytes).hexdigest()
    size_mb = len(tflite_bytes) / (1024 * 1024)
    print(f"\nwrote {args.out} ({size_mb:.2f} MB, mode={args.mode})")
    print(f"sha256 {digest}")
    print(f"\nPin this in FaceModelStore:\n  {args.out.name}  {digest}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
