package com.playtranslate.model

import com.playtranslate.RegionEntry
import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.language.SourceLangId

/**
 * Represents one tappable segment of recognised text.
 * Each segment maps to a single ML Kit TextElement so the user
 * can tap it for a Jisho dictionary lookup.
 */
data class TextSegment(
    val text: String,
    /** True if this segment is just whitespace / punctuation used as a separator */
    val isSeparator: Boolean = false
)

/**
 * Provenance of an OCR-derived translation result: which engine actually read the
 * text ([engineLabel] for display + [engineToken] to identify it) and exactly how
 * to re-read it ([displayId] + [region] crop the identical rectangle even if the
 * live region override later changes; [sourceLangId] is the language the OCR ran
 * under). Set ONLY by the real OCR pipeline (region capture / live) — left null
 * for drag-/sentence-/edit-derived results — so a non-null value doubles as the
 * gate for the "Scanned by …" row, the OCR-switcher gear, and re-OCR eligibility.
 *
 * [engineToken] is the engine that produced THIS result, NOT the current global
 * OCR preference — so the picker highlights/keeps the engine applied to the result
 * even when the global selection has since drifted (e.g. changed in Settings).
 */
data class OcrProvenance(
    val engineLabel: String,
    val engineToken: String,
    val displayId: Int,
    val sourceLangId: SourceLangId,
    val region: RegionEntry,
    /** Whether the saved screenshot contains system UI — the fact a re-OCR
     *  needs to re-crop the SAME pixels the original OCR saw
     *  ([com.playtranslate.capture.CapturedFrame]). NULLABLE deliberately:
     *  Gson does not run Kotlin defaults on missing fields, so results saved
     *  before this field existed deserialize as null, and readers apply
     *  `?: true` (legacy saves were always full-display). */
    val frameIncludesSystemUi: Boolean? = null,
    /** Whether the saved screenshot can contain this app's own overlay
     *  windows — true for live raw frames (e.g. furigana's raw-delegated
     *  first pass caches an icon-bearing frame), so a re-OCR must black the
     *  floating icon back out ([com.playtranslate.capture.CapturedFrame]).
     *  Same nullable-for-Gson idiom as [frameIncludesSystemUi]; readers
     *  apply `?: false` (matches pre-field behavior). */
    val frameIncludesOwnOverlays: Boolean? = null,
)

/**
 * The language context a [TranslationResult] was produced under: the source language
 * it was read/translated from, the target language it was translated into, and the
 * Chinese script variant applied to that target. Captured at construction so a surface
 * can tell when the result has gone stale because the user has since changed any of
 * them (e.g. via the language picker or Settings) and clear it. Unlike [OcrProvenance]
 * this is present for EVERY result, so the staleness check is uniform across result types.
 */
data class TranslationLangContext(
    val sourceLangId: SourceLangId,
    val targetLang: String,
    val chineseVariant: ChineseScriptVariant,
)

/**
 * Full result returned after one capture → OCR → translate cycle.
 */
data class TranslationResult(
    /** Original text reconstructed from ML Kit blocks, with newlines between blocks */
    val originalText: String,
    /** Flat list of tappable segments derived from TextElements */
    val segments: List<TextSegment>,
    /** Translated full text */
    val translatedText: String,
    /** Human-readable timestamp, e.g. "14:32:05" */
    val timestamp: String,
    /** Absolute path to the saved screenshot JPEG, or null if saving failed */
    val screenshotPath: String? = null,
    /** Optional inline warning shown below the translation (e.g. offline-mode notice) */
    val note: String? = null,
    /** Display name of the backend that produced [translatedText], used by the
     *  results view to render "Translated by …" below the translation. Null
     *  when no backend ran (same-language OCR bypass, Translating placeholder)
     *  or when the producing path doesn't track backend identity. */
    val backendDisplayName: String? = null,
    /** OCR provenance for this result, or null when it wasn't produced by the OCR
     *  pipeline (drag/sentence/edit). Drives the "Scanned by …" source label, the
     *  OCR-switcher gear, and re-OCR. See [OcrProvenance]. */
    val ocrProvenance: OcrProvenance? = null,
    /** The source/target/variant this result was translated under, so a surface can
     *  detect staleness after a language change and clear it. See [TranslationLangContext]. */
    val langContext: TranslationLangContext,
)
