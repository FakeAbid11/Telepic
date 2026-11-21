"""Inspect the YuNet TFLite detector: input/output tensor contract.

The Android side has to feed exactly the tensor this file declares, so the shapes and dtypes are
copied from here rather than from documentation. YuNet's raw ONNX export has a different signature
(boxes/scores/landmarks as separate graph outputs with fixed input size); the LiteRT port may differ.
"""

import sys

import numpy as np

import tensorflow as tf

path = sys.argv[1] if len(sys.argv) > 1 else "yunet_fp16.tflite"

interpreter = tf.lite.Interpreter(model_path=path)
interpreter.allocate_tensors()

print(f"file: {path}")
print(f"ops : {len(interpreter._get_ops_details())}")

print("\nINPUTS")
for detail in interpreter.get_input_details():
    print(f"  index {detail['index']}")
    print(f"  name  {detail['name']}")
    print(f"  shape {detail['shape']}  (shape_signature {detail['shape_signature']})")
    print(f"  dtype {detail['dtype']}  quant {detail['quantization']}")

print("\nOUTPUTS")
for detail in interpreter.get_output_details():
    print(f"  index {detail['index']}")
    print(f"  name  {detail['name']}")
    print(f"  shape {detail['shape']}  (shape_signature {detail['shape_signature']})")
    print(f"  dtype {detail['dtype']}  quant {detail['quantization']}")

inp = interpreter.get_input_details()[0]
shape = tuple(inp["shape"])
probe = np.zeros(shape, dtype=inp["dtype"])
interpreter.set_tensor(inp["index"], probe)
interpreter.invoke()
print("\nforward pass on zeros ok")
for detail in interpreter.get_output_details():
    out = interpreter.get_tensor(detail["index"])
    print(f"  {detail['name']}: shape {out.shape}, dtype {out.dtype}, "
          f"range [{float(np.min(out)):.4f}, {float(np.max(out)):.4f}]")
