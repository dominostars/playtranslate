package com.playtranslate.ocr

import java.text.Normalizer

/**
 * Shared OCR scoring metrics, extracted from [OcrGoldenSetTest] so both the
 * preprocessing sweep and the PaddleOCR comparison harness
 * ([com.playtranslate.ocr.paddle.PaddleOcrComparisonTest]) score against the
 * same definitions: NFKC normalize, Levenshtein CER, char-alignment op counts,
 * and the "meaningful" (Japanese-script-affecting) subset.
 *
 * Pure functions, no Android deps. The original copies in [OcrGoldenSetTest]
 * remain to avoid churning that file; this is the canonical version for new code.
 */
object OcrMetrics {

    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC)
            .replace(Regex("\\s+"), " ")
            .trim()

    fun cer(actual: String, expected: String): Double {
        val a = normalize(actual); val e = normalize(expected)
        if (e.isEmpty()) return if (a.isEmpty()) 0.0 else 1.0
        return levenshtein(a, e).toDouble() / e.length
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val n = b.length
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[n]
    }

    enum class Op { MATCH, SUB, DEL, INS }
    data class CharOp(val kind: Op, val expectedCh: Char?, val actualCh: Char?)
    data class OpCounts(val sub: Int, val del: Int, val ins: Int)

    /** Levenshtein DP + traceback. DEL = in expected, missing from actual (drop);
     *  INS = in actual, not expected (hallucination); SUB = swap. Tie pref
     *  MATCH>SUB>DEL>INS so substitutions read as one op. */
    fun alignChars(actual: String, expected: String): List<CharOp> {
        val a = actual; val e = expected
        val m = a.length; val n = e.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) for (j in 1..n) {
            val cost = if (a[i - 1] == e[j - 1]) 0 else 1
            dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
        }
        val ops = ArrayList<CharOp>()
        var i = m; var j = n
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && a[i - 1] == e[j - 1] && dp[i][j] == dp[i - 1][j - 1] -> {
                    ops += CharOp(Op.MATCH, e[j - 1], a[i - 1]); i--; j--
                }
                i > 0 && j > 0 && dp[i][j] == dp[i - 1][j - 1] + 1 -> {
                    ops += CharOp(Op.SUB, e[j - 1], a[i - 1]); i--; j--
                }
                j > 0 && dp[i][j] == dp[i][j - 1] + 1 -> {
                    ops += CharOp(Op.DEL, e[j - 1], null); j--
                }
                else -> { ops += CharOp(Op.INS, null, a[i - 1]); i-- }
            }
        }
        return ops.reversed()
    }

    fun opCounts(actual: String, expected: String): OpCounts {
        var s = 0; var d = 0; var ins = 0
        for (op in alignChars(normalize(actual), normalize(expected))) when (op.kind) {
            Op.SUB -> s++; Op.DEL -> d++; Op.INS -> ins++; Op.MATCH -> {}
        }
        return OpCounts(s, d, ins)
    }

    /** Hiragana, katakana, kanji (incl. CJK Ext A + half-width kana). */
    fun isJapanese(c: Char?): Boolean {
        if (c == null) return false
        return c in '぀'..'ゟ' || c in '゠'..'ヿ' ||
            c in '一'..'鿿' || c in '㐀'..'䶿' ||
            c in '･'..'ﾟ'
    }

    private fun isMeaningful(op: CharOp): Boolean = when (op.kind) {
        Op.MATCH -> false
        Op.SUB -> isJapanese(op.expectedCh) || isJapanese(op.actualCh)
        Op.DEL -> isJapanese(op.expectedCh)
        Op.INS -> isJapanese(op.actualCh)
    }

    /** Op counts restricted to Japanese-script-affecting changes — the metric
     *  that maps to translation quality (see OcrGoldenSetTest kdoc). */
    fun meaningfulOpCounts(actual: String, expected: String): OpCounts {
        var s = 0; var d = 0; var ins = 0
        for (op in alignChars(normalize(actual), normalize(expected))) {
            if (!isMeaningful(op)) continue
            when (op.kind) { Op.SUB -> s++; Op.DEL -> d++; Op.INS -> ins++; Op.MATCH -> {} }
        }
        return OpCounts(s, d, ins)
    }
}
