"""Convert the per-language PP-OCRv5 mobile recognizers the app references but
that weren't built in the original spike (latin, korean) to .mnn.

Same pipeline as convert_all.py: HF download (Paddle inference) -> paddle2onnx ->
MNNConvert; charset from inference.yml -> keys.txt; verify each .mnn loads.

Output layout matches the app's pack dirs: mnn/<pack-key>/{rec.mnn, keys.txt}
(the detector is the APK-bundled PP-OCRv5 mobile det, shared by all langs).
Idempotent; per-model try/except so one failure doesn't sink the others.
"""
import os, subprocess, traceback

HERE = os.path.dirname(os.path.abspath(__file__))
VENV_BIN = os.path.join(HERE, "venv", "bin")
PADDLE2ONNX = os.path.join(VENV_BIN, "paddle2onnx")
MNNCONVERT = os.path.join(VENV_BIN, "mnnconvert")

# pack-key -> HF repo (PP-OCRv5 mobile rec, one per script group)
MODELS = {
    "paddle-rec-latin":      "PaddlePaddle/latin_PP-OCRv5_mobile_rec",
    "paddle-rec-korean":     "PaddlePaddle/korean_PP-OCRv5_mobile_rec",
    # Hosted ahead of language wiring (support coming soon). NOTE: Arabic / Cyrillic
    # / Thai have NO ML Kit OCR fallback, so Paddle is their SOLE recognizer once
    # wired (the no-floor case). Arabic is also RTL.
    "paddle-rec-arabic":     "PaddlePaddle/arabic_PP-OCRv5_mobile_rec",
    "paddle-rec-cyrillic":   "PaddlePaddle/cyrillic_PP-OCRv5_mobile_rec",
    "paddle-rec-devanagari": "PaddlePaddle/devanagari_PP-OCRv5_mobile_rec",
    "paddle-rec-thai":       "PaddlePaddle/th_PP-OCRv5_mobile_rec",
}

def run(cmd):
    print("  $", " ".join(cmd))
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        print("  STDERR:", (r.stderr or "")[-2000:])
        print("  STDOUT:", (r.stdout or "")[-500:])
    return r

def find_model_files(d):
    prog = next((c for c in ("inference.json", "inference.pdmodel")
                 if os.path.exists(os.path.join(d, c))), None)
    params = "inference.pdiparams" if os.path.exists(os.path.join(d, "inference.pdiparams")) else None
    return prog, params

def extract_charset(src, out_keys):
    import yaml
    with open(os.path.join(src, "inference.yml"), encoding="utf-8") as f:
        cfg = yaml.safe_load(f)
    chars = None
    def walk(o):
        nonlocal chars
        if isinstance(o, dict):
            for k, v in o.items():
                if k.lower() in ("character_dict", "character_dict_list") and isinstance(v, list):
                    chars = v
                walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)
    walk(cfg)
    assert chars, "character_dict not found in inference.yml"
    with open(out_keys, "w", encoding="utf-8") as f:
        f.write("\n".join(str(c) for c in chars))
    return len(chars)

def verify(mnn_path):
    import MNN.expr as F
    F.load_as_dict(mnn_path)

def convert(key, repo):
    from huggingface_hub import snapshot_download
    print(f"\n=== {key}  ({repo}) ===")
    src = os.path.join(HERE, "paddle", key)
    onnx_path = os.path.join(HERE, "onnx", f"{key}.onnx")
    outdir = os.path.join(HERE, "mnn", key)
    os.makedirs(os.path.dirname(onnx_path), exist_ok=True)
    os.makedirs(outdir, exist_ok=True)
    mnn_path = os.path.join(outdir, "rec.mnn")
    keys_path = os.path.join(outdir, "keys.txt")

    snapshot_download(repo_id=repo, local_dir=src,
                      allow_patterns=["inference.*", "*.yml", "config.json"])
    prog, params = find_model_files(src)
    print("  program:", prog, "| params:", params)
    if not (prog and params):
        print("  !! missing program/params"); return None

    if not os.path.exists(onnx_path):
        run([PADDLE2ONNX, "--model_dir", src, "--model_filename", prog,
             "--params_filename", params, "--save_file", onnx_path, "--opset_version", "17"])
        if not os.path.exists(onnx_path):
            print("  !! paddle2onnx produced no file"); return None
    if not os.path.exists(mnn_path):
        run([MNNCONVERT, "-f", "ONNX", "--modelFile", onnx_path,
             "--MNNModel", mnn_path, "--bizCode", "ocr"])
        if not os.path.exists(mnn_path):
            print("  !! MNNConvert produced no file"); return None
    n = extract_charset(src, keys_path)
    verify(mnn_path)
    print(f"  OK rec.mnn={os.path.getsize(mnn_path)/1e6:.2f}MB  keys.txt={n} chars  (loads in pymnn)")
    return mnn_path

if __name__ == "__main__":
    for key, repo in MODELS.items():
        try:
            convert(key, repo)
        except Exception:
            print(f"  !! exception for {key}:\n{traceback.format_exc()}")
    print("\nDONE")
