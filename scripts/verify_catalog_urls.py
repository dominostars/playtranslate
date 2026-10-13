#!/usr/bin/env python3
"""Holds the live redirect layer to every download URL in langpack_catalog.json.

For each `url` in the catalog (pack zips and the per-file URLs of engine, OCR
and Bergamot entries):

  1. the packs host must answer a 302 whose Location is exactly the upstream
     pack_urls.upstream_of predicts (the `_redirects` contract in the website
     repo, playtranslate_com);
  2. following it with `Range: bytes=0-0` must reach a 206 whose Content-Range
     total equals the catalog `size`, except on `gzip: true` files, whose
     catalog size is the inflated one.

A URL off the packs host fails outright. Run after deploying a `_redirects`
change and before committing a catalog change: a misrouted rule, a missing
rule for a new Hugging Face repo and a deleted upstream asset all fail here,
before a user's install does. Exit status 1 on any failure.

  python3 scripts/verify_catalog_urls.py [--jobs 6] [--pack ja --pack meiki-ja]
"""
from __future__ import annotations

import argparse
import json
import socket
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path

import pack_urls

ROOT = Path(__file__).resolve().parent.parent
CATALOG = ROOT / "app" / "src" / "main" / "assets" / "langpack_catalog.json"
USER_AGENT = "playtranslate-verify-catalog-urls/1"
TIMEOUT_S = 60
RETRY_DELAYS_S = (2, 5, 10)  # Hugging Face sheds load with 429 "maximum queue size reached"


@dataclass(frozen=True)
class Target:
    pack: str
    url: str
    size: int | None
    gzip: bool


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: N802
        return None


_FIRST_HOP = urllib.request.build_opener(_NoRedirect())
_FOLLOW = urllib.request.build_opener()


def targets_of(catalog: dict) -> list[Target]:
    out: list[Target] = []
    for pack_id, entry in catalog["packs"].items():
        if entry.get("url"):
            out.append(Target(pack_id, entry["url"], entry.get("size"), False))
        for f in entry.get("files") or []:
            out.append(Target(pack_id, f["url"], f.get("size"), bool(f.get("gzip"))))
    return out


def _with_retries(fn):
    last: Exception | None = None
    for attempt, delay in enumerate((0,) + RETRY_DELAYS_S):
        if delay:
            time.sleep(delay)
        try:
            return fn()
        except urllib.error.HTTPError as e:
            if e.code in (429, 502, 503, 504) and attempt < len(RETRY_DELAYS_S):
                last = e
                continue
            raise
        except (urllib.error.URLError, socket.timeout, TimeoutError, ConnectionError) as e:
            if attempt < len(RETRY_DELAYS_S):
                last = e
                continue
            raise
    raise last  # unreachable; keeps type checkers honest


def _request(url: str) -> urllib.request.Request:
    return urllib.request.Request(url, headers={"Range": "bytes=0-0", "User-Agent": USER_AGENT})


def first_hop(url: str) -> tuple[int, str | None]:
    """Status and Location of the packs host's own answer, redirects not followed."""
    def go():
        try:
            with _FIRST_HOP.open(_request(url), timeout=TIMEOUT_S) as r:
                return r.status, r.headers.get("Location")
        except urllib.error.HTTPError as e:
            if 300 <= e.code < 400:
                return e.code, e.headers.get("Location")
            raise
    return _with_retries(go)


def followed(url: str) -> tuple[int, str | None]:
    """Final status and Content-Range after following every redirect."""
    def go():
        with _FOLLOW.open(_request(url), timeout=TIMEOUT_S) as r:
            return r.status, r.headers.get("Content-Range")
    return _with_retries(go)


def content_range_total(header: str | None) -> int | None:
    # "bytes 0-0/99889772"
    if not header or "/" not in header:
        return None
    total = header.rsplit("/", 1)[1].strip()
    return int(total) if total.isdigit() else None


def check(t: Target) -> str | None:
    """None when the URL passes, else one line saying what failed."""
    try:
        upstream = pack_urls.upstream_of(t.url)
    except ValueError as e:
        return f"{t.pack}: {e}"
    try:
        status, location = first_hop(t.url)
    except Exception as e:  # noqa: BLE001
        return f"{t.pack}: {t.url}: first hop failed: {e}"
    if status != 302:
        return f"{t.pack}: {t.url}: expected 302 from the packs host, got {status}"
    if location != upstream:
        return f"{t.pack}: {t.url}: redirects to {location}, expected {upstream}"
    try:
        status, content_range = followed(t.url)
    except Exception as e:  # noqa: BLE001
        return f"{t.pack}: {t.url}: following the redirect failed: {e}"
    if status != 206:
        return f"{t.pack}: {t.url}: upstream answered {status} to a Range request, expected 206"
    total = content_range_total(content_range)
    if total is None:
        return f"{t.pack}: {t.url}: upstream 206 without a Content-Range total ({content_range!r})"
    if not t.gzip and t.size is not None and total != t.size:
        return f"{t.pack}: {t.url}: upstream has {total} bytes, catalog says {t.size}"
    return None


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--jobs", type=int, default=6, help="concurrent URLs (default 6; Hugging Face throttles bursts)")
    ap.add_argument("--pack", action="append", help="only these catalog ids (repeatable)")
    args = ap.parse_args()

    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    targets = targets_of(catalog)
    if args.pack:
        targets = [t for t in targets if t.pack in set(args.pack)]
    if not targets:
        print("verify_catalog_urls: no URLs selected", file=sys.stderr)
        return 1

    with ThreadPoolExecutor(max_workers=max(1, args.jobs)) as pool:
        failures = [f for f in pool.map(check, targets) if f]
    for line in failures:
        print("FAIL " + line)
    print(f"verify_catalog_urls: urls={len(targets)} packs={len({t.pack for t in targets})} "
          f"ok={len(targets) - len(failures)} failed={len(failures)}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
