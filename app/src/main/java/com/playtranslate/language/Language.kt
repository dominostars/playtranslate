package com.playtranslate.language

import com.google.mlkit.nl.translate.TranslateLanguage

/**
 * Identifier for every source language PlayTranslate supports (or will support
 * in future phases). The [code] matches ML Kit / DeepL / Lingva convention so
 * it can double as the storage format in [com.playtranslate.Prefs.sourceLang].
 *
 * Phase 1 only has [JA]; the rest of the enum populates as each phase lands
 * (see `v2-architecture.md` § Phase roadmap).
 */
enum class SourceLangId(val code: String) {
    JA("ja"),
    EN("en"),
    ZH("zh"),
    ZH_HANT("zh-Hant"),
    ES("es"),
    // KO, AR, FR, DE, IT, PT, NL, TR, VI, ID — deferred to later phases
    ;

    /** The lang ID used for pack directory/catalog lookup. Variants that share
     *  a pack (e.g. ZH_HANT shares ZH's pack) override this. */
    val packId: SourceLangId get() = when (this) {
        ZH_HANT -> ZH
        else -> this
    }

    /** Display name in [locale]. e.g. `JA.displayName(Locale("en"))` → "Japanese";
     *  `JA.displayName(Locale("ja"))` → "日本語". Defaults to system locale. */
    fun displayName(locale: java.util.Locale = java.util.Locale.getDefault()): String = when (this) {
        ZH      -> java.util.Locale.forLanguageTag("zh-Hans").getDisplayName(locale)
            .replaceFirstChar { it.uppercase() }
        ZH_HANT -> java.util.Locale.forLanguageTag("zh-Hant").getDisplayName(locale)
            .replaceFirstChar { it.uppercase() }
        else    -> java.util.Locale(code).getDisplayLanguage(locale)
            .replaceFirstChar { it.uppercase() }
    }

    companion object {
        /** Region codes that imply Traditional Chinese script. */
        private val TRADITIONAL_REGIONS = setOf("tw", "hk", "mo")

        fun fromCode(code: String?): SourceLangId? {
            if (code.isNullOrBlank()) return null
            val lower = code.lowercase()
            // Exact match first (handles "zh-hant" → ZH_HANT)
            entries.firstOrNull { it.code.lowercase() == lower }?.let { return it }
            // Map zh-TW, zh-HK, zh-MO, zh-Hant-TW etc. to ZH_HANT
            if (lower.startsWith("zh-")) {
                val parts = lower.removePrefix("zh-").split('-')
                if (parts.any { it == "hant" || it in TRADITIONAL_REGIONS }) return ZH_HANT
            }
            // Fall back to primary subtag (handles "ja-JP" → JA)
            val primary = lower.substringBefore('-')
            return entries.firstOrNull { it.code == primary }
        }
    }
}

/** Broad script family, used for OCR / segmentation / rendering decisions. */
enum class ScriptFamily { LATIN, CJK_JAPANESE, CJK_CHINESE, CJK_KOREAN, ARABIC, DEVANAGARI }

/** Text direction for rendering source text. */
enum class TextDirection { LTR, RTL }

/** Text orientation: horizontal (left-to-right lines) or vertical (top-to-bottom columns). */
enum class TextOrientation { HORIZONTAL, VERTICAL }

/**
 * The on-device OCR backend that produces recognized text for a source
 * language. Sealed so the [ScreenTextRecognizerFactory] `when` is exhaustive
 * at compile time.
 */
sealed interface OcrBackend {
    data object MLKitLatin : OcrBackend
    data object MLKitChinese : OcrBackend
    data object MLKitJapanese : OcrBackend
    data object MLKitKorean : OcrBackend
    data object MLKitDevanagari : OcrBackend
    data class Tesseract(val traineddataCode: String) : OcrBackend
}

/**
 * Kind of hint text rendered above source text (furigana for Japanese, pinyin
 * for Chinese, harakat for Arabic). Only [FURIGANA] is implemented in v2;
 * [PINYIN] and [HARAKAT] are reserved so the architecture stays forward-
 * compatible without locking in a boolean we would have to widen later.
 */
enum class HintTextKind {
    NONE,
    FURIGANA,
    PINYIN,
    HARAKAT,
}

/**
 * Static, const-like description of one source language. All the knobs that
 * come from *knowing* "this is Japanese" without needing any on-device data.
 * One value per supported language, defined in [SourceLanguageProfiles].
 */
data class SourceLanguageProfile(
    val id: SourceLangId,
    val scriptFamily: ScriptFamily,
    val textDirection: TextDirection,
    val ocrBackend: OcrBackend,
    val hintTextKind: HintTextKind,
    val wordsSeparatedByWhitespace: Boolean,
    val isScriptChar: (Char) -> Boolean,
    val translationCode: String,
    /** When true, dictionary results show traditional headword first. */
    val preferTraditional: Boolean = false,
)

private val CJK_CHAR_CHECK: (Char) -> Boolean = { c ->
    c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF'
}

/** Static profile registry. Phase 3 added EN; later phases add more languages. */
object SourceLanguageProfiles {
    private val all: Map<SourceLangId, SourceLanguageProfile> = mapOf(
        SourceLangId.JA to SourceLanguageProfile(
            id = SourceLangId.JA,
            scriptFamily = ScriptFamily.CJK_JAPANESE,
            textDirection = TextDirection.LTR,
            ocrBackend = OcrBackend.MLKitJapanese,
            hintTextKind = HintTextKind.FURIGANA,
            wordsSeparatedByWhitespace = false,
            isScriptChar = { c ->
                c in '\u3040'..'\u309F'     // Hiragana
                    || c in '\u30A0'..'\u30FF'  // Katakana
                    || c in '\u4E00'..'\u9FFF'  // CJK Unified Ideographs
                    || c in '\u3400'..'\u4DBF'  // CJK Extension A
                    || c in '\uFF65'..'\uFF9F'  // Half-width Katakana
            },
            translationCode = TranslateLanguage.JAPANESE,
        ),
        SourceLangId.EN to SourceLanguageProfile(
            id = SourceLangId.EN,
            scriptFamily = ScriptFamily.LATIN,
            textDirection = TextDirection.LTR,
            ocrBackend = OcrBackend.MLKitLatin,
            hintTextKind = HintTextKind.NONE,
            wordsSeparatedByWhitespace = true,
            isScriptChar = { c ->
                c in '\u0041'..'\u005A'     // A-Z
                    || c in '\u0061'..'\u007A'  // a-z
                    || c in '\u00C0'..'\u00FF'  // Latin-1 Supplement letters
            },
            translationCode = TranslateLanguage.ENGLISH,
        ),
        SourceLangId.ZH to SourceLanguageProfile(
            id = SourceLangId.ZH,
            scriptFamily = ScriptFamily.CJK_CHINESE,
            textDirection = TextDirection.LTR,
            ocrBackend = OcrBackend.MLKitChinese,
            hintTextKind = HintTextKind.PINYIN,
            wordsSeparatedByWhitespace = false,
            isScriptChar = CJK_CHAR_CHECK,
            translationCode = TranslateLanguage.CHINESE,
        ),
        SourceLangId.ZH_HANT to SourceLanguageProfile(
            id = SourceLangId.ZH_HANT,
            scriptFamily = ScriptFamily.CJK_CHINESE,
            textDirection = TextDirection.LTR,
            ocrBackend = OcrBackend.MLKitChinese,
            hintTextKind = HintTextKind.PINYIN,
            wordsSeparatedByWhitespace = false,
            isScriptChar = CJK_CHAR_CHECK,
            translationCode = TranslateLanguage.CHINESE,
            preferTraditional = true,
        ),
        SourceLangId.ES to SourceLanguageProfile(
            id = SourceLangId.ES,
            scriptFamily = ScriptFamily.LATIN,
            textDirection = TextDirection.LTR,
            ocrBackend = OcrBackend.MLKitLatin,
            hintTextKind = HintTextKind.NONE,
            wordsSeparatedByWhitespace = true,
            isScriptChar = { c ->
                c in '\u0041'..'\u005A'     // A-Z
                    || c in '\u0061'..'\u007A'  // a-z
                    || c in '\u00C0'..'\u00FF'  // Latin-1 Supplement (á, é, ñ, ü, etc.)
            },
            translationCode = TranslateLanguage.SPANISH,
        ),
    )

    /** Non-null lookup by ID. Throws for unknown IDs (shouldn't happen in Phase 1). */
    operator fun get(id: SourceLangId): SourceLanguageProfile =
        all[id] ?: error("No profile registered for $id")

    /** Defensive raw-string lookup. Returns null for unknown codes. */
    fun forCode(code: String?): SourceLanguageProfile? =
        SourceLangId.fromCode(code)?.let { all[it] }
}
