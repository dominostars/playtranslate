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
    // KO, AR, ES, FR, DE, IT, PT, NL, TR, VI, ID — deferred to later phases
    ;

    companion object {
        /**
         * Defensive raw-string lookup. Strips BCP-47 region suffix (`ja-JP` → `ja`)
         * and lowercases so `"JA"`, `"ja-JP"`, and `"ja"` all resolve identically.
         * Returns null for blank, null, or unknown codes.
         */
        fun fromCode(code: String?): SourceLangId? {
            if (code.isNullOrBlank()) return null
            val primary = code.substringBefore('-').lowercase()
            return entries.firstOrNull { it.code == primary }
        }
    }
}

/** Broad script family, used for OCR / segmentation / rendering decisions. */
enum class ScriptFamily { LATIN, CJK_JAPANESE, CJK_CHINESE, CJK_KOREAN, ARABIC, DEVANAGARI }

/** Text direction for rendering source text. */
enum class TextDirection { LTR, RTL }

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
    val displayName: String,
    val scriptFamily: ScriptFamily,
    val textDirection: TextDirection,
    val ocrBackend: OcrBackend,
    val hintTextKind: HintTextKind,
    val wordsSeparatedByWhitespace: Boolean,
    val isScriptChar: (Char) -> Boolean,
    val translationCode: String,
)

/** Static profile registry. Phase 3 added EN; later phases add more languages. */
object SourceLanguageProfiles {
    private val all: Map<SourceLangId, SourceLanguageProfile> = mapOf(
        SourceLangId.JA to SourceLanguageProfile(
            id = SourceLangId.JA,
            displayName = "Japanese",
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
            displayName = "English",
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
            displayName = "Chinese",
            scriptFamily = ScriptFamily.CJK_CHINESE,
            textDirection = TextDirection.LTR,
            ocrBackend = OcrBackend.MLKitChinese,
            hintTextKind = HintTextKind.NONE,
            wordsSeparatedByWhitespace = false,
            isScriptChar = { c ->
                c in '\u4E00'..'\u9FFF'     // CJK Unified Ideographs
                    || c in '\u3400'..'\u4DBF'  // CJK Extension A
            },
            translationCode = TranslateLanguage.CHINESE,
        ),
    )

    /** Non-null lookup by ID. Throws for unknown IDs (shouldn't happen in Phase 1). */
    operator fun get(id: SourceLangId): SourceLanguageProfile =
        all[id] ?: error("No profile registered for $id")

    /** Defensive raw-string lookup. Returns null for unknown codes. */
    fun forCode(code: String?): SourceLanguageProfile? =
        SourceLangId.fromCode(code)?.let { all[it] }
}
