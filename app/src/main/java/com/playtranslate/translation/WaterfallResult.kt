package com.playtranslate.translation

/**
 * Outcome of a successful waterfall translation. The chosen backend is
 * carried so callers can surface backend-specific UI signals (e.g. the
 * "degraded" badge, future quota indicators) and so the caller can
 * decide whether to cache the result (callers skip caching when
 * [isDegraded] is true so the slot can be reclaimed by an online
 * backend on recovery).
 *
 * [displacedLlmId] is the [BackendId] of the first on-device LLM that
 * threw [com.playtranslate.translation.translategemma.TranslateGemmaTransientException]
 * on this call (typically a low-availMem fall-through during translate).
 * Non-null means "the user's preferred on-device LLM couldn't run; we
 * fell through to a lower-priority backend." Callers should skip caching
 * the result so the next call can re-attempt the preferred LLM once
 * memory pressure relaxes; without this, a low-quality fallback output
 * outlasts the pressure window.
 */
data class WaterfallResult(
    val text: String,
    val backend: TranslationBackend,
    val isDegraded: Boolean,
    val displacedLlmId: BackendId? = null,
)
