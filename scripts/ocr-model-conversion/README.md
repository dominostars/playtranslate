# OCR model conversion → MNN

How the on-device OCR models that PlayTranslate ships are produced. The app's engine
runtime loads **MNN** models; this is the recipe for rebuilding an existing model or
adding a new one. Covers **both** model families the app ships:

- **PaddleOCR** — the shared detector + per-script recognizers (`paddle-rec-*`).
  PaddlePaddle ships *inference* bundles → `paddle2onnx` → `mnnconvert`.
- **Meiki** — the Japanese `meiki-ja` pack (D-FINE detector + horizontal/vertical
  recognizers, LGPL-3.0). Meiki publishes ONNX directly, so it's just `mnnconvert`
  (no Paddle step). See the **Meiki** section below.

Same venv/toolchain for both (`mnnconvert` from the `mnn` pip package).

## TL;DR

```bash
cd scripts/ocr-model-conversion
python3.9 -m venv venv
venv/bin/pip install -r requirements.txt
venv/bin/python convert_langs.py     # PaddleOCR latin + korean recognizers (edit MODELS for more)
venv/bin/python convert_meiki.py     # Meiki meiki-ja pack (det + horizontal/vertical rec)
# outputs under mnn/  — then host + catalog (see below)
```

Everything except the scripts / `requirements.txt` / this README is git-ignored
(the `venv/`, downloaded `paddle/`, intermediate `onnx/`, and output `mnn/` dirs are
all regenerable).

## Validated environment

macOS (Apple Silicon), **Python 3.9.6**. The entire toolchain is pip-installable —
both `paddle2onnx` and `mnnconvert` are console scripts from pip packages, so there is
**no native MNN build to do**. Pinned versions (see `requirements.txt`):

| package | version | role |
|---|---|---|
| `paddlepaddle` | 3.3.1 | loads the Paddle inference model |
| `paddle2onnx` | 2.1.0 | Paddle inference → ONNX (opset 17) |
| `mnn` | 3.5.0 | ONNX → MNN (`mnnconvert` CLI) |
| `onnx` | 1.17.0 | ONNX IR |
| `PyYAML` | 6.0.3 | read the charset out of `inference.yml` |
| `huggingface_hub` | 1.8.0 | download Paddle bundles from the PaddlePaddle HF org |

## The pipeline (per model)

1. **Download** the Paddle inference bundle from `huggingface.co/PaddlePaddle/<repo>`
   (`snapshot_download`, patterns `inference.* *.yml config.json`). PP-OCRv5 ships PIR
   format (`inference.json` + `inference.pdiparams`).
2. **paddle2onnx** → `.onnx` (opset 17, dynamic shapes preserved).
3. **mnnconvert** `-f ONNX --modelFile … --MNNModel … --bizCode ocr` → `.mnn`.
4. **Charset** (recognizers only): `inference.yml` embeds the rec character dict inline
   under `PostProcess.character_dict` — written one entry per line to `keys.txt`.
5. **Verify** the `.mnn` loads via `MNN.expr.load_as_dict` (pymnn).

## The three scripts

- **`convert_all.py`** — the original 4 PP-OCRv5 *general* models: `det_mobile`,
  `rec_mobile` (the CJK+JA+EN recognizer = pack `paddle-rec-cjk`), plus the `*_server`
  variants. Outputs flat into `mnn/` (`det_mobile.mnn`, `rec_mobile.mnn`, `keys.txt`, …).
- **`convert_langs.py`** — the per-script PaddleOCR recognizers, output into the app's
  pack layout `mnn/<pack-key>/{rec.mnn, keys.txt}`. Edit its `MODELS` dict to add a language.
- **`convert_meiki.py`** — the Meiki `meiki-ja` pack (det + horizontal/vertical rec).
  Downloads ONNX from the pinned rtr46 HF repos and `mnnconvert`s each. See **Meiki** below.

## Model → pack mapping

The shared detector is **bundled in the APK**; each source-language *script family* maps
to one recognizer pack (PaddleOCR detection is language-independent).

| PaddleOCR HF repo | app pack key | files | wired? |
|---|---|---|---|
| `PP-OCRv5_mobile_det` | *(bundled)* `assets/ocr/paddle_det.mnn` | det.mnn | yes (APK) |
| `PP-OCRv5_mobile_rec` | `paddle-rec-cjk` | rec.mnn + keys.txt | yes (zh/ja/en) |
| `latin_PP-OCRv5_mobile_rec` | `paddle-rec-latin` | rec.mnn + keys.txt | yes (fr/es/de/…) |
| `korean_PP-OCRv5_mobile_rec` | `paddle-rec-korean` | rec.mnn + keys.txt | yes (ko) |
| `arabic` / `cyrillic` / `devanagari` / `el` / `eslav` / `th` / `ta` / `te` `_PP-OCRv5_mobile_rec` | *(unwired)* | — | no source language uses these yet |

> Naming note: `convert_all.py` emits the CJK recognizer as `rec_mobile.mnn`; the app
> pack expects it as **`rec.mnn`**. Rename on staging. `convert_langs.py` already emits
> `rec.mnn`. The detector `det_mobile.mnn` is staged as `paddle_det.mnn`.

## Adding a new recognizer (end-to-end)

1. **Convert**: add `"paddle-rec-<script>": "PaddlePaddle/<script>_PP-OCRv5_mobile_rec"`
   to `convert_langs.py`'s `MODELS`, then `venv/bin/python convert_langs.py`.
2. **Host**: upload to the models repo (per-pack subdir):
   ```bash
   hf upload playtranslate/ocr-models mnn/paddle-rec-<script> paddle-rec-<script> --repo-type model
   ```
   (The `hf` CLI = `pip install huggingface_hub`; authenticate with `hf auth login`.)
3. **Catalog**: add a `type:"ocr"` entry to `app/src/main/assets/langpack_catalog.json`
   with real `size` + `sha256` per file (`shasum -a 256 <file>`, `stat -f%z <file>`),
   top-level `size` = sum of file sizes. `url` =
   `https://huggingface.co/playtranslate/ocr-models/resolve/main/<pack-key>/<path>`.
   Entries with placeholder/missing sha are treated as *not shippable* and the engine
   is hidden (`OcrPackModelHelper.isShippable` / `OcrModelManager.availableBackends`).
4. **Wire** (only if a new source language / script family is involved): map it in
   `SourceLanguageProfile.ocrBackends` (`app/.../language/Language.kt`) to
   `OcrBackend.Paddle("paddle-rec-<script>")`. The reconcile planner, downloader, and
   Settings → OCR section pick it up automatically from the pack key.
5. **Verify**: anonymous download + sha match before shipping, e.g.
   `curl -sL <resolve-url> | shasum -a 256`.

## Meiki (`meiki-ja`)

A different model family from PaddleOCR: a D-FINE object detector + MobileNetV4 backbone
that reframes recognition as **character detection**. Japanese-only, trained on video-game
text. `convert_meiki.py` handles it end-to-end (ONNX → MNN; no Paddle step).

**Source (pinned):** `github.com/rtr46/meikiocr`; weights on HuggingFace, **LGPL-3.0**:

| HF repo @ revision | ONNX file | → pack file |
|---|---|---|
| `rtr46/meiki.text.detect.v0` @ `a9cffa4f` | `meiki.text.detect.v0.1.960x544.onnx` | `det.mnn` |
| `rtr46/meiki.txt.recognition.v0` @ `a28cf587` | `meiki.text.rec.v0.960x32.onnx` | `rec_horizontal.mnn` |
| `rtr46/meiki.txt.recognition.v0` @ `a28cf587` | `meiki.text.rec.v0.vertical.32x480.onnx` | `rec_vertical.mnn` |

(The recognition repo was re-trained 2026-02-21; the prior checkpoint is revision
`ddd06176a4da56fba082293dbe9898d4e5998af2`.)

**No charset file.** Meiki emits Unicode codepoints directly (`char_codes` → `chr(code)`),
so `meiki-ja` is just the three `.mnn` files — no `keys.txt` (unlike the Paddle packs).

**I/O contract** (implemented by `MeikiSession`/`MeikiDetector`/`MeikiRecognizer`, mirrored
from each repo's `inference.py`):
- inputs: `images` (NCHW float32, **BGR**, `/255`, aspect-resized + zero-padded to the
  model size — 960×544 det, 960×32 horizontal rec, 32×480 vertical rec) and
  `orig_target_sizes` — **must be int32 `[W,H]`**; int64 silently zeroes the height
  scaling (boxes collapse to y=0, reading order scrambles, recognition still correct).
- outputs: `char_codes`, `boxes`, `scores`. post: confidence filter → overlap-dedup →
  positional sort → join.

Verified: re-converting the pinned ONNX with the documented toolchain reproduces the
shipped `.mnn` to within 4 bytes (header metadata only), and the bake-off confirmed
MNN == ONNX with 0 output diffs. The exact source ONNX + upstream `inference.py` /
`README.md` that produced the shipped pack are archived at `mnn-spike/meiki-convert/`
(local safety copy; also re-downloadable from the pinned revisions above).

Host + catalog like a Paddle pack (`type:"ocr"` entry, `files` = the 3 `.mnn`, no keys);
already wired in `Language.kt` (`JA → OcrBackend.Meiki("meiki-ja")`).

## Provenance / licensing

- **PaddleOCR** PP-OCRv5 models © PaddlePaddle Authors, **Apache-2.0**
  (<https://github.com/PaddlePaddle/PaddleOCR>).
- **Meiki** © rtr46, **LGPL-3.0** (<https://github.com/rtr46/meikiocr>) — copyleft; keep
  the attribution on the hosted repo and in the app's open-source licenses.
- **MNN** runtime/format © Alibaba, **Apache-2.0**.

The hosted repo (`huggingface.co/playtranslate/ocr-models`) carries a README with
per-pack attribution; keep it in sync when adding packs.

## Original working directory

This was first run in `mnn-spike/paddleocr-convert/` (a scratch repo, git-ignored
venv + downloaded models). These scripts are the canonical, durable copy — prefer
running from here.
