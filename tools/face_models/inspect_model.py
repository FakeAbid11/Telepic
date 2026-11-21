"""Inspect the GhostFaceNet .h5 so the conversion targets the right graph.

Prints the model's input/output shapes and layer inventory. We need to know:
  - the exact input name/shape (112x112x3 expected)
  - whether the 'embedding' layer is the 512-D output we want
  - which custom layers/ops appear, because those are what break TFLite conversion

The weights were saved with Keras 2, so they are loaded through `tf_keras` (the Keras 2
compatibility package). Keras 3's loader rejects the legacy config with
`'str' object has no attribute 'quantization_mode'`.
"""

import sys

import numpy as np

import tf_keras

MODEL_PATH = sys.argv[1] if len(sys.argv) > 1 else "gfn_w13s1.h5"

print(f"tf-keras {tf_keras.__version__}")

model = tf_keras.models.load_model(MODEL_PATH, compile=False)

print(f"\nmodel name : {model.name}")
print(f"inputs     : {[(t.name, tuple(t.shape)) for t in model.inputs]}")
print(f"outputs    : {[(t.name, tuple(t.shape)) for t in model.outputs]}")
print(f"layers     : {len(model.layers)}")
print(f"params     : {model.count_params():,}")

print("\nlayer types:")
types = {}
for layer in model.layers:
    types[type(layer).__name__] = types.get(type(layer).__name__, 0) + 1
for name, count in sorted(types.items(), key=lambda kv: -kv[1]):
    print(f"  {name:<28} {count}")

print("\nlast 12 layers:")
for layer in model.layers[-12:]:
    shape = getattr(layer, "output_shape", None)
    print(f"  {layer.name:<28} {type(layer).__name__:<24} {shape}")

# A single forward pass proves the graph is executable before we try to convert it.
dummy = np.zeros((1, 112, 112, 3), dtype=np.float32)
out = model(dummy, training=False)
print(f"\nforward pass ok, output shape {tuple(out.shape)}, dtype {out.dtype}")
print(
    f"output stats: min={float(np.min(out)):.4f} max={float(np.max(out)):.4f} "
    f"l2={float(np.linalg.norm(out)):.4f}"
)
