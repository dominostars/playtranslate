#!/usr/bin/env python3
"""Shared Arabic text specs for the dictionary build.

Dependency-free on purpose: both build_latin_dict.py and the heavy
arabic_morphology.py (CAMeL Tools) import these without pulling any third-party
package, so the spec stays the single source of truth across passes.

Two specs, each mirrored by a Kotlin counterpart and pinned by a golden fixture
so build-time keys and runtime lookup keys agree character-for-character (drift
= silently-missed lookups):

    arabic_normalize  <->  ArabicNormalize.normalize  (display key; strip only)
    arabic_fold       <->  ArabicFold.fold            (lookup-only fold key)

ArabicNormalize.kt / ArabicFold.kt live in
app/src/main/java/com/playtranslate/language/.
"""

from __future__ import annotations

import unicodedata


def arabic_normalize(word: str) -> str:
    """NFKC + strip tashkeel (U+064B..U+065F), superscript alef (U+0670), and
    tatweel/kashida (U+0640). Letter identities are PRESERVED — no fold of
    ة->ه / ى->ي / أ->ا — because the normalized form doubles as the displayed
    position-0 lemma, and folding would corrupt the shown spelling. Casual
    letter-variant spellings are handled separately by [arabic_fold]."""
    s = unicodedata.normalize("NFKC", word)
    out = []
    for ch in s:
        o = ord(ch)
        if 0x064B <= o <= 0x065F or o == 0x0670 or o == 0x0640:
            continue  # strip tashkeel / combining marks, superscript alef, tatweel
        out.append(ch)
    return "".join(out)


# Letter folds applied ON TOP of arabic_normalize to build the lookup-only fold
# key. Matches Lucene's ArabicNormalizer letter set, plus alef wasla (U+0671).
_ARABIC_FOLD_MAP = {
    "أ": "ا",  # أ alef hamza above  -> ا alef
    "إ": "ا",  # إ alef hamza below  -> ا alef
    "آ": "ا",  # آ alef madda        -> ا alef
    "ٱ": "ا",  # ٱ alef wasla        -> ا alef
    "ة": "ه",  # ة teh marbuta       -> ه heh
    "ى": "ي",  # ى alef maksura      -> ي yeh
}


def arabic_fold(word: str) -> str:
    """letterFold o arabic_normalize — a SEPARATE internal lookup key that
    tolerates casual / variant spellings (hamza dropped, ة written as ه, ى as
    ي). Stored as position-3 headword rows and tried as a fallback AFTER the
    canonical surface/stem queries miss. NEVER used as a display form."""
    normalized = arabic_normalize(word)
    return "".join(_ARABIC_FOLD_MAP.get(ch, ch) for ch in normalized)


def _assert_arabic_normalize() -> None:
    """Golden fixture — MUST match ArabicNormalizeTest.kt so build-time and
    runtime Arabic normalization agree (drift = silently-missed lookups)."""
    cases = {
        "كَت": "كت",   # strip fatha
        "كـت": "كت",   # strip tatweel
        "هٰ": "ه",               # strip superscript alef
        "آ": "آ", "أ": "أ", "إ": "إ", "ٱ": "ٱ",  # alef variants PRESERVED (no fold)
        "ى": "ى",                      # alef maqsura PRESERVED
        "ة": "ة",                      # taa marbuta PRESERVED
        "ﷲ": "الله",    # NFKC ligature expands
        "كتاب": "كتاب",  # unchanged
        "100": "100",
        "abc": "abc",
    }
    for inp, exp in cases.items():
        got = arabic_normalize(inp)
        if got != exp:
            raise SystemExit(
                f"arabic_normalize golden fixture FAILED: {inp!r} -> {got!r}, expected {exp!r}"
            )


def _assert_arabic_fold() -> None:
    """Golden fixture — MUST match ArabicFoldTest.kt. Folding is intentionally
    lossy: it changes letter identity, so it is ONLY a lookup key, never a
    display form."""
    cases = {
        # alef variants fold to bare alef
        "أنا": "انا", "إن": "ان", "آمن": "امن", "ٱلله": "الله",
        # taa marbuta -> heh, alef maksura -> yeh
        "مدرسة": "مدرسه", "فتوى": "فتوي",
        # composition: diacritics still stripped before folding
        "كِتَاب": "كتاب",
        # already-bare alef stays; non-folded letters untouched
        "كتاب": "كتاب",
        # the partially-folded case the runtime fallback exists for:
        # hamza dropped but maksura kept -> both normalized into the fold key
        "انثى": "انثي",
        # non-Arabic unchanged
        "100": "100", "abc": "abc",
    }
    for inp, exp in cases.items():
        got = arabic_fold(inp)
        if got != exp:
            raise SystemExit(
                f"arabic_fold golden fixture FAILED: {inp!r} -> {got!r}, expected {exp!r}"
            )


if __name__ == "__main__":
    _assert_arabic_normalize()
    _assert_arabic_fold()
    print("arabic_text golden fixtures OK")
