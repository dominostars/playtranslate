# OCR model conversion — PaddleOCR → ONNX → MNN

How the on-device PaddleOCR models that PlayTranslate ships are produced. The app
loads MNN models; PaddleOCR publishes PaddlePaddle *inference* bundles. This pipeline
bridges the two and is the recipe for adding a new recognizer or rebuilding an
existing one.

Covers **PaddleOCR** only (the shared detector + the per-script recognizers). Meiki
(the Japanese `meiki-ja` pack) is a different model family converted separately — not
this pipeline.

## TL;DR

```bash
cd scripts/ocr-model-conversion
python3.9 -m venv venv
venv/bin/pip install -r requirements.txt
venv/bin/python convert_langs.py     # latin + korean recognizers (edit MODELS to add more)
# outputs: mnn/<pack-key>/{rec.mnn, keys.txt}  — then host + catalog (see below)
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

## The two scripts

- **`convert_all.py`** — the original 4 PP-OCRv5 *general* models: `det_mobile`,
  `rec_mobile` (the CJK+JA+EN recognizer = pack `paddle-rec-cjk`), plus the `*_server`
  variants. Outputs flat into `mnn/` (`det_mobile.mnn`, `rec_mobile.mnn`, `keys.txt`, …).
- **`convert_langs.py`** — the per-script recognizers, output into the app's pack layout
  `mnn/<pack-key>/{rec.mnn, keys.txt}`. Edit its `MODELS` dict to add a language.

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

## Provenance / licensing

PP-OCRv5 models © PaddlePaddle Authors, **Apache-2.0**
(<https://github.com/PaddlePaddle/PaddleOCR>). MNN © Alibaba, Apache-2.0. The hosted
repo (`huggingface.co/playtranslate/ocr-models`) carries a README with per-pack
attribution; keep it in sync when adding packs.

## Original working directory

This was first run in `mnn-spike/paddleocr-convert/` (a scratch repo, git-ignored
venv + downloaded models). These scripts are the canonical, durable copy — prefer
running from here.
