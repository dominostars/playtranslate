package com.playtranslate.language

/** One unit of a fused headword, in headword order, for [alignMemberReadings]. */
internal data class AlignUnit(
    val surface: String,
    val lookupForm: String,
    /** Dictionary readings (hiragana) of the entries [lookupForm] resolves
     *  to; for a kana unit, its own hiragana. Null = no entry known: the
     *  unit is a WILDCARD that absorbs any non-empty run of the phrase
     *  reading (手当たり in 手当たり次第 has no JMdict entry). */
    val dictReadings: Set<String>?,
)

/**
 * Chooses, for each unit of a fused headword, the dictionary reading under
 * which the units' surface readings concatenate to the headword's OWN
 * reading — the hint that selects the right ENTRY downstream. Sudachi's
 * per-token readings are guesses made on the isolated headword and miss
 * lexicalized phrases: 瞬く間に is またたくまに, but the tokenizer reads
 * 瞬く as しばたたく and 間 as あいだ, and a wrong hint resolves 瞬く to
 * entry 2831002 "to blink repeatedly" instead of 1341200 またたく.
 *
 * Per unit the candidates are its dictionary readings adjusted for the
 * surface's inflection ([surfaceReadingCandidates]). Tolerance for the two
 * regular compound sound changes: rendaku on a unit's FIRST kana (一人暮らし
 * = ひとりぐらし matches 暮らし's くらし; the hint stays くらし, the
 * dictionary's form) and sokuon on its LAST (一泊 = いっぱく matches いち).
 * Depth-first, first full alignment wins; a wildcard tries the shortest
 * run first so it can't swallow a neighbour whose reading fits.
 *
 * Returns the per-unit DICTIONARY reading (null for a wildcard), or null
 * overall when the reading doesn't decompose — a stem whose reading
 * changes under inflection (来た/くる), sandhi beyond the tolerance, or a
 * unit with no entry at a position the wildcard can't pin. Callers then
 * keep the tokenizer's hint.
 */
internal fun alignMemberReadings(units: List<AlignUnit>, phraseReading: String): List<String?>? {
    if (units.isEmpty() || phraseReading.isEmpty()) return null
    val out = arrayOfNulls<String>(units.size)
    fun go(i: Int, pos: Int): Boolean {
        if (i == units.size) return pos == phraseReading.length
        val unit = units[i]
        val unitsAfter = units.size - i - 1
        val dict = unit.dictReadings
        if (dict == null) {
            // Wildcard: every non-empty run that still leaves one character
            // per remaining unit.
            for (len in 1..(phraseReading.length - pos - unitsAfter)) {
                out[i] = null
                if (go(i + 1, pos + len)) return true
            }
            return false
        }
        for ((surfaceReading, dictReading) in surfaceReadingCandidates(unit.surface, unit.lookupForm, dict)) {
            if (readingMatchesAt(phraseReading, pos, surfaceReading)) {
                out[i] = dictReading
                if (go(i + 1, pos + surfaceReading.length)) return true
            }
        }
        return false
    }
    return if (go(0, 0)) out.toList() else null
}

/**
 * (surface reading, dictionary reading) pairs for one unit. An uninflected
 * unit reads as its dictionary reading. An inflected kanji unit (漏らし for
 * 漏らす, 気になった's なった for なる) shares a prefix with its lemma; the
 * reading's matching tail is swapped for the surface's tail — okurigana is
 * kana, so the swap is exact whenever the stem's reading is stable.
 * Readings that don't end in the lemma's tail yield nothing. A KANA
 * surface is already its own reading, whatever its lemma (the copula に
 * lemmatizes to だ, します to する): its readings are taken as given.
 */
internal fun surfaceReadingCandidates(
    surface: String,
    lookupForm: String,
    dictReadings: Set<String>,
): List<Pair<String, String>> {
    if (surface == lookupForm || surface.none(::isKanjiChar)) return dictReadings.map { it to it }
    val k = surface.commonPrefixWith(lookupForm).length
    val tailDict = lookupForm.substring(k)
    val tailSurface = surface.substring(k)
    return dictReadings.mapNotNull { dr ->
        if (dr.endsWith(tailDict)) (dr.dropLast(tailDict.length) + tailSurface) to dr else null
    }
}

/** [reading] occurs in [phrase] at [pos], allowing rendaku on its first
 *  kana and a sokuon in place of its last つ/ち/く/き. */
private fun readingMatchesAt(phrase: String, pos: Int, reading: String): Boolean {
    if (reading.isEmpty() || pos + reading.length > phrase.length) return false
    for (j in reading.indices) {
        val want = reading[j]
        val have = phrase[pos + j]
        if (have == want) continue
        if (j == 0 && have in VOICED[want].orEmpty()) continue
        if (j == reading.lastIndex && have == 'っ' && want in "つちくき") continue
        return false
    }
    return true
}

private fun isKanjiChar(c: Char): Boolean = c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF

/** Dakuten / handakuten variants a unit-initial kana may take under rendaku. */
private val VOICED: Map<Char, String> = mapOf(
    'か' to "が", 'き' to "ぎ", 'く' to "ぐ", 'け' to "げ", 'こ' to "ご",
    'さ' to "ざ", 'し' to "じ", 'す' to "ず", 'せ' to "ぜ", 'そ' to "ぞ",
    'た' to "だ", 'ち' to "ぢ", 'つ' to "づ", 'て' to "で", 'と' to "ど",
    'は' to "ばぱ", 'ひ' to "びぴ", 'ふ' to "ぶぷ", 'へ' to "べぺ", 'ほ' to "ぼぽ",
)
