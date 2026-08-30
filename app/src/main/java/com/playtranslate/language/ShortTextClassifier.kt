package com.playtranslate.language

import java.text.Normalizer

/** A text with at most this many content words routes to the fast offline
 *  translation tier instead of the online waterfall. */
internal const val SHORT_TEXT_MAX_CONTENT_WORDS = 2

/**
 * Content-char ceiling above which a text is never "short", applied BEFORE any
 * segmentation. This is the load-bearing backstop for every segmenter
 * over-merge case (Sudachi mode-C long units, HanLP custom-dict merges, a Thai
 * trie that degraded to whole-run segments): an under-COUNT can only misroute a
 * text that is also short in characters, so the worst case stays bounded at
 * "a ≤15-char line went to the offline tier".
 */
internal const val SHORT_TEXT_MAX_CONTENT_CHARS = 15

/**
 * Content-word count for an unspaced script, or null = NO SIGNAL (tokenizer
 * unavailable / failed / pack missing). No signal reads as NOT short — the
 * conservative default is today's all-online behavior.
 */
internal fun interface ContentTokenCounter {
    fun count(text: String): Int?
}

private val WHITESPACE = Regex("\\s+")

/**
 * True when [text] is a "short text" — at most [SHORT_TEXT_MAX_CONTENT_WORDS]
 * content words — and should be served by the fast offline translation tier
 * rather than spending an online request. Menu items, HUD labels, and one-word
 * lines qualify; sentences don't.
 *
 * Content chars are source-script letters excluding digits (the same defense
 * the translation log uses against clocks/counters), counted after NFKC
 * normalization, so half-width katakana and full-width Latin/digits are seen
 * in canonical form. NFKC ONLY — deliberately NOT LogWriteGate.fold, whose
 * small-kana fold (っ→つ, ょ→よ) is comparison-only and would destroy the
 * morphology Sudachi needs (ちょっと→ちよつと no longer matches its entry, the
 * lattice falls back to OOV noun units, and the count inflates past the
 * threshold — silently defeating the route on 拗音/促音-bearing shorts). The
 * NFKC'd text is what reaches [counter]. Zero content chars is NOT short —
 * icon/number-only strings keep today's path.
 *
 * Terminal punctuation deliberately does NOT exclude a text (はい！ routes
 * offline too): the output is a translation either way, and short punctuated
 * dialogue is among the most-repeated quota burners.
 *
 * Spaced scripts count whitespace tokens bearing ≥1 content char; unspaced
 * scripts delegate to [counter] (raw segmentation — never the annotate path).
 */
internal fun isShortText(
    text: String,
    profile: SourceLanguageProfile,
    counter: ContentTokenCounter,
): Boolean {
    val folded = Normalizer.normalize(text, Normalizer.Form.NFKC).trim()
    if (folded.isEmpty()) return false
    val isContent = { c: Char -> !c.isDigit() && profile.isScriptChar(c) }
    val contentChars = folded.count(isContent)
    if (contentChars == 0 || contentChars > SHORT_TEXT_MAX_CONTENT_CHARS) return false
    val words = if (profile.wordsSeparatedByWhitespace) {
        folded.split(WHITESPACE).count { tok -> tok.any(isContent) }
    } else {
        counter.count(folded) ?: return false
    }
    return words in 1..SHORT_TEXT_MAX_CONTENT_WORDS
}
