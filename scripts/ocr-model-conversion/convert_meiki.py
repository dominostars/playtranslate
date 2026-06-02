"""Convert the Meiki OCR models (detector + horizontal/vertical recognizers) to MNN
— the `meiki-ja` pack the app ships for Japanese.

Meiki publishes ONNX directly (D-FINE object detector + MobileNetV4 backbone,
LGPL-3.0), so there is NO Paddle/ONNX export step — just download the ONNX from the
rtr46 HF repos and run the same `mnnconvert` used for PaddleOCR. Uses the venv from
requirements.txt (mnn 3.5.0); see README.md.

Upstream: https://github.com/rtr46/meikiocr  (models on HF, pinned below).

There is NO charset/keys file: Meiki reframes recognition as *character detection*
and emits Unicode codepoints directly (output `char_codes` → `chr(code)`), so the
pack is just the three .mnn files — unlike the PaddleOCR packs which carry keys.txt.

I/O contract (implemented by MeikiSession in the app):
  inputs  : images (NCHW float32, BGR, /255, aspect-resized + zero-padded to the
            model's HxW), orig_target_sizes (**int32 [W,H]** — feeding int64
            silently zeroes the height scaling: boxes collapse to y=0).
  outputs : char_codes, boxes, scores.
  pre/post: see each repo's inference.py (resize+pad; conf filter → overlap dedup →
            positional sort). Detector 960x544; horizontal rec 960x32; vertical 32x480.
"""
import os, subprocess
from huggingface_hub import hf_hub_download

HERE = os.path.dirname(os.path.abspath(__file__))
MNNCONVERT = os.path.join(HERE, "venv", "bin", "mnnconvert")
OUT = os.path.join(HERE, "mnn", "meiki-ja")
os.makedirs(OUT, exist_ok=True)

# (repo, pinned revision, onnx filename in repo) -> output .mnn name in the pack.
# Revisions pinned to what shipped (verify on HF before bumping; the rec repo was
# re-trained 2026-02-21, older checkpoint = ddd06176a4da56fba082293dbe9898d4e5998af2).
FILES = [
    ("rtr46/meiki.text.detect.v0", "a9cffa4f60cbf72ddb87edf19c6f98a01cd042e6",
     "meiki.text.detect.v0.1.960x544.onnx", "det.mnn"),
    ("rtr46/meiki.txt.recognition.v0", "a28cf5874dc2438ebb1c86336be26bcec51e3375",
     "meiki.text.rec.v0.960x32.onnx", "rec_horizontal.mnn"),
    ("rtr46/meiki.txt.recognition.v0", "a28cf5874dc2438ebb1c86336be26bcec51e3375",
     "meiki.text.rec.v0.vertical.32x480.onnx", "rec_vertical.mnn"),
]

def run(cmd):
    print("  $", " ".join(cmd))
    r = subprocess.run(cmd, capture_output=True, text=True)
    if "Converted Success" not in (r.stdout + r.stderr):
        print("  STDERR:", (r.stderr or "")[-1500:])
        print("  STDOUT:", (r.stdout or "")[-500:])
    return r

for repo, rev, onnx_name, out_name in FILES:
    print(f"\n=== {out_name}  <- {repo}@{rev[:8]} / {onnx_name} ===")
    onnx = hf_hub_download(repo_id=repo, filename=onnx_name, revision=rev)
    mnn_path = os.path.join(OUT, out_name)
    run([MNNCONVERT, "-f", "ONNX", "--modelFile", onnx, "--MNNModel", mnn_path, "--bizCode", "ocr"])
    print(f"  -> {mnn_path}  {os.path.getsize(mnn_path)/1e6:.2f} MB" if os.path.exists(mnn_path) else "  !! FAILED")

print("\nDONE. Pack = mnn/meiki-ja/{det.mnn, rec_horizontal.mnn, rec_vertical.mnn} (no keys.txt).")
