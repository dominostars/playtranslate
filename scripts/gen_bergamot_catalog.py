#!/usr/bin/env python3
"""
Generate Bergamot (Firefox Translations) catalog entries for langpack_catalog.json.

Mozilla's manifest (db/models.json) only carries an uncompressed hash for the
*model* file, not the vocab/shortlist — so for each direction we download every
file from the public GCS bucket, gunzip it, and compute the uncompressed SHA-256
and size ourselves. The catalog `url` still points at Mozilla's gzipped object
(the app downloads from there at runtime and gunzips via the MultiFile downloader,
verifying against these uncompressed hashes).

We ship the `base-memory` tier (what Firefox Android uses). Most directions ship
one shared `vocab`; the CJK targets (en->ja/zh/ko) ship a split `srcVocab`+`trgVocab`
pair (separate source/target SentencePiece models), which our slimt fork loads via
the Package.target_vocabulary path. Both shapes are emitted with role-named files.

Per direction we also read the model's sibling `metadata.json` and bake an `arch`
object (encoder/decoder layers, heads, ffn depth) into the entry ONLY when it
differs from the base-memory default (6/4/8/2). Base-memory entries omit it and
fall back to the app's constants; a future non-base-memory model gets its real
architecture baked so the engine loads it correctly instead of mis-loading into
fluent garbage.

Usage:
    python3 scripts/gen_bergamot_catalog.py ja-en es-en en-es ...
    python3 scripts/gen_bergamot_catalog.py            # default core set
"""
import json
import sys
import gzip
import hashlib
import os
import urllib.request

BUCKET = "https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data"
MANIFEST_URL = f"{BUCKET}/db/models.json"
MANIFEST_CACHE = "/tmp/live_models.json"
CATALOG = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "langpack_catalog.json")

DEFAULT_DIRS = ["ja-en", "es-en", "en-es"]

# Bergamot "base-memory" architecture (6 enc / 4 dec / 8 heads / ffn 2). Every
# current Mozilla on-device model is this; entries matching it carry NO `arch`
# and use the app's BASE_MEMORY_* fallback. An entry only gets an explicit
# `arch` when its metadata.json reports something different.
BASE_MEMORY_ARCH = {"encoderLayers": 6, "decoderLayers": 4, "feedForwardDepth": 2, "numHeads": 8}


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read()


def load_manifest() -> dict:
    if not os.path.exists(MANIFEST_CACHE):
        with open(MANIFEST_CACHE, "wb") as f:
            f.write(fetch(MANIFEST_URL))
    return json.load(open(MANIFEST_CACHE))


def pick_variant(variants):
    for arch in ("base-memory", "base"):
        for v in variants:
            if v.get("architecture") == arch:
                return v
    return None


def read_arch(model_path: str):
    """Read the transformer arch (enc/dec/heads/ffn) from the model's sibling
    metadata.json on the GCS bucket, in the app's catalog field names. Returns
    None if the metadata can't be fetched/parsed (operator should investigate
    rather than silently assuming base-memory for a non-default model)."""
    meta_url = f"{BUCKET}/{os.path.dirname(model_path)}/metadata.json"
    try:
        mc = json.loads(fetch(meta_url))["modelConfig"]
        arch = {
            "encoderLayers": int(mc["enc-depth"]),
            "decoderLayers": int(mc["dec-depth"]),
            "feedForwardDepth": int(mc["transformer-ffn-depth"]),
            "numHeads": int(mc["transformer-heads"]),
        }
        if min(arch.values()) <= 0:
            raise ValueError(f"non-positive layer count {arch}")
        return arch
    except Exception as e:
        print(f"  WARN  could not read arch from {meta_url}: {e}")
        return None


def build_entry(direction: str, variant: dict):
    files = variant["files"]
    # Vocab roles: most pairs ship one shared `vocab`; CJK en->{ja,zh,ko} ship a
    # split `srcVocab` + `trgVocab`. Mozilla's basenames (vocab.*/srcvocab.*/
    # trgvocab.*.spm) already match the on-device role regexes, so we keep them.
    if "vocab" in files:
        vocab_roles = ["vocab"]
    elif "srcVocab" in files and "trgVocab" in files:
        vocab_roles = ["srcVocab", "trgVocab"]
    else:
        print(f"  SKIP {direction}: no vocab / srcVocab+trgVocab role")
        return None
    entry_files = []
    total = 0
    # Stable order: model, vocab(s), shortlist
    for role in ("model", *vocab_roles, "lexicalShortlist"):
        info = files.get(role)
        if not info:
            print(f"  SKIP {direction}: missing {role}")
            return None
        path = info["path"]
        url = f"{BUCKET}/{path}"
        name = os.path.basename(path)
        if name.endswith(".gz"):
            name = name[:-3]
        raw = gzip.decompress(fetch(url))
        sha = hashlib.sha256(raw).hexdigest()
        size = len(raw)
        total += size
        entry_files.append({"path": name, "url": url, "size": size, "sha256": sha, "gzip": True})
        print(f"  {direction} {role:16s} {name}  {size} bytes")
    entry = {
        "display": f"Firefox Translations {direction}",
        "type": "engine",
        "script": "",
        "bundled": False,
        "packVersion": 1,
        "additiveFromVersion": 1,
        "size": total,
        "files": entry_files,
        "licenses": [{
            "component": f"Firefox Translations model ({direction})",
            "license": "CC-BY-SA-4.0",
            "attribution": "(c) Mozilla / Firefox Translations, https://github.com/mozilla/translations",
        }],
    }
    # Architecture from the model's metadata.json. It's load-bearing: with the
    # wrong layer counts the engine mis-loads the weights into fluent garbage.
    # A failed read means "unknown arch", NOT "base-memory" — and pick_variant
    # can also pick a plain `base` variant (6/2, not 6/4) — so we must NOT emit
    # an entry whose arch we couldn't verify. Skip it, like a missing vocab role.
    arch = read_arch(files["model"]["path"])
    if arch is None:
        print(f"  SKIP {direction}: could not determine architecture from metadata.json")
        return None
    if arch != BASE_MEMORY_ARCH:
        entry["arch"] = arch  # non-default: bake it so the app loads correctly
        print(f"  {direction} arch NON-DEFAULT -> baked {arch}")
    else:
        print(f"  {direction} arch base-memory (default, omitted)")
    return entry


def main():
    dirs = sys.argv[1:] or DEFAULT_DIRS
    manifest = load_manifest()
    models = manifest["models"]
    catalog = json.load(open(CATALOG))
    added = 0
    for d in dirs:
        variants = models.get(d)
        if not variants:
            print(f"  MISSING {d} in manifest")
            continue
        v = pick_variant(variants)
        if not v:
            print(f"  no base/base-memory variant for {d}")
            continue
        entry = build_entry(d, v)
        if entry:
            catalog["packs"][f"bergamot-{d}"] = entry
            added += 1
    with open(CATALOG, "w") as f:
        json.dump(catalog, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"merged {added} bergamot entries into {os.path.relpath(CATALOG)}")


if __name__ == "__main__":
    main()
