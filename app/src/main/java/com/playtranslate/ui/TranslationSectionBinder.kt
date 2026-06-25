package com.playtranslate.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.TextPaint
import android.text.Spanned
import android.text.StaticLayout
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.card.MaterialCardView
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.language.HintTextKind
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.model.TranslationResult
import com.playtranslate.themeColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Renders the source (original) + target (translation) sections — the two shared
 * `<merge>` layouts (section_source.xml / section_target.xml) — given a
 * [TranslationResult] plus [Prefs]. Used by BOTH the in-app results page
 * ([TranslationResultFragment]) and the over-game capture panel
 * ([CaptureResultOverlay]) so the section look + behavior can't drift.
 *
 * The only surface-specific inputs are the [scope] for async furigana + TTS, the
 * [alertTarget] (an Activity vs a capture overlay), and the [ctx] for resources.
 * The word-lookup tap is NOT here — it's surface-parameterized separately — but
 * the inline word highlight IS, because [applyFurigana] must re-attach it after
 * every text swap (toggling furigana would otherwise drop the highlight; that is
 * the latent regression this shared owner prevents).
 *
 * Views are found from [root]; ids are shared with both layouts.
 */
class TranslationSectionBinder(
    root: View,
    private val ctx: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val alertTarget: TtsAlertTarget,
) {
    // ── Section views (shared ids across both surfaces) ──────────────────
    val tvOriginal: ClickableTextView = root.findViewById(R.id.tvOriginal)
    private val tvTranslation: TextView = root.findViewById(R.id.tvTranslation)
    private val tvTranslationNote: TextView = root.findViewById(R.id.tvTranslationNote)
    private val labelOriginal: TextView = root.findViewById(R.id.labelOriginal)
    private val labelTranslation: TextView = root.findViewById(R.id.labelTranslation)
    private val cardOriginal: MaterialCardView = root.findViewById(R.id.cardOriginal)
    private val cardTranslation: MaterialCardView = root.findViewById(R.id.cardTranslation)
    // The card's inner content holder (wraps the text + the translation note row),
    // so its height minus the main text is the in-card overhead — measured live,
    // independent of whether the card itself is pinned to a fill height.
    private val originalContent: View = root.findViewById(R.id.originalContent)
    private val translationContent: View = root.findViewById(R.id.translationContent)
    private val btnCopyOriginal: ImageButton = root.findViewById(R.id.btnCopyOriginal)
    private val btnCopyTranslation: ImageButton = root.findViewById(R.id.btnCopyTranslation)
    private val btnEditOriginal: ImageButton = root.findViewById(R.id.btnEditOriginal)
    private val btnSpeakOriginal: ImageButton = root.findViewById(R.id.btnSpeakOriginal)
    private val btnToggleTranslation: ImageButton = root.findViewById(R.id.btnToggleTranslation)
    private val btnToggleOriginal: ImageButton = root.findViewById(R.id.btnToggleOriginal)
    private val btnToggleFurigana: ImageButton = root.findViewById(R.id.btnToggleFurigana)

    private var speakButton: OriginalSpeakButton? = null

    /** Char range currently highlighted (a word-lookup popup is active), or null.
     *  Tracked here so [applyFurigana] can re-attach the highlight after
     *  rebuilding the spannable. */
    private var highlightedRange: IntRange? = null

    /** Bumped on every [applyFurigana] call so an in-flight async render can tell
     *  it's been superseded and bail before stamping stale spans. */
    private var furiganaRenderToken = 0

    /** Invoked after a section's eye-toggle flips its visibility, so a host can
     *  re-layout (the over-game panel collapses the side-by-side column). Null on
     *  the in-app results page, whose vertical layout just reflows. */
    var onSectionVisibilityChanged: (() -> Unit)? = null

    /** Invoked after inline furigana finishes rendering (it tokenizes async, so the
     *  source text grows TALLER than the initial fit measured). Lets the panel
     *  re-fit/re-size to the now-taller text. Null on the results page (its single
     *  scroll just reflows). */
    var onSourceTextHeightChanged: (() -> Unit)? = null

    // ── Source section ───────────────────────────────────────────────────

    fun setSourceSegments(segments: List<com.playtranslate.model.TextSegment>) {
        tvOriginal.setSegments(segments)
    }

    /** Returns the displayed source text (with OCR line breaks preserved). */
    fun displayedSourceText(): String = tvOriginal.text?.toString() ?: ""

    fun applyOriginalVisibility() {
        val hidden = prefs.hideOriginalSection
        cardOriginal.visibility = if (hidden) View.GONE else View.VISIBLE
        btnCopyOriginal.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        btnEditOriginal.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        btnSpeakOriginal.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        val hintKind = SourceLanguageProfiles[prefs.sourceLangId].hintTextKind
        val hasHintText = hintKind != HintTextKind.NONE
        btnToggleFurigana.visibility = if (hidden || !hasHintText) View.GONE else View.VISIBLE
        if (hasHintText) {
            val label = when (hintKind) { HintTextKind.PINYIN -> "pinyin"; else -> "furigana" }
            btnToggleFurigana.contentDescription = "Toggle inline $label"
            btnToggleFurigana.setImageResource(
                if (hintKind == HintTextKind.PINYIN) R.drawable.ic_pinyin else R.drawable.ic_furigana
            )
        }
        btnToggleOriginal.setImageResource(if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
    }

    fun applyFurigana() {
        val active = prefs.showFuriganaInline
        val accentColor = ctx.themeColor(R.attr.ptAccent)
        val secondaryColor = ctx.themeColor(R.attr.ptTextMuted)
        btnToggleFurigana.imageTintList = ColorStateList.valueOf(
            if (active) accentColor else secondaryColor
        )

        // Every call represents the latest desired furigana state; bump the token
        // so any async render still in flight from a prior call bails out.
        val token = ++furiganaRenderToken
        val plainText = tvOriginal.text.toString()
        if (!active || plainText.isEmpty()) {
            tvOriginal.text = plainText
            // The text reference just got swapped, so any active accent highlight
            // span was dropped — re-attach it from the tracked range.
            highlightedRange?.let { setWordHighlight(it) }
            return
        }
        // annotateForHintText tokenizes off the main thread (it's suspend); apply
        // the furigana spans back on the main thread. Bail if a newer applyFurigana
        // superseded us (toggle-off / re-render → token), or the displayed text
        // changed out from under us (new result → text guard).
        scope.launch {
            val engine = SourceLanguageEngines.get(ctx.applicationContext, prefs.sourceLangId)
            val annotations = engine.annotateForHintText(plainText)
            if (token != furiganaRenderToken || tvOriginal.text.toString() != plainText) return@launch
            if (annotations.isEmpty()) {
                tvOriginal.text = plainText
            } else {
                val spannable = SpannableString(plainText)
                for (ann in annotations) {
                    if (ann.baseEnd > plainText.length) continue
                    spannable.setSpan(
                        FuriganaSpan(ann.hintText, ann.pitchDownstep),
                        ann.baseStart, ann.baseEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                tvOriginal.text = spannable
            }
            // Re-attach the accent highlight dropped by the text swap.
            highlightedRange?.let { setWordHighlight(it) }
            // The furigana spans changed the source's rendered height — let the
            // panel re-fit so the now-taller text doesn't eat its bottom buffer.
            onSourceTextHeightChanged?.invoke()
        }
    }

    /**
     * Highlight the character [range] inside [tvOriginal] with the accent
     * background, or clear any active highlight when [range] is null. Rebuilds a
     * fresh Spannable from the current text so any FuriganaSpans are preserved and
     * prior BackgroundColorSpans are stripped cleanly. Driven by the word-lookup
     * tap on either surface.
     */
    fun setWordHighlight(range: IntRange?) {
        val current = tvOriginal.text ?: return
        val rebuilt = SpannableString(current)
        rebuilt.getSpans(0, rebuilt.length, BackgroundColorSpan::class.java)
            .forEach { rebuilt.removeSpan(it) }
        highlightedRange = range
        if (range != null) {
            val safeEnd = (range.last + 1).coerceAtMost(rebuilt.length)
            val safeStart = range.first.coerceAtLeast(0).coerceAtMost(safeEnd)
            if (safeStart < safeEnd) {
                val accentBg = withAlpha(ctx.themeColor(R.attr.ptAccent), 0.30f)
                rebuilt.setSpan(
                    BackgroundColorSpan(accentBg),
                    safeStart, safeEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        tvOriginal.text = rebuilt
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    // ── Target section ───────────────────────────────────────────────────

    fun applyTranslationVisibility() {
        val hidden = prefs.hideTranslationSection
        cardTranslation.visibility = if (hidden) View.GONE else View.VISIBLE
        btnCopyTranslation.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        btnToggleTranslation.setImageResource(if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
    }

    /** Flip a section's hidden pref, re-apply its visibility, and notify the host
     *  so it can re-layout. Both the section's own eye button and the panel's
     *  collapsed-strip eye route through these. */
    fun toggleOriginalHidden() {
        prefs.hideOriginalSection = !prefs.hideOriginalSection
        applyOriginalVisibility()
        onSectionVisibilityChanged?.invoke()
    }

    fun toggleTranslationHidden() {
        prefs.hideTranslationSection = !prefs.hideTranslationSection
        applyTranslationVisibility()
        onSectionVisibilityChanged?.invoke()
    }

    /** Localized section header names — used for the panel's collapsed strips. */
    fun sourceSectionLabel(): String = sourceLangLocalizedDisplayName()
    fun targetSectionLabel(): String = targetLangDisplayName()

    /** Bind the target text + note for a Ready result. A blank translation means a
     *  re-translate is in flight (edit commit): show the "Translating…"
     *  placeholder and suppress the now-stale backend label. */
    fun bindTargetReady(result: TranslationResult) {
        val retranslating = result.translatedText.isBlank()
        tvTranslation.text =
            if (retranslating) ctx.getString(R.string.status_translating)
            else result.translatedText
        val warning = result.note
        val sourceLabel = result.backendDisplayName?.let {
            ctx.getString(R.string.translation_source_label, it)
        }
        val bottomLabel = if (retranslating) null else (warning ?: sourceLabel)
        tvTranslationNote.text = bottomLabel ?: ""
        tvTranslationNote.visibility = if (bottomLabel != null) View.VISIBLE else View.GONE
        tvTranslationNote.setTypeface(
            null,
            if (warning == null && sourceLabel != null) Typeface.ITALIC else Typeface.NORMAL,
        )
    }

    /** Target placeholder while a translation is still pending (drag-sentence /
     *  Translating state). */
    fun setTargetTranslatingPlaceholder() {
        tvTranslation.text = ctx.getString(R.string.status_translating)
        tvTranslationNote.text = ""
        tvTranslationNote.visibility = View.GONE
    }

    /** Update both section headers to the current source/target language names. */
    fun updateLabels() {
        labelOriginal.text = sourceLangLocalizedDisplayName()
        labelTranslation.text = targetLangDisplayName()
    }

    // ── Convenience for surfaces that bind a whole result at once (panel) ─

    fun bindResult(result: TranslationResult) {
        setSourceSegments(result.segments)
        bindTargetReady(result)
        updateLabels()
        applyOriginalVisibility()
        applyTranslationVisibility()
    }

    // ── Buttons + text fitting ───────────────────────────────────────────

    /** Wire copy / show-hide / furigana toggle / speak. [onEdit] is invoked by the
     *  source Edit button — the surface decides what editing means (an Activity
     *  overlay in-app, an in-place IME over the game). */
    fun setupSectionButtons(onEdit: () -> Unit) {
        btnCopyOriginal.setOnClickListener {
            copyToClipboard(tvOriginal.text?.toString() ?: return@setOnClickListener)
        }
        btnCopyTranslation.setOnClickListener {
            copyToClipboard(tvTranslation.text?.toString() ?: return@setOnClickListener)
        }
        btnEditOriginal.setOnClickListener { onEdit() }
        btnToggleTranslation.setOnClickListener { toggleTranslationHidden() }
        btnToggleOriginal.setOnClickListener { toggleOriginalHidden() }
        btnToggleFurigana.setOnClickListener {
            prefs.showFuriganaInline = !prefs.showFuriganaInline
            applyFurigana()
        }
        speakButton = OriginalSpeakButton(
            btnSpeakOriginal,
            scope,
            alertTarget,
        ) {
            val text = displayedSourceText()
            if (text.isBlank()) null
            else OriginalSpeakButton.Request(text, prefs.sourceLangId)
        }
    }

    /** Side-by-side / results-page: fit each text to its own target height with a
     *  CONTINUOUS size (so the load animation + resize scale smoothly instead of
     *  in 1sp steps). */
    fun fitText(translationTargetPx: Int, sourceTargetPx: Int) {
        tvTranslation.setTextSize(TypedValue.COMPLEX_UNIT_SP, fitSize(tvTranslation, translationTargetPx))
        tvOriginal.setTextSize(TypedValue.COMPLEX_UNIT_SP, fitSize(tvOriginal, sourceTargetPx))
    }

    /** Set both text sizes directly — used to interpolate each frame of the height
     *  animation between the fitted start/end sizes (a float, not a 1sp step). */
    fun setSizes(sourceSp: Float, targetSp: Float) {
        tvOriginal.setTextSize(TypedValue.COMPLEX_UNIT_SP, sourceSp)
        tvTranslation.setTextSize(TypedValue.COMPLEX_UNIT_SP, targetSp)
    }

    /** Largest size that fits [targetPx] — computed, not applied (for the
     *  animation endpoints). */
    fun sourceSizeFor(targetPx: Int): Float = fitSize(tvOriginal, targetPx)
    fun targetSizeFor(targetPx: Int): Float = fitSize(tvTranslation, targetPx)

    /** Natural text heights at max size, measured the SAME way as the fit
     *  (StaticLayout), so the auto-height target and the fit agree — otherwise the
     *  taller column's bottom gets clipped. */
    fun sourceTextHeightAtMax(): Int = textHeightAt(tvOriginal, TEXT_SIZE_MAX_SP)
    fun targetTextHeightAtMax(): Int = textHeightAt(tvTranslation, TEXT_SIZE_MAX_SP)

    fun sourceHeaderHeight(): Int = (labelOriginal.parent as? View)?.height ?: 0
    fun targetHeaderHeight(): Int = (labelTranslation.parent as? View)?.height ?: 0

    // The card's non-text vertical space splits into two parts the panel sums:
    //  • contentOverhead — the content holder's height minus the MAIN text:
    //    padding + (for the target) the "Translated by" note row. Measured LIVE
    //    (the holder wraps its content even when the card is pinned to a fill
    //    height), so the note is always counted even if it laid out a frame late.
    //  • cardInset — the card frame's own inset (rounded-corner overlap + stroke).
    //    Constant; the panel measures it ONCE while the card is still wrap.
    fun sourceContentOverhead(): Int = originalContent.height - tvOriginal.height
    fun targetContentOverhead(): Int = translationContent.height - tvTranslation.height
    fun sourceCardInset(): Int = (cardOriginal.height - originalContent.height).coerceAtLeast(0)
    fun targetCardInset(): Int = (cardTranslation.height - translationContent.height).coerceAtLeast(0)
    // Whole-card heights — the panel reads these ONCE while the cards still wrap to
    // derive the stacked non-card chrome (headers + divider + padding).
    fun sourceCardHeight(): Int = cardOriginal.height
    fun targetCardHeight(): Int = cardTranslation.height

    /** Set each section card's MINIMUM height, so its background fills the column
     *  (sized by the view, not the text). The card still wraps content TALLER than
     *  this — so an imperfect text fit, or text already at min size, grows the card
     *  (and the scroll takes over) instead of clipping the bottom line / note row. */
    fun setCardMinHeights(sourcePx: Int, targetPx: Int) {
        cardOriginal.minimumHeight = sourcePx
        cardTranslation.minimumHeight = targetPx
    }

    /** Largest float size in [[TEXT_SIZE_MIN_SP], [TEXT_SIZE_MAX_SP]] whose text
     *  fits [targetPx] (binary search → continuous, not 1sp steps). */
    private fun fitSize(tv: TextView, targetPx: Int): Float {
        if (tv.width <= 0) return TEXT_SIZE_MAX_SP
        if (textHeightAt(tv, TEXT_SIZE_MAX_SP) <= targetPx) return TEXT_SIZE_MAX_SP
        if (textHeightAt(tv, TEXT_SIZE_MIN_SP) > targetPx) return TEXT_SIZE_MIN_SP
        var lo = TEXT_SIZE_MIN_SP
        var hi = TEXT_SIZE_MAX_SP
        repeat(BISECT_STEPS) {
            val mid = (lo + hi) / 2f
            if (textHeightAt(tv, mid) <= targetPx) lo = mid else hi = mid
        }
        return lo
    }

    /** Height [tv]'s text would occupy at [sizeSp] and its current width, without
     *  touching the live view — a paint copy + StaticLayout configured to match
     *  the live TextView exactly, so measure and render agree (a mismatch makes the
     *  card height track its content instead of the panel → wobble). */
    private fun textHeightAt(tv: TextView, sizeSp: Float): Int {
        val widthPx = (tv.width - tv.compoundPaddingLeft - tv.compoundPaddingRight)
            .takeIf { it > 0 } ?: return 0
        val paint = TextPaint(tv.paint)
        paint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, sizeSp, ctx.resources.displayMetrics,
        )
        val layoutHeight = StaticLayout.Builder
            .obtain(tv.text, 0, tv.text.length, paint, widthPx)
            .setLineSpacing(tv.lineSpacingExtra, tv.lineSpacingMultiplier)
            // The live TextView (API 28+) grows line height via fallback fonts so
            // tall CJK glyphs fit; the StaticLayout default is off. Without this we
            // under-measure the Japanese source and its card wobbles.
            .setUseLineSpacingFromFallbacks(true)
            .build()
            .height
        return layoutHeight + tv.compoundPaddingTop + tv.compoundPaddingBottom
    }

    fun release() {
        speakButton?.release()
        speakButton = null
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun copyToClipboard(text: String) {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PlayTranslate", text))
        Toast.makeText(ctx, ctx.getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
    }

    private fun sourceLangLocalizedDisplayName(): String =
        prefs.sourceLangId.displayName(Locale.forLanguageTag(prefs.targetLang))

    private fun targetLangDisplayName(): String {
        val code = prefs.targetLang
        val variant = prefs.targetChineseVariant
        return ChineseScriptVariant.targetDisplayName(code, variant, Locale.forLanguageTag(code))
    }

    private companion object {
        const val TEXT_SIZE_MAX_SP = 24f
        const val TEXT_SIZE_MIN_SP = 16f
        /** Binary-search iterations for the continuous text fit. */
        const val BISECT_STEPS = 8
    }
}
