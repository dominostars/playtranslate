package com.playtranslate.language

import java.util.Locale

/**
 * Pinyin formatting helpers. CC-CEDICT stores pinyin in numbered form
 * ("Bei3 jing1") but the app displays tone-marked form ("Běi jīng"). This
 * object owns the conversion so the dictionary manager and any new caller
 * (per-hanzi detail, overlays, Anki cards…) stay in lock-step — there was a
 * character-row drift bug because each path did its own thing.
 */
object PinyinFormatter {

    private val BRACKET_REGEX = Regex("""\[([^\[\]]+)\]""")

    /**
     * Converts numbered pinyin ("fu4 wu4") to tone-marked pinyin ("fù wù").
     * Tone 5 (neutral) drops the digit without adding a mark. Leading-letter
     * capitalization is preserved so proper nouns like "Bei3 jing1" render as
     * "Běi jīng" instead of lowercased.
     *
     * Idempotent on already-formatted strings: the regex only fires when a
     * syllable ends in an ASCII digit.
     */
    /** CC-CEDICT writes ü as "u:" (e.g. "nu:3" for 女 nǚ); some data uses the
     *  legacy "v". Fold both to ü. Single source of ü-spelling normalization,
     *  shared by [numberedToToneMarks] and [canonicalReading]. */
    private fun foldUmlaut(s: String): String =
        s.replace("u:", "ü").replace('v', 'ü')

    fun numberedToToneMarks(numbered: String): String =
        numbered.split(' ').joinToString(" ") { syllable ->
            val tone = syllable.lastOrNull()?.digitToIntOrNull()
            // No trailing tone digit → unnumbered / already-formatted / non-pinyin:
            // return verbatim. This keeps the function exact and idempotent and
            // avoids folding ü into arbitrary strings (e.g. "version" → "üersion").
            if (tone == null || tone == 0) return@joinToString syllable
            // Now that it's a numbered pinyin syllable, fold ü ("u:"/legacy "v").
            val base = foldUmlaut(if (tone in 1..5) syllable.dropLast(1) else syllable)
            if (tone == 5 || tone !in 1..4) return@joinToString base
            val wasCapital = base.firstOrNull()?.isUpperCase() == true
            // Pinyin syllables are ASCII — ROOT avoids Turkish-locale
            // devices mangling "Yin" into "yın".
            val marked = applyToneMark(base.lowercase(Locale.ROOT), tone)
            if (wasCapital) marked.replaceFirstChar { it.uppercase(Locale.ROOT) } else marked
        }

    /**
     * True when [a] and [b] denote the same reading regardless of whether
     * pinyin is numbered ("dong1 xi1") or tone-marked ("dōng xī").
     * [numberedToToneMarks] is idempotent and a no-op on strings without a
     * trailing tone digit (kana, already-tone-marked pinyin), so this stays an
     * exact comparison for Japanese/Korean readings and only bridges the
     * numbered↔tone-marked gap between the ZH source dict (tone-marked when the
     * app reads it) and the CFDICT/HanDeDict target packs (stored numbered).
     */
    fun readingsEqual(a: String, b: String): Boolean =
        a == b || numberedToToneMarks(a) == numberedToToneMarks(b)

    /**
     * Case- and ü-normalized form of a reading syllable for equality
     * comparison: lowercased (Locale.ROOT, so a Turkish-locale 'I' doesn't
     * become 'ı') with CC-CEDICT "u:" / legacy "v" folded to ü. Shared by the
     * heteronym disambiguator so source readings and HanLP context pinyin are
     * matched through one canonical form rather than a divergent copy.
     */
    fun canonicalReading(syllable: String): String =
        foldUmlaut(syllable.lowercase(Locale.ROOT))

    /**
     * CC-CEDICT definitions embed pinyin cross-references in brackets, e.g.
     * `"capital of Hebei Province 河北省[He2 bei3 sheng3] in China"`. Convert
     * only the content inside `[...]` so numeric tones become accents while
     * surrounding English text (which may legitimately contain digits like
     * "H2O") is left untouched.
     */
    fun convertPinyinInBrackets(text: String): String =
        BRACKET_REGEX.replace(text) { m ->
            "[${numberedToToneMarks(m.groupValues[1])}]"
        }

    private fun applyToneMark(syllable: String, tone: Int): String {
        // Standard rule: mark goes on 'a' or 'e' if present, 'o' in 'ou',
        // else the last vowel.
        val vowels = "aeiouü"
        val toneMap = mapOf(
            'a' to arrayOf("ā", "á", "ǎ", "à"),
            'e' to arrayOf("ē", "é", "ě", "è"),
            'i' to arrayOf("ī", "í", "ǐ", "ì"),
            'o' to arrayOf("ō", "ó", "ǒ", "ò"),
            'u' to arrayOf("ū", "ú", "ǔ", "ù"),
            'ü' to arrayOf("ǖ", "ǘ", "ǚ", "ǜ"),
        )
        // ü is already folded from "u:"/"v" upstream (foldUmlaut).
        val s = syllable

        val idx = when {
            'a' in s -> s.indexOf('a')
            'e' in s -> s.indexOf('e')
            "ou" in s -> s.indexOf('o')
            else -> s.indexOfLast { it in vowels }
        }
        if (idx < 0) return s
        val vowel = s[idx]
        val marked = toneMap[vowel]?.getOrNull(tone - 1) ?: return s
        return s.substring(0, idx) + marked + s.substring(idx + 1)
    }
}
