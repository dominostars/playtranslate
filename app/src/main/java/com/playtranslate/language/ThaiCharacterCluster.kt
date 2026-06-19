package com.playtranslate.language

import java.util.regex.Pattern

/**
 * Thai Character Cluster (TCC) segmentation — groups a base consonant with its
 * leading/following vowels and tone marks into inseparable clusters, so the
 * word segmenter ([MaximalMatchThaiSegmenter]) only ever cuts at cluster
 * boundaries (never mid-syllable). This is what makes dictionary matching
 * combining-mark-safe.
 *
 * Faithful Kotlin port of PyThaiNLP `pythainlp/tokenize/tcc_p.py`:
 *   SPDX-FileCopyrightText: 2016-2026 PyThaiNLP Project
 *   SPDX-License-Identifier: Apache-2.0
 * TCC rules: Theeramunkong et al. 2000 (https://doi.org/10.1145/355214.355225);
 * grammar: Wittawat Jitkrittum (jtcc); Python: Korakot Chaovavanich.
 *
 * The [RULES] template and the four placeholder substitutions are reproduced
 * verbatim from upstream so the compiled regex is byte-identical. The Latin
 * letters c/t/k/d in [RULES] are PLACEHOLDERS, expanded below; every other
 * character is literal Thai or regex syntax.
 *
 * Boundary fidelity vs. the real `tcc_p.py` is pinned by `ThaiCharacterClusterTest`
 * (golden clusters generated from PyThaiNLP).
 */
object ThaiCharacterCluster {

    /** One TCC rule per entry, verbatim from `tcc_p.py` `_RE_TCC` (pre-expansion). */
    private val RULES: List<String> = listOf(
        "เc็ck",
        "เcctาะk",
        "เccีtยะk",
        "เccีtย(?=[เ-ไก-ฮ]|\$)k",
        "เcc็ck",
        "เcิc์ck",
        "เcิtck",
        "เcีtยะ?k",
        "เcืtอะ?k",
        "เc[ิีุู]tย(?=[เ-ไก-ฮ]|\$)k",
        "เctา?ะ?k",
        "cัtวะk",
        "c[ัื]tc[ุิะ]?k",
        "c[ิุู]์",
        "c[ะ-ู]tk",
        "cรรc์",
        "c็",
        "ct[ะาำ]?k",
        "ck",
        "แc็c",
        "แcc์",
        "แctะ",
        "แcc็c",
        "แccc์",
        "โctะ",
        "[เ-ไ]ct",
        "ก็",
        "อึ",
        "หึ",
    )

    /** Expanded TCC pattern, alternatives joined with `|`. The substitution
     *  order (k, c, t, d) and replacement strings mirror `tcc_p.py` exactly:
     *  `k -> (cc?[dิ]?[์])?`, `c -> [ก-ฮ]`, `t -> [่-๋]?`, `d -> ูุ` (lower vowels). */
    private val PAT_TCC: Pattern = run {
        val expanded = RULES.map { rule ->
            rule.replace("k", "(cc?[dิ]?[์])?")
                .replace("c", "[ก-ฮ]")
                .replace("t", "[่-๋]?")
                .replace("d", "ูุ")
        }
        Pattern.compile(expanded.joinToString("|"))
    }

    /** Number of TCC alternatives — a cheap structural guard for the port. */
    internal val ruleCount: Int get() = RULES.size

    /**
     * BooleanArray of size `text.length + 1`; index `i` is true iff `i` is a
     * valid TCC cluster-end boundary (the only positions the word segmenter may
     * cut at). Mirrors `tcc_p.tcc_pos_array`.
     */
    fun tccBoundaryArray(text: String): BooleanArray {
        val arr = BooleanArray(text.length + 1)
        if (text.isEmpty()) return arr
        val m = PAT_TCC.matcher(text)
        var p = 0
        while (p < text.length) {
            // region(p, len) + default bounds (anchoring on, transparent off)
            // makes lookingAt() match exactly at p and `$` match at text end —
            // equivalent to upstream's `text[p:]` substring match.
            m.region(p, text.length)
            val n = if (m.lookingAt() && m.end() > p) m.end() - p else 1
            p += n
            arr[p] = true
        }
        return arr
    }

    /** Cluster substrings, in order (used by tests). Mirrors `tcc_p.tcc`. */
    fun clusters(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val m = PAT_TCC.matcher(text)
        var p = 0
        while (p < text.length) {
            m.region(p, text.length)
            val n = if (m.lookingAt() && m.end() > p) m.end() - p else 1
            out.add(text.substring(p, p + n))
            p += n
        }
        return out
    }
}
