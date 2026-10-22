package com.playtranslate

/**
 * LRU cache of past translations keyed by the `(text, source, target)`
 * triple so cross-pair stale reads are impossible by construction. A
 * cached JA→EN gloss can never be served for a JA→ES lookup.
 *
 * The ROUTING IDENTITY — the preferred backend's id plus the short-text
 * routing policy knobs, built by [routingIdentity] — is tracked separately
 * via [reconcilePreferredBackend]. Any identity change clears the cache so
 * translations produced under the old routing (a different preferred
 * backend, or shorts served offline under a policy the user has since
 * flipped) aren't shadowed into the new one. Pair changes are NOT handled
 * here — the key does that job.
 *
 * One instance serves the whole process ([shared]): the capture service's
 * cycles, the live tiers, the camera tool, the import tool and
 * select-to-translate all read and write it through
 * [com.playtranslate.translation.GroupTranslator], so a translation made on
 * one surface serves the next and a settings change that clears it clears
 * it for every surface. Those consumers run on different dispatchers (the
 * service on Main, the tools on Default), so every method is synchronized
 * on the instance; [reconcilePreferredBackend] is one atomic step, and
 * [get] too, since the access-ordered LRU moves an entry on every read.
 *
 * A translate call can be in flight when the cache is cleared, by a settings
 * page ([clear]: a configuration changed under the same routing identity, an
 * LLM's model, key or URL, the cloud's key or switch) or by the routing
 * identity moving ([reconcilePreferredBackend]: a service toggled, a
 * cooldown entered or ended), and its result was made under the
 * configuration or the routing that clear ended. [configGeneration] counts
 * every clear, of either kind; whoever makes a result reads it before the
 * request goes out (the waterfall at its lookup, after its own reconcile; a
 * frame reader as it sends its frame, carried to the consumer on
 * [com.playtranslate.ocr.ReaderTranslations.configGeneration]) and the write
 * goes through [setIfConfigUnchanged] with that value, so such a result is
 * shown but never cached. The identity's clear counts in both directions: a
 * cooldown entered during a call moves the identity to the fallback whose
 * result is in flight, which would have been right to cache, but a cooldown
 * ending during a call moves it to the recovered backend, under whose
 * identity the fallback's result would then sit until evicted, serving where
 * the recovered backend should (Codex adversarial review, 2026-10-21). One
 * rule for both costs one uncached batch per transition; telling them apart
 * would need each result's backend at the write.
 */
class TranslationCache(private val capacity: Int = 500) {

    companion object {
        /** The process's one cache. */
        val shared = TranslationCache()

        /**
         * Composite routing identity for [reconcilePreferredBackend]. Beyond
         * the preferred online backend id, it folds in the two user-actionable
         * knobs that decide whether a SHORT text is served by the offline
         * route or rides the online batch: [llmContextEnabled] (the LLM full
         * bypass) and [bergamotEnabled]. Without them, a short cached from
         * Bergamot before a toggle keeps serving after it — the user flips
         * "LLM context" expecting Gemini to take over and the overlay keeps
         * showing the offline text (adversarial-review find). Deliberately
         * prefs-only: folding in the RESOLVED offline route would put
         * Bergamot's filesystem probe on every reconcile; a pack/model
         * install mid-session is the accepted residual (heals on restart).
         */
        fun routingIdentity(
            preferredOnlineId: String,
            llmContextEnabled: Boolean,
            bergamotEnabled: Boolean,
            shortTextRoutingEnabled: Boolean,
        ): String =
            "$preferredOnlineId|ctx=$llmContextEnabled|brg=$bergamotEnabled|str=$shortTextRoutingEnabled"
    }

    data class Key(val text: String, val source: String, val target: String)

    /** Value is the translated text and the display name of the backend that
     *  produced it (used to render "Translated by …" on cache hits). Degraded
     *  ML Kit fallback results are never written here — the caller's write
     *  gate (`outcome.note == null && outcome.displacedLlmId == null`) keeps
     *  this slot to online/on-device-LLM wins so an online backend can
     *  reclaim the slot when it recovers. */
    private val lru = object : LinkedHashMap<Key, Pair<String, String?>>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Pair<String, String?>>?): Boolean =
            size > capacity
    }

    private var lastPreferredBackend: String? = null

    private var generation = 0

    /** The number of clears so far, configuration ([clear]) and identity
     *  ([reconcilePreferredBackend]) alike; see the class doc. */
    val configGeneration: Int
        @Synchronized get() = generation

    @Synchronized
    operator fun get(key: Key): Pair<String, String?>? = lru[key]

    /** [set], unless a clear happened since the caller read
     *  [configGeneration] as [generation]: the value was made under the
     *  configuration or the routing that clear ended. Returns whether it
     *  wrote. */
    @Synchronized
    fun setIfConfigUnchanged(key: Key, value: Pair<String, String?>, generation: Int): Boolean {
        if (generation != this.generation) return false
        lru[key] = value
        return true
    }

    @Synchronized
    operator fun set(key: Key, value: Pair<String, String?>) {
        lru[key] = value
    }

    @Synchronized
    operator fun contains(key: Key): Boolean = key in lru

    @Synchronized
    fun size(): Int = lru.size

    /**
     * Called at the top of each translation waterfall. If [preferredBackend]
     * differs from the last-seen value, the cache is cleared — a backend
     * toggle (adding or removing a DeepL key) shouldn't serve results from
     * the previous backend. No-op on first call and when the preference
     * hasn't changed. The clear counts a generation, as [clear]'s does, so
     * a result in flight across the move is shown but never cached (the
     * class doc).
     */
    @Synchronized
    fun reconcilePreferredBackend(preferredBackend: String) {
        if (lastPreferredBackend != null && lastPreferredBackend != preferredBackend) {
            lru.clear()
            generation++
        }
        lastPreferredBackend = preferredBackend
    }

    /**
     * Force-clear every cached entry. Used by configuration changes that
     * [reconcilePreferredBackend] can't catch — e.g. an LLM backend's model
     * or API key changing while the backend id stays "openai"/"gemini".
     * Without this, cached entries produced by the old config keep getting
     * served after the user explicitly switched to a different model.
     */
    @Synchronized
    fun clear() {
        lru.clear()
        generation++
    }

    /** For tests of the [shared] instance: every entry and the routing
     *  identity dropped, so a test starts from the empty cache a fresh
     *  process has. */
    @Synchronized
    fun resetForTest() {
        lru.clear()
        lastPreferredBackend = null
        generation++
    }
}
