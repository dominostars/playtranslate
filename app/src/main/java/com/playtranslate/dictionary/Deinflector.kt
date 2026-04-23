package com.playtranslate.dictionary

import android.util.Log
import com.atilika.kuromoji.ipadic.Tokenizer
import java.io.File

private const val TAG = "Deinflector"

/**
 * Japanese morphological analyser + de-inflector.
 *
 * [tokenize] uses Kuromoji (IPADIC) to split text into content words and
 * returns their dictionary (base) form, ready for direct JMdict lookup.
 * This handles all verb conjugations, katakana, onomatopoeia, etc. natively.
 *
 * [candidates] is kept for the word-tap path in DictionaryManager, where the
 * raw OCR segment (potentially inflected) is looked up directly by the user.
 */
object Deinflector {

    data class Candidate(val text: String, val reason: String)

    private data class Rule(val inflected: String, val dictionary: String, val reason: String)

    // Kuromoji tokenizer — lazy so it initialises on first use (always on IO thread).
    // Pack-aware: if [initPackDir] has been called with a directory containing
    // IPADIC bin files before first access, the tokenizer loads them from that
    // directory instead of the APK classpath. See [PackAwareKuromojiBuilder].
    @Volatile private var packDir: File? = null
    @Volatile private var _tokenizer: Tokenizer? = null
    private val tokenizerLock = Any()

    private val tokenizer: Tokenizer
        get() = _tokenizer ?: synchronized(tokenizerLock) {
            _tokenizer ?: createTokenizer().also { _tokenizer = it }
        }

    private fun createTokenizer(): Tokenizer {
        val dir = packDir
        return if (dir != null && File(dir, "doubleArrayTrie.bin").isFile) {
            PackAwareKuromojiBuilder(dir).build()
        } else {
            // Classpath fallback — used when no pack dir is set, when the pack
            // doesn't contain tokenizer files yet (pre-migration packs), or
            // when the APK hasn't been resource-stripped.
            Tokenizer()
        }
    }

    /**
     * Register a directory containing IPADIC bin files. Always clears the
     * cached tokenizer so the next [preload] or tokenize call re-deserializes
     * from whatever is on disk now. Called from [JapaneseEngine]'s init block,
     * which runs once per engine instance — a new engine appearing after
     * [SourceLanguageEngines.release] means the pack on disk may have been
     * swapped (install's safeSwap reuses the same directory path), so an
     * "idempotent no-op on same path" would keep serving the old bins.
     * Safe to call from any thread.
     */
    fun initPackDir(dir: File?) {
        synchronized(tokenizerLock) {
            packDir = dir
            _tokenizer = null
        }
    }

    /** Call once on a background thread early in startup to avoid first-use latency. */
    fun preload() {
        tokenizer
    }

    /** Raw token info returned by [rawTokenInfos]. */
    internal data class TokenInfo(
        val surface: String,
        val pos: String?,
        val baseForm: String?,
        val reading: String?
    )

    /**
     * Returns raw Kuromoji token info for every token in [text] — surface,
     * part-of-speech level 1, and base form (null when Kuromoji returns "*").
     * Used by [DictionaryManager] for greedy N-gram phrase detection.
     */
    internal fun rawTokenInfos(text: String): List<TokenInfo> =
        tokenizer.tokenize(text).map { t ->
            Log.d(TAG, "token: surface=${t.surface} pos=${t.partOfSpeechLevel1} baseForm=${t.baseForm} reading=${t.reading}")
            TokenInfo(
                surface  = t.surface,
                pos      = t.partOfSpeechLevel1,
                baseForm = t.baseForm.takeIf { !it.isNullOrEmpty() && it != "*" },
                reading  = t.reading.takeIf { !it.isNullOrEmpty() && it != "*" }
            )
        }

    /** Convert katakana to hiragana (Kuromoji returns katakana, JMdict stores hiragana). */
    fun katakanaToHiragana(text: String): String = buildString {
        for (c in text) {
            if (c in '\u30A1'..'\u30F6') append(c - 0x60) else append(c)
        }
    }

    // ── Kana/kanji helpers ────────────────────────────────────────────────

    internal fun isKanji(c: Char) = c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF'
    internal fun isKana(c: Char) = c in '\u3040'..'\u309F' || c in '\u30A0'..'\u30FF'
    internal fun hiraganaOf(c: Char): Char = if (c in '\u30A1'..'\u30F6') c - 0x60 else c

    // ── Furigana tokenization ─────────────────────────────────────────────

    /** A Kuromoji token with its hiragana reading. */
    data class ReadingToken(
        val surface: String,
        val reading: String?,
        val hasKanji: Boolean
    )

    /** A segment from compound furigana splitting. */
    data class FuriganaPart(
        val text: String,
        val reading: String?   // reading if kanji segment, null if shared kana
    )

    /**
     * Tokenize text into morphemes with hiragana readings from Kuromoji.
     * Each token gets its conjugation-aware reading (e.g. 来た → き for 来).
     * Used by furigana overlay — NOT for dictionary lookup (use [rawTokenInfos] for that).
     */
    fun tokenizeWithReadings(text: String): List<ReadingToken> =
        tokenizer.tokenize(text).map { t ->
            val reading = t.reading.takeIf { !it.isNullOrEmpty() && it != "*" }
                ?.let { katakanaToHiragana(it) }
            val hasKanji = t.surface.any(::isKanji)
            ReadingToken(t.surface, reading, hasKanji)
        }

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

    /**
     * Tokenises [text] using Kuromoji morphological analysis and returns the
     * dictionary form of each content word, deduplicated, in sentence order.
     *
     * Content words kept: nouns (名詞), verbs (動詞), i-adjectives (形容詞),
     * na-adjectives (形容動詞), adverbs (副詞), interjections (感動詞).
     * Particles, auxiliary verbs, punctuation, etc. are discarded.
     *
     * Katakana loanwords and onomatopoeia are classified as 名詞 by Kuromoji
     * and are returned verbatim (baseForm = "*" for some, fallback to surface).
     */
    fun tokenize(text: String): List<String> {
        val tokens = tokenizer.tokenize(text)
        return tokens
            .filter { isContentWord(it.partOfSpeechLevel1) }
            .map { token ->
                val base = token.baseForm
                if (!base.isNullOrEmpty() && base != "*") base else token.surface
            }
            .filter { isLookupWorthy(it) }
            .distinct()
    }

    private fun isContentWord(pos: String?): Boolean = pos in setOf(
        "名詞",     // noun — includes katakana loanwords, proper nouns, onomatopoeia
        "動詞",     // verb
        "形容詞",   // i-adjective
        "形容動詞", // na-adjective
        "副詞",     // adverb
        "感動詞"    // interjection
    )

    private fun isLookupWorthy(token: String): Boolean {
        if (token.isBlank()) return false
        if (token.all { it.code <= 0x007F }) return false          // ASCII only
        if (token.length == 1 && token[0] in '\u3041'..'\u3096') return false  // single hiragana
        return true
    }

    // ── De-inflection rules ───────────────────────────────────────────────
    // Used by DictionaryManager.lookup() when a raw (potentially inflected)
    // word is tapped by the user and needs a fallback dictionary hit.

    private val rules: List<Rule> = listOf(

        // ── Progressive / compound te-forms  (check before plain て/た) ──
        Rule("ていなかった", "る",  "was not doing"),
        Rule("ていません",  "る",  "polite neg. progressive"),
        Rule("ていない",    "る",  "not doing"),
        Rule("ていた",      "る",  "was doing"),
        Rule("ていて",      "る",  "doing (te)"),
        Rule("ている",      "る",  "doing"),
        Rule("てみた",      "る",  "tried doing"),
        Rule("てみる",      "る",  "try doing"),
        Rule("てしまった",  "る",  "ended up doing"),
        Rule("てしまう",    "る",  "end up doing"),
        Rule("てた",        "る",  "was doing (colloquial)"),
        Rule("てる",        "る",  "doing (colloquial)"),

        // ── Past (ta-form) ──
        Rule("いった",  "いく", "past"),
        Rule("った",    "う",   "past"),
        Rule("った",    "つ",   "past"),
        Rule("った",    "る",   "past"),
        Rule("いた",    "く",   "past"),
        Rule("いだ",    "ぐ",   "past"),
        Rule("した",    "す",   "past"),
        Rule("んだ",    "ぬ",   "past"),
        Rule("んだ",    "ぶ",   "past"),
        Rule("んだ",    "む",   "past"),
        Rule("た",      "る",   "past"),
        Rule("した",    "する", "past"),
        Rule("きた",    "くる", "past"),
        Rule("来た",    "来る", "past"),

        // ── Negative (nai-form) ──
        Rule("わなかった","う",  "past negative"),
        Rule("かなかった","く",  "past negative"),
        Rule("がなかった","ぐ",  "past negative"),
        Rule("さなかった","す",  "past negative"),
        Rule("たなかった","つ",  "past negative"),
        Rule("ばなかった","ぶ",  "past negative"),
        Rule("まなかった","む",  "past negative"),
        Rule("らなかった","る",  "past negative"),
        Rule("わない",   "う",   "negative"),
        Rule("かない",   "く",   "negative"),
        Rule("がない",   "ぐ",   "negative"),
        Rule("さない",   "す",   "negative"),
        Rule("たない",   "つ",   "negative"),
        Rule("ばない",   "ぶ",   "negative"),
        Rule("まない",   "む",   "negative"),
        Rule("らない",   "る",   "negative"),
        Rule("なかった", "る",   "past negative"),
        Rule("ない",     "る",   "negative"),
        Rule("しない",   "する", "negative"),
        Rule("こない",   "くる", "negative"),
        Rule("来ない",   "来る", "negative"),

        // ── Te-form ──
        Rule("いって",   "いく", "te-form"),
        Rule("って",     "う",   "te-form"),
        Rule("って",     "つ",   "te-form"),
        Rule("って",     "る",   "te-form"),
        Rule("いて",     "く",   "te-form"),
        Rule("いで",     "ぐ",   "te-form"),
        Rule("して",     "す",   "te-form"),
        Rule("んで",     "ぬ",   "te-form"),
        Rule("んで",     "ぶ",   "te-form"),
        Rule("んで",     "む",   "te-form"),
        Rule("て",       "る",   "te-form"),
        Rule("して",     "する", "te-form"),
        Rule("きて",     "くる", "te-form"),
        Rule("来て",     "来る", "te-form"),

        // ── Polite (masu-form) ──
        Rule("いきます", "いく", "polite"),
        Rule("きます",   "く",   "polite"),
        Rule("ぎます",   "ぐ",   "polite"),
        Rule("します",   "す",   "polite"),
        Rule("ちます",   "つ",   "polite"),
        Rule("にます",   "ぬ",   "polite"),
        Rule("びます",   "ぶ",   "polite"),
        Rule("みます",   "む",   "polite"),
        Rule("ります",   "る",   "polite"),
        Rule("います",   "いる", "polite"),
        Rule("ます",     "る",   "polite"),
        Rule("します",   "する", "polite"),
        Rule("きます",   "くる", "polite"),
        Rule("ました",   "る",   "polite past"),
        Rule("しました", "する", "polite past"),
        Rule("きました", "くる", "polite past"),
        Rule("ません",   "る",   "polite neg."),
        Rule("しません", "する", "polite neg."),
        Rule("きません", "くる", "polite neg."),

        // ── Potential ──
        Rule("える",    "う",   "potential"),
        Rule("ける",    "く",   "potential"),
        Rule("げる",    "ぐ",   "potential"),
        Rule("せる",    "す",   "potential"),
        Rule("てる",    "つ",   "potential"),
        Rule("ねる",    "ぬ",   "potential"),
        Rule("べる",    "ぶ",   "potential"),
        Rule("める",    "む",   "potential"),
        Rule("れる",    "る",   "potential"),
        Rule("られる",  "る",   "potential"),
        Rule("できる",  "する", "potential"),

        // ── Passive ──
        Rule("われる",   "う",   "passive"),
        Rule("かれる",   "く",   "passive"),
        Rule("がれる",   "ぐ",   "passive"),
        Rule("される",   "す",   "passive"),
        Rule("たれる",   "つ",   "passive"),
        Rule("なれる",   "ぬ",   "passive"),
        Rule("ばれる",   "ぶ",   "passive"),
        Rule("まれる",   "む",   "passive"),
        Rule("られる",   "る",   "passive"),
        Rule("される",   "する", "passive"),
        Rule("こられる", "くる", "passive"),

        // ── Causative ──
        Rule("わせる",   "う",   "causative"),
        Rule("かせる",   "く",   "causative"),
        Rule("がせる",   "ぐ",   "causative"),
        Rule("させる",   "す",   "causative"),
        Rule("たせる",   "つ",   "causative"),
        Rule("なせる",   "ぬ",   "causative"),
        Rule("ばせる",   "ぶ",   "causative"),
        Rule("ませる",   "む",   "causative"),
        Rule("らせる",   "る",   "causative"),
        Rule("させる",   "る",   "causative"),
        Rule("させる",   "する", "causative"),
        Rule("こさせる", "くる", "causative"),

        // ── Volitional ──
        Rule("おう",    "う",   "volitional"),
        Rule("こう",    "く",   "volitional"),
        Rule("ごう",    "ぐ",   "volitional"),
        Rule("そう",    "す",   "volitional"),
        Rule("とう",    "つ",   "volitional"),
        Rule("のう",    "ぬ",   "volitional"),
        Rule("ぼう",    "ぶ",   "volitional"),
        Rule("もう",    "む",   "volitional"),
        Rule("ろう",    "る",   "volitional"),
        Rule("よう",    "る",   "volitional"),
        Rule("しよう",  "する", "volitional"),
        Rule("こよう",  "くる", "volitional"),

        // ── Imperative ──
        Rule("しろ",    "する", "imperative"),
        Rule("せよ",    "する", "imperative"),
        Rule("こい",    "くる", "imperative"),
        Rule("え",      "う",   "imperative"),
        Rule("け",      "く",   "imperative"),
        Rule("げ",      "ぐ",   "imperative"),
        Rule("せ",      "す",   "imperative"),
        Rule("ね",      "ぬ",   "imperative"),
        Rule("べ",      "ぶ",   "imperative"),
        Rule("め",      "む",   "imperative"),
        Rule("れ",      "る",   "imperative"),

        // ── Conditional (ba-form) ──
        Rule("すれば",  "する", "conditional"),
        Rule("くれば",  "くる", "conditional"),
        Rule("えば",    "う",   "conditional"),
        Rule("けば",    "く",   "conditional"),
        Rule("げば",    "ぐ",   "conditional"),
        Rule("せば",    "す",   "conditional"),
        Rule("てば",    "つ",   "conditional"),
        Rule("ねば",    "ぬ",   "conditional"),
        Rule("べば",    "ぶ",   "conditional"),
        Rule("めば",    "む",   "conditional"),
        Rule("れば",    "る",   "conditional"),

        // ── I-adjective inflections ──
        Rule("くなかった","い",  "past negative"),
        Rule("かった",   "い",   "past"),
        Rule("くない",   "い",   "negative"),
        Rule("くなる",   "い",   "become"),
        Rule("くて",     "い",   "te-form"),
        Rule("ければ",   "い",   "conditional"),
        Rule("く",       "い",   "adverb"),
    )

    /**
     * Returns candidate dictionary forms for [word] by suffix-replacement,
     * deduplicated and ordered longest-suffix-first.
     */
    fun candidates(word: String): List<Candidate> {
        val seen = mutableSetOf<String>()
        val results = mutableListOf<Candidate>()
        for (rule in rules) {
            if (word.length > rule.inflected.length && word.endsWith(rule.inflected)) {
                val stem = word.dropLast(rule.inflected.length)
                val candidate = stem + rule.dictionary
                if (candidate.length >= 2 && seen.add(candidate)) {
                    results.add(Candidate(candidate, rule.reason))
                }
            }
        }
        return results
    }
}
