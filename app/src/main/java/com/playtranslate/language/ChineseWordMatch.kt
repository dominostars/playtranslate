package com.playtranslate.language

import java.util.Locale

/**
 * The dictionary decides what a Chinese unit is. HanLP segments text with
 * its own vocabulary (its core dictionary, its n-gram model and its name,
 * place and organisation recognisers), which is not CC-CEDICT's: it fuses
 * 放在 (its core dictionary lists it as one verb) and 包里 (its name
 * recogniser reads the surname 包 plus 里 as a person), and CC-CEDICT
 * defines neither, so each was a tap with no definition and no way down.
 * Japanese keeps the invariant "a unit fuses only when some dictionary
 * lists it" through its re-glob oracle; this object is the Chinese side of
 * it, one rule for the segmenter ([pieces], [narrow]) and for the member
 * words of a looked-up unit ([memberSplit]).
 *
 * The match is a BACKWARD maximal match over the listed words: from the
 * right end, the longest listed suffix of two or more characters, else one
 * character. A single hanzi is always a unit, since CC-CEDICT lists single
 * hanzi as words. Backward beats forward on 新版本 (新 + 版本, not 新版 +
 * 本) and 受限制 (受 + 限制), the only two cases out of 83 unlisted tokens
 * in a corpus of Chinese app prose where the two directions differ.
 *
 * [listed] is whatever the caller looked up for [probeCandidates]: the
 * pack's headwords plus the imported term dictionaries' terms. Pure, so the
 * rule is tested without HanLP or a database.
 */
internal object ChineseWordMatch {

    /** CJK Unified Ideographs and Extension A. BMP only: a word holding a
     *  surrogate pair is left alone by every rule here. */
    fun isHanzi(c: Char): Boolean =
        c.code in 0x4e00..0x9fff || c.code in 0x3400..0x4dbf

    fun isHanziWord(word: String): Boolean = word.isNotEmpty() && word.all(::isHanzi)

    /**
     * The listed words inside [word], right to left, longest first. Every
     * character lands in exactly one piece, so the pieces concatenate back
     * to [word]. A suffix equal to the whole word counts only when
     * [allowWhole]: the segmenter keeps a listed term whole, the member
     * split descends into it.
     */
    fun backwardMaximalMatch(word: String, listed: Set<String>, allowWhole: Boolean = true): List<String> {
        val pieces = ArrayDeque<String>()
        var end = word.length
        while (end > 0) {
            var take = 1
            for (start in 0 until end - 1) {
                val candidate = word.substring(start, end)
                if (!allowWhole && candidate.length == word.length) continue
                if (candidate in listed) {
                    take = end - start
                    break
                }
            }
            pieces.addFirst(word.substring(end - take, end))
            end -= take
        }
        return pieces.toList()
    }

    /** [word] as the segmenter should emit it: itself when shorter than two
     *  hanzi or not pure hanzi, else its listed pieces, which is the word
     *  itself when it is listed (the match allows the whole). */
    fun pieces(word: String, listed: Set<String>): List<String> =
        if (word.length < 2 || !isHanziWord(word)) listOf(word)
        else backwardMaximalMatch(word, listed)

    /** [pieces] over a whole segmentation. */
    fun narrow(words: List<String>, listed: Set<String>): List<String> =
        words.flatMap { pieces(it, listed) }

    /**
     * Every string one probe must answer for [words]: each word of two or
     * more hanzi and every substring of it two or more characters long, so
     * one query decides both whether a word is listed and how an unlisted
     * one splits.
     */
    fun probeCandidates(words: Iterable<String>): Set<String> {
        val out = LinkedHashSet<String>()
        for (word in words) {
            if (word.length < 2 || !isHanziWord(word)) continue
            for (start in 0 until word.length - 1) {
                for (end in start + 2..word.length) out.add(word.substring(start, end))
            }
        }
        return out
    }

    /**
     * The member words of [headword] for the lens's member sections and
     * the detail page's Words section: the listed words inside it, the
     * headword itself excluded, so a listed compound descends too (常用 →
     * 常 + 用, 翻译器 → 翻译 + 器) and an unlisted one lands its parts
     * (放在 → 放 + 在). Hanzi units only: punctuation inside a headword
     * ("，", "·") and ASCII letters are never members. Distinct, in order; a
     * reduplication (妈妈) yields its one member. Empty for a headword of
     * fewer than two characters or one holding a non-BMP character.
     *
     * [headwordReading] is the displayed reading, one tone-marked token per
     * headword character (CC-CEDICT writes one syllable per hanzi, erhua
     * 儿 included, and keeps punctuation as its own token), so when the
     * token count equals the character count each member carries its slice
     * as its lookup hint (银行 yín háng gives 行 háng, not the frequency
     * default xíng), lowercased so a proper-noun compound (中国 Zhōng guó)
     * sends 中 to its common entry. Any other reading gives no hint.
     */
    fun memberSplit(headword: String, listed: Set<String>, headwordReading: String?): List<TokenSpan> {
        if (headword.length < 2 || headword.any { it.isSurrogate() }) return emptyList()
        val tokens = headwordReading?.trim()?.split(' ')?.takeIf { it.size == headword.length }
        val members = LinkedHashMap<String, String?>()
        var start = 0
        for (piece in backwardMaximalMatch(headword, listed, allowWhole = false)) {
            val end = start + piece.length
            if (isHanziWord(piece) && piece !in members) {
                members[piece] = tokens?.subList(start, end)
                    ?.joinToString(" ")
                    ?.lowercase(Locale.ROOT)
            }
            start = end
        }
        return members.map { (surface, reading) -> TokenSpan(surface = surface, lookupForm = surface, reading = reading) }
    }
}
