#!/usr/bin/env bash
# Ship the entry_id indexes that d2f628cf added to the pack builders but never
# delivered to a pack (buildEntry = 29% of app CPU until a rebuild lands them).
#
# Two tracks:
#   A. 22 Wiktionary sources + zh -> index surgery on the shipped database.
#      d2f628cf is the ONLY commit to have touched their builder (or its inputs)
#      since they were built, and those builders never ANALYZE/VACUUM, so adding
#      the indexes yields a pack logically identical to a fresh build -- without
#      re-downloading 16 GB of kaikki extracts or importing unreviewed upstream
#      content drift into languages that have no smoke-test fixtures.
#   B. ja -> a real rebuild. build_jmdict.py also gained the curated-misc filter
#      (de4e007c) after ja-v3 was built, so ja needs more than an index.
#
# Unattended, resumable, and gated: NOTHING is uploaded until all 24 packs pass
# verify_pack.py, and nothing is committed until the uploads are re-downloaded
# and their hashes confirmed. Never pushes.
#
#   nohup bash scripts/rebuild_packs_overnight.sh > local/packs-v3/logs/run.log 2>&1 &
set -uo pipefail

ROOT="/Users/giladgurantz/playtranslate/playtranslate"
REPO="dominostars/playtranslate-langpacks"
BUILD="$ROOT/local/packs-v3"
PACKS="$BUILD/packs"
STAGE="$BUILD/stage"
TREE="$BUILD/tree"
LOGS="$BUILD/logs"
CATALOG="$ROOT/app/src/main/assets/langpack_catalog.json"
# The documented build venv (docs/building-source-packs.md). build_jmdict.py and
# the two scripts here are stdlib-only, but this is the interpreter the runbook
# prescribes and it carries a current pip for the SudachiDict wheel.
PY="$HOME/playtranslate/.venv-pt/bin/python"

SOURCES=(ar ca da de en es fi fr hi hu id it ko nl no pt ro ru sv th tr vi)
SRC_VERSION=3
# 5 = the ke_inf pass (headword.ke_inf; see project_jmdict_keinf_pack_pass):
# drives --pack-version for build AND verify (the verifier's ke_inf gate only
# runs at >= 5) and the upload tag. Never leave this at a released version
# with a newer build_jmdict.py: the upload would clobber that release in
# place, which older catalogs pin by sha.
JA_VERSION=5
ZH_VERSION=2

cd "$ROOT" || exit 1
mkdir -p "$PACKS" "$STAGE" "$LOGS"

die()  { echo; echo "FATAL: $*" >&2; echo "ABORTED — nothing uploaded, nothing committed." >&2; exit 1; }
step() { echo; echo "──────── $* ────────"; }
now()  { date "+%H:%M:%S"; }

# ═══════════ PHASE 0 — preflight ═══════════
step "$(now) PHASE 0  preflight"

[ "$(git rev-parse --abbrev-ref HEAD)" = "v3" ] || die "not on branch v3"
HEAD_SHA="$(git rev-parse HEAD)"
echo "HEAD $HEAD_SHA"

gh auth status >/dev/null 2>&1 || die "gh is not authenticated"
echo "gh: authenticated"

FREE_GB=$(df -g "$ROOT" | awk 'NR==2 {print $4}')
[ "$FREE_GB" -ge 8 ] || die "only ${FREE_GB} GiB free; need >= 8"
echo "disk: ${FREE_GB} GiB free"

# Pin the build inputs. misc_vocabulary.json is read LAZILY (first sense), so a
# branch flip mid-build would silently swap the misc filter under the ja build.
rm -rf "$TREE" && mkdir -p "$TREE"
git archive v3 scripts app/src/main/resources/misc_vocabulary.json | tar -x -C "$TREE" \
  || die "could not snapshot the v3 tree"
echo "pinned build inputs at v3 -> $TREE"

# The 22 zips we are about to migrate must BE the shipped artifacts.
$PY - "$CATALOG" "${SOURCES[@]}" <<'EOF' || die "a local source zip does not match the catalog sha256"
import hashlib, json, pathlib, sys
cat = json.load(open(sys.argv[1]))["packs"]
bad = []
for lang in sys.argv[2:]:
    p = pathlib.Path(f"local/source-v2/{lang}/{lang}.zip")
    if not p.is_file():
        bad.append(f"{lang}: missing {p}"); continue
    h = hashlib.sha256(p.read_bytes()).hexdigest()
    if h != cat[lang]["sha256"]:
        bad.append(f"{lang}: sha {h[:12]} != catalog {cat[lang]['sha256'][:12]}")
print(f"source zips: {len(sys.argv)-2-len(bad)}/{len(sys.argv)-2} match the catalog sha256")
if bad:
    print("\n".join(bad)); sys.exit(1)
EOF

# zh's input is the hosted artifact (there is no local copy).
ZH_SRC="$STAGE/zh-src/zh.zip"
if [ ! -f "$ZH_SRC" ]; then
  mkdir -p "$STAGE/zh-src"
  ZH_URL=$($PY -c "import json;print(json.load(open('$CATALOG'))['packs']['zh']['url'])")
  echo "fetching hosted zh: $ZH_URL"
  curl -fL --retry 5 --retry-all-errors -o "$ZH_SRC" "$ZH_URL" || die "zh download failed"
fi
$PY - "$CATALOG" "$ZH_SRC" <<'EOF' || die "hosted zh.zip does not match the catalog sha256"
import hashlib, json, sys
cat = json.load(open(sys.argv[1]))["packs"]["zh"]
h = hashlib.sha256(open(sys.argv[2], "rb").read()).hexdigest()
assert h == cat["sha256"], f"sha {h[:12]} != catalog {cat['sha256'][:12]}"
print("zh source: matches the catalog sha256")
EOF

# ═══════════ PHASE 1 — migrate 23 ═══════════
step "$(now) PHASE 1  index surgery (22 sources + zh)"
for lang in "${SOURCES[@]}"; do
  if [ -f "$PACKS/$lang/$lang.zip" ]; then echo "[skip] $lang already built"; continue; fi
  $PY "$ROOT/scripts/add_entry_indexes.py" \
      --zip "local/source-v2/$lang/$lang.zip" --lang "$lang" \
      --pack-version "$SRC_VERSION" --output "$PACKS" || die "$lang migration failed"
done
if [ ! -f "$PACKS/zh/zh.zip" ]; then
  $PY "$ROOT/scripts/add_entry_indexes.py" \
      --zip "$ZH_SRC" --lang zh --pack-version "$ZH_VERSION" --output "$PACKS" \
      || die "zh migration failed"
else
  echo "[skip] zh already built"
fi

# ═══════════ PHASE 2 — rebuild ja ═══════════
step "$(now) PHASE 2  rebuild ja (JMdict + kanjidic2 + Tatoeba + Sudachi)"
if [ -f "$PACKS/ja/ja.zip" ]; then
  echo "[skip] ja already built"
else
  # SudachiDict-core -> system.dic (~207 MB). Without it the pack installs,
  # passes the schema probe, and dies at the first Japanese lookup.
  SUDACHI_DIC="$STAGE/sd_x/sudachidict_core/resources/system.dic"
  if [ ! -f "$SUDACHI_DIC" ]; then
    echo "$(now) staging SudachiDict-core..."
    mkdir -p "$STAGE/sd" "$STAGE/sd_x"
    $PY -m pip download SudachiDict-core --no-deps -d "$STAGE/sd" -q || die "SudachiDict download failed"
    unzip -o -q "$STAGE"/sd/*.whl -d "$STAGE/sd_x" || die "SudachiDict wheel unzip failed"
  fi
  [ -f "$SUDACHI_DIC" ] || die "no system.dic at $SUDACHI_DIC"
  echo "sudachi: $(du -h "$SUDACHI_DIC" | cut -f1)"

  # Tatoeba: the two .tsv stay bz2-compressed; links/jpn_indices MUST be plain
  # .csv (_open_text only decompresses a .bz2 suffix). A wrong name here silently
  # yields a pack with zero examples -- verify_pack.py fails the run if so.
  TAT="$STAGE/tatoeba"
  mkdir -p "$TAT"
  fetch() { [ -f "$TAT/$1" ] || curl -fL --retry 5 --retry-all-errors -C - -o "$TAT/$1" "$2" || die "fetch $1 failed"; }
  if [ ! -f "$TAT/links.csv" ] || [ ! -f "$TAT/jpn_indices.csv" ] \
     || [ ! -f "$TAT/jpn_sentences.tsv.bz2" ] || [ ! -f "$TAT/eng_sentences.tsv.bz2" ]; then
    echo "$(now) staging Tatoeba..."
    fetch jpn_sentences.tsv.bz2 https://downloads.tatoeba.org/exports/per_language/jpn/jpn_sentences.tsv.bz2
    fetch eng_sentences.tsv.bz2 https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2
    fetch links.tar.bz2         https://downloads.tatoeba.org/exports/links.tar.bz2
    fetch jpn_indices.tar.bz2   https://downloads.tatoeba.org/exports/jpn_indices.tar.bz2
    tar xjf "$TAT/links.tar.bz2"       -C "$TAT" || die "links.tar.bz2 untar failed"
    tar xjf "$TAT/jpn_indices.tar.bz2" -C "$TAT" || die "jpn_indices.tar.bz2 untar failed"
    # Some Tatoeba tarballs nest their payload; flatten so the 4 names are exact.
    for f in links.csv jpn_indices.csv; do
      [ -f "$TAT/$f" ] || find "$TAT" -name "$f" -exec mv {} "$TAT/$f" \;
    done
    rm -f "$TAT"/*.tar.bz2
  fi
  for f in jpn_sentences.tsv.bz2 eng_sentences.tsv.bz2 links.csv jpn_indices.csv; do
    [ -f "$TAT/$f" ] || die "Tatoeba staging incomplete: $f missing"
  done
  echo "tatoeba: 4/4 files staged"

  echo "$(now) building ja (this is the long pole; ~1.5-2.5 GB RSS)..."
  $PY "$TREE/scripts/build_jmdict.py" \
      --output "$PACKS/ja" \
      --rebuild-sqlite \
      --sudachi-dic "$SUDACHI_DIC" \
      --sudachi-edition core \
      --tatoeba-dir "$TAT" \
      --pack-version "$JA_VERSION" \
      --app-min-version 9 \
      2>&1 | tee "$LOGS/10-ja-build.log"
  [ "${PIPESTATUS[0]}" -eq 0 ] || die "ja build failed"
fi

# ═══════════ PHASE 3 — verification gate ═══════════
step "$(now) PHASE 3  verify all 24 (nothing ships until this is clean)"
FAILED=()
for lang in "${SOURCES[@]}"; do
  $PY "$ROOT/scripts/verify_pack.py" --zip "$PACKS/$lang/$lang.zip" --lang "$lang" \
      --pack-version "$SRC_VERSION" || FAILED+=("$lang")
done
$PY "$ROOT/scripts/verify_pack.py" --zip "$PACKS/zh/zh.zip" --lang zh \
    --pack-version "$ZH_VERSION" || FAILED+=("zh")
$PY "$ROOT/scripts/verify_pack.py" --zip "$PACKS/ja/ja.zip" --lang ja \
    --pack-version "$JA_VERSION" --repo-root "$TREE" || FAILED+=("ja")

[ ${#FAILED[@]} -eq 0 ] || die "${#FAILED[@]} pack(s) failed verification: ${FAILED[*]}"
echo "$(now) all 24 packs verified"

# Free the ja staging (~1.5 GB) before uploads.
rm -rf "$STAGE/tatoeba" "$STAGE/sd" "$STAGE/sd_x"
echo "freed ja staging; $(df -g "$ROOT" | awk 'NR==2 {print $4}') GiB free"

[ "$(git rev-parse HEAD)" = "$HEAD_SHA" ] || die "HEAD moved during the build"

# ═══════════ PHASE 4 — upload, then re-download and confirm ═══════════
step "$(now) PHASE 4  upload to $REPO + verify the hosted bytes"
NOTES_SRC="Adds idx_headword_entry / idx_reading_entry / idx_sense_entry (d2f628cf).

Dictionary content is unchanged from the previous version — asserted by
comparing row counts and the full sqlite_master schema before and after, with
the three new indexes as the only permitted delta.

Fixes buildEntry's per-entry full table scans: a 5-entry word lookup drops from
~195ms to ~2ms."
NOTES_JA="Rebuilt at packVersion 4.

- entry_id indexes (d2f628cf): buildEntry's per-entry full table scans are gone
  (~195ms -> ~2ms for a 5-entry word).
- curated misc register tags (de4e007c): sense.misc now carries only vocabulary
  terms, not raw freeform s_inf sentences.
- refreshed JMdict / KANJIDIC2 / Tatoeba; Sudachi core tokenizer."

upload() {  # lang tag zip notes
  local lang="$1" tag="$2" zip="$3" notes="$4"
  if gh release view "$tag" -R "$REPO" >/dev/null 2>&1; then
    gh release upload "$tag" "$zip" -R "$REPO" --clobber >/dev/null || return 1
  else
    gh release create "$tag" "$zip" -R "$REPO" --title "$tag" --notes "$notes" >/dev/null || return 1
  fi
  # A wrong sha in the catalog fails that pack's download on every retry,
  # forever. Confirm the bytes GitHub will actually serve. A just-created asset
  # can 404 for a moment, so retry rather than abort the night's run.
  local url="https://github.com/$REPO/releases/download/$tag/$lang.zip"
  local want got
  want=$(shasum -a 256 "$zip" | cut -d' ' -f1)
  got=$(curl -fsL --retry 6 --retry-all-errors --retry-delay 5 "$url" | shasum -a 256 | cut -d' ' -f1)
  [ "$got" = "$want" ] || { echo "  SHA MISMATCH for $tag: hosted $got != local $want"; return 1; }
  echo "  $tag  uploaded + hosted sha confirmed"
}

for lang in "${SOURCES[@]}"; do
  upload "$lang" "$lang-v$SRC_VERSION" "$PACKS/$lang/$lang.zip" "$NOTES_SRC" || FAILED+=("$lang")
done
upload zh "zh-v$ZH_VERSION" "$PACKS/zh/zh.zip" "$NOTES_SRC" || FAILED+=("zh")
upload ja "ja-v$JA_VERSION" "$PACKS/ja/ja.zip" "$NOTES_JA" || FAILED+=("ja")
[ ${#FAILED[@]} -eq 0 ] || die "upload/verify failed for: ${FAILED[*]}"

# ═══════════ PHASE 5 — catalog ═══════════
step "$(now) PHASE 5  catalog"
cp "$CATALOG" "$BUILD/catalog.before.json"
$PY "$ROOT/scripts/finalize_source_catalog.py" --build-dir "$PACKS" \
    --pack-version "$SRC_VERSION" --lang "${SOURCES[@]}" || die "finalize (sources) failed"
$PY "$ROOT/scripts/finalize_source_catalog.py" --build-dir "$PACKS" \
    --pack-version "$JA_VERSION" --lang ja || die "finalize (ja) failed"
$PY "$ROOT/scripts/finalize_source_catalog.py" --build-dir "$PACKS" \
    --pack-version "$ZH_VERSION" --lang zh || die "finalize (zh) failed"

# finalize rewrites the WHOLE file; prove it touched only what it should.
$PY - "$BUILD/catalog.before.json" "$CATALOG" "$PACKS" <<'EOF' || die "catalog delta is not what we intended"
import hashlib, json, pathlib, sys
before = json.load(open(sys.argv[1]))
after  = json.load(open(sys.argv[2]))
packs  = pathlib.Path(sys.argv[3])
EXPECT = {"ja": 4, "zh": 2, **{l: 3 for l in
          "ar ca da de en es fi fr hi hu id it ko nl no pt ro ru sv th tr vi".split()}}
ALLOWED = {"packVersion", "url", "sha256", "size"}
if set(before) != set(after) or set(before["packs"]) != set(after["packs"]):
    sys.exit("catalog gained or lost top-level keys / pack entries")
changed = {k for k in after["packs"] if after["packs"][k] != before["packs"][k]}
if changed != set(EXPECT):
    sys.exit(f"changed {sorted(changed)}, expected {sorted(EXPECT)}")
for k in sorted(changed):
    diff = {f for f in after["packs"][k] if after["packs"][k].get(f) != before["packs"][k].get(f)}
    if not diff <= ALLOWED:
        sys.exit(f"{k}: touched disallowed field(s) {sorted(diff - ALLOWED)}")
    e = after["packs"][k]
    if e["packVersion"] != EXPECT[k]:
        sys.exit(f"{k}: packVersion {e['packVersion']} != {EXPECT[k]}")
    zip_path = packs / k / f"{k}.zip"
    data = zip_path.read_bytes()
    if e["sha256"] != hashlib.sha256(data).hexdigest() or e["size"] != len(data):
        sys.exit(f"{k}: catalog sha/size does not match the built zip")
    if e["url"] != f"https://github.com/dominostars/playtranslate-langpacks/releases/download/{k}-v{EXPECT[k]}/{k}.zip":
        sys.exit(f"{k}: unexpected url {e['url']}")
print(f"catalog: exactly {len(changed)} entries changed, only {sorted(ALLOWED)}, "
      f"every sha/size/url matches an uploaded zip")
EOF

# ═══════════ PHASE 6 — tests, then commit ═══════════
step "$(now) PHASE 6  unit tests"
./gradlew :app:testDebugUnitTest --console=plain > "$LOGS/60-tests.log" 2>&1 \
  || { tail -40 "$LOGS/60-tests.log"; die "unit tests failed (see $LOGS/60-tests.log)"; }
echo "unit tests: green"

step "$(now) PHASE 6  commit (no push)"
git add "$CATALOG" \
        app/src/test/java/com/playtranslate/language/LanguagePackStoreStalenessTest.kt \
        scripts/add_entry_indexes.py scripts/verify_pack.py \
        scripts/rebuild_packs_overnight.sh scripts/build_source_packs.py \
        scripts/requirements.txt
git commit -q -F - <<'EOF'
Ship the entry_id indexes: rebuild every dictionary pack

d2f628cf added idx_headword_entry / idx_reading_entry / idx_sense_entry to the
three pack builders and deferred delivery to "the next rebuild + packVersion
bump of each pack". That rebuild never happened, so every hosted pack still does
three full table scans per entry in buildEntry — 29% of all app CPU during
dictionary-heavy use, and ~195ms for a 5-entry word lookup that should take 2ms.
Pack DBs are opened OPEN_READONLY with no migration path, so the index can only
arrive inside a rebuilt pack.

The 22 Wiktionary sources were rebuilt on 2026-06-26, one day BEFORE d2f628cf
landed; ja-v3 and zh predate it too. For all 23 of those, d2f628cf is the only
commit that has touched their builder or any of its inputs since they were
built, and those builders never ANALYZE/VACUUM — so creating the three indexes
on the shipped database yields a pack logically identical to a fresh build.
add_entry_indexes.py does exactly that and asserts it: row counts and the full
sqlite_master schema are compared before and after, with the three new indexes
as the only permitted delta. This avoids re-downloading 16 GB of kaikki extracts
and importing two weeks of unreviewed upstream drift into en/de/es/fi/ru, none
of which have smoke-test fixtures.

ja is the exception and gets a real rebuild: build_jmdict.py also gained the
curated-misc filter (de4e007c) after ja-v3 was built, so its sense.misc still
carries raw freeform s_inf sentences.

verify_pack.py gates every pack before upload on the things that otherwise fail
silently: the indexes themselves, manifest sizes and hashes (which
validateManifest rejects byte-exactly), ja's tokenizer (absent, the pack
installs, passes JmdictSchemaProbe, then dies at the first Japanese lookup),
ja's example rows (a mis-staged Tatoeba dir degrades to zero while still
stamping the license), and ja's misc curation. Each uploaded asset is
re-downloaded and its hash confirmed against the catalog, because a wrong sha256
there fails that pack's download on every retry, forever.

Every upgrade is additive: sources 2 -> 3 and zh 1 -> 2 (additiveFromVersion 1),
ja 3 -> 4 (additiveFromVersion stays 3). No user is left without a working pack
mid-download.

build_source_packs.py's PACK_VERSION follows the catalog to 3 so the next real
rebuild can't stamp v2 manifests into v3 packs, and jieba is declared — without
it build_zh_dict.py dies on its first entry.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF

step "$(now) DONE"
echo "24/24 packs built, verified, uploaded, sha-confirmed; catalog + tests green; committed (not pushed)."
git --no-pager show --stat HEAD | head -20
