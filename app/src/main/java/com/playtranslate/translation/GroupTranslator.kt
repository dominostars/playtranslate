package com.playtranslate.translation

import android.content.Context
import android.util.Log
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.TranslationCache
import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.language.ShortTextTokenCounters
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.language.isShortText
import com.playtranslate.net.NetworkConnectivity
import com.playtranslate.ocr.ReaderTranslations
import com.playtranslate.ui.DegradedMessages
import com.playtranslate.ui.DegradedWarningKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "GroupTranslator"

/** Per-group translation triple returned by the batch / single paths.
 *  Carries the backend's display name so the results view can render
 *  "Translated by …" alongside the existing warning [note]. */
data class GroupTranslation(
    val text: String,
    val note: String?,
    val backendDisplayName: String?,
)

/**
 * The one translate handshake every surface that translates OCR groups
 * goes through: the capture service's cycles and live tiers, the camera
 * tool, the import tool and select-to-translate. One instance per surface
 * over the process's one [TranslationCache] ([TranslationCache.shared]), so
 * a translation made on one surface serves the next, and a configuration
 * change that clears the cache clears it for all.
 *
 * It holds the cache-first batched waterfall ([translateThroughCache]), the
 * cache-write policy, the same-language bypass, the degraded note, the
 * script-variant localization, and the handshake with a frame reader that
 * translates beside its OCR ([ReaderTranslations]): what can be shown at
 * once ([translationsAtOnce]), and the one wait rule
 * ([readerTranslationsToTake]) by which [translateGroupsSeparately] and the
 * capture core's deferral decide whether to wait for the reader.
 *
 * [onDegraded] receives each batch's worst degradation kind, and None on the
 * same-language bypass: the service passes its floating icon's badge, a tool
 * passes nothing (its degraded note is inline in the panel, and a camera
 * outcome must not change the icon over the game).
 *
 * The cache is thread-safe, so a surface calls from any dispatcher (the
 * service from its Main scope, the tools from Default); a reader's state is
 * collected on the caller's dispatcher, so [onProgress] callbacks run there.
 */
class GroupTranslator(
    private val context: Context,
    private val cache: TranslationCache,
    private val onDegraded: (DegradedWarningKind) -> Unit = {},
) {

    companion object {
        /** A backend's configuration changed under the same id (an LLM's
         *  model, key or URL; the cloud's switch or key): every translation
         *  the process's one cache holds was made under the old one, so it
         *  is dropped, for every surface, whether or not the capture service
         *  runs. The settings pages' one hook for that. */
        fun clearShared() = TranslationCache.shared.clear()

        /** The preferred backend or a routing knob may have changed (a
         *  service added, toggled, deleted or reordered; the short-text
         *  switch): the shared cache's routing identity is reconciled now
         *  ([reconcileBackendPreference]), so an all-cached batch cannot
         *  serve the old routing's entries. Service-free for the same reason
         *  as [clearShared]. */
        fun reconcileShared(context: Context) =
            GroupTranslator(context, TranslationCache.shared).reconcileBackendPreference()
    }

    /** Immutable snapshot of the translation pair at the moment a
     *  translation request enters. Threaded through every downstream call
     *  so that a concurrent [Prefs] change mid-batch can't poison a cache
     *  entry (translated under the new pair but keyed under the old): both
     *  the cache key and the translator selection derive from the *same*
     *  target value. No secret is read here: each backend reads its own key
     *  when it is called. */
    private data class TranslationTarget(
        val source: String,
        val target: String,
        /** Chinese script choice; meaningful only when [target] == "zh". */
        val chineseVariant: ChineseScriptVariant,
        /** True when the OCR'd source is already Traditional (ZH_HANT) — so a
         *  same-language passthrough must NOT be re-run through a Simplified→
         *  Traditional pass. Only [source]/[target] feed the cache key, so these
         *  extra fields don't fragment the shared "zh" cache. */
        val sourceIsTraditional: Boolean,
        /** The resolved source-language id behind [source], for the short-text
         *  classifier's per-language segmenter. Snapshotted with the pair so a
         *  mid-batch source switch can't classify under one language and
         *  translate under another. */
        val sourceId: SourceLangId,
    ) {
        /** OpenCC converter for Traditional Chinese output, or null when no
         *  conversion applies (non-Chinese target, Simplified, or an already-
         *  Traditional source passthrough). */
        private val chineseConverter: ChineseScriptConverter?
            get() = ChineseScriptConverter.forTarget(target, chineseVariant, sourceIsTraditional)

        /** Convert a finished target-language string to the chosen Traditional
         *  variant. No-op for non-Chinese / Simplified targets. Applied strictly
         *  at read/return time — never before a cache write (the cache stores
         *  Simplified, shared across all variants). */
        fun localize(text: String): String = chineseConverter?.convert(text) ?: text
    }

    /** Capture a [TranslationTarget] from current [Prefs]. Called once at
     *  the outermost layer of each translation entry point; downstream calls
     *  thread the captured value rather than re-reading Prefs, so mid-batch
     *  changes can't create inconsistency between key-derivation and
     *  translator selection. */
    private fun snapshotTranslationTarget(sourceOverride: SourceLangId? = null): TranslationTarget {
        val prefs = Prefs(context)
        // [sourceOverride] lets a re-OCR translate in the language that actually
        // produced the result (its provenance), not the current pref — the user may
        // have switched source language since. Target/variant still come from prefs.
        val srcId = sourceOverride ?: prefs.sourceLangId
        return TranslationTarget(
            source = SourceLanguageProfiles[srcId].translationCode,
            target = prefs.targetLang,
            chineseVariant = prefs.targetChineseVariant,
            sourceIsTraditional = srcId == SourceLangId.ZH_HANT,
            sourceId = srcId,
        )
    }

    /** Called at the top of every translation call to keep the cache's
     *  ROUTING identity (preferred backend + short-text policy knobs, see
     *  [TranslationCache.routingIdentity]) in sync with current
     *  configuration. Backends themselves are owned by
     *  [TranslationBackendRegistry] and are pair-agnostic singletons —
     *  there is no per-pair instance churn to reconcile. Pair changes are
     *  handled by the cache key itself — no explicit clear needed. */
    private fun reconcileCache(target: TranslationTarget) {
        val prefs = Prefs(context)
        cache.reconcilePreferredBackend(
            TranslationCache.routingIdentity(
                preferredOnlineId =
                    TranslationBackendRegistry.preferredOnlineId(target.source, target.target),
                llmContextEnabled = prefs.llmContextEnabled,
                bergamotEnabled = prefs.bergamotEnabled,
                shortTextRoutingEnabled = prefs.debugShortTextRouting,
            )
        )
    }

    /** Public hook for callers (Settings UI today) to drive cache
     *  reconciliation eagerly when they know the user just changed
     *  backend preferences — e.g. flipped the DeepL toggle, saved a new
     *  DeepL key. Without this, an all-cached translate batch can serve
     *  stale entries because the waterfall (where reconciliation lives) is
     *  never invoked. Cheap: a Map-clear on transition, no-op otherwise. */
    fun reconcileBackendPreference() {
        reconcileCache(snapshotTranslationTarget())
    }

    private fun cacheKey(text: String, target: TranslationTarget): TranslationCache.Key =
        TranslationCache.Key(text, target.source, target.target)

    /** The cache's entry for [sourceText] under the current pair (with
     *  [sourceOverride] as the source), localized; null when there is none.
     *  Hits carry the producing backend's display name so cache-filled boxes
     *  stay attributable. */
    fun cachedTranslation(sourceText: String, sourceOverride: SourceLangId? = null): GroupTranslation? =
        cachedTranslation(sourceText, snapshotTranslationTarget(sourceOverride))

    /** Shared by [cachedTranslation], [translationsAtOnce] and
     *  [readerTranslationsToTake]. */
    private fun cachedTranslation(sourceText: String, target: TranslationTarget): GroupTranslation? =
        cache[cacheKey(sourceText, target)]?.let { (text, backend) ->
            GroupTranslation(target.localize(text), note = null, backendDisplayName = backend)
        }

    /**
     * Translates each group in parallel, using cached results for groups
     * whose original text hasn't changed. Only cache misses hit the network.
     *
     * The [TranslationTarget] is snapshotted once at entry and threaded to
     * every downstream call so key-derivation and translator selection agree
     * even if another code path mutates Prefs mid-batch.
     *
     * Cache write policy: online backend results (DeepL and Lingva) and
     * fully-successful on-device LLM results (TG, Qwen) are cached. Two
     * categories are skipped:
     *   - ML Kit degraded fallback (signalled by a non-null note set in
     *     [translate]) — so online services can reclaim the slot on recovery.
     *   - On-device LLM displacement (signalled by a non-null
     *     [TranslateOutcome.displacedLlmId]) — when TG or Qwen threw a
     *     transient low-memory exception and the waterfall fell through to
     *     a lower-priority backend, the fallback's output shouldn't outlast
     *     the memory pressure window.
     *
     * [reader]: the translations the frame's reader produces with its OCR (a
     * combined OCR and translation service), keyed by position in
     * [groupTexts]. Used only when they are for this call's pair; under any
     * other pair they are ignored and every position goes through the cache
     * and the waterfall as if there were none. Otherwise this waits for them
     * only when the reader can still name a position whose text the cache
     * has no entry for ([ReaderTranslations.awaited]), reporting each
     * delivery on the way through [onProgress] by position (null = nothing
     * yet; the reader's texts, localized and named by its provider, not
     * cached, since they are provisional). When the cache has an entry for
     * every position the reader has yet to name, this takes what is final
     * now ([ReaderTranslations.finalSoFar]: what arrived with the OCR, or
     * everything once the reader settled) without waiting: the cache already
     * answers those positions, so waiting would only hold the caller to
     * reword text it can show at once. A position the reader names takes its
     * text, cached as the provider's like any backend's result, over a cache
     * entry: the reader's text is the fresher one, so a call that waits
     * takes the reader's text at a position the cache filled too. Every
     * position it does not name (it left the group untranslated, its final
     * part failed, the consumer maps no group there, or the call did not
     * wait for it) goes through the cache and the waterfall in this same
     * call. [onProgress] is never called without a wait, nor for the settled
     * state, which this returns.
     *
     * The reader's state is collected on the caller's dispatcher, so
     * [onProgress] runs there too.
     */
    suspend fun translateGroupsSeparately(
        groupTexts: List<String>,
        sourceOverride: SourceLangId? = null,
        reader: ReaderTranslations? = null,
        onProgress: (suspend (List<GroupTranslation?>) -> Unit)? = null,
    ): List<GroupTranslation> {
        val target = snapshotTranslationTarget(sourceOverride)

        // OCR-only bypass: when source and target language are the same,
        // skip translation entirely — OCR output is the final result.
        // This handles all paths: one-shot hold, live mode, and in-app panel.
        // Clear degraded state too — bypass means we aren't going through a
        // backend, so any stale "Offline"/"degraded" badge from a prior
        // fallback should drop.
        if (target.source == target.target) {
            onDegraded(DegradedWarningKind.None)
            // Edge case: Chinese source → Chinese-Traditional target. The OCR
            // text is the result, but a Simplified (ZH) source still needs
            // s2t/s2tw/s2hk applied; an already-Traditional (ZH_HANT) source is
            // a no-op (target.localize handles both via sourceIsTraditional).
            return groupTexts.map { GroupTranslation(target.localize(it), null, null) }
        }

        val provided: Map<Int, String> = when {
            reader == null -> emptyMap()
            !reader.isFor(target.source, target.target) -> {
                Log.i(TAG, "provided translations ignored: the language pair changed since the read")
                emptyMap()
            }
            else -> readerTranslationsToTake(groupTexts, reader, target, onProgress)
        }
        if (reader == null || provided.isEmpty()) return translateThroughCache(groupTexts, target)

        val taken = takeReaderTranslations(groupTexts, provided, reader, target)
        val rest = groupTexts.indices.filter { taken[it] == null }
        // In position order, as the nulls they fill.
        val restTranslated =
            if (rest.isEmpty()) emptyList() else translateThroughCache(rest.map { groupTexts[it] }, target)
        val next = restTranslated.iterator()
        return taken.map { it ?: next.next() }
    }

    /** What [reader], which the caller has checked to be for the current
     *  pair (with [sourceOverride] as the source), gives [groupTexts] by
     *  position, by the one wait rule ([translateGroupsSeparately]'s kdoc):
     *  for the capture core's deferral, which decides by it so that a
     *  capture that may defer waits for the reader exactly when its
     *  translate call would. */
    suspend fun readerTranslationsToTake(
        groupTexts: List<String>,
        reader: ReaderTranslations,
        sourceOverride: SourceLangId? = null,
        onProgress: (suspend (List<GroupTranslation?>) -> Unit)? = null,
    ): Map<Int, String> = readerTranslationsToTake(groupTexts, reader, snapshotTranslationTarget(sourceOverride), onProgress)

    /** What [reader], already checked to be for [target]'s pair, gives
     *  [groupTexts] by position: its translations once it settles
     *  ([ReaderTranslations.await], each delivery on the way reported through
     *  [onProgress] as [translateGroupsSeparately] describes) when it can still
     *  name a position whose text the cache has no entry for under [target],
     *  else what is final now ([ReaderTranslations.finalSoFar]) without
     *  waiting, since the cache already answers every position the reader has
     *  yet to name. */
    private suspend fun readerTranslationsToTake(
        groupTexts: List<String>,
        reader: ReaderTranslations,
        target: TranslationTarget,
        onProgress: (suspend (List<GroupTranslation?>) -> Unit)?,
    ): Map<Int, String> {
        val cacheLacksOne = reader.awaited.any { cachedTranslation(groupTexts[it], target) == null }
        if (!cacheLacksOne) return reader.finalSoFar
        return reader.await(
            onProgress?.let { report ->
                { delivered ->
                    report(
                        List(groupTexts.size) { i ->
                            delivered[i]?.let {
                                GroupTranslation(target.localize(it), note = null, backendDisplayName = reader.providerName)
                            }
                        },
                    )
                }
            },
        )
    }

    /** What can be shown for [groupTexts] before any wait, by position: the reader's
     *  translation that arrived with its OCR where there is one (final, so cached here
     *  as the reader's, like any backend's result), else the cache's entry, else null.
     *  The reader's are taken only for this call's pair; all null when source and
     *  target are the same language (nothing is translated). */
    fun translationsAtOnce(
        groupTexts: List<String>,
        reader: ReaderTranslations?,
        sourceOverride: SourceLangId? = null,
    ): List<GroupTranslation?> {
        val target = snapshotTranslationTarget(sourceOverride)
        if (target.source == target.target) return List(groupTexts.size) { null }
        val cached = groupTexts.map { cachedTranslation(it, target) }
        if (reader == null || !reader.isFor(target.source, target.target)) return cached
        // The reader's text wins over a cache entry: it is the fresher one.
        return takeReaderTranslations(groupTexts, reader.ready, reader, target)
            .zip(cached) { fromReader, fromCache -> fromReader ?: fromCache }
    }

    /** [reader]'s [byPosition] texts for [groupTexts] under [target]: each one
     *  cached as its provider's ([ReaderTranslations.providerName]), so the live
     *  tiers and the next capture serve it like any backend's entry, and returned
     *  localized and named by that provider; null at a position it does not name.
     *  Shared by [translationsAtOnce] and [translateGroupsSeparately]. Cached only
     *  while the configuration the reader's request went out under still stands
     *  ([ReaderTranslations.configGeneration], [TranslationCache.setIfConfigUnchanged]),
     *  not the one this call found: the request came first, so a settings clear
     *  between the two ended the configuration these texts were made under. */
    private fun takeReaderTranslations(
        groupTexts: List<String>,
        byPosition: Map<Int, String>,
        reader: ReaderTranslations,
        target: TranslationTarget,
    ): List<GroupTranslation?> {
        if (byPosition.isEmpty()) return List(groupTexts.size) { null }
        val providerName = reader.providerName
        // The cache's routing identity is reconciled before these writes, the
        // rule every cache write follows (see translateThroughCache), so a
        // backend toggle since the last call clears the old entries first
        // instead of these landing among them.
        reconcileCache(target)
        // The degraded state is left untouched: no waterfall ran for these,
        // the same rule as a fully cached batch.
        return groupTexts.mapIndexed { i, text ->
            byPosition[i]?.let { translation ->
                cache.setIfConfigUnchanged(cacheKey(text, target), translation to providerName, reader.configGeneration)
                // Localized after the cache write, like the waterfall's results:
                // the shared "zh" cache keeps Simplified for every variant.
                GroupTranslation(target.localize(translation), note = null, backendDisplayName = providerName)
            }
        }
    }

    /** [groupTexts] translated under [target] cache-first, the misses in one
     *  batched waterfall: [translateGroupsSeparately]'s path for every position
     *  no reader names.
     *
     *  The hits are taken at the lookup and kept for the return: the cache is
     *  shared by every surface, and between the lookup and the return another
     *  surface's call (or this call's own identity reconcile after the
     *  waterfall) can clear or evict an entry, which a second read would then
     *  answer with a blank. The misses' results are cached only while the
     *  configuration the lookup saw still stands ([TranslationCache.configGeneration]):
     *  a settings clear during the waterfall ended the configuration they
     *  were made under, so they are returned and not written. */
    private suspend fun translateThroughCache(
        groupTexts: List<String>,
        target: TranslationTarget,
    ): List<GroupTranslation> {
        // Reconcile the cache's preferred-backend identity BEFORE the
        // cache lookup. If a fully-cached batch returns early, the
        // identity check inside translateBatch never runs, so a backend
        // toggle / cooldown enter-or-exit since the last call would
        // leave stale entries serving forever. Cheap (one Map clear on
        // transition, no-op otherwise).
        reconcileCache(target)
        val configGeneration = cache.configGeneration

        val keys = groupTexts.map { cacheKey(it, target) }
        val hits = HashMap<TranslationCache.Key, Pair<String, String?>>()
        val uncached = keys.withIndex()
            .filter { (_, key) -> cache[key]?.also { hits[key] = it } == null }

        val freshByKey: Map<TranslationCache.Key, GroupTranslation> = if (uncached.isNotEmpty()) {
            // Single batched waterfall (one HTTP request per backend
            // pass when the backend implements BatchTranslator —
            // DeepL, Gemini, OpenAI, Lingva) instead of N parallel
            // single-text calls. Eliminates the rate-limit thrashing
            // we saw on free-tier Gemini where parallel pairs of
            // requests would each lose ~half to 429s. Non-batching
            // backends (ML Kit, on-device LLMs) keep their per-text
            // parallel fan-out inside the registry, so today's
            // structured-cancellation guarantee (children of the
            // calling capture job) is preserved end-to-end.
            val texts = uncached.map { it.value.text }

            // Short-text offline routing — OFF BY DEFAULT, debug-row gated
            // (Prefs.debugShortTextRouting; device-pass verdict 2026-09-02:
            // 2★ output too poor even on ordinary short phrases). When on:
            // texts with ≤2 content words (menu items, HUD labels, one-word
            // lines) go to the fast offline tier instead of spending online
            // quota; everything else still travels as ONE online batch.
            // Bypassed entirely when the backend that
            // would serve is a context-carrying batching LLM — shorts then
            // ride its batch so dialogue-choice one-worders keep the context
            // ring (user decision; the cost is one LLM request on all-short
            // pages). NOTE: translateOnce (deliberate re-translate / edit /
            // select) stays online-only by design. Short results — including
            // deliberately-routed ML Kit ones (user decision: cached, no
            // degraded note) — enter the cache as first-class entries. The
            // cache's routing identity folds in llmContextEnabled +
            // bergamotEnabled (TranslationCache.routingIdentity), so flipping
            // either toggle clears cached shorts and routing changes apply
            // immediately; the residual pin (a pack/model INSTALL
            // mid-session, identity unchanged) is accepted — bounded by the
            // 500-entry LRU and process restart. The whole routing decision
            // runs off-main: the bypass check and route resolve do filesystem
            // probes (Bergamot's isUsable) and possibly a GMS round-trip
            // (ML Kit model presence), and a first JA/ZH/TH classification
            // pays lazy segmenter init (Sudachi mmap / HanLP models / Thai
            // trie). Resolve happens only AFTER classification found shorts,
            // so all-sentence pages pay nothing beyond the classifier. No
            // cache access crosses the dispatcher — only the (route, shorts)
            // pair comes back; null = today's single batch.
            val routing: Pair<ShortTextOfflineRoute, List<Boolean>>? =
                if (!Prefs(context).debugShortTextRouting) null
                else withContext(Dispatchers.Default) {
                    val llmBypass = shouldBypassForLlm(
                        TranslationBackendRegistry.preferredBackend(target.source, target.target),
                        Prefs(context).llmContextEnabled,
                    )
                    if (llmBypass) return@withContext null
                    val profile = SourceLanguageProfiles[target.sourceId]
                    val counter = ShortTextTokenCounters.forLanguage(context, target.sourceId)
                    val shorts = texts.map { isShortText(it, profile, counter) }
                    if (shorts.none { it }) return@withContext null
                    ShortTextOfflineRoute.resolve(target.source, target.target)
                        ?.let { it to shorts }
                }

            val dispatch = if (routing == null) {
                translateBatch(texts, target).let { PartitionedResult(it, it) }
            } else {
                val (route, shorts) = routing
                dispatchPartitioned(
                    texts, shorts,
                    offline = { t ->
                        withContext(Dispatchers.Default) {
                            route.translateOrNull(t, target.source, target.target)
                        }?.let {
                            TranslateOutcome(it, null, DegradedWarningKind.None, null, route.displayName)
                        }
                    },
                    online = { batch -> translateBatch(batch, target) },
                )
            }
            val outcomes = dispatch.all

            // Set the icon/menu state ONCE from the worst outcome the ONLINE
            // waterfall produced. Each child's `translate` no longer touches
            // the global state, so a clean group that happens to finish after
            // a displaced sibling can't clear the warning. Deliberately-offline
            // short results are excluded: their kind is None by construction,
            // and letting an all-short page aggregate to None would CLEAR the
            // "Offline" badge while the online tier is still cooling down
            // (flapping between all-short and mixed pages). When no online
            // call ran at all (fully-cached batch, or an all-short page), the
            // state is left untouched — the same rule the fully-cached early
            // return has always followed.
            dispatch.fromOnline.maxByOrNull { it.kind.severity() }?.let { onDegraded(it.kind) }

            // Re-reconcile AFTER translate too. A higher-priority backend
            // may have cooled down DURING translateBatch — preferredOnlineId
            // would now return the fallback id, but the pre-call reconcile
            // didn't see that change. Without this second pass, the new
            // fallback entry below would be cached under the old
            // (pre-cooldown) preferred-backend identity; if the user
            // doesn't translate again before the cooldown expires, identity
            // never flips and the lower-quality result pins forever. Cheap
            // (one Map clear on transition, no-op when no cooldown change).
            reconcileCache(target)

            uncached.zip(outcomes).forEach { (indexedKey, outcome) ->
                // Cache write policy: skip when an on-device LLM was displaced
                // by transient low memory (outcome.displacedLlmId != null).
                // Without this, a single low-memory moment freezes the
                // fallback's output in the cache, so the next call returns
                // the same lower-quality result even after memory recovers.
                // The existing note-based skip (ML Kit degraded fallback)
                // still applies in parallel.
                //
                // Online-backend cooldowns (rate-limit / quota / billing)
                // are handled at the cache-identity layer (the
                // reconcileCache call above ran twice — once before lookup,
                // once after translate — so any cooldown entered during this
                // batch is reflected in the identity before these writes
                // land).
                if (outcome.note == null && outcome.displacedLlmId == null) {
                    cache.setIfConfigUnchanged(
                        indexedKey.value, outcome.text to outcome.backendDisplayName, configGeneration,
                    )
                }
            }

            uncached.map { it.value }.zip(
                outcomes.map { GroupTranslation(it.text, it.note, it.backendDisplayName) }
            ).toMap()
        } else emptyMap()

        return keys.map { key ->
            hits[key]?.let { (text, backendDisplayName) ->
                GroupTranslation(text, note = null, backendDisplayName = backendDisplayName)
            }
                ?: freshByKey[key]
                ?: GroupTranslation("", null, null)
        }
            // Convert to the chosen Traditional variant AFTER the cache read, so
            // the shared "zh" cache keeps storing Simplified for every variant.
            .map { it.copy(text = target.localize(it.text)) }
    }

    /** On-demand translation for a single text string (used by edit overlay, drag-sentence, etc.). */
    suspend fun translateOnce(text: String): GroupTranslation =
        translateOnce(text, snapshotTranslationTarget())

    /** [translateOnce] under an EXPLICIT pair — the caller's snapshot, not
     *  the prefs at call time — so a deliberate sentence translated after a
     *  language change (a deferred reveal, a call that outlived a switch)
     *  runs, records and displays under the one pair its result claims.
     *  The script variant still comes from prefs. */
    suspend fun translateOnce(
        text: String,
        sourceLangId: SourceLangId,
        targetLang: String,
    ): GroupTranslation =
        translateOnce(text, snapshotTranslationTarget(sourceLangId).copy(target = targetLang))

    private suspend fun translateOnce(text: String, target: TranslationTarget): GroupTranslation {
        val outcome = translate(text, target)
        onDegraded(outcome.kind)
        return GroupTranslation(target.localize(outcome.text), outcome.note, outcome.backendDisplayName)
    }

    /**
     * Active cooldown for the floating-icon pill while the degradation
     * kind is [DegradedWarningKind.Offline]: the soonest-ending cooldown
     * (with its typed cause) among backends usable for the CURRENT
     * language pair, or null when the degradation isn't cooldown-driven
     * (genuinely offline, or a fresh failure nothing has cooled down
     * yet). Null with no network — same ordering rule as the note.
     */
    fun currentDegradedCooldown(): ActiveCooldown? {
        if (!NetworkConnectivity.isAvailable(context)) return null
        val target = snapshotTranslationTarget()
        return TranslationBackendRegistry.earliestCooldownEnd(target.source, target.target)
    }

    /** Internal translation outcome. Carries the user-visible (text, note)
     *  pair plus the [WaterfallResult.displacedLlmId] cache-skip signal and
     *  the per-call [kind] used to aggregate degradation state across the
     *  groups in a batch. Internal to this class — the public methods
     *  ([translateOnce], [translateGroupsSeparately]) flatten back to
     *  [GroupTranslation] so callers outside the cache layer don't grow a
     *  dependency on the registry's displacement type. */
    private data class TranslateOutcome(
        val text: String,
        val note: String?,
        val kind: DegradedWarningKind,
        val displacedLlmId: BackendId?,
        val backendDisplayName: String?,
    )

    /** Order DegradedWarningKind by severity so a batch's worst outcome
     *  drives the icon/menu state, regardless of completion order. */
    private fun DegradedWarningKind.severity(): Int = when (this) {
        DegradedWarningKind.None -> 0
        DegradedWarningKind.Offline -> 1
        DegradedWarningKind.LowMemory -> 2
    }

    /**
     * Run the translation waterfall and synthesise an inline note when
     * the chosen backend is the degraded fallback.
     *
     * The waterfall itself lives in [TranslationBackendRegistry.translate]
     * so it is testable on the JVM without dragging a service in. This
     * method is the single choke point that:
     *   - reconciles the cache's preferred-backend identity (mid-batch
     *     pref changes pick up here without a per-caller round trip),
     *   - turns the [WaterfallResult] into the (text, note) tuple consumed
     *     by callers,
     *   - and propagates [kotlinx.coroutines.CancellationException] so a
     *     cancelled capture reaches its terminal Cancelled state instead of
     *     stuck-in-flight.
     *
     * Note discipline: a non-null note is the "don't cache" signal in
     * [translateGroupsSeparately] — online backends can then reclaim the
     * cache slot on recovery.
     */
    private suspend fun translate(text: String, target: TranslationTarget): TranslateOutcome {
        // OCR-only bypass: when source and target language are the same, skip
        // translation entirely. This is the universal choke point — every
        // single-text translation path (translateOnce callers: edit overlay,
        // drag-sentence, sentence tab) flows through here. The earlier
        // bypass in translateGroupsSeparately is a redundant early-return
        // for the group/cache path.
        if (target.source == target.target) {
            return TranslateOutcome(text, null, DegradedWarningKind.None, null, null)
        }

        reconcileCache(target)
        val result = TranslationBackendRegistry.translate(text, target.source, target.target)
        return result.toOutcome(target.source, target.target)
    }

    /**
     * Batched counterpart to [translate]. The fan-out used to live in
     * [translateGroupsSeparately] as N parallel single-text [translate]
     * calls; the batch waterfall in [TranslationBackendRegistry.translateBatch]
     * replaces that fan-out with one HTTP request per backend pass
     * where the backend implements [BatchTranslator].
     *
     * Must call [reconcileCache] here once before dispatch — the per-text
     * [translate] used to do that on every call, and the cache's
     * preferred-backend reconciliation rides on it. Skipping it would let a
     * backend toggled mid-session serve stale cache entries.
     */
    private suspend fun translateBatch(
        texts: List<String>,
        target: TranslationTarget,
    ): List<TranslateOutcome> {
        if (target.source == target.target) {
            return texts.map { TranslateOutcome(it, null, DegradedWarningKind.None, null, null) }
        }
        reconcileCache(target)
        val results = TranslationBackendRegistry.translateBatch(texts, target.source, target.target)
        return results.map { it.toOutcome(target.source, target.target) }
    }

    /** Map a [WaterfallResult] to a [TranslateOutcome] with the per-result
     *  kind + inline-note logic. Used by both the single-text [translate]
     *  and the batched [translateBatch] paths so the degraded-state
     *  semantics stay identical between them. */
    private fun WaterfallResult.toOutcome(
        source: String,
        target: String,
    ): TranslateOutcome {
        // Per-group kind. Displacement that bottomed out at ML Kit is the
        // LowMemory kind; ML Kit chosen for network/service reasons is
        // Offline. Displacement that stayed in the offline tier (Qwen
        // picked up after TG) is None — the result is high-quality offline
        // output, so we don't visually flag it; the Settings row's
        // live availMem check carries that signal on its own.
        //
        // We do NOT call onDegraded here — that would let one group's
        // outcome clobber a sibling group's worse outcome in a batched
        // translation. Aggregation happens once per batch in
        // [translateGroupsSeparately] / per call in [translateOnce].
        val kind = when {
            !this.isDegraded -> DegradedWarningKind.None
            this.displacedLlmId != null -> DegradedWarningKind.LowMemory
            else -> DegradedWarningKind.Offline
        }
        // The inline note adds more detail than the icon needs — when the
        // cause is "Offline", distinguish network-not-present from the
        // active-cooldown wordings (transient / quota / account, selected
        // by the typed CooldownCause inside DegradedMessages) and the
        // generic service-unavailable. The floating icon's pill makes the
        // same cooldown query at render time via [currentDegradedCooldown].
        // Network is checked FIRST: with no internet, a cooldown still
        // ticking on some backend is not what the user needs to fix.
        val note = when (kind) {
            DegradedWarningKind.None -> null
            DegradedWarningKind.LowMemory ->
                context.getString(R.string.note_low_memory_fallback)
            DegradedWarningKind.Offline ->
                if (!NetworkConnectivity.isAvailable(context))
                    context.getString(R.string.note_mlkit_no_internet)
                else DegradedMessages.onlineFailureNote(
                    context,
                    TranslationBackendRegistry.earliestCooldownEnd(source, target),
                )
        }
        return TranslateOutcome(this.text, note, kind, this.displacedLlmId, this.backend.displayName)
    }
}
