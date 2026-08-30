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
 * Not thread-safe. [com.playtranslate.CaptureService] mutates exclusively
 * from its `serviceScope` (Main-dispatched) with network calls dispatched
 * to IO via `withContext`, so read/write ordering on the cache is
 * implicitly serial.
 */
class TranslationCache(private val capacity: Int = 500) {

    companion object {
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
        ): String = "$preferredOnlineId|ctx=$llmContextEnabled|brg=$bergamotEnabled"
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

    operator fun get(key: Key): Pair<String, String?>? = lru[key]

    operator fun set(key: Key, value: Pair<String, String?>) {
        lru[key] = value
    }

    operator fun contains(key: Key): Boolean = key in lru

    fun size(): Int = lru.size

    /**
     * Called at the top of each translation waterfall. If [preferredBackend]
     * differs from the last-seen value, the cache is cleared — a backend
     * toggle (adding or removing a DeepL key) shouldn't serve results from
     * the previous backend. No-op on first call and when the preference
     * hasn't changed.
     */
    fun reconcilePreferredBackend(preferredBackend: String) {
        if (lastPreferredBackend != null && lastPreferredBackend != preferredBackend) {
            lru.clear()
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
    fun clear() {
        lru.clear()
    }
}
