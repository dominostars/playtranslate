#!/usr/bin/env bash
# v5c: three builder changes, all 23 source packs rebuilt, and the (still
# unreleased) v4/v5 assets overwritten again.
#
#   1. FORM_TAG_BLOCKLIST gains "class", wiktextract's inflection-CLASS label.
#      fi "38/nainen" is a Kotus declension type plus its model word, not a word
#      form, and wordfreq TOKENIZES it — so every Finnish lemma inherited the
#      model word's corpus frequency and cleared MIN_FREQUENCY on its own
#      rarity. 139,305 of fi's 139,717 spurious lexemes, and is_common 966 ->
#      5,934. Filtered by TAG, never by shape: a "/" rule would eat real English
#      forms like "3/4 sister" and "1/sgt".
#   2. A redirect page that pass 2 cannot alias onto is now kept as a lemma
#      carrying its own redirect gloss instead of vanishing. Recovers hi's 34
#      lost nuqta-less spellings (the COMMON spelling, redirecting to a rarer
#      nuqta-bearing lemma the frequency cut had dropped) and the 279 words the
#      same hole cost across the fleet.
#   3. The v5b top-10 aggregate cap is REVERTED: measured a no-op on en/tr/hu
#      and on fi, and built on a diagnosis that change 1 replaces.
#
# ja-v5 is untouched and not rebuilt; zh, target, OCR and engine packs untouched.
# Overwriting stays safe because nothing from v5/v5b has reached users.
#
#   nohup bash scripts/rebuild_packs_v5c.sh > local/packs-v5c/logs/run.log 2>&1 &
set -uo pipefail

ROOT="/d/translate_app"
REPO="dominostars/playtranslate-langpacks"
BRANCH="ja-v5-keinf"
BASE_SHA="e16e1b5298c4df2d5d24c8707ec2bac05e6936c7"

BUILD="local/packs-v5c"
STAGE="$BUILD/stage"
TREE="$BUILD/tree"
LOGS="$BUILD/logs"
V3="local/packs-v5/compare"     # the packs users actually run (v3; hi v4)
V4REF="$BUILD/v4ref"            # last night's v4, for the did-we-lose-anything delta
SRCWORK="local/source-v4c"
CATALOG="app/src/main/assets/langpack_catalog.json"
PRODCAT="local/packs-v5/catalog.before.json"   # the pre-v5-campaign (released) catalog
RESULTS="$BUILD/RESULTS.tsv"
PY="python"
export PYTHONIOENCODING=utf-8   # stdout is cp1252 here; builders print non-Latin-1 on failure paths
export JAVA_HOME="${JAVA_HOME:-$ROOT/.sdk/jdk17}"

SOURCES=(ar ca da de en es fi fr hi hu id it ko nl no pl pt ro ru sv th tr vi)
SRC_VERSION=4
declare -A SRC_VERSION_OVERRIDE=( [hi]=5 )

# Tags this run may OVERWRITE (no released catalog references them).
CLOBBER_OK=(ar-v4 ca-v4 da-v4 de-v4 en-v4 es-v4 fi-v4 fr-v4 hu-v4 id-v4 it-v4 ko-v4
            nl-v4 no-v4 pl-v4 pt-v4 ro-v4 ru-v4 sv-v4 th-v4 tr-v4 vi-v4 hi-v5)
# Nothing is created this round: v5b made es-v4 and fi-v4, and hi-v5 has carried
# an asset since the v5 run. An EMPTY create list means a tag that unexpectedly
# does not exist is refused rather than invented.
CREATE_OK=()

# Fix B safety gate: a language whose newly-dropped lemma count exceeds this
# fraction of its previously-kept lemmas is reported, not uploaded.
REDIRECT_DROP_CEILING=0.02

cd "$ROOT" || exit 1
mkdir -p "$STAGE" "$LOGS" "$V4REF" "$SRCWORK"

die()  { echo; echo "FATAL: $*" >&2; echo "ABORTED — nothing uploaded, nothing committed." >&2; exit 1; }
step() { echo; echo "──────── $* ────────"; }
now()  { date "+%H:%M:%S"; }
sha()  { sha256sum "$1" | cut -d' ' -f1; }
srcver() { echo "${SRC_VERSION_OVERRIDE[$1]:-$SRC_VERSION}"; }
in_list() { local n="$1"; shift; local x; for x in "$@"; do [ "$x" = "$n" ] && return 0; done; return 1; }

: > "$RESULTS"
record() { printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "${4:-}" >> "$RESULTS"; }

SKIP_BUILD=0
[ "${1:-}" = "--no-build" ] && SKIP_BUILD=1

# ═══════════════════════ PHASE 0 — preflight ═══════════════════════
step "$(now) PHASE 0  preflight"

[ "$(git rev-parse --abbrev-ref HEAD)" = "$BRANCH" ] || die "not on branch $BRANCH"
HEAD_SHA="$(git rev-parse HEAD)"
git merge-base --is-ancestor "$BASE_SHA" HEAD || die "HEAD is not $BASE_SHA or a descendant"
echo "branch $BRANCH @ $HEAD_SHA (descendant of ${BASE_SHA:0:8})"
record _campaign head ok "$HEAD_SHA"

gh auth status >/dev/null 2>&1 || die "gh is not authenticated"
FREE_GB=$(df -k . | awk 'NR==2 {print int($4/1048576)}')
[ "$FREE_GB" -ge 10 ] || die "only ${FREE_GB} GiB free; need >= 10"
echo "gh: authenticated · disk: ${FREE_GB} GiB free"

# Pin the build inputs. The three builder fixes are uncommitted working-tree
# edits at this point, so the snapshot is taken from the WORKING TREE, not from
# git archive — and the pin is the assertion that nothing else is dirty.
EXPECT_DIRTY=" M scripts/build_latin_dict.py
 M scripts/build_source_packs.py
?? scripts/rebuild_packs_v5c.sh
?? scripts/test_redirect_rescue.py"
ACTUAL_DIRTY="$(git status --porcelain scripts app/src/main/resources/misc_vocabulary.json | sort)"
if [ "$ACTUAL_DIRTY" != "$(echo "$EXPECT_DIRTY" | sort)" ]; then
  echo "unexpected working-tree state under scripts/:"; echo "$ACTUAL_DIRTY"
  die "build inputs are not pinned"
fi
rm -rf "$TREE" && mkdir -p "$TREE/scripts" "$TREE/app/src/main/resources"
cp scripts/*.py "$TREE/scripts/" || die "snapshot failed"
cp app/src/main/resources/misc_vocabulary.json "$TREE/app/src/main/resources/" || die "snapshot failed"
echo "build inputs pinned -> $TREE (3 intended edits, nothing else dirty)"

# The v3 baselines must BE what production serves. Assert against the released
# catalog, not the branch's (which this campaign is rewriting).
$PY - "$PRODCAT" "$V3" "${SOURCES[@]}" <<'PYEOF' || die "a v3 baseline does not match the released catalog"
import hashlib, json, pathlib, sys
cat = json.load(open(sys.argv[1], encoding="utf-8"))["packs"]
out = pathlib.Path(sys.argv[2])
bad = []
for lang in sys.argv[3:]:
    p = out / f"{lang}.zip"
    if not p.is_file():
        bad.append(f"{lang}: missing {p}"); continue
    if hashlib.sha256(p.read_bytes()).hexdigest() != cat[lang]["sha256"]:
        bad.append(f"{lang}: sha != released catalog")
print(f"v3 baselines: {len(sys.argv)-3-len(bad)}/{len(sys.argv)-3} match the released catalog")
if bad: print("\n".join(bad)); sys.exit(1)
PYEOF

# Last night's v4 assets, for the second (did-the-fixes-take-only-what-they-should)
# delta. es never shipped a v4; fi's was held locally.
step "$(now) PHASE 0  stage the CURRENTLY HOSTED v5b assets as the reference delta"
for lang in "${SOURCES[@]}"; do
  [ -s "$V4REF/$lang.zip" ] && { echo "  $lang: v4 ref already staged"; continue; }
  v=$(srcver "$lang")
  curl -fsL --retry 5 --retry-all-errors -o "$V4REF/$lang.zip" \
      "https://github.com/$REPO/releases/download/$lang-v$v/$lang.zip" \
    && echo "  $lang: v4 ref fetched ($(stat -c%s "$V4REF/$lang.zip") B)" \
    || echo "  $lang: v4 ref UNAVAILABLE (delta will be skipped)"
done

# ═══════════════════════ PHASE 1 — build ═══════════════════════
if [ "$SKIP_BUILD" -eq 1 ]; then
  step "$(now) PHASE 1  SKIPPED (--no-build)"
else
step "$(now) PHASE 1  build all 23 source packs"
$PY "scripts/build_source_packs.py" --allow-failures 2>&1 | tee "$LOGS/20-sources-build.log"

# Retry anything that failed, fetching its extract with a resumable curl first
# (the runner's urllib carries a 120 s socket timeout that the multi-GB extracts
# trip on a slow leg).
failed=$($PY -c "
import json,pathlib
p=pathlib.Path('$SRCWORK/SUMMARY.json')
print(' '.join(sorted(json.loads(p.read_text())['failed'])) if p.is_file() else '')
")
if [ -n "$failed" ]; then
  echo; echo "$(now) retry pass for: $failed"
  for lang in $failed; do
    extract="$SRCWORK/kaikki-$lang.jsonl"
    if [ ! -s "$extract" ]; then
      url=$($PY -c "
import sys; sys.path.insert(0,'scripts')
import build_source_packs as b; print(b.kaikki_url(b.LANGS['$lang']))
") && curl -C - --retry 10 --retry-all-errors --speed-limit 1000 --speed-time 60 \
             -fL -o "$extract" "$url"
    fi
    $PY "scripts/build_source_packs.py" --allow-failures --only "$lang" 2>&1 | tee -a "$LOGS/20-sources-build.log"
  done
fi
fi

# ═══════════════════ PHASE 2 — verify + the two deltas ═══════════════════
step "$(now) PHASE 2  verify vs v3, delta vs v4, redirect audit"
VERIFIED=()
for lang in "${SOURCES[@]}"; do
  zip="$SRCWORK/$lang/$lang.zip"
  if [ ! -f "$zip" ]; then
    echo "verify $lang — NOT BUILT"; record "$lang" build FAILED "no zip; see 20-sources-build.log"
    continue
  fi
  record "$lang" build ok "$(stat -c%s "$zip")"
  if ! $PY "scripts/verify_pack.py" --zip "$zip" --lang "$lang" \
        --pack-version "$(srcver "$lang")" --compare-against "$V3/$lang.zip" \
        2>&1 | tee "$LOGS/30-verify-$lang.log"; then
    record "$lang" verify FAILED "see 30-verify-$lang.log"
    continue
  fi
  # Second gate: against last night's v4. Fix A should move nothing outside fi,
  # Fix B should remove only the redirect pages it can name, so an unexplained
  # collapse here is a stop even though the v3 gate passed.
  if [ -s "$V4REF/$lang.zip" ]; then
    if $PY "$BUILD/redirect_audit.py" --lang "$lang" --old "$V4REF/$lang.zip" --new "$zip" \
          --ceiling "$REDIRECT_DROP_CEILING" 2>&1 | tee "$LOGS/40-audit-$lang.log"; then
      VERIFIED+=("$lang"); record "$lang" verify ok
    else
      record "$lang" verify FAILED "redirect audit over ceiling; see 40-audit-$lang.log"
    fi
  else
    echo "  (no v4 reference for $lang — v3 gate only)"
    VERIFIED+=("$lang"); record "$lang" verify ok "no v4 ref"
  fi
done
echo; echo "$(now) verified (${#VERIFIED[@]}/${#SOURCES[@]}): ${VERIFIED[*]:-none}"
[ "$(git rev-parse HEAD)" = "$HEAD_SHA" ] || die "HEAD moved during the build"

# ═══════════════════ PHASE 3 — upload ═══════════════════
step "$(now) PHASE 3  upload + confirm the hosted bytes"
NOTES="Rebuilt from source at packVersion %V% with three builder fixes.

- Lexeme-aggregate frequency cut now sums only the ten most frequent forms[]
  surfaces. Summing every surface stopped the cut from cutting for languages
  with both a large wordfreq list and many inflected forms per lemma.
- Redirect detection now also fires on a form-of TAG and on a prose form-of
  gloss, with the target read back out of the gloss. Pages glossed \"apocopic
  form of suyo\" with no structured field used to survive as their own lemma and
  shadow the real word.
- Finnish only: an inflected form earns an alias row when wordfreq has actually
  seen it. The Snowball stem row remains the fallback for the rest.

Plus the coverage fixes this version already carried: the lexeme-aggregate cut
itself (confiscated / besieged / deafening / larvae / décédé), chained redirect
resolution (criticised -> criticise -> criticize), and the det / article / postp
parts of speech (every, the/a, fr mon/votre, es lo/su/tus, hi को/से).

Verified against the previously-published pack: no material row-count drop in
entry / headword / sense / example.

Additive upgrade (additiveFromVersion 1): the previous version stays usable and
the update is optional."

upload() {
  local lang="$1" tag="$2" zip="$3" notes="$4"
  printf '%s\n' "$notes" > "$STAGE/notes-$lang.md"
  if in_list "$tag" "${CLOBBER_OK[@]}"; then
    gh release view "$tag" -R "$REPO" >/dev/null 2>&1 || {
      echo "  $tag is on the overwrite list but does not exist — refusing to guess"
      record "$lang" upload FAILED "clobber target missing"; return 1; }
    gh release upload "$tag" "$zip" -R "$REPO" --clobber >/dev/null || {
      echo "  $tag: gh release upload failed"; record "$lang" upload FAILED "upload"; return 1; }
  elif in_list "$tag" "${CREATE_OK[@]}"; then
    if gh release view "$tag" -R "$REPO" >/dev/null 2>&1; then
      echo "  $tag already exists but is on the CREATE list — refusing to overwrite"
      record "$lang" upload FAILED "unexpected existing tag"; return 1
    fi
    gh release create "$tag" "$zip" -R "$REPO" --title "$tag" --notes-file "$STAGE/notes-$lang.md" >/dev/null || {
      echo "  $tag: gh release create failed"; record "$lang" upload FAILED "create"; return 1; }
  else
    # Every -v3 tag, hi-v4, ja-v4, ja-v5, zh-v2 land here. Released catalogs pin
    # them by sha; touching one breaks fresh installs for those builds forever.
    echo "  REFUSING $tag — not on the overwrite or create allowlist"
    record "$lang" upload REFUSED "$tag not allowlisted"; return 1
  fi

  local url="https://github.com/$REPO/releases/download/$tag/$lang.zip"
  local want got attempt
  want=$(sha "$zip")
  for attempt in 1 2 3 4; do
    got=$(curl -fsL --retry 6 --retry-all-errors --retry-delay 5 "$url" | sha256sum | cut -d' ' -f1)
    [ "$got" = "$want" ] && { echo "  $tag  uploaded + hosted sha confirmed"; record "$lang" upload ok "$tag $want"; return 0; }
    echo "  $tag: hosted sha $got != local $want (attempt $attempt)"
    if [ "$attempt" -eq 2 ]; then
      gh release delete-asset "$tag" "$lang.zip" -y -R "$REPO" >/dev/null 2>&1
      gh release upload "$tag" "$zip" -R "$REPO" >/dev/null 2>&1
    fi
    sleep 15
  done
  echo "  $tag: SHA MISMATCH after 4 attempts"; record "$lang" upload FAILED "hosted sha"; return 1
}

UPLOADED=()
for lang in "${VERIFIED[@]}"; do
  v=$(srcver "$lang")
  if upload "$lang" "$lang-v$v" "$SRCWORK/$lang/$lang.zip" "${NOTES//%V%/$v}"; then
    UPLOADED+=("$lang")
  fi
done
echo; echo "$(now) uploaded (${#UPLOADED[@]}): ${UPLOADED[*]:-none}"

# ═══════════════════ PHASE 4 — catalog ═══════════════════
step "$(now) PHASE 4  catalog"
cp "$CATALOG" "$BUILD/catalog.before.json"
CAT_SRC=(); CAT_HI=0
for lang in "${UPLOADED[@]}"; do
  if [ "$lang" = "hi" ]; then CAT_HI=1; else CAT_SRC+=("$lang"); fi
done
[ ${#CAT_SRC[@]} -gt 0 ] && { $PY "scripts/finalize_source_catalog.py" --build-dir "$SRCWORK" \
    --pack-version "$SRC_VERSION" --lang "${CAT_SRC[@]}" || die "finalize (sources) failed"; }
[ "$CAT_HI" -eq 1 ] && { $PY "scripts/finalize_source_catalog.py" --build-dir "$SRCWORK" \
    --pack-version 5 --lang hi || die "finalize (hi) failed"; }

$PY - "$BUILD/catalog.before.json" "$CATALOG" "$SRCWORK" "${UPLOADED[@]}" <<'PYEOF' \
  || die "catalog delta is not what we intended"
import hashlib, json, pathlib, sys
before = json.load(open(sys.argv[1], encoding="utf-8"))
after  = json.load(open(sys.argv[2], encoding="utf-8"))
srcwork = pathlib.Path(sys.argv[3])
uploaded = sys.argv[4:]
# es and fi were still at v3 in the branch catalog, so they also move
# packVersion and url. Everything already at v4/v5 moves only sha256 + size.
# v5b already moved every entry to its final packVersion and url, so this run
# may only refresh the bytes. A packVersion or url move here would mean the
# catalog and the uploaded tag had drifted apart.
BUMPED: set = set()
ALLOWED_BUMPED = {"packVersion", "url", "sha256", "size"}
ALLOWED_SAME = {"sha256", "size"}
if set(before) != set(after) or set(before["packs"]) != set(after["packs"]):
    sys.exit("catalog gained or lost top-level keys / pack entries")
changed = {k for k in after["packs"] if after["packs"][k] != before["packs"][k]}
if not changed <= set(uploaded):
    sys.exit(f"changed entries not uploaded: {sorted(changed - set(uploaded))}")
for k in sorted(changed):
    b, a = before["packs"][k], after["packs"][k]
    diff = {f for f in set(a) | set(b) if a.get(f) != b.get(f)}
    allowed = ALLOWED_BUMPED if k in BUMPED else ALLOWED_SAME
    if not diff <= allowed:
        sys.exit(f"{k}: touched disallowed field(s) {sorted(diff - allowed)}")
    want_v = 5 if k == "hi" else 4
    if a["packVersion"] != want_v:
        sys.exit(f"{k}: packVersion {a['packVersion']} != {want_v}")
    if a.get("additiveFromVersion") != b.get("additiveFromVersion"):
        sys.exit(f"{k}: additiveFromVersion moved")
    data = (srcwork / k / f"{k}.zip").read_bytes()
    if a["sha256"] != hashlib.sha256(data).hexdigest() or a["size"] != len(data):
        sys.exit(f"{k}: catalog sha/size does not match the uploaded zip")
    want_url = ("https://github.com/dominostars/playtranslate-langpacks/releases/download/"
                f"{k}-v{want_v}/{k}.zip")
    if a["url"] != want_url:
        sys.exit(f"{k}: unexpected url {a['url']}")
if after["packs"]["ja"] != before["packs"]["ja"]:
    sys.exit("ja was modified")
print(f"catalog: {len(changed)} entries changed ({', '.join(sorted(changed))}); "
      f"es/fi moved packVersion+url, the rest only sha256+size; "
      f"additiveFromVersion intact; ja and every other pack untouched")
PYEOF
record _campaign catalog ok "${#UPLOADED[@]} entries"

# ═══════════════════ PHASE 5 — tests ═══════════════════
step "$(now) PHASE 5  tests"
for t in test_redirect_rescue test_redirect_detection test_alias_chains test_misc_filter; do
  (cd scripts && $PY "$t.py") > "$LOGS/50-$t.log" 2>&1 \
    && echo "python $t: ok" || { tail -20 "$LOGS/50-$t.log"; record _campaign pytests FAILED "$t"; }
done
./gradlew :app:testDebugUnitTest --console=plain > "$LOGS/60-tests.log" 2>&1
if [ $? -eq 0 ]; then echo "unit tests: green"; record _campaign tests ok
else tail -60 "$LOGS/60-tests.log"; echo "unit tests: FAILED"; record _campaign tests FAILED; fi

[ "$(git rev-parse HEAD)" = "$HEAD_SHA" ] || die "HEAD moved during the campaign"
step "$(now) DONE"
echo "verified ${#VERIFIED[@]}/${#SOURCES[@]} · uploaded ${#UPLOADED[@]} — ${UPLOADED[*]:-none}"
echo "NOT committed and NOT pushed — write the commit from $RESULTS."
