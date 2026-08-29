package com.playtranslate.camera

import android.content.Context
import com.playtranslate.Prefs
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.translation.ChineseScriptConverter
import com.playtranslate.translation.TranslationBackendRegistry

/**
 * Translation for the camera tool: a thin wrapper over
 * [TranslationBackendRegistry.translateBatch] with a small LRU cache, the
 * same-language OCR-only bypass, and render-time Traditional-Chinese
 * localization — the minimal subset of `CaptureService.translateGroupsSeparately`
 * an in-app Activity needs. Deliberately does NOT bind [com.playtranslate.CaptureService]:
 * that service is a screen-capture host (MediaProjection/accessibility,
 * notifications, per-display state) irrelevant here, and the registry is
 * already initialized at Application scope.
 *
 * The cache stores the raw backend output (Simplified for zh targets, matching
 * the service's cache policy); [localize] applies the script variant at read
 * time so a variant change doesn't invalidate entries.
 */
class CameraTranslator(private val context: Context) {

    private companion object {
        const val MAX_CACHE_ENTRIES = 256
    }

    private data class Key(val source: String, val target: String, val text: String)

    /** One translation plus the attribution the capture panel binds
     *  ("Translated by …" + the degraded-fallback note). */
    data class Detailed(val text: String, val note: String?, val backendDisplayName: String?)

    // Values keep their attribution so a snapshot re-run served from cache
    // still names its backend (only non-degraded outcomes are cached, so a
    // cached note is always null). Text is stored UNLOCALIZED; the zh script
    // variant applies at read time.
    private val cache = object : LinkedHashMap<Key, Detailed>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Detailed>): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    /** Snapshot of the language configuration at one translate call, so a
     *  mid-batch pref change can't split key derivation from translation. */
    private class Snapshot(context: Context) {
        private val prefs = Prefs(context)
        private val srcId = prefs.sourceLangId
        val source: String = SourceLanguageProfiles[srcId].translationCode
        val target: String = prefs.targetLang
        private val converter = ChineseScriptConverter.forTarget(
            target,
            prefs.targetChineseVariant,
            inputIsTraditional = srcId == SourceLangId.ZH_HANT,
        )

        fun localize(text: String): String = converter?.convert(text) ?: text
    }

    /**
     * Translate [texts] (one entry per OCR group) into the configured target
     * language. Returns one string per input, in order; a text whose
     * translation failed across the whole backend waterfall comes back empty.
     */
    suspend fun translate(texts: List<String>): List<String> =
        translateDetailed(texts).map { it.text }

    /**
     * [translate] plus per-text attribution — the snapshot pipeline mirrors
     * the capture flow's orchestration, and its panel binds the backend name
     * and degraded note.
     */
    suspend fun translateDetailed(texts: List<String>): List<Detailed> {
        if (texts.isEmpty()) return emptyList()
        val snap = Snapshot(context)

        // OCR-only bypass: source == target means OCR output IS the result
        // (modulo Simplified→Traditional conversion for zh variants).
        if (snap.source == snap.target) return texts.map { Detailed(snap.localize(it), null, null) }

        val results = arrayOfNulls<Detailed>(texts.size)
        val uncachedIndices = mutableListOf<Int>()
        synchronized(cache) {
            texts.forEachIndexed { idx, text ->
                val hit = cache[Key(snap.source, snap.target, text)]
                if (hit != null) results[idx] = hit else uncachedIndices.add(idx)
            }
        }

        if (uncachedIndices.isNotEmpty()) {
            val outcomes = TranslationBackendRegistry.translateBatch(
                uncachedIndices.map { texts[it] },
                snap.source,
                snap.target,
            )
            android.util.Log.d(
                "CameraTranslator",
                "batch ${snap.source}->${snap.target}: backends=" +
                    outcomes.joinToString { "${it.backend.id}${if (it.isDegraded) "(degraded)" else ""}" }
                        .take(200),
            )
            synchronized(cache) {
                uncachedIndices.forEachIndexed { i, idx ->
                    val outcome = outcomes.getOrNull(i) ?: return@forEachIndexed
                    val detailed = Detailed(
                        outcome.text,
                        noteFor(outcome, snap.source, snap.target),
                        outcome.backend.displayName,
                    )
                    results[idx] = detailed
                    // Mirror the service's cache-write policy: skip degraded
                    // fallback output and LLM-displacement output so a
                    // transient failure doesn't pin a low-quality result.
                    if (!outcome.isDegraded && outcome.displacedLlmId == null) {
                        cache[Key(snap.source, snap.target, texts[idx])] = detailed
                    }
                }
            }
        }

        return results.map { d ->
            d?.copy(text = snap.localize(d.text)) ?: Detailed("", null, null)
        }
    }

    /** Mirror of the service's degraded-note derivation — the subset the
     *  panel renders inline (no icon-state aggregation here). Network is
     *  checked first, then the shared cause-typed cooldown wording via
     *  [com.playtranslate.ui.DegradedMessages] — same ordering as
     *  [com.playtranslate.CaptureService]'s toOutcome. */
    private fun noteFor(
        outcome: com.playtranslate.translation.WaterfallResult,
        source: String,
        target: String,
    ): String? = when {
        !outcome.isDegraded -> null
        outcome.displacedLlmId != null ->
            context.getString(com.playtranslate.R.string.note_low_memory_fallback)
        !isNetworkAvailable() ->
            context.getString(com.playtranslate.R.string.note_mlkit_no_internet)
        else -> com.playtranslate.ui.DegradedMessages.onlineFailureNote(
            context,
            TranslationBackendRegistry.earliestCooldownEnd(source, target),
        )
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        return cm?.activeNetwork != null
    }
}
