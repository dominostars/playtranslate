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
 * Everything needed to run a machine translation that was deliberately SKIPPED
 * because the translation section was hidden ([com.playtranslate.Prefs.hideTranslationSection])
 * when the result was produced. Carried on [TranslationResult.pendingTranslation];
 * non-null means [TranslationResult.translatedText] is empty because no backend
 * ever ran — not because one is in flight or failed.
 *
 * Exactly one consumer completes it (translate → attach History rows → rebind
 * with the field cleared); surfaces trigger that the moment the translation is
 * needed: the section's eye toggle, a bind while the section is visible, a
 * "show on screen" tap.
 */
data class PendingTranslation(
    /** Per-OCR-group source texts, index-aligned with the skeleton overlay boxes
     *  and with the null-translation History rows recorded at capture time. */
    val groupTexts: List<String>,
    // (Serializable so the Anki review flow can carry the pending through its
    // activity trampoline's intent extras — same transport as the enrichment
    // map. In-process only; nothing persists it.)
    /** Source language pinned when the result was produced — the completion must
     *  translate under the language the OCR ran as, not current Prefs. */
    val sourceLangId: SourceLangId,
    /** Target language at capture time. The completion translates into the
     *  CURRENT target (display truth) but attaches to History only while the
     *  target still matches this value — cross-pair translations are
     *  display-only, never attached (the deliberate flow's rule). */
    val targetLang: String,
    /** Capture-pipeline result (per-group completion + session-scoped History
     *  attach via CaptureService.completeDeferredTranslation) vs a deliberate
     *  single-sentence lookup (translateOnce + the deliberate-row attach rules
     *  owned by the launching activity). The shapes have different History
     *  contracts — this is the discriminator, independent of eligibility. */
    val isCapture: Boolean = false,
    /** History capture-session id the null rows were recorded under — the
     *  completion's ONLY History write target (attach-only; the completion
     *  never inserts rows). Null ⇒ the completion must not touch History:
     *  Text History was disabled when the text was captured, or the producing
     *  surface records none (Process-Text). */
    val historySessionId: String? = null,
    /** Whether Text History was enabled when this result was PRODUCED. The
     *  sentence shape's completion consults this (its rows are deliberate
     *  rows, not capture-session rows): enabling History between lookup and
     *  reveal must not let the completion record text captured while the
     *  user was opted out. Capture shape encodes the same fact in
     *  [historySessionId]'s nullness. */
    val historyEligible: Boolean = false,
    /** Whether the LLM context ring was enabled when this result was
     *  PRODUCED. The completion feeds the ring only when this AND the
     *  current pref both hold — enabling context after an opted-out capture
     *  must not leak that capture into later prompts. */
    val contextEligible: Boolean = false,
    /** Unique per-produced-result token, so data-class equality IS identity.
     *  Every completion guard (VM apply, host in-flight dedupe, panel
     *  funnel) compares pendings by equality; without this, two captures of
     *  the same text under the same pair and eligibility compare equal —
     *  especially with History off, where [historySessionId] is null — and
     *  a stale completion could pass a newer result's guard, clear its
     *  pending, and carry the OLDER capture's on-screen boxes onto it.
     *  Generated at construction; `copy()` deliberately preserves it (a
     *  copy of a pending is the same logical request). */
    val requestToken: String = java.util.UUID.randomUUID().toString(),
) : java.io.Serializable

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
    /** Non-null ⇒ the machine translation was skipped because the translation
     *  section was hidden; [translatedText] is empty and no backend ran. See
     *  [PendingTranslation] for the completion contract. Every path that derives
     *  a NEW translation from this result (edit commit, completion rebind) must
     *  clear it, or a later reveal would clobber that translation. */
    val pendingTranslation: PendingTranslation? = null,
    /** The source/target/variant this result was translated under, so a surface can
     *  detect staleness after a language change and clear it. See [TranslationLangContext]. */
    val langContext: TranslationLangContext,
    /** Epoch ms of the moment this result REPRESENTS — the Anki flow passes it
     *  as the game-audio ring anchor, so the trim view opens at the sentence's
     *  own moment. Capture pipelines pass the frame's shutter time EXPLICITLY
     *  (construction there happens after MT, and backend latency must not
     *  drift the anchor — adversarial-review finding); the constructor default
     *  covers only paths that construct at the moment they represent.
     *  Preserved by copy() (an edit/rebind is the same capture moment). 0 ⇒
     *  unknown (a deserialized legacy value); consumers gate on `> 0`, and
     *  [timestamp] stays the display string it always was. */
    val createdAtMs: Long = System.currentTimeMillis(),
)
