package com.playtranslate.model

import com.playtranslate.RegionEntry
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
)
