#!/usr/bin/env bash
# Overnight pack campaign: ja-v5 (the ke_inf pass) + the Wiktionary source packs
# v3 -> v4 (hi v4 -> v5, it shipped its forms[] upgrade ahead of the fleet).
#
# Two tracks, run CONCURRENTLY because they share nothing but the disk:
#   A. ja  -> a real rebuild at packVersion 5 from a805bceb's build_jmdict.py,
#             which stores JMdict's per-form kanji info tags (headword.ke_inf:
#             sK search-only, rK rare, ...) and reading.uk_applicable.
#   B. 23 Wiktionary sources -> a real from-source rebuild so 72254080's three
#             build-side coverage fixes reach users: the lexeme-AGGREGATE
#             frequency cut (confiscated/besieged/deafening/riveting/larvae/
#             décédé stop vanishing with their whole lexeme), chained redirect
#             resolution (criticised -> criticise -> criticize), and the
#             det/article/postp parts of speech (every, the/a, fr mon/votre,
#             es lo/su/tus, hi को/से).
#
# Unattended, resumable, PER-PACK gated: a language that fails verification is
# simply not uploaded and does not block the others. ja is the one exception --
# if ja fails verification nothing ships for ja, and the whole ja track is
# independent of track B either way.
#
# NOTHING is uploaded until that pack passes verify_pack.py, and nothing reaches
# the catalog until the uploaded asset has been re-downloaded and its SHA-256
# confirmed against the local zip. Every pack in this campaign gets a NEW tag;
# an already-existing tag is a hard skip for that pack (never --clobber: older
# app builds pin the old tags by sha in their bundled catalog, so clobbering one
# breaks fresh installs for them forever).
#
#   nohup bash scripts/rebuild_packs_v5.sh > local/packs-v5/logs/run.log 2>&1 &
#
# This script deliberately stops after the catalog + unit tests. The commit is
# written by hand from local/packs-v5/RESULTS.tsv so its message can state what
# actually happened (which languages shipped, the ke_inf counts, the
# compare-against deltas).
set -uo pipefail

ROOT="/d/translate_app"
REPO="dominostars/playtranslate-langpacks"
BRANCH="ja-v5-keinf"

BUILD="local/packs-v5"
PACKS="$BUILD/packs"          # ja only (track A)
STAGE="$BUILD/stage"
TREE="$BUILD/tree"            # pinned snapshot of the branch's build inputs
LOGS="$BUILD/logs"
CMP="$BUILD/compare"          # currently-shipped zips, for --compare-against
SRCWORK="local/source-v4"
CATALOG="app/src/main/assets/langpack_catalog.json"
RESULTS="$BUILD/RESULTS.tsv"

# The documented build interpreter on this machine. The Mac runbook's
# ~/playtranslate/.venv-pt does not exist here; this Python 3.13 carries
# wordfreq / snowballstemmer / jieba / pythainlp / arramooz and camel-tools
# 1.6.0 installed --no-deps (>= the 1.5.7 floor requirements-ar.txt names;
# 1.5.7 has no distribution for cp313-win_amd64).
PY="python"
export JAVA_HOME="${JAVA_HOME:-$ROOT/.sdk/jdk17}"

SOURCES=(ar ca da de en es fi fr hi hu id it ko nl no pl pt ro ru sv th tr vi)
# Languages that BUILT and would VERIFY, but are deliberately not shipped by this
# run. fi: the rebuild is 906,818,183 bytes against a shipped 3,229,344 -- 281x,
# where the next-largest growth in the fleet is tr at 26x and the largest pack the
# app ships at all is ja at 100 MB. The mechanism is the lexeme-AGGREGATE frequency
# cut (72254080) meeting Finnish morphology: the cut now sums the lemma's frequency
# with every forms[] surface, and Finnish carries ~150 inflected forms per lemma, so
# the sum clears MIN_FREQUENCY for almost every lemma and the cut stops filtering --
# 156,345 entries kept (every other language keeps 10k-35k) x ~152 alias rows each
# = 23.5M alias rows. Nothing is wrong with the DATA; the question is whether a
# ~900 MB pack should be offered to phone users, and burning the fi-v4 tag is not
# reversible. Held for a human call. Its zip is parked in stage/fi-held/.
HOLD=(fi)
SRC_VERSION=4
declare -A SRC_VERSION_OVERRIDE=( [hi]=5 )   # mirrors PACK_VERSION_OVERRIDES
# 5 = the ke_inf pass. Drives --pack-version for build AND verify (the
# verifier's ke_inf gate only runs at >= 5) and the upload tag.
JA_VERSION=5

# --no-build: every pack is already built and on disk; run the gates, the uploads
# and the catalog only. PHASE 1 is otherwise re-entrant, but a HOLD language has no
# zip on disk by design, and re-running the source runner would rebuild it (and
# re-download its extract) rather than skip it.
SKIP_BUILD=0
[ "${1:-}" = "--no-build" ] && SKIP_BUILD=1

cd "$ROOT" || exit 1
mkdir -p "$PACKS" "$STAGE" "$LOGS" "$CMP" "$SRCWORK"

held() { local l; for l in "${HOLD[@]}"; do [ "$l" = "$1" ] && return 0; done; return 1; }

die()  { echo; echo "FATAL: $*" >&2; echo "ABORTED — nothing uploaded, nothing committed." >&2; exit 1; }
step() { echo; echo "──────── $* ────────"; }
now()  { date "+%H:%M:%S"; }
sha()  { sha256sum "$1" | cut -d' ' -f1; }
srcver() { echo "${SRC_VERSION_OVERRIDE[$1]:-$SRC_VERSION}"; }

# lang<TAB>phase<TAB>status<TAB>detail — the machine-readable campaign record.
: > "$RESULTS"
record() { printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "${4:-}" >> "$RESULTS"; }

# ═══════════════════════ PHASE 0 — preflight ═══════════════════════
step "$(now) PHASE 0  preflight"

[ "$(git rev-parse --abbrev-ref HEAD)" = "$BRANCH" ] || die "not on branch $BRANCH"
HEAD_SHA="$(git rev-parse HEAD)"
echo "branch $BRANCH @ $HEAD_SHA"
record _campaign head ok "$HEAD_SHA"

gh auth status >/dev/null 2>&1 || die "gh is not authenticated"
echo "gh: authenticated ($REPO)"

FREE_GB=$(df -k . | awk 'NR==2 {print int($4/1048576)}')
[ "$FREE_GB" -ge 10 ] || die "only ${FREE_GB} GiB free; need >= 10"
echo "disk: ${FREE_GB} GiB free"

# Pin the build inputs. misc_vocabulary.json is read LAZILY (first sense), so a
# branch flip mid-build would silently swap the misc filter under the ja build.
rm -rf "$TREE" && mkdir -p "$TREE"
git archive "$BRANCH" scripts app/src/main/resources/misc_vocabulary.json | tar -x -C "$TREE" \
  || die "could not snapshot the $BRANCH tree"
echo "pinned build inputs at $BRANCH -> $TREE"

# Track A builds ja from $TREE, exactly as the v3 campaign did. Track B runs the
# REPO copy of build_source_packs.py instead, because that script derives its
# work dir from its own location (ROOT = <script>/../..) and running it out of
# $TREE would bury local/source-v4 inside the snapshot. The pin is preserved by
# asserting the working tree carries EXACTLY this campaign's two intended
# changes and nothing else -- a git-status assertion rather than a byte compare
# against $TREE, because core.autocrlf=true means `git archive` emits CRLF for
# files the working tree holds as LF, and the ignored scripts/thor-wifi-connect.sh
# is absent from the archive entirely. Neither is a content difference.
EXPECT_DIRTY=" M scripts/build_source_packs.py
?? scripts/rebuild_packs_v5.sh"
ACTUAL_DIRTY="$(git status --porcelain scripts app/src/main/resources/misc_vocabulary.json | sort)"
if [ "$ACTUAL_DIRTY" != "$(echo "$EXPECT_DIRTY" | sort)" ]; then
  echo "working tree carries unexpected changes under scripts/:"
  echo "$ACTUAL_DIRTY"
  die "build inputs are not pinned"
fi
echo "build inputs pinned: working tree == $BRANCH except build_source_packs.py (PACK_VERSION 3->4, hi->5, WORK source-v4) and this script"

# The comparison baselines: the pack each language's users are running TODAY.
# verify_pack.py --compare-against fails on a material row-count drop, which is
# the only gate that catches a rebuild silently losing data to upstream churn.
# Use a local copy when its sha matches the catalog; otherwise fetch the hosted
# artifact and confirm it against the catalog before trusting it as a baseline.
step "$(now) PHASE 0  stage the shipped packs as comparison baselines"
$PY - "$CATALOG" "$CMP" "${SOURCES[@]}" <<'PYEOF' || die "could not stage the comparison baselines"
import hashlib, json, pathlib, sys, urllib.request
cat = json.load(open(sys.argv[1], encoding="utf-8"))["packs"]
out = pathlib.Path(sys.argv[2]); out.mkdir(parents=True, exist_ok=True)
missing = []
for lang in sys.argv[3:]:
    want = cat[lang]["sha256"]
    dest = out / f"{lang}.zip"
    if dest.is_file() and hashlib.sha256(dest.read_bytes()).hexdigest() == want:
        print(f"  {lang}: baseline v{cat[lang]['packVersion']} already staged"); continue
    url = cat[lang]["url"]
    try:
        with urllib.request.urlopen(url, timeout=180) as r:
            data = r.read()
    except Exception as e:
        missing.append(f"{lang}: {e!r}"); print(f"  {lang}: DOWNLOAD FAILED {e!r}"); continue
    got = hashlib.sha256(data).hexdigest()
    if got != want:
        missing.append(f"{lang}: hosted sha {got[:12]} != catalog {want[:12]}")
        print(f"  {lang}: SHA MISMATCH vs catalog"); continue
    dest.write_bytes(data)
    print(f"  {lang}: baseline v{cat[lang]['packVersion']} fetched ({len(data):,} B)")
if missing:
    # Not fatal: a language with no baseline just loses its regression gate, and
    # the verifier reports that explicitly. Fatal would strand the whole night.
    print("WARNING: no baseline for:", "; ".join(missing))
PYEOF

# ═══════════════════ PHASE 1 — build (A and B in parallel) ═══════════════════
if [ "$SKIP_BUILD" -eq 1 ]; then
  step "$(now) PHASE 1  SKIPPED (--no-build); using the packs already on disk"
  JA_RC=0; SRC_RC=0
  record ja build ok "pre-built"
else
step "$(now) PHASE 1  build"

# ── track A: ja ────────────────────────────────────────────────────────────
build_ja() {
  if [ -f "$PACKS/ja/ja.zip" ]; then echo "[skip] ja already built"; return 0; fi

  # SudachiDict-core -> system.dic (~207 MB). Without it the pack installs,
  # passes JmdictSchemaProbe, and dies at the first Japanese lookup.
  local SUDACHI_DIC="$STAGE/sd_x/sudachidict_core/resources/system.dic"
  if [ ! -f "$SUDACHI_DIC" ]; then
    echo "$(now) staging SudachiDict-core..."
    mkdir -p "$STAGE/sd" "$STAGE/sd_x"
    $PY -m pip download SudachiDict-core --no-deps -d "$STAGE/sd" -q || return 1
    unzip -o -q "$STAGE"/sd/*.whl -d "$STAGE/sd_x" || return 1
  fi
  [ -f "$SUDACHI_DIC" ] || { echo "no system.dic at $SUDACHI_DIC"; return 1; }
  echo "sudachi: $(du -h "$SUDACHI_DIC" | cut -f1)"

  # Tatoeba: the two .tsv stay bz2-compressed; links/jpn_indices MUST be plain
  # .csv (_open_text only decompresses a .bz2 suffix). A wrong name here silently
  # yields a pack with zero examples -- verify_pack.py fails the run if so.
  local TAT="$STAGE/tatoeba"
  mkdir -p "$TAT"
  fetch() { [ -f "$TAT/$1" ] || curl -fL --retry 8 --retry-all-errors -C - -o "$TAT/$1" "$2"; }
  if [ ! -f "$TAT/links.csv" ] || [ ! -f "$TAT/jpn_indices.csv" ] \
     || [ ! -f "$TAT/jpn_sentences.tsv.bz2" ] || [ ! -f "$TAT/eng_sentences.tsv.bz2" ]; then
    echo "$(now) staging Tatoeba (fresh monthly dump)..."
    fetch jpn_sentences.tsv.bz2 https://downloads.tatoeba.org/exports/per_language/jpn/jpn_sentences.tsv.bz2
    fetch eng_sentences.tsv.bz2 https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2
    fetch links.tar.bz2         https://downloads.tatoeba.org/exports/links.tar.bz2
    fetch jpn_indices.tar.bz2   https://downloads.tatoeba.org/exports/jpn_indices.tar.bz2
    [ -f "$TAT/links.tar.bz2" ]       && tar xjf "$TAT/links.tar.bz2"       -C "$TAT"
    [ -f "$TAT/jpn_indices.tar.bz2" ] && tar xjf "$TAT/jpn_indices.tar.bz2" -C "$TAT"
    # Some Tatoeba tarballs nest their payload; flatten so the 4 names are exact.
    for f in links.csv jpn_indices.csv; do
      [ -f "$TAT/$f" ] || find "$TAT" -name "$f" -exec mv {} "$TAT/$f" \;
    done
    rm -f "$TAT"/*.tar.bz2
  fi
  # Fall back to this machine's already-staged April dump only for a file the
  # fresh fetch could not produce -- a pack with stale examples beats no pack.
  for f in jpn_sentences.tsv.bz2 eng_sentences.tsv.bz2 links.csv jpn_indices.csv; do
    if [ ! -f "$TAT/$f" ] && [ -f "local/tatoeba/$f" ]; then
      echo "  tatoeba: $f fetch failed, falling back to local/tatoeba/$f"
      cp "local/tatoeba/$f" "$TAT/$f"
    fi
    [ -f "$TAT/$f" ] || { echo "Tatoeba staging incomplete: $f missing"; return 1; }
  done
  echo "tatoeba: 4/4 files staged"

  echo "$(now) building ja (the long pole; ~1.5-2.5 GB RSS)..."
  $PY "$TREE/scripts/build_jmdict.py" \
      --output "$PACKS/ja" \
      --rebuild-sqlite \
      --sudachi-dic "$SUDACHI_DIC" \
      --sudachi-edition core \
      --tatoeba-dir "$TAT" \
      --pack-version "$JA_VERSION" \
      --app-min-version 9
  return $?
}

( build_ja > "$LOGS/10-ja-build.log" 2>&1; echo $? > "$STAGE/ja.rc" ) &
JA_PID=$!
echo "[track A] ja build started (pid $JA_PID) -> $LOGS/10-ja-build.log"

# ── track B: the 23 Wiktionary sources ─────────────────────────────────────
build_sources() {
  # Pass 1: the runner downloads each kaikki extract, builds, runs that
  # language's SMOKE_FIXTURES, deletes the extract, moves on.
  $PY "scripts/build_source_packs.py" --allow-failures

  # Pass 2: the runner's urllib download carries a 120 s socket timeout, and the
  # multi-GB extracts (fi, en, de, ru, es) trip it on a slow leg. The runbook's
  # remedy is a resumable curl into the exact path the runner then finds and
  # skips -- do that automatically for anything that failed, once per language.
  local failed
  failed=$($PY -c "
import json,pathlib
p=pathlib.Path('$SRCWORK/SUMMARY.json')
print(' '.join(sorted(json.loads(p.read_text())['failed'])) if p.is_file() else '')
")
  [ -z "$failed" ] && return 0
  echo
  echo "$(now) retry pass for: $failed"
  for lang in $failed; do
    local url extract
    url=$($PY -c "
import sys; sys.path.insert(0,'scripts')
import build_source_packs as b; print(b.kaikki_url(b.LANGS['$lang']))
") || continue
    extract="$SRCWORK/kaikki-$lang.jsonl"
    if [ ! -s "$extract" ]; then
      echo "  [curl] $lang <- $url"
      curl -C - --retry 10 --retry-all-errors --speed-limit 1000 --speed-time 60 \
           -fL -o "$extract" "$url" || echo "  [curl] $lang FAILED"
    else
      echo "  [have] $lang extract already on disk ($(du -h "$extract" | cut -f1))"
    fi
    $PY "scripts/build_source_packs.py" --allow-failures --only "$lang"
  done
  return 0
}

( build_sources > "$LOGS/20-sources-build.log" 2>&1; echo $? > "$STAGE/src.rc" ) &
SRC_PID=$!
echo "[track B] source builds started (pid $SRC_PID) -> $LOGS/20-sources-build.log"

wait "$JA_PID";  JA_RC=$(cat "$STAGE/ja.rc" 2>/dev/null || echo 1)
wait "$SRC_PID"; SRC_RC=$(cat "$STAGE/src.rc" 2>/dev/null || echo 1)
echo
echo "$(now) PHASE 1 done — ja rc=$JA_RC, sources rc=$SRC_RC"
[ "$JA_RC" -eq 0 ] && record ja build ok || record ja build FAILED "rc=$JA_RC, see 10-ja-build.log"
fi

# ═══════════════════════ PHASE 2 — verification gate ═══════════════════════
step "$(now) PHASE 2  verify (nothing ships that does not pass)"

VERIFIED=()     # source langs cleared for upload
JA_OK=0

if [ -f "$PACKS/ja/ja.zip" ]; then
  if $PY "scripts/verify_pack.py" --zip "$PACKS/ja/ja.zip" --lang ja \
        --pack-version "$JA_VERSION" --repo-root "$TREE" 2>&1 | tee "$LOGS/30-verify-ja.log"; then
    JA_OK=1; record ja verify ok
  else
    record ja verify FAILED "see 30-verify-ja.log"
  fi
else
  echo "ja: no zip to verify"; record ja verify FAILED "no ja.zip"
fi

for lang in "${SOURCES[@]}"; do
  zip="$SRCWORK/$lang/$lang.zip"
  if held "$lang"; then
    echo "verify $lang — HELD, not shipped by this run (see the HOLD comment)"
    record "$lang" verify HELD "built but deliberately not uploaded"
    continue
  fi
  if [ ! -f "$zip" ]; then
    echo "verify $lang — NOT BUILT"; record "$lang" build FAILED "no zip; see 20-sources-build.log"
    continue
  fi
  record "$lang" build ok
  cmp_arg=()
  [ -f "$CMP/$lang.zip" ] && cmp_arg=(--compare-against "$CMP/$lang.zip")
  if $PY "scripts/verify_pack.py" --zip "$zip" --lang "$lang" \
        --pack-version "$(srcver "$lang")" "${cmp_arg[@]}" 2>&1 | tee "$LOGS/30-verify-$lang.log"; then
    VERIFIED+=("$lang"); record "$lang" verify ok
  else
    record "$lang" verify FAILED "see 30-verify-$lang.log"
  fi
done
echo
echo "$(now) verified sources (${#VERIFIED[@]}/${#SOURCES[@]}): ${VERIFIED[*]:-none}"

# ── PHASE 2b — the two specimen checks the campaign has to report on ───────
step "$(now) PHASE 2b  specimen checks"
if [ "$JA_OK" -eq 1 ]; then
  $PY - "$PACKS/ja/ja.zip" <<'PYEOF' 2>&1 | tee "$LOGS/35-ja-spotcheck.log"
import sqlite3, sys, zipfile, pathlib
# Extract to a persistent scratch dir, not TemporaryDirectory: on Windows the
# cleanup raises WinError 32 if anything is still holding the sqlite handle.
td = pathlib.Path("local/packs-v5/stage/spotcheck"); td.mkdir(parents=True, exist_ok=True)
if True:
    with zipfile.ZipFile(sys.argv[1]) as z: z.extract("dict.sqlite", td)
    c = sqlite3.connect(f"file:{td/'dict.sqlite'}?mode=ro", uri=True)
    print("headword.ke_inf spot check")
    want = {"矢張り": "ateji,rK", "矢張": "sK", "其れとも": "rK",
            "居らっしゃる": "sK", "沢山": "", "何時も": ""}
    for text, expect in want.items():
        rows = c.execute("SELECT ke_inf FROM headword WHERE text=?", (text,)).fetchall()
        got = sorted({r[0] for r in rows})
        mark = "ok  " if got == [expect] else "MISMATCH"
        print(f"  {mark} {text:8} ke_inf={got}  expected [{expect!r}]")
    print("reading.uk_applicable for entry 2260200 (新)")
    for r, u in c.execute("SELECT text, uk_applicable FROM reading WHERE entry_id=2260200"):
        print(f"    {r} = {u}")
    print("ke_inf tag census")
    from collections import Counter
    cnt = Counter()
    for (inf,) in c.execute("SELECT ke_inf FROM headword WHERE ke_inf<>''"):
        for t in inf.split(","):
            cnt[t] += 1
    print("   ", dict(sorted(cnt.items(), key=lambda kv: -kv[1])))
    print("    entries with an sK form:",
          c.execute("SELECT count(DISTINCT entry_id) FROM headword WHERE ke_inf LIKE '%sK%'").fetchone()[0])
    for t in ("entry", "headword", "reading", "sense", "example", "kanjidic"):
        print(f"    {t}: {c.execute(f'SELECT count(*) FROM {t}').fetchone()[0]:,}")
    c.close()
PYEOF
fi

if [ -f "$SRCWORK/en/en.zip" ]; then
  $PY - "$SRCWORK/en/en.zip" "$CMP/en.zip" <<'PYEOF' 2>&1 | tee "$LOGS/36-en-oneself.log"
import sqlite3, sys, zipfile, pathlib
# Source packs keep pos on `sense`, not on `headword` (headword is entry_id /
# position / text). position 0 = the lemma's own row, >0 = an alias row.
def probe(path, label, slot):
    if not pathlib.Path(path).is_file():
        print(f"{label}: no zip"); return
    td = pathlib.Path(f"local/packs-v5/stage/oneself-{slot}"); td.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path) as z: z.extract("dict.sqlite", td)
    c = sqlite3.connect(f"file:{td/'dict.sqlite'}?mode=ro", uri=True)
    rows = c.execute(
        "SELECT h.text, h.position, e.freq_score, "
        "  (SELECT group_concat(s.pos) FROM sense s WHERE s.entry_id = h.entry_id), "
        "  (SELECT s.glosses FROM sense s WHERE s.entry_id = h.entry_id ORDER BY s.position LIMIT 1) "
        "FROM headword h JOIN entry e ON e.id = h.entry_id WHERE h.text='oneself'").fetchall()
    print(f"{label}: {len(rows)} headword row(s) for 'oneself'")
    for r in rows: print("   ", r)
    # Same shape for a pronoun that WAS kept, as a control.
    n = c.execute("SELECT count(*) FROM headword WHERE text='myself'").fetchone()[0]
    print(f"{label}: control 'myself' headword rows = {n}")
    c.close()
probe(sys.argv[2], "shipped en (baseline)", "old")
probe(sys.argv[1], "rebuilt en (v4)", "new")
PYEOF
fi

[ "$(git rev-parse HEAD)" = "$HEAD_SHA" ] || die "HEAD moved during the build"

# ═══════════════ PHASE 3 — upload, then re-download and confirm ═══════════════
step "$(now) PHASE 3  upload to $REPO + confirm the hosted bytes"

NOTES_SRC="Rebuilt from source at packVersion %V%.

Coverage fixes from 72254080 — all three deleted real words from the shipped
pack and none of them could be fixed app-side:

- Lexeme-aggregate frequency cut. wordfreq counts each surface separately, so
  inflection-heavy lexemes concentrate usage in non-citation forms and the
  bare-citation-form cut deleted the lexeme whole — and with the lemma went
  every resolution path for its inflections. confiscated, besieged, deafening,
  riveting, larvae and French décédé resolved to nothing. The cut now tests the
  SUM of the lemma's and its junk-filtered forms[] surfaces.
- Chained redirect resolution. Alias targets can themselves be redirect pages:
  criticised is form_of criticise, which is only altspell_of criticize, so every
  inflection of every alternative-spelling lemma had no row at all. Resolution
  is now a cycle-guarded BFS capped at 3 hops.
- det / article / postp parts of speech. These sat outside CONTENT_POS, which
  silently deleted top-frequency function words with no homograph under a kept
  POS: English \"every\" (zipf 5.8) had NO entry, the/a survived only as their
  adv/prep homographs, French mon/ma/votre/notre/cet and Spanish lo/su/tus were
  absent, and Hindi's postpositions को / से — among the most common words in the
  language — were missing entirely.

Also a fresh kaikki extract. Verified against the previously-published pack: no
material row-count drop in entry / headword / sense / example.

Additive upgrade (additiveFromVersion 1): the previous version stays usable and
the update is optional."

NOTES_JA="Rebuilt at packVersion 5 — the ke_inf pass.

The pack now carries JMdict's per-form kanji info tags (headword.ke_inf) and the
per-reading usually-kana scope (reading.uk_applicable). Until now the builder
copied every JMdict kanji spelling into headword with its ke_pri but dropped
ke_inf, so a search-only form (sK — JMdict's \"lookup key, never display\") and a
rarely-used one (rK — under 3% of the common form) were indistinguishable from
the everyday spelling. Today's JMdict carries ~15.5k sK forms across ~9.3k
entries; 141 entries list an sK form FIRST (居らっしゃる before いらっしゃる,
其れから before それから) and 361 common entries list an rK form first (彼処
before あそこ, お握り before おにぎり) — and every fallback in headword selection
took the first one.

With this pack the app hides search-only spellings, ranks rare ones last, and
narrows the kana-only collapse to the readings a uk sense actually covers (新
shows さら in kana and keeps 新 for にい).

Refreshed JMdict / KANJIDIC2 / Tatoeba; Sudachi core tokenizer.

Additive upgrade (additiveFromVersion 3): ja-v4 installs stay usable — the app
guards every new column — so the update is optional."

# lang tag zip notes -> 0 on success
upload() {
  local lang="$1" tag="$2" zip="$3" notes="$4"
  # NEVER onto an existing tag. Older app builds pin the old tags by sha in
  # their bundled catalog; clobbering one breaks fresh installs for them forever.
  if gh release view "$tag" -R "$REPO" >/dev/null 2>&1; then
    echo "  $tag ALREADY EXISTS — refusing to upload onto a released tag"
    record "$lang" upload FAILED "tag $tag already exists"
    return 1
  fi
  printf '%s
' "$notes" > "$STAGE/notes-$lang.md"
  gh release create "$tag" "$zip" -R "$REPO" --title "$tag" --notes-file "$STAGE/notes-$lang.md" >/dev/null || {
    echo "  $tag: gh release create failed"; record "$lang" upload FAILED "gh release create"; return 1; }

  local url="https://github.com/$REPO/releases/download/$tag/$lang.zip"
  local want got attempt
  want=$(sha "$zip")
  for attempt in 1 2 3 4; do
    got=$(curl -fsL --retry 6 --retry-all-errors --retry-delay 5 "$url" | sha256sum | cut -d' ' -f1)
    [ "$got" = "$want" ] && { echo "  $tag  uploaded + hosted sha confirmed"; record "$lang" upload ok "$tag $want"; return 0; }
    echo "  $tag: hosted sha $got != local $want (attempt $attempt)"
    if [ "$attempt" -eq 2 ]; then
      echo "  $tag: deleting and re-uploading the asset"
      gh release delete-asset "$tag" "$lang.zip" -y -R "$REPO" >/dev/null 2>&1
      gh release upload "$tag" "$zip" -R "$REPO" >/dev/null 2>&1
    fi
    sleep 15
  done
  echo "  $tag: SHA MISMATCH after 4 attempts"
  record "$lang" upload FAILED "hosted sha != local after re-upload"
  return 1
}

UPLOADED=()
if [ "$JA_OK" -eq 1 ]; then
  if upload ja "ja-v$JA_VERSION" "$PACKS/ja/ja.zip" "$NOTES_JA"; then UPLOADED+=(ja); fi
else
  echo "ja: not verified — uploading nothing for ja"
fi
for lang in "${VERIFIED[@]}"; do
  v=$(srcver "$lang")
  if upload "$lang" "$lang-v$v" "$SRCWORK/$lang/$lang.zip" "${NOTES_SRC//%V%/$v}"; then
    UPLOADED+=("$lang")
  fi
done
echo
echo "$(now) uploaded (${#UPLOADED[@]}): ${UPLOADED[*]:-none}"

# ═══════════════════════════ PHASE 4 — catalog ═══════════════════════════
step "$(now) PHASE 4  catalog"
cp "$CATALOG" "$BUILD/catalog.before.json"

CAT_SRC=(); CAT_HI=0; CAT_JA=0
for lang in "${UPLOADED[@]}"; do
  case "$lang" in
    ja) CAT_JA=1 ;;
    hi) CAT_HI=1 ;;
    *)  CAT_SRC+=("$lang") ;;
  esac
done
if [ ${#CAT_SRC[@]} -gt 0 ]; then
  $PY "scripts/finalize_source_catalog.py" --build-dir "$SRCWORK" \
      --pack-version "$SRC_VERSION" --lang "${CAT_SRC[@]}" || die "finalize (sources) failed"
fi
if [ "$CAT_HI" -eq 1 ]; then
  $PY "scripts/finalize_source_catalog.py" --build-dir "$SRCWORK" \
      --pack-version 5 --lang hi || die "finalize (hi) failed"
fi
if [ "$CAT_JA" -eq 1 ]; then
  $PY "scripts/finalize_source_catalog.py" --build-dir "$PACKS" \
      --pack-version "$JA_VERSION" --lang ja || die "finalize (ja) failed"
fi

# finalize rewrites the WHOLE file; prove it touched only what it should, that
# every failed language is ABSENT from the delta, and that additiveFromVersion
# survived (ja 3, sources 1).
$PY - "$BUILD/catalog.before.json" "$CATALOG" "$SRCWORK" "$PACKS" "${UPLOADED[@]}" <<'PYEOF' \
  || die "catalog delta is not what we intended"
import hashlib, json, pathlib, sys
before = json.load(open(sys.argv[1], encoding="utf-8"))
after  = json.load(open(sys.argv[2], encoding="utf-8"))
srcwork, packs = pathlib.Path(sys.argv[3]), pathlib.Path(sys.argv[4])
uploaded = sys.argv[5:]
EXPECT = {l: (5 if l in ("ja", "hi") else 4) for l in uploaded}
ADDITIVE = {"ja": 3}
ALLOWED = {"packVersion", "url", "sha256", "size"}
if set(before) != set(after) or set(before["packs"]) != set(after["packs"]):
    sys.exit("catalog gained or lost top-level keys / pack entries")
changed = {k for k in after["packs"] if after["packs"][k] != before["packs"][k]}
if changed != set(EXPECT):
    sys.exit(f"changed {sorted(changed)}, expected exactly the uploaded set {sorted(EXPECT)}")
for k in sorted(changed):
    diff = {f for f in set(after["packs"][k]) | set(before["packs"][k])
            if after["packs"][k].get(f) != before["packs"][k].get(f)}
    if not diff <= ALLOWED:
        sys.exit(f"{k}: touched disallowed field(s) {sorted(diff - ALLOWED)}")
    e = after["packs"][k]
    if e["packVersion"] != EXPECT[k]:
        sys.exit(f"{k}: packVersion {e['packVersion']} != {EXPECT[k]}")
    if e.get("additiveFromVersion") != ADDITIVE.get(k, 1):
        sys.exit(f"{k}: additiveFromVersion {e.get('additiveFromVersion')} != {ADDITIVE.get(k, 1)}")
    zip_path = (packs if k == "ja" else srcwork) / k / f"{k}.zip"
    data = zip_path.read_bytes()
    if e["sha256"] != hashlib.sha256(data).hexdigest() or e["size"] != len(data):
        sys.exit(f"{k}: catalog sha/size does not match the uploaded zip")
    want_url = ("https://github.com/dominostars/playtranslate-langpacks/releases/download/"
                f"{k}-v{EXPECT[k]}/{k}.zip")
    if e["url"] != want_url:
        sys.exit(f"{k}: unexpected url {e['url']}")
print(f"catalog: exactly {len(changed)} entries changed ({', '.join(sorted(changed))}), "
      f"only {sorted(ALLOWED)}, additiveFromVersion intact, every sha/size/url "
      f"matches an uploaded zip")
PYEOF
record _campaign catalog ok "${#UPLOADED[@]} entries"

# ═══════════════════════════ PHASE 5 — unit tests ═══════════════════════════
step "$(now) PHASE 5  unit tests"
./gradlew :app:testDebugUnitTest --console=plain > "$LOGS/60-tests.log" 2>&1
TEST_RC=$?
if [ "$TEST_RC" -eq 0 ]; then
  echo "unit tests: green"; record _campaign tests ok
else
  tail -60 "$LOGS/60-tests.log"; echo "unit tests: FAILED (rc=$TEST_RC)"; record _campaign tests FAILED "rc=$TEST_RC"
fi

[ "$(git rev-parse HEAD)" = "$HEAD_SHA" ] || die "HEAD moved during the campaign"

step "$(now) DONE"
echo "ja: built=$([ -f "$PACKS/ja/ja.zip" ] && echo yes || echo no) verified=$JA_OK"
echo "sources verified: ${#VERIFIED[@]}/${#SOURCES[@]}"
echo "uploaded: ${#UPLOADED[@]} — ${UPLOADED[*]:-none}"
echo "results: $RESULTS"
echo "NOT committed and NOT pushed — write the commit from $RESULTS."
