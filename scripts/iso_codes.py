"""Shared language-code normalization for PlayTranslate pack builders.

Background
----------
The runtime queries target packs with the SOURCE PACK's code, which is always
2-letter (`ja`, `zh`, `ko`, `en`, …). Any `source_lang` value stored in
`glosses.sqlite` that doesn't match a 2-letter code is dead weight — unreachable
from any current or future source pack that uses 2-letter codes.

Two upstream sources of non-2-letter codes:
1. kaikki.org dumps: most entries are 2-letter (`ja`, `fr`), but a minority
   carry the more-specific ISO 639-3 (`cmn` for Mandarin, `nob` for Bokmål) or
   whitespace-padded ASCII (`' oc'`) or non-ASCII junk (`'з'` Cyrillic letter).
2. PanLex TSVs: filenames are ISO 639-3. The builders map a handful back to
   2-letter via their own per-script `APP_TO_PANLEX`/`PANLEX_TO_APP` tables,
   but those tables are partial. Codes not in them stay 3-letter.

This module centralizes the mapping so all three builders agree.

What this normalizes
--------------------
1. Whitespace: stripped from both ends.
2. Case: lowercased.
3. Non-ASCII or non-alphanumeric (after a permitted hyphen): rejected (None).
4. Length > 3: rejected.
5. ISO 639-3 → ISO 639-1 where a 639-1 code exists.
6. Source-pack-aware overrides where we ship an umbrella code (e.g. Bokmål
   `nob` and Nynorsk `nno` both collapse to `no`, because we ship one Norwegian
   source pack covering both).

What this does NOT do
---------------------
- It does not invent codes. If ISO 639-3 has no 639-1 equivalent (e.g. Asturian
  `ast`, Sicilian `scn`, Frisian-Saterland `stq`), the code passes through
  unchanged. Those rows remain unreachable today but are preserved for any
  future source-pack additions.
- It does not change the gloss text or other fields. Code normalization only.
- It does not deduplicate. Callers using `INSERT OR IGNORE` will silently drop
  PK collisions created by normalization (e.g. one `cmn` row and one `zh` row
  sharing `(written, reading, sense_ord)` after both collapse to `zh`); this is
  consistent with how the builders already handle duplicates.

Usage
-----
    from iso_codes import normalize_lang_code

    raw = entry.get("lang_code")     # whatever upstream emits
    code = normalize_lang_code(raw)
    if code is None:
        continue                      # invalid / un-parseable; skip the row
"""
from __future__ import annotations
from typing import Optional

# ─── Tier 1: source-pack-aware umbrella collapses ─────────────────────────────
#
# These override any ISO mapping. They reflect the umbrella codes our source
# packs use. If we ever ship separate `nb`/`nn` (Bokmål/Nynorsk) source packs,
# update this table — not the ISO mapping below.
SOURCE_PACK_OVERRIDES: dict[str, str] = {
    # Norwegian: we ship one umbrella `no` source pack covering both varieties
    "nob": "no",  # Bokmål  (ISO 639-1 would say `nb`)
    "nno": "no",  # Nynorsk (ISO 639-1 would say `nn`)
    "nor": "no",  # explicit umbrella in some classifications

    # Chinese: our `zh` source pack uses Simplified Mandarin tokenization;
    # `cmn` (Mandarin) and `zho` (umbrella) both collapse to it.
    # Cantonese (`yue`), Wu (`wuu`), Min Nan (`nan`), Hakka (`hak`) intentionally
    # NOT collapsed — they're distinct languages and our `zh` source pack
    # wouldn't tokenize them correctly anyway.
    "cmn": "zh",
    "zho": "zh",
    "chi": "zh",  # ISO 639-2/B bibliographic

    # Persian: our `fa` source pack is the umbrella covering Iranian + Dari
    "pes": "fa",  # Western Persian (Iranian)
    "prs": "fa",  # Dari (Eastern Persian)
    "fas": "fa",  # ISO 639-3 / 639-2/T
    "per": "fa",  # ISO 639-2/B bibliographic

    # Arabic: our `ar` source pack covers Modern Standard Arabic;
    # `arb` (MSA) explicitly collapses. Vernacular Arabic codes (`arz`
    # Egyptian, `ary` Moroccan, etc.) NOT collapsed — they're functionally
    # different languages.
    "arb": "ar",
    "ara": "ar",

    # Azerbaijani: `azj` (North) is by far the dominant variety; collapse
    # to umbrella. South Azerbaijani `azb` is a distinct variety we don't
    # ship and don't collapse.
    "azj": "az",
    "aze": "az",

    # Armenian: Western Armenian `hyw` is functionally close enough to Eastern
    # to collapse for our purposes (single Armenian source pack would cover both)
    "hye": "hy",
    "hyw": "hy",
    "arm": "hy",  # bibliographic

    # Mongolian: Khalkha `khk` is the standard variety
    "khk": "mn",
    "mon": "mn",

    # Uzbek: Northern Uzbek `uzn` is the standard variety
    "uzn": "uz",
    "uzb": "uz",

    # Nepali umbrella `npi` is the standard variety
    "npi": "ne",
    "nep": "ne",

    # Malagasy: Plateau `plt` is the standard variety
    "plt": "mg",
    "mlg": "mg",

    # Estonian: Standard `ekk` is the dominant variety
    "ekk": "et",
    "est": "et",

    # Latvian: Standard `lvs` is the dominant variety
    "lvs": "lv",
    "lav": "lv",

    # Swahili: Coastal `swh` is the standard literary variety
    "swh": "sw",
    "swa": "sw",

    # Yiddish: Eastern `ydd` is the standard variety
    "ydd": "yi",
    "yid": "yi",

    # Pashto: Northern Pashto `pbu` is the standard variety
    "pbu": "ps",
    "pus": "ps",

    # Malay: Standard `zsm` collapses to umbrella `ms`
    "zsm": "ms",
    "msa": "ms",

    # Bengali umbrella
    "ben": "bn",

    # Burmese
    "mya": "my",
    "bur": "my",

    # Kazakh
    "kaz": "kk",
    # Kyrgyz
    "kir": "ky",
    # Uzbek covered above
    # Tajik
    "tgk": "tg",
    # Tatar
    "tat": "tt",
    # Turkmen
    "tuk": "tk",
    # Bashkir
    "bak": "ba",
    # Chuvash
    "chv": "cv",
    # Chechen
    "che": "ce",

    # Other CIS/Indic
    "guj": "gu",
    "kan": "kn",
    "mal": "ml",
    "mar": "mr",
    "ori": "or",
    "ory": "or",  # Standard Odia
    "pan": "pa",
    "pnb": "pa",  # Western Punjabi (Pakistan) -> umbrella `pa`
    "asm": "as",
    "sin": "si",
    "snd": "sd",
    "tam": "ta",
    "tel": "te",
    "urd": "ur",
    "hin": "hi",
    "khm": "km",
    "lao": "lo",
    "tha": "th",

    # Tibetan
    "bod": "bo",
    "tib": "bo",

    # CJK
    "jpn": "ja",
    "kor": "ko",

    # African
    "amh": "am",
    "tir": "ti",
    "som": "so",
    "hau": "ha",
    "ibo": "ig",
    "yor": "yo",
    "zul": "zu",
    "xho": "xh",
    "sna": "sn",
    "kin": "rw",
    "run": "rn",
    "lin": "ln",
    "kon": "kg",
    "lug": "lg",
    "aka": "ak",
    "twi": "tw",
    "wol": "wo",
    "ful": "ff",
    "fuh": "ff",  # West Niger Fulfulde
    "fuc": "ff",  # Pulaar (Senegalese)
    "bam": "bm",
    "ewe": "ee",
    "sag": "sg",
    "nya": "ny",
    "sot": "st",
    "tsn": "tn",
    "tso": "ts",
    "ssw": "ss",
    "nde": "nd",
    "nbl": "nr",
    "ven": "ve",
    "swh": "sw",

    # European
    "eng": "en",
    "spa": "es",
    "fra": "fr",
    "fre": "fr",  # bibliographic
    "deu": "de",
    "ger": "de",  # bibliographic
    "ita": "it",
    "por": "pt",
    "nld": "nl",
    "dut": "nl",  # bibliographic
    "tur": "tr",
    "vie": "vi",
    "ind": "id",
    "swe": "sv",
    "dan": "da",
    "fin": "fi",
    "hun": "hu",
    "ron": "ro",
    "rum": "ro",  # bibliographic
    "cat": "ca",
    "ukr": "uk",
    "pol": "pl",
    "ces": "cs",
    "cze": "cs",  # bibliographic
    "ell": "el",
    "gre": "el",  # bibliographic
    "rus": "ru",
    "heb": "he",
    "afr": "af",
    "sqi": "sq",
    "alb": "sq",  # bibliographic
    "bel": "be",
    "bul": "bg",
    "cym": "cy",
    "wel": "cy",  # bibliographic
    "epo": "eo",
    "gle": "ga",
    "glg": "gl",
    "hrv": "hr",
    "hat": "ht",
    "isl": "is",
    "ice": "is",  # bibliographic
    "kat": "ka",
    "geo": "ka",  # bibliographic
    "lit": "lt",
    "mkd": "mk",
    "mac": "mk",  # bibliographic
    "mlt": "mt",
    "slk": "sk",
    "slo": "sk",  # bibliographic
    "slv": "sl",
    "tgl": "tl",

    # Other ISO 639-3 with 639-1 equivalents (where there's no ambiguity)
    "lat": "la",  # Latin
    "fao": "fo",  # Faroese
    "bre": "br",  # Breton
    "oci": "oc",  # Occitan
    "bos": "bs",  # Bosnian
    "srp": "sr",  # Serbian
    "eus": "eu",  # Basque
    "baq": "eu",  # bibliographic
    "gla": "gd",  # Scottish Gaelic
    "glv": "gv",  # Manx
    "fry": "fy",  # West Frisian
    "ltz": "lb",  # Luxembourgish
    "jav": "jv",  # Javanese
    "sun": "su",  # Sundanese
    "kal": "kl",  # Kalaallisut (Greenlandic)
    "ina": "ia",  # Interlingua
    "ile": "ie",  # Interlingue/Occidental
    "ido": "io",  # Ido
    "mri": "mi",  # Māori
    "mao": "mi",  # bibliographic
    "smo": "sm",  # Samoan
    "ton": "to",  # Tongan
    "tah": "ty",  # Tahitian
    "fij": "fj",  # Fijian
    "div": "dv",  # Dhivehi
    "kur": "ku",  # Kurdish umbrella
    "aym": "ay",  # Aymara
    "ave": "ae",  # Avestan
    "abk": "ab",  # Abkhaz
    "aar": "aa",  # Afar
    "iku": "iu",  # Inuktitut
    "ipk": "ik",  # Inupiaq
    "cor": "kw",  # Cornish
    "cos": "co",  # Corsican
    "cre": "cr",  # Cree
    "ava": "av",  # Avar
    "her": "hz",  # Herero
    "hmo": "ho",  # Hiri Motu
    "iii": "ii",  # Sichuan Yi
    "kau": "kr",  # Kanuri
    "kas": "ks",  # Kashmiri
    "kik": "ki",  # Kikuyu
    "kom": "kv",  # Komi
    "kua": "kj",  # Kuanyama
    "lim": "li",  # Limburgish
    "lub": "lu",  # Luba-Katanga
    "luo": "luo", # no 639-1
    "mah": "mh",  # Marshallese
    "nau": "na",  # Nauru
    "nav": "nv",  # Navajo
    "ndo": "ng",  # Ndonga
    "nrm": "nrm", # Norman (no 639-1)
    "oji": "oj",  # Ojibwe
    "orm": "om",  # Oromo
    "oss": "os",  # Ossetian
    "pli": "pi",  # Pali
    "que": "qu",  # Quechua
    "roh": "rm",  # Romansh
    "san": "sa",  # Sanskrit
    "srd": "sc",  # Sardinian
    "tlh": None,  # Klingon — no real code; pass through
    "tum": None,  # not 639-1
    "tvl": None,  # Tuvaluan — no 639-1
    "vol": "vo",  # Volapük
    "wln": "wa",  # Walloon
    "zha": "za",  # Zhuang
}
# Drop None entries (cleaner)
SOURCE_PACK_OVERRIDES = {k: v for k, v in SOURCE_PACK_OVERRIDES.items() if v is not None}


def normalize_lang_code(raw: Optional[str]) -> Optional[str]:
    """Normalize a kaikki/PanLex/JMdict `lang_code` to the form the runtime queries.

    Returns the canonical code (lowercase, 2- or 3-letter ASCII) or None if the
    input is invalid (empty, non-ASCII, has internal whitespace, too long, or
    contains punctuation other than a single hyphen for variants like
    `zh-hant` — though we don't currently emit those).

    Bug-class behaviour:
    - whitespace-padded codes from upstream (e.g. `' oc'`, `'brabançon '`)
      are stripped, then re-validated.
    - non-ASCII characters (e.g. the Cyrillic `'з'` we saw in kaikki-ru) cause
      None to be returned — caller skips the row.
    - already-2-letter codes pass through unchanged after strip+lower.
    - 3-letter codes are looked up in the override / ISO table; if found,
      return the 2-letter; otherwise return the cleaned 3-letter as-is.
    """
    if raw is None:
        return None
    s = raw.strip()
    if not s:
        return None
    s = s.lower()
    if not s.isascii():
        return None
    # Allow alphanumerics + a single hyphen (for codes like `zh-hant`,
    # though we don't currently emit these). Disallow whitespace.
    if any(c.isspace() for c in s):
        return None
    body = s.replace("-", "")
    if not body or not body.isalnum():
        return None
    # Plain alpha codes must be ≤ 3 chars (ISO 639-1 / 639-3).
    # Hyphenated variants like `zh-hant` are allowed up to 7 chars.
    has_hyphen = "-" in s
    if not has_hyphen and len(s) > 3:
        return None
    if has_hyphen and len(s) > 7:
        return None
    # 2-letter codes are already canonical
    if len(s) <= 2:
        return s
    # 3-letter (or hyphenated) — try the table
    if s in SOURCE_PACK_OVERRIDES:
        return SOURCE_PACK_OVERRIDES[s]
    # Unknown 3-letter code with no override: pass through (e.g. `ast`, `scn`)
    return s


# Convenience exports for callers that want to introspect (e.g. for PanLex's
# bidirectional file-naming logic).
SOURCE_PACK_OVERRIDES_REVERSE: dict[str, list[str]] = {}
for k, v in SOURCE_PACK_OVERRIDES.items():
    SOURCE_PACK_OVERRIDES_REVERSE.setdefault(v, []).append(k)
