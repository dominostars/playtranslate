package com.playtranslate.dictionary.deinflect

import com.playtranslate.dictionary.Deinflector

/**
 * One candidate dictionary form of a looked-up word: [text], the condition
 * flag mask it carries ([conditions], 0 when the last rule applied names no
 * conditionsOut), and [transformKeys], the transforms between it and the
 * word, dictionary form outward (upstream trace order, trace[0] first).
 */
internal data class Deinflection(
    val text: String,
    val transformKeys: List<String>,
    val conditions: Int,
) {
    val chainLength: Int get() = transformKeys.size
}

/** Yomitan's Japanese deinflector ([JapaneseTransforms] through [LanguageTransformer]) as lookups call it. */
internal object JapaneseDeinflector {

    private const val CACHE_CAPACITY = 256

    /**
     * Pack part-of-speech prefix to the Yomitan condition it names; the first
     * matching prefix wins, so "Ichidan verb (zuru)" precedes "Ichidan verb".
     * The prefixes are the tokens the JA pack stores in sense.pos (measured in
     * the packVersion 4 dict.sqlite): scripts/build_jmdict.py's POS_ABBREV
     * forms ("Ichidan verb (kureru)", "Suru verb (special)", "I-adjective
     * (ii)"), JMdict's own names where POS_ABBREV has no matching key (every
     * "Godan verb with 'X' ending", whose POS_ABBREV keys spell the quote as
     * a backtick, "Godan verb - -aru special class", and "su verb - precursor
     * to the modern suru"). Nidan, Yodan, the irregular nu and ru verbs, and
     * every other token (nouns, "noun or participle which takes the aux. verb
     * suru", Na-adjective, Aux. verb, transitive and intransitive verb) name
     * no condition.
     */
    private val POS_PREFIX_CONDITIONS = listOf(
        "Ichidan verb (zuru)" to "vz",
        "Ichidan verb" to "v1",
        "Godan verb" to "v5",
        "Suru verb" to "vs",
        "su verb" to "vs",
        "Kuru verb" to "vk",
        "I-adjective" to "adj-i",
    )

    private val transformer: LanguageTransformer by lazy { LanguageTransformer(JapaneseTransforms.descriptor) }

    private val cache = object : LinkedHashMap<String, List<Deinflection>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Deinflection>>): Boolean =
            size > CACHE_CAPACITY
    }

    /**
     * Every deinflection of [word] except [word] itself (the transformer's
     * result 0) and empty texts (a rule with an empty suffixOut applied to a
     * text that is exactly its suffixIn, such as でした), de-duplicated by
     * (text, conditions) keeping the first. The order is discovery order,
     * which is already chain-length order: the transformer appends a result's
     * children after every result with a shorter trace. Memoized per word in
     * a [CACHE_CAPACITY]-entry LRU; a repeated word returns the same list.
     */
    fun candidates(word: String): List<Deinflection> {
        synchronized(cache) { cache[word]?.let { return it } }
        val computed = compute(word)
        return synchronized(cache) { cache[word] ?: computed.also { cache[word] = it } }
    }

    /** The condition flags of an entry's parts of speech (the pack's sense.pos tokens), mapped by [POS_PREFIX_CONDITIONS]. */
    fun posFlags(partsOfSpeech: Iterable<String>): Int =
        transformer.partOfSpeechFlags(
            partsOfSpeech.mapNotNull { pos -> POS_PREFIX_CONDITIONS.firstOrNull { pos.startsWith(it.first) }?.second },
        )

    /**
     * Whether [candidate] may resolve to an entry whose parts of speech give
     * [posFlags]: [LanguageTransformer.conditionsMatch] of the candidate's
     * conditions against them. A candidate with conditions 0 accepts any
     * entry, and an entry with no recognized class (flags 0) rejects every
     * candidate that carries conditions.
     */
    fun accepts(candidate: Deinflection, posFlags: Int): Boolean =
        LanguageTransformer.conditionsMatch(candidate.conditions, posFlags)

    /**
     * The kana reading of [surface] as an occurrence of the dictionary word
     * [lemma] read [lemmaReading], whose entry's parts of speech give
     * [posFlags]: 弾けた as 弾く read ひく reads ひけた. A [surface] equal to
     * [lemma] reads [lemmaReading], and a kana [surface] reads itself.
     * Otherwise the stem [surface] shares with [lemma] keeps its reading (弾,
     * ひ) and [lemma]'s kana tail (く) gives way to [surface]'s (けた), which
     * is accepted only when that reading deinflects to [lemmaReading] by a
     * chain [surface] deinflects to [lemma] by, both under the entry's class.
     * The class is what rejects the kanji whose reading changes as it
     * conjugates: 来た as 来る read くる would read くた, which deinflects
     * to くる only as an ichidan verb. Null when [surface] shares no stem with
     * [lemma], carries kanji past it, or no chain agrees.
     */
    fun occurrenceReading(surface: String, lemma: String, lemmaReading: String, posFlags: Int): String? {
        if (surface == lemma) return lemmaReading
        if (surface.all(Deinflector::isKana)) return surface
        val stem = surface.commonPrefixWith(lemma).length
        if (stem == 0) return null
        val lemmaTail = lemma.substring(stem)
        val surfaceTail = surface.substring(stem)
        if (!lemmaTail.all(Deinflector::isKana) || !surfaceTail.all(Deinflector::isKana)) return null
        if (!lemmaReading.endsWith(lemmaTail)) return null
        val reading = lemmaReading.dropLast(lemmaTail.length) + surfaceTail
        val chains = candidates(surface)
            .filter { it.text == lemma && accepts(it, posFlags) }
            .mapTo(HashSet()) { it.transformKeys }
        val agrees = candidates(reading).any {
            it.text == lemmaReading && accepts(it, posFlags) && it.transformKeys in chains
        }
        return reading.takeIf { agrees }
    }

    private fun compute(word: String): List<Deinflection> {
        val results = transformer.transform(word)
        val seen = HashSet<Pair<String, Int>>()
        val out = ArrayList<Deinflection>()
        for (i in 1 until results.size) {
            val result = results[i]
            if (result.text.isEmpty() || !seen.add(result.text to result.conditions)) continue
            out.add(Deinflection(result.text, result.trace.map { it.transformKey }, result.conditions))
        }
        return out
    }
}
