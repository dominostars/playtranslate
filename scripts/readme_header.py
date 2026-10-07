#!/usr/bin/env python3
"""The shared header block of README.md and readme/README.<tag>.md: a centered
language row (every other README linked, the current one as plain text) and the
badge row. Modelled on PaddleOCR's README header, without flags.

    python3 scripts/readme_header.py            # print the English block
    python3 scripts/readme_header.py ja         # print the block for readme/README.ja.md
    python3 scripts/readme_header.py --apply    # splice the block into README.md and
                                                # every existing readme/README.*.md

--apply is idempotent: a block between the <!-- l10n-header --> markers is replaced,
otherwise the block is inserted after the H1 line. Only the header block changes.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPO = "https://github.com/dominostars/playtranslate"
BEGIN, END = "<!-- l10n-header -->", "<!-- /l10n-header -->"

# (README file tag, Android locale folder, label in its own language), in the order
# of the README's game-language table (total speakers), English first.
LOCALES = [
    ("zh-CN", "zh-rCN", "简体中文"),
    ("zh-HK", "zh-rHK", "繁體中文"),
    ("es", "es", "Español"),
    ("ar", "ar", "العربية"),
    ("fr", "fr", "Français"),
    ("pt-BR", "pt-rBR", "Português (Brasil)"),
    ("ru", "ru", "Русский"),
    ("de", "de", "Deutsch"),
    ("ja", "ja", "日本語"),
    ("tr", "tr", "Türkçe"),
    ("vi", "vi", "Tiếng Việt"),
    ("ko", "ko", "한국어"),
    ("th", "th", "ไทย"),
]
TAG_BY_ANDROID = {android: tag for tag, android, _ in LOCALES}

BADGES = [
    f"[![Downloads](https://img.shields.io/github/downloads/dominostars/playtranslate/total)]({REPO}/releases)",
    f"[![Stars](https://img.shields.io/github/stars/dominostars/playtranslate?style=flat)]({REPO}/stargazers)",
    "![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)",
    f"[![License](https://img.shields.io/github/license/dominostars/playtranslate)]({REPO}/blob/main/LICENSE)",
]


def language_row(current):
    """current: None for README.md, else the README file tag."""
    items = []
    if current is None:
        items.append("English")
    else:
        items.append("[English](../README.md)")
    for tag, _, label in LOCALES:
        if tag == current:
            items.append(label)
        elif current is None:
            items.append(f"[{label}](./readme/README.{tag}.md)")
        else:
            items.append(f"[{label}](./README.{tag}.md)")
    return " | ".join(items)


def header_block(current):
    return "\n".join([BEGIN, '<div align="center">', "", language_row(current), "", *BADGES, "", "</div>", END])


def split_header(md):
    """-> (header text or None, md with the header and its surrounding blank line removed)."""
    m = re.search(re.escape(BEGIN) + r".*?" + re.escape(END) + r"\n?", md, re.S)
    if not m:
        return None, md
    return m.group(0).rstrip("\n"), md[: m.start()] + md[m.end():].lstrip("\n")


def apply(path, current):
    md = path.read_text(encoding="utf-8")
    block = header_block(current)
    _, body = split_header(md)
    m = re.search(r"^# .+\n", body, re.M)
    if not m:
        sys.exit(f"{path}: no H1 to insert after")
    out = body[: m.end()] + "\n" + block + "\n\n" + body[m.end():].lstrip("\n")
    if out != md:
        path.write_text(out, encoding="utf-8")
    print(("updated " if out != md else "unchanged ") + str(path.relative_to(ROOT)))


def main(argv):
    if argv == ["--apply"]:
        apply(ROOT / "README.md", None)
        for tag, _, _ in LOCALES:
            p = ROOT / "readme" / f"README.{tag}.md"
            if p.exists():
                apply(p, tag)
        return
    current = argv[0] if argv else None
    if current is not None and current not in {t for t, _, _ in LOCALES}:
        sys.exit(f"unknown tag {current}")
    print(header_block(current))


if __name__ == "__main__":
    main(sys.argv[1:])
