"""Convert the 4 PP-OCRv5 models (general rec line) to .mnn for the spike.

Pipeline per model:  HF download (Paddle inference format) -> paddle2onnx -> MNNConvert
Then: extract charset from inference.yml -> keys.txt, and verify each .mnn loads in pymnn.

Records REAL output sizes to convert_out.txt. No estimates.
Idempotent: skips a stage whose output already exists.
"""
import os, sys, subprocess, traceback, glob
from huggingface_hub import snapshot_download

HERE = os.path.dirname(os.path.abspath(__file__))
VENV_BIN = os.path.join(HERE, "venv", "bin")
PADDLE2ONNX = os.path.join(VENV_BIN, "paddle2onnx")
MNNCONVERT = os.path.join(VENV_BIN, "mnnconvert")
OUT = open(os.path.join(HERE, "convert_out.txt"), "w")

MODELS = [
    ("det_mobile", "PaddlePaddle/PP-OCRv5_mobile_det"),
    ("rec_mobile", "PaddlePaddle/PP-OCRv5_mobile_rec"),
    ("det_server", "PaddlePaddle/PP-OCRv5_server_det"),
    ("rec_server", "PaddlePaddle/PP-OCRv5_server_rec"),
]

def log(*a):
    msg = " ".join(str(x) for x in a)
    print(msg); print(msg, file=OUT); OUT.flush()

def mb(p):
    return f"{os.path.getsize(p)/1e6:.2f} MB"

def run(cmd, **kw):
    log("  $", " ".join(cmd))
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        log("  STDERR:", (r.stderr or "")[-1500:])
        log("  STDOUT:", (r.stdout or "")[-800:])
    return r

def find_model_files(d):
    """Return (program_file, params_file) handling PIR (.json) and legacy (.pdmodel)."""
    prog = None
    for cand in ("inference.json", "inference.pdmodel"):
        if os.path.exists(os.path.join(d, cand)):
            prog = cand; break
    params = "inference.pdiparams" if os.path.exists(os.path.join(d, "inference.pdiparams")) else None
    return prog, params

def convert(name, repo):
    log(f"\n=== {name}  ({repo}) ===")
    src = os.path.join(HERE, "paddle", name)
    onnx_path = os.path.join(HERE, "onnx", f"{name}.onnx")
    mnn_path = os.path.join(HERE, "mnn", f"{name}.mnn")
    os.makedirs(os.path.dirname(onnx_path), exist_ok=True)
    os.makedirs(os.path.dirname(mnn_path), exist_ok=True)

    # 1) download Paddle inference bundle
    snapshot_download(repo_id=repo, local_dir=src,
                      allow_patterns=["inference.*", "*.yml", "config.json"])
    prog, params = find_model_files(src)
    log("  paddle files:", sorted(os.listdir(src)))
    log("  program:", prog, "| params:", params)
    if not prog or not params:
        log("  !! missing program/params, abort this model"); return None

    # 2) paddle2onnx (dynamic shapes preserved from the inference model; opset 17)
    if not os.path.exists(onnx_path):
        r = run([PADDLE2ONNX,
                 "--model_dir", src,
                 "--model_filename", prog,
                 "--params_filename", params,
                 "--save_file", onnx_path,
                 "--opset_version", "17"])
        if not os.path.exists(onnx_path):
            log("  !! paddle2onnx produced no file"); return None
    log("  onnx:", mb(onnx_path))

    # 3) MNNConvert ONNX -> MNN
    if not os.path.exists(mnn_path):
        r = run([MNNCONVERT, "-f", "ONNX",
                 "--modelFile", onnx_path,
                 "--MNNModel", mnn_path,
                 "--bizCode", "ocr"])
        if not os.path.exists(mnn_path):
            log("  !! MNNConvert produced no file"); return None
    log("  MNN:", mb(mnn_path))
    return mnn_path

def extract_charset():
    """PaddleX embeds the rec charset inline in inference.yml under
    PostProcess.character_dict (a YAML list). Pull it from the mobile rec."""
    import yaml
    log("\n=== charset extraction (rec_mobile inference.yml) ===")
    yml = os.path.join(HERE, "paddle", "rec_mobile", "inference.yml")
    if not os.path.exists(yml):
        log("  !! no inference.yml"); return
    with open(yml, encoding="utf-8") as f:
        cfg = yaml.safe_load(f)
    # locate character_dict anywhere in the nested config
    chars = None
    def walk(o):
        nonlocal chars
        if isinstance(o, dict):
            for k, v in o.items():
                if k.lower() in ("character_dict", "character_dict_list") and isinstance(v, list):
                    chars = v
                walk(v)
        elif isinstance(o, list):
            for v in o: walk(v)
    walk(cfg)
    if not chars:
        log("  !! character_dict not found; keys present:", list(cfg.keys())); return
    keys_path = os.path.join(HERE, "mnn", "keys.txt")
    with open(keys_path, "w", encoding="utf-8") as f:
        f.write("\n".join(str(c) for c in chars))
    hira = sum(1 for c in chars if len(str(c))==1 and "぀" <= c <= "ゟ")
    kata = sum(1 for c in chars if len(str(c))==1 and "゠" <= c <= "ヿ")
    log(f"  keys.txt: {len(chars)} entries, hiragana={hira} katakana={kata}  ({mb(keys_path)})")

def verify(mnn_path):
    try:
        import MNN.expr as F
        d = F.load_as_dict(mnn_path)
        log(f"  verify {os.path.basename(mnn_path)}: load_as_dict OK, {len(d)} vars")
        return True
    except Exception as e:
        log(f"  verify {os.path.basename(mnn_path)}: FAILED {e}")
        return False

def main():
    log("PaddleOCR -> MNN conversion  (paddle2onnx + MNNConvert)")
    results = {}
    for name, repo in MODELS:
        try:
            results[name] = convert(name, repo)
        except Exception:
            log(f"  !! exception converting {name}:\n{traceback.format_exc()}")
            results[name] = None
    try:
        extract_charset()
    except Exception:
        log(f"  !! charset extraction failed:\n{traceback.format_exc()}")

    log("\n=== SUMMARY (real .mnn sizes) ===")
    for name, _ in MODELS:
        p = results.get(name)
        if p and os.path.exists(p):
            log(f"  {name:12} {mb(p):>10}   {p}")
            verify(p)
        else:
            log(f"  {name:12}   FAILED")
    log("\nDONE")
    OUT.close()

if __name__ == "__main__":
    main()
