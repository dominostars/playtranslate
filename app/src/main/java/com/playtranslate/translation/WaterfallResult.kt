package com.playtranslate.translation

/**
 * Outcome of a successful waterfall translation. The chosen backend is
 * carried so callers can surface backend-specific UI signals (e.g. the
 * "degraded" badge, future quota indicators) and so the caller can
 * decide whether to cache the result (callers skip caching when
 * [isDegraded] is true so the slot can be reclaimed by an online
 * backend on recovery).
 */
data class WaterfallResult(
    val text: String,
    val backend: TranslationBackend,
    val isDegraded: Boolean,
)
