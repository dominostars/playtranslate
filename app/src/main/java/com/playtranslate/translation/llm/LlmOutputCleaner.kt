package com.playtranslate.translation.llm

/**
 * Post-process raw LLM translation output to remove the two cosmetic
 * mistakes the API models still make despite the system prompt's
 * "only the translation, no commentary" instruction:
 *
 * 1. A leading `Translation:` / `翻訳:` / `翻译:` (occasionally seen with
 *    `gpt-4o-mini` on short inputs).
 * 2. Wrapping quote characters when both ends match — gpt-4o-mini and
 *    gemini-2.5-flash both do this on single-sentence inputs sometimes.
 *
 * Intentionally conservative: only strips matching pairs and a single
 * leading prefix, so legitimate quoted dialogue (`"Hello," she said.`)
 * survives unchanged.
 */
fun cleanLlmOutput(raw: String): String {
    var out = raw.trim()
    out = stripLeadingPrefix(out)
    out = stripWrappingQuotes(out)
    return out
}

private val LEADING_PREFIX_PATTERN = Regex(
    """^(?:translation|翻訳|翻译)\s*[:：]\s*""",
    RegexOption.IGNORE_CASE,
)

private fun stripLeadingPrefix(s: String): String =
    LEADING_PREFIX_PATTERN.replaceFirst(s, "").trimStart()

private val QUOTE_PAIRS = listOf(
    '"' to '"',
    '\'' to '\'',
    '“' to '”', // “ ”
    '‘' to '’', // ‘ ’
    '「' to '」', // 「 」
    '『' to '』', // 『 』
)

private fun stripWrappingQuotes(s: String): String {
    if (s.length < 2) return s
    val first = s.first()
    val last = s.last()
    for ((open, close) in QUOTE_PAIRS) {
        if (first == open && last == close) {
            return s.substring(1, s.length - 1).trim()
        }
    }
    return s
}
