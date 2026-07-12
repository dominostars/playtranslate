package com.playtranslate.translationlog

/**
 * The LLM-context sink: a small in-memory ring of recent gated
 * source→translation pairs. EPHEMERAL BY CONTRACT — never touches disk,
 * cleared on live-session end and on language change — and fully
 * independent of the History store (context can be on while history is
 * off, and vice versa).
 *
 * Thread model: mutations arrive on the main thread (the recorder's
 * seams); [block] is read from translation-backend threads via
 * [com.playtranslate.translation.llm.LlmPromptTemplates.contextProvider].
 * State is one @Volatile immutable list swapped under [lock] — readers
 * never lock, never see partial mutations.
 *
 * Read-time caps (all PROVISIONAL, sized against
 * PROMPT_LENGTH_ADVISORY_CHARS=4000 and Luna/Sakura's shipped default of
 * 3 pairs): last [MAX_PAIRS] pairs, [MAX_CHARS] total block chars,
 * [MAX_AGE_MS] recency, same language pair only. A single over-budget
 * pair is skipped outright — one monster line is exactly the prefill
 * latency this cap exists to prevent.
 */
internal class ContextRing {

    data class ContextPair(
        val source: String,
        val translation: String,
        val atMs: Long,
        val normKey: String,
        val sourceLang: String,
        val targetLang: String,
    )

    private val lock = Any()

    @Volatile
    private var pairs: List<ContextPair> = emptyList()

    fun push(pair: ContextPair) = synchronized(lock) {
        pairs = (pairs + pair).takeLast(RING_CAP)
    }

    /** Supersession: the fuller read of [prevKey]'s line replaces it in place. */
    fun replaceByKey(prevKey: String, pair: ContextPair) = synchronized(lock) {
        val i = pairs.indexOfLast { it.normKey == prevKey }
        pairs = if (i >= 0) {
            pairs.toMutableList().also { it[i] = pair }
        } else {
            (pairs + pair).takeLast(RING_CAP)
        }
    }

    fun clear() = synchronized(lock) { pairs = emptyList() }

    /** The `{context}` block: "" when nothing qualifies, else a
     *  "\n\n"-terminated block (the [LlmPromptTemplates.TOKEN_CONTEXT]
     *  contract — empty must render the prompt byte-identical). */
    fun block(sourceLang: String, targetLang: String, nowMs: Long): String {
        val snapshot = pairs
        val eligible = snapshot.filter {
            it.sourceLang == sourceLang && it.targetLang == targetLang &&
                nowMs - it.atMs <= MAX_AGE_MS
        }.takeLast(MAX_PAIRS)
        if (eligible.isEmpty()) return ""

        val lines = ArrayList<String>(eligible.size)
        var chars = HEADER.length
        // Newest pairs are the most relevant — spend the char budget on
        // them first, then emit in chronological order.
        for (pair in eligible.asReversed()) {
            val line = "- ${pair.source} → ${pair.translation}\n"
            if (chars + line.length > MAX_CHARS) continue
            chars += line.length
            lines.add(line)
        }
        if (lines.isEmpty()) return ""
        lines.reverse()
        return HEADER + lines.joinToString("") + "\n"
    }

    companion object {
        const val MAX_PAIRS = 3
        const val MAX_CHARS = 600
        const val MAX_AGE_MS = 3L * 60 * 1000
        private const val RING_CAP = 8
        private const val HEADER = "Recent dialogue lines, for context only:\n"
    }
}
