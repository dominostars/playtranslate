package com.playtranslate.imageimport

import android.util.SparseArray

/**
 * Completed scenes per page of the document under review — revisiting a
 * cached page republishes instantly (no OCR, no translation, skeleton-free).
 *
 * Admission rules (enforced by the caller):
 *  - only FULL-FRAME runs are cached: the region rect is scene-scoped and
 *    cleared on every page switch, so a cached region-filtered result would
 *    silently show filtered text with no indicator explaining it;
 *  - NoText verdicts are not cached (revisits re-run; accepted minor waste).
 *
 * Invalidation: cleared WHOLESALE on any read-settings change (language or
 * OCR engine invalidates every page's text) and on document close. Payloads
 * are text + rects + colors — small enough to keep for every page; the
 * heavy per-page artifacts (bitmaps, frame files) are never cached here.
 *
 * Main thread only.
 */
class PageResults {
    private val scenes = SparseArray<ImageImportSession.CachedScene>()

    fun get(page: Int): ImageImportSession.CachedScene? = scenes.get(page)

    fun put(page: Int, scene: ImageImportSession.CachedScene) {
        scenes.put(page, scene)
    }

    fun clear() {
        scenes.clear()
    }
}
