package com.playtranslate.dictionary

/**
 * Japanese kana and furigana helpers: kana conversion ([katakanaToHiragana],
 * [hiraganaToKatakana]), character classes ([isKanji], [isKana],
 * [hiraganaOf]) and compound furigana splitting ([splitFurigana]). The name
 * predates the move of deinflection out of this object: lookups deinflect
 * through [com.playtranslate.dictionary.deinflect.JapaneseDeinflector], and
 * morphological analysis lives in [SudachiJapaneseTokenizer].
 */
object Deinflector {

    /** Convert katakana to hiragana (analyzers emit katakana; JMdict stores hiragana). */
    fun katakanaToHiragana(text: String): String = buildString {
        for (c in text) {
            if (c in '\u30A1'..'\u30F6') append(c - 0x60) else append(c)
        }
    }

    /** Convert hiragana to katakana \u2014 the inverse of [katakanaToHiragana].
     *  Prefix search folds a query both ways before scanning the reading
     *  table, because JMdict stores loanword readings in katakana (\u30C6\u30EC\u30D3)
     *  while a user typically types hiragana (\u3066\u308C\u3073). */
    fun hiraganaToKatakana(text: String): String = buildString {
        for (c in text) {
            if (c in '\u3041'..'\u3096') append(c + 0x60) else append(c)
        }
    }

    // ── Kana/kanji helpers ────────────────────────────────────────────────

    internal fun isKanji(c: Char) = c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF'
    internal fun isKana(c: Char) = c in '\u3040'..'\u309F' || c in '\u30A0'..'\u30FF'
    internal fun hiraganaOf(c: Char): Char = if (c in '\u30A1'..'\u30F6') c - 0x60 else c

    // ── Furigana tokenization ─────────────────────────────────────────────

    /** A segment from compound furigana splitting. */
    data class FuriganaPart(
        val text: String,
        val reading: String?   // reading if kanji segment, null if shared kana
    )

    /**
     * Split a surface/reading pair at all shared kana boundaries.
     *
     * Handles trailing okurigana (聞い/きい → [聞/き, い/—]), leading kana,
     * AND internal kana in compounds (取り出/とりだ → [取/と, り/—, 出/だ]).
     *
     * Uses a two-pointer scan: kana in the surface that matches the reading
     * at the corresponding position is a shared boundary. Kanji blocks consume
     * reading characters until the next shared kana is found (minimum 1 reading
     * char per kanji to prevent false matches like 相合い/あいあい).
     */
    fun splitFurigana(surface: String, reading: String): List<FuriganaPart> {
        val result = mutableListOf<FuriganaPart>()
        var si = 0
        var ri = 0

        while (si < surface.length && ri < reading.length) {
            if (isKana(surface[si]) && hiraganaOf(surface[si]) == hiraganaOf(reading[ri])) {
                // Shared kana — no furigana needed
                result += FuriganaPart(surface[si].toString(), reading = null)
                si++; ri++
            } else if (isKanji(surface[si])) {
                // Kanji block — find extent and corresponding reading
                val kanjiStart = si
                val readingStart = ri
                while (si < surface.length && isKanji(surface[si])) si++
                val kanjiCount = si - kanjiStart

                if (si < surface.length && isKana(surface[si])) {
                    // Search for next shared kana in reading (min offset = kanjiCount)
                    val target = hiraganaOf(surface[si])
                    var search = ri + kanjiCount
                    while (search < reading.length && hiraganaOf(reading[search]) != target) search++
                    if (search < reading.length) {
                        result += FuriganaPart(
                            surface.substring(kanjiStart, si),
                            reading.substring(readingStart, search)
                        )
                        ri = search
                        continue  // kana at si handled next iteration
                    }
                }
                // No split found or end of surface — emit remaining as one kanji block
                result += FuriganaPart(surface.substring(kanjiStart), reading.substring(readingStart))
                return result
            } else {
                // Kana not matching reading — emit as-is
                result += FuriganaPart(surface[si].toString(), reading = null)
                si++; ri++
            }
        }
        if (si < surface.length) {
            result += FuriganaPart(surface.substring(si), reading = null)
        }
        return result
    }
}
