#!/usr/bin/env python3
"""Mechanical checker for the localized READMEs in readme/.

Read-only. The quality layer (naturalness, register, terminology) is the human
review in l10n-review/readme/<locale>.md; this script only proves a translation
is structurally the English README:

  * same heading sequence (levels) through "Optional: Anki Flashcards", then exactly
    one extra H2 (the pointer to the English Credits + License)
  * every URL of the translated portion present as many times as in English and
    byte-identical (relative links resolved against the repo), no relative links left,
    no URLs that English lacks except the Credits/LICENSE pointers
  * both language tables: same row count, native-name and code columns identical
  * brand names and multi-digit numbers of the English prose all present
  * the video line alone on its line; Arabic wrapped in <div dir="rtl">
  * the shared header block (language row + badges) byte-identical to
    scripts/readme_header.py's output for that file; required with --require-header

    python3 scripts/readme_l10n_check.py            # all 13 locales
    python3 scripts/readme_l10n_check.py ja zh-rCN  # Android locale codes
    python3 scripts/readme_l10n_check.py --require-header   # once the header is in

Exit status 1 when any locale fails.
"""
import re
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from readme_header import header_block, split_header  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
REPO = "https://github.com/dominostars/playtranslate"
LOCALES = {  # Android locale folder -> README file tag (BCP-47)
    "ja": "ja", "zh-rCN": "zh-CN", "zh-rHK": "zh-HK", "ko": "ko", "ru": "ru",
    "ar": "ar", "th": "th", "vi": "vi", "tr": "tr", "de": "de", "fr": "fr",
    "es": "es", "pt-rBR": "pt-BR",
}
RTL = {"ar"}
# Canonical list from docs/l10n-language-parameters.md plus the README-only names.
BRANDS = [
    "PlayTranslate", "Anki", "AnkiDroid", "DeepL", "OpenAI", "Gemini", "DeepSeek",
    "Lingva", "Qwen", "Gemma", "Hunyuan", "Tencent", "Tatoeba", "Firefox Translations",
    "Bergamot", "ML Kit", "PaddleOCR", "Meiki", "Discord", "GitHub", "Migaku", "Lapis",
    "JPMN", "Mistral", "Groq", "OpenRouter", "Claude", "Ko-fi", "Yomitan", "Ayn Thor",
]
POINTER_LINKS = {REPO + "#credits", REPO + "/blob/main/LICENSE"}
VIDEO = re.compile(r"\[[^\]]*\]\(https://github\.com/user-attachments/assets/[^)\s]+\)|https://github\.com/user-attachments/assets/\S+")


def headings(md):
    return [(len(m.group(1)), m.group(2).strip()) for m in re.finditer(r"^(#{1,6})\s+(.+)$", md, re.M)]


def english_portion(md):
    i = md.find("\n## Credits")
    assert i > 0, "English README has no '## Credits' heading"
    return md[:i]


def translated_portion(md):
    """Everything before the translation's last H2 (the Credits/License pointer)."""
    h2 = [m.start() for m in re.finditer(r"^## .+$", md, re.M)]
    if not h2:
        return md, ""
    return md[: h2[-1]], md[h2[-1]:]


def normalize(url):
    url = url.rstrip(".,;:)!?")
    if url.startswith("../../"):
        return REPO + "/" + url[len("../../"):]
    if url == "LICENSE":
        return REPO + "/blob/main/LICENSE"
    return url


def links(md):
    """Occurrence count per normalized URL. A set would let a translation drop one of two
    identical links (the releases page and the Discord invite both appear twice) and pass."""
    found = [m.group(1) for m in re.finditer(r"\]\(([^)\s]+)\)", md)]
    found += [m.group(0) for m in re.finditer(r"(?<![(\w])https?://[^\s)\]]+", md)]
    return Counter(normalize(u) for u in found)


def tables(md):
    out, cur = [], None
    for line in md.splitlines():
        t = line.strip()
        if t.startswith("|"):
            if cur is None:
                cur = []
                out.append(cur)
            if re.fullmatch(r"\|[-| :]+\|", t):
                continue
            cur.append([c.strip() for c in t[1:-1].split("|")])
        else:
            cur = None
    return out


def numbers(md):
    # ASCII-letter boundaries only: CJK and other scripts legitimately abut a numeral (26種類).
    return set(re.findall(r"(?<![0-9A-Za-z.])\d{2,}(?![0-9A-Za-z.])", md))


def check(locale, en, require_header=False):
    tag = LOCALES[locale]
    path = ROOT / "readme" / f"README.{tag}.md"
    errors, warnings = [], []
    if not path.exists():
        return [f"missing file {path.relative_to(ROOT)}"], warnings
    tr = path.read_text(encoding="utf-8")
    _, en = split_header(en)
    tr_header, tr = split_header(tr)
    if tr_header is None:
        if require_header:
            errors.append("header block missing (scripts/readme_header.py --apply)")
    elif tr_header != header_block(tag):
        errors.append("header block differs from scripts/readme_header.py output for this file")
    en_body = english_portion(en)
    tr_body, tr_tail = translated_portion(tr)

    # Structure
    en_levels = [lvl for lvl, _ in headings(en_body)]
    tr_levels = [lvl for lvl, _ in headings(tr)]
    if tr_levels != en_levels + [2]:
        errors.append(f"heading levels {tr_levels} != English {en_levels} + [2] (one trailing H2 pointer)")
    if locale in RTL:
        stripped = tr.strip()
        if not re.match(r'<div dir="rtl">\s*\n\s*\n', stripped) or not stripped.endswith("</div>"):
            errors.append('RTL locale must start with <div dir="rtl"> + blank line and end with </div>')

    # Links
    if re.search(r"\]\(\.\./|\]\(LICENSE\)", tr):
        errors.append("relative link left in translation (breaks from readme/); use absolute URLs")
    en_links, tr_links = links(en_body), links(tr_body)
    for u, n in sorted((en_links - tr_links).items()):
        errors.append(f"English link missing ({n} of {en_links[u]} occurrences): {u}")
    for u, n in sorted((tr_links - en_links).items()):
        errors.append(f"link not in English ({n} extra): {u}")
    tail_links = links(tr_tail)
    if set(tail_links) != POINTER_LINKS or any(n != 1 for n in tail_links.values()):
        errors.append(f"pointer section links {sorted(tail_links.elements())} != {sorted(POINTER_LINKS)}")

    # Tables
    en_t, tr_t = tables(en_body), tables(tr_body)
    if len(tr_t) != len(en_t):
        errors.append(f"{len(tr_t)} tables, English has {len(en_t)}")
    else:
        for ti, (a, b) in enumerate(zip(en_t, tr_t)):
            if len(a) != len(b):
                errors.append(f"table {ti + 1}: {len(b)} rows, English has {len(a)}")
                continue
            for ri, (ra, rb) in enumerate(zip(a[1:], b[1:]), start=1):
                if len(rb) != 3 or ra[1:] != rb[1:]:
                    errors.append(f"table {ti + 1} row {ri}: native/code {rb[1:] if len(rb) == 3 else rb} != {ra[1:]}")
            if b[0] == a[0]:
                warnings.append(f"table {ti + 1} header row still English: {a[0]}")
            elif rb_first := [c for c in b[0] if c in a[0]]:
                warnings.append(f"table {ti + 1} header cells left in English: {rb_first}")

    # Brands and numbers
    for brand in BRANDS:
        if brand in en_body and brand not in tr_body:
            errors.append(f"brand name missing: {brand}")
    for n in sorted(numbers(en_body) - numbers(tr_body)):
        errors.append(f"number missing: {n}")

    # Video line
    en_video = [l for l in en_body.splitlines() if "user-attachments/assets" in l]
    tr_video = [l for l in tr_body.splitlines() if "user-attachments/assets" in l]
    if len(tr_video) != len(en_video):
        errors.append(f"{len(tr_video)} video lines, English has {len(en_video)}")
    for l in tr_video:
        if not VIDEO.fullmatch(l.strip()):
            errors.append("video URL must stand alone on its line (bare URL or a single link)")

    # Soft signals
    en_bold, tr_bold = len(re.findall(r"\*\*[^*\n]+\*\*", en_body)), len(re.findall(r"\*\*[^*\n]+\*\*", tr_body))
    if en_bold != tr_bold:
        warnings.append(f"{tr_bold} bold spans, English has {en_bold}")
    return errors, warnings


def main(argv):
    require_header = "--require-header" in argv
    argv = [a for a in argv if a != "--require-header"]
    targets = argv or list(LOCALES)
    bad = [t for t in targets if t not in LOCALES]
    if bad:
        sys.exit(f"unknown locale(s) {bad}; known: {sorted(LOCALES)}")
    en = (ROOT / "README.md").read_text(encoding="utf-8")
    failed = 0
    en_header, _ = split_header(en)
    if en_header is None and require_header:
        print("[FAIL] README.md: header block missing")
        failed += 1
    elif en_header is not None and en_header != header_block(None):
        print("[FAIL] README.md: header block differs from scripts/readme_header.py output")
        failed += 1
    for loc in targets:
        errors, warnings = check(loc, en, require_header)
        status = "FAIL" if errors else "PASS"
        failed += bool(errors)
        print(f"[{status}] {loc} -> readme/README.{LOCALES[loc]}.md")
        for e in errors:
            print(f"    ERROR   {e}")
        for w in warnings:
            print(f"    warning {w}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main(sys.argv[1:])
