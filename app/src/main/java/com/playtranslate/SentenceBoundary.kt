package com.playtranslate

/**
 * Sentence-terminal boundary detection for [TypewriterGate] — the
 * text-domain completion signal that replaces waiting. A read captured
 * mid-reveal almost never ends exactly at a sentence boundary, so a read
 * whose tail IS one is strong evidence the message (or a whole leading
 * sentence of it) is complete and can be dispatched without a confirming
 * read. The signal only ever REMOVES waiting: a false boundary costs one
 * dispatch at a read that would have dispatched under Level 0 anyway,
 * never a wrong hold.
 *
 * Pure functions of (text, translationCode). The terminal set is the UNION
 * across supported scripts — cross-script marks do not collide in practice
 * (。 does not occur in Latin prose, danda does not occur in Japanese), and
 * one set keeps mixed-script game text working without per-language tables.
 * Thai is the one source language with no terminal-mark convention:
 * [supports] returns false and the gate falls back to its agreement/cap
 * releases (current shipped behavior).
 *
 * Priced imprecision (bounded, see the gate's design record):
 *  - ASCII '.' after an abbreviation ("Mr.") can mark a false boundary —
 *    only when a read lands exactly there, and the cost is one extra
 *    dispatch, never worse than Level 0.
 *  - Decimal points ("3.5") and inline letters ("e.g.x") are excluded:
 *    an ASCII terminal counts only when followed (after closers) by
 *    end-of-text or whitespace.
 *  - '・' (interpunct) separates name parts ("デビッド・スミス") and is
 *    terminal only when doubled — the ・・・ game-ellipsis idiom.
 */
object SentenceBoundary {

    /** Marks that end a sentence unconditionally, any script. */
    private const val UNCONDITIONAL_TERMINALS = "。．！？…‥‼⁉⁈⁇؟۔।॥‽"

    /** ASCII marks that end a sentence only in a terminal CONTEXT (see the
     *  class doc's decimal/abbreviation notes). */
    private const val ASCII_TERMINALS = ".!?"

    /** Characters allowed to trail a terminal mark and still belong to the
     *  completed sentence: closing quotes/brackets across scripts. */
    private const val CLOSERS = "」』】〉》）］｝〕〙〛\"'’”›»)]}"

    /** Does [translationCode] have a terminal-punctuation convention this
     *  object can detect? Thai does not; everything else shipped does. */
    fun supports(translationCode: String): Boolean = translationCode != "th"

    /**
     * Longest prefix of [text] ending at a sentence boundary (terminal mark
     * plus any trailing closers), or null when no boundary exists. Trailing
     * whitespace after the boundary is NOT included.
     */
    fun terminalPrefix(text: String, translationCode: String): String? {
        if (!supports(translationCode)) return null
        var i = text.length - 1
        while (i >= 0) {
            if (isTerminalAt(text, i)) {
                var end = i + 1
                while (end < text.length && text[end] in CLOSERS) end++
                return text.substring(0, end)
            }
            i--
        }
        return null
    }

    /** Does [text] as a whole end at a sentence boundary (ignoring trailing
     *  whitespace)? The gate's immediate-release predicate. */
    fun endsAtBoundary(text: String, translationCode: String): Boolean {
        val prefix = terminalPrefix(text, translationCode) ?: return false
        for (i in prefix.length until text.length) {
            if (!text[i].isWhitespace()) return false
        }
        return true
    }

    /** Is the character at [i] a sentence terminal in its context? */
    private fun isTerminalAt(text: String, i: Int): Boolean {
        val c = text[i]
        if (c in UNCONDITIONAL_TERMINALS) return true
        // ・・ run = the JA game-ellipsis idiom; a lone ・ separates name parts.
        if (c == '・') return i > 0 && text[i - 1] == '・'
        if (c in ASCII_TERMINALS) {
            var k = i + 1
            while (k < text.length && text[k] in CLOSERS) k++
            if (k >= text.length) return true
            return text[k].isWhitespace()
        }
        return false
    }
}
