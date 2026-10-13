"""The one place the app's pack download URL scheme lives.

Every download URL in app/src/main/assets/langpack_catalog.json is on
packs.playtranslate.com, a redirect layer served from the website repo
(playtranslate_com, its `_redirects` file). Each path answers a 302 to the
backend that holds the bytes today, so moving a backend is a line there, never
an app update: the catalog ships inside every installed APK, and old installs
keep whatever host they shipped with.

Three path shapes, one per `_redirects` rule family:

  /langpacks/<tag>/<file>        github.com/dominostars/playtranslate-langpacks/releases/download/<tag>/<file>
  /models/<owner>/<repo>/<path>  huggingface.co/<owner>/<repo>/resolve/main/<path>  (one `_redirects` line per repo)
  /bergamot/<path>               Mozilla's Firefox Translations bucket, models/<path>

Builders mint catalog URLs through langpack_url, model_url and bergamot_url.
verify_catalog_urls.py checks that the live layer answers every catalog URL
with the upstream `upstream_of` predicts, and LanguagePackCatalogHostTest holds
the shipped catalog to this host at build time.
"""
from __future__ import annotations

PACKS_HOST = "https://packs.playtranslate.com"
LANGPACK_RELEASES = "https://github.com/dominostars/playtranslate-langpacks/releases/download"
HUGGINGFACE = "https://huggingface.co"
BERGAMOT_BUCKET = "https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data/models"


def langpack_url(tag: str, filename: str) -> str:
    """A source or target pack zip: the release [tag] (ja-v5, target-fr-v2) and its asset name."""
    return f"{PACKS_HOST}/langpacks/{tag}/{filename}"


def model_url(owner: str, repo: str, path: str) -> str:
    """A file of a Hugging Face model repo (OCR models, on-device engines)."""
    return f"{PACKS_HOST}/models/{owner}/{repo}/{path}"


def bergamot_url(path: str) -> str:
    """A Bergamot model file; [path] is relative to the bucket's models/ prefix
    (<pair>/<dir>/exported/<file>)."""
    return f"{PACKS_HOST}/bergamot/{path}"


def upstream_of(packs_url: str) -> str:
    """The URL the redirect layer must answer [packs_url] with: the `_redirects`
    contract, restated here so verify_catalog_urls.py can hold the live layer
    to it. Raises ValueError for a URL off the host or outside the three shapes."""
    prefix = PACKS_HOST + "/"
    if not packs_url.startswith(prefix):
        raise ValueError(f"not on {PACKS_HOST}: {packs_url}")
    kind, _, rest = packs_url[len(prefix):].partition("/")
    if kind == "langpacks":
        tag, _, filename = rest.partition("/")
        if not tag or not filename or "/" in filename:
            raise ValueError(f"langpacks path must be <tag>/<file>: {packs_url}")
        return f"{LANGPACK_RELEASES}/{tag}/{filename}"
    if kind == "models":
        owner, _, rest = rest.partition("/")
        repo, _, path = rest.partition("/")
        if not owner or not repo or not path:
            raise ValueError(f"models path must be <owner>/<repo>/<path>: {packs_url}")
        return f"{HUGGINGFACE}/{owner}/{repo}/resolve/main/{path}"
    if kind == "bergamot":
        if not rest:
            raise ValueError(f"bergamot path must be <pair>/<dir>/exported/<file>: {packs_url}")
        return f"{BERGAMOT_BUCKET}/{rest}"
    raise ValueError(f"unknown packs path shape: {packs_url}")
