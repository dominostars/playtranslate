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
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.card.MaterialCardView
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.language.HintTextKind
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.hintAnnotations
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.model.OcrProvenance
import com.playtranslate.model.TranslationResult
import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.themeColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

/** A results section card's fill at [alpha] (0 to 1) of the theme's card
 *  colour: what the capture sheet tints every section's card with so its
 *  frosted backdrop shows faintly through. */
internal fun sectionCardFill(ctx: Context, alpha: Float): Int {
    val card = ctx.themeColor(R.attr.ptCard)
    return Color.argb(
        (alpha.coerceIn(0f, 1f) * 255).toInt(), Color.red(card), Color.green(card), Color.blue(card),
    )
}

/**
 * Renders the source (original) + target (translation) sections — the two shared
 * `<merge>` layouts (section_source.xml / section_target.xml) — given a
 * [TranslationResult] plus [Prefs]. Used by BOTH the in-app results page
 * ([TranslationResultFragment]) and the over-game capture panel
 * ([CaptureResultOverlay]) so the section look + behavior can't drift.
 *
 * The only surface-specific inputs are the [scope] for async furigana + TTS, the
 * [alertTarget] (an Activity vs a capture overlay), the [ctx] for resources, and
 * the surface's [popovers] (the headers' ⋯ menu shows there). The word-lookup
 * tap is NOT here — it's surface-parameterized separately — but the inline word
 * highlight IS, because [applyFurigana] must re-attach it after every text swap
 * (toggling furigana would otherwise drop the highlight; that is the latent
 * regression this shared owner prevents).
 *
 * The section headers' actions are [HeaderAction] models rendered by the two
 * [SectionHeaderRow]s: this binder only ever writes the models (availability,
 * icon, accent, handlers), never an action view, so each action behaves the
 * same inline and in its header's ⋯ menu. List order is fold priority (the
 * tail folds first); the eyes are pinned and never fold.
 *
 * A hidden section's header is its name (receded) and its crossed eye, and
 * nothing else: every other action goes with the card. Two of the
 * Translation header's actions are about the whole result, not that card
 * (Text size sizes both texts, Show on screen puts the result over the
 * game), so they do not go: while the Translation section is hidden they
 * sit in the Original header ([syncResultActions]).
 *
 * Views are found from [root]; ids are shared with both layouts.
 */
class TranslationSectionBinder(
    root: View,
    private val ctx: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val alertTarget: TtsAlertTarget,
    private val popovers: PopoverHost,
) {
    // ── Section views (shared ids across both surfaces) ──────────────────
    val tvOriginal: ClickableTextView = root.findViewById(R.id.tvOriginal)
    private val tvTranslation: TextView = root.findViewById(R.id.tvTranslation)
    private val tvTranslationNote: TextView = root.findViewById(R.id.tvTranslationNote)
    private val labelOriginal: TextView = root.findViewById(R.id.labelOriginal)
    private val labelTranslation: TextView = root.findViewById(R.id.labelTranslation)
    private val sourceRow: SectionHeaderRow = root.findViewById(R.id.sourceHeaderRow)
    private val targetRow: SectionHeaderRow = root.findViewById(R.id.targetHeaderRow)
    private val cardOriginal: MaterialCardView = root.findViewById(R.id.cardOriginal)
    private val cardTranslation: MaterialCardView = root.findViewById(R.id.cardTranslation)
    // The card's inner content holder (wraps the text + the translation note row),
    // so its height minus the main text is the in-card overhead — measured live,
    // independent of whether the card itself is pinned to a fill height.
    private val originalContent: View = root.findViewById(R.id.originalContent)
    private val translationContent: View = root.findViewById(R.id.translationContent)
    // "Scanned by <engine>" + gear, under the source text (mirror of tvTranslationNote).
    private val sourceNoteRow: View = root.findViewById(R.id.sourceNoteRow)
    private val tvSourceNote: TextView = root.findViewById(R.id.tvSourceNote)
    private val btnSourceOcr: ImageView = root.findViewById(R.id.btnSourceOcr)

    // ── Header actions (fold priority order; eyes pinned) ────────────────

    private val furigana = HeaderAction(
        R.id.btnToggleFurigana, R.drawable.ic_furigana,
        ctx.getString(R.string.header_action_furigana),
        ctx.getString(R.string.cd_toggle_inline_furigana),
    )
    private val speak = HeaderAction(
        R.id.btnSpeakOriginal, R.drawable.ic_lens_speak,
        ctx.getString(R.string.header_action_read_aloud),
        ctx.getString(R.string.cd_read_original_aloud),
    )
    private val edit = HeaderAction(
        R.id.btnEditOriginal, R.drawable.ic_edit,
        ctx.getString(R.string.header_action_edit),
        ctx.getString(R.string.cd_edit_original),
    )
    private val sourceEye = HeaderAction(
        R.id.btnToggleOriginal, R.drawable.ic_visibility,
        ctx.getString(R.string.cd_toggle_original_visibility), pinned = true,
    )
    private val showOnScreen = HeaderAction(
        R.id.btnShowOnScreen, R.drawable.ic_subtitles, ctx.getString(R.string.capture_show_on_screen),
        presentation = HeaderAction.Presentation.TEXT_PILL,
    )
    // The two whole-result actions' second homes, in the Original header
    // (class doc). Same look and handler as the Translation header's; only
    // one of each pair is ever available ([syncResultActions]).
    private val showOnScreenInSource = HeaderAction(
        R.id.btnShowOnScreenOriginal, R.drawable.ic_subtitles,
        ctx.getString(R.string.capture_show_on_screen),
        presentation = HeaderAction.Presentation.TEXT_PILL,
    )
    private val textSizeInSource = HeaderAction(
        R.id.btnFontSizeOriginal, R.drawable.ic_format_size, ctx.getString(R.string.cd_text_size),
    )
    private val textSize = HeaderAction(
        R.id.btnFontSize, R.drawable.ic_format_size, ctx.getString(R.string.cd_text_size),
    )
    private val targetEye = HeaderAction(
        R.id.btnToggleTranslation, R.drawable.ic_visibility,
        ctx.getString(R.string.cd_toggle_translation_visibility), pinned = true,
    )

    init {
        // The gear is tinted in CODE, not via XML app:tint: the over-game overlay
        // inflates these views with a plain (non-AppCompat) LayoutInflater, which
        // silently drops app:tint, so the white ic_settings would render white
        // there while the in-app (AppCompat) surface tints it. imageTintList works
        // on a plain ImageView too, so both surfaces match. (The header actions
        // are tinted by their row for the same reason.)
        btnSourceOcr.imageTintList =
            ColorStateList.valueOf(ctx.themeColor(R.attr.ptTextHint))
        // Neither whole-result action is offered until a host says so
        // (show-on-screen via setShowOnScreenAvailable, text size via
        // onChooseFontSize), which is what [syncResultActions] then decides.
        for (a in listOf(showOnScreen, showOnScreenInSource, textSize, textSizeInSource)) {
            a.available = false
        }
        // The Original header takes the two whole-result actions at its
        // tail, so they are the first to fold behind ⋯ in the busier header.
        sourceRow.setActions(
            listOf(furigana, speak, edit, showOnScreenInSource, textSizeInSource, sourceEye),
            R.id.btnMoreOriginal,
        )
        targetRow.setActions(listOf(showOnScreen, textSize, targetEye), R.id.btnMoreTranslation)
        sourceRow.onMoreClick = ::toggleOverflow
        targetRow.onMoreClick = ::toggleOverflow
    }

    private var speaker: OriginalSpeaker? = null

    /** The header actions the controller cursor can reach, filtered to what's
     *  currently on screen: each header's inline actions, its ⋯ while
     *  anything is folded, and its eye. Deliberately excludes the language
     *  labels and the OCR row — both open picker windows the controller
     *  can't drive (and the language path dismisses the sheet). */
    fun navigableActions(): List<NavAction> = sourceRow.navActions() + targetRow.navActions()

    /** ⋯: open the header's menu of folded actions, or close the popover
     *  that's up (the scrim makes that a keyboard-focus-only path). */
    private fun toggleOverflow(row: SectionHeaderRow) {
        if (popovers.isShowing) {
            popovers.dismiss()
            return
        }
        popovers.show(ActionOverflowMenu(row), row.moreButton)
    }

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

    /** Invoked when the user taps the source OCR-attribution row (text or gear).
     *  Each surface opens its own OCR picker. Null → the row is inert. */
    var onChooseOcr: (() -> Unit)? = null

    /** Invoked when the user taps a language section header — `isSource = true` for the
     *  source (original) header, false for the target (translation) header. Each surface
     *  opens the language picker for that side. Null → the headers are inert. */
    var onChooseLanguage: ((isSource: Boolean) -> Unit)? = null

    /** Invoked with the view presenting the target header's Text size action
     *  (its button, or the header's ⋯ when folded). Each surface shows
     *  [FontSizeRangePopover] on its [PopoverHost] anchored there, then re-fits
     *  on change. Null (the default) → the action is unavailable, so a surface
     *  that can't host the popover never renders a dead control. */
    var onChooseFontSize: ((anchor: View) -> Unit)? = null
        set(value) {
            field = value
            syncResultActions()
        }

    /** Whether the host has boxes to show on screen right now
     *  ([setShowOnScreenAvailable]). */
    private var showOnScreenOffered = false

    /**
     * Put the two whole-result actions (Text size, Show on screen) in the
     * header that can hold them: the Translation header while that section
     * is shown, the Original header while it is hidden and the Original is
     * shown. With both sections hidden neither header offers them (a hidden
     * header is its name and its eye). Every input that decides this ends
     * here: both eyes ([applyOriginalVisibility], [applyTranslationVisibility])
     * and both hosts' offers ([onChooseFontSize], [setShowOnScreenAvailable]).
     */
    private fun syncResultActions() {
        val inTarget = !prefs.hideTranslationSection
        val inSource = !inTarget && !prefs.hideOriginalSection
        val sizeOffered = onChooseFontSize != null
        // The compact page has no headers to choose between: its one Text
        // size stands for both, so whoever lists it holds one model.
        textSize.available = sizeOffered && (inTarget || (compact && inSource))
        textSizeInSource.available = sizeOffered && inSource && !compact
        showOnScreen.available = showOnScreenOffered && inTarget
        showOnScreenInSource.available = showOnScreenOffered && inSource
    }

    init {
        sourceNoteRow.setOnClickListener { onChooseOcr?.invoke() }
        labelOriginal.setOnClickListener { onChooseLanguage?.invoke(true) }
        labelTranslation.setOnClickListener { onChooseLanguage?.invoke(false) }
        for (size in listOf(textSize, textSizeInSource)) {
            size.onClick = { anchor -> onChooseFontSize?.invoke(anchor) }
        }
    }

    // ── Source section ───────────────────────────────────────────────────

    /** Bind the source text for display: set the text AND paint inline furigana in
     *  one step, so no surface can render source text without its ruby. No-ops when
     *  the displayed text is unchanged (a Translating→Ready promotion, a backend
     *  re-translate), preserving already-painted furigana + the word highlight and
     *  skipping a redundant re-tokenize / ruby flash. The "Scanned by…" attribution
     *  row is bound separately via [bindSourceOcr]; the furigana toggle re-runs
     *  [applyFurigana] directly (a pref change isn't a source change). */
    fun bindSource(segments: List<com.playtranslate.model.TextSegment>) {
        val newText = segments.joinToString("") { it.text }
        // toString() is the base text even with ruby spans attached (FuriganaSpan is
        // a ReplacementSpan that inserts no characters), so equal text means the
        // source is unchanged — leave the existing furigana/highlight spans in place.
        if (newText == tvOriginal.text?.toString()) return
        setSourceSegments(segments)  // plain text (drops any old spans)
        applyFurigana()              // repaints ruby (async) + re-attaches the highlight
    }

    private fun setSourceSegments(segments: List<com.playtranslate.model.TextSegment>) {
        tvOriginal.setSegments(segments)
    }

    /** Returns the displayed source text (with OCR line breaks preserved). */
    fun displayedSourceText(): String = tvOriginal.text?.toString() ?: ""

    /** Bind the "Scanned by <engine>" attribution row under the source text. Shown
     *  only for OCR-derived results ([provenance] != null); the gear + tap are
     *  enabled only when the language has >1 available OCR tool to switch between
     *  AND [canReOcr] (a cached screenshot is still on hand to re-run). Without a
     *  screenshot the switch would be a dead control — cache-save can fail while OCR
     *  still ran on the in-memory bitmap — so we show the attribution text alone
     *  (mirroring Settings hiding its OCR card). Italic to match "Translated by". */
    fun bindSourceOcr(provenance: OcrProvenance?, canReOcr: Boolean) {
        if (provenance == null) {
            sourceNoteRow.visibility = View.GONE
            return
        }
        tvSourceNote.text = ctx.getString(R.string.ocr_source_label, provenance.engineLabel)
        tvSourceNote.setTypeface(null, Typeface.ITALIC)
        sourceNoteRow.visibility = View.VISIBLE
        val canSwitch = canReOcr &&
            OcrModelManager.availableBackends(ctx, provenance.sourceLangId).size > 1
        btnSourceOcr.visibility = if (canSwitch) View.VISIBLE else View.GONE
        sourceNoteRow.isClickable = canSwitch
    }

    /** Whether this surface can edit the source (an Activity overlay, the
     *  sheet's in-place IME). False removes the Edit action outright, inline
     *  and from the ⋯ menu — a surface with no editor never renders a dead
     *  control. */
    var editAvailable: Boolean = true
        set(value) {
            field = value
            applyOriginalVisibility()
        }

    /** A hidden source takes its text actions with it (the eye stays, pinned
     *  at the end, so it never moves under the finger that just hid it).
     *  Unavailable is GONE: the space goes back to the fit. */
    fun applyOriginalVisibility() {
        val hidden = prefs.hideOriginalSection
        cardOriginal.visibility = if (hidden) View.GONE else View.VISIBLE
        edit.available = editAvailable && !hidden
        speak.available = !hidden
        val hintKind = SourceLanguageProfiles[prefs.sourceLangId].hintTextKind
        val hasHintText = hintKind != HintTextKind.NONE
        furigana.available = !hidden && hasHintText
        if (hasHintText) {
            val pinyin = hintKind == HintTextKind.PINYIN
            furigana.icon = if (pinyin) R.drawable.ic_pinyin else R.drawable.ic_furigana
            furigana.label = ctx.getString(
                if (pinyin) R.string.header_action_pinyin else R.string.header_action_furigana,
            )
            furigana.contentDescription = ctx.getString(
                if (pinyin) R.string.cd_toggle_inline_pinyin else R.string.cd_toggle_inline_furigana,
            )
        }
        sourceEye.icon = if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility
        // The eye's name where it is listed in a menu (the compact page's);
        // inline it is its icon and its spoken name, which stay.
        sourceEye.label = ctx.getString(
            if (hidden) R.string.header_action_show_original else R.string.header_action_hide_original,
        )
        sourceRow.labelDimmed = hidden
        syncResultActions()
    }

    fun applyFurigana() {
        val active = prefs.showFuriganaInline
        furigana.active = active

        // Every call represents the latest desired furigana state; bump the token
        // so any async render still in flight from a prior call bails out.
        val token = ++furiganaRenderToken
        val plainText = tvOriginal.text.toString()
        if (!active || plainText.isEmpty()) {
            tvOriginal.text = plainText
            // The text reference just got swapped, so any active accent highlight
            // span was dropped — re-attach it from the tracked range.
            highlightedRange?.let { setWordHighlight(it) }
            // Furigana off → the source shrank; let the panel re-fit to it.
            onSourceTextHeightChanged?.invoke()
            return
        }
        // The FULL-depth annotation resolves dictionary readings off the main
        // thread (suspend); apply the furigana spans back on the main thread.
        // FULL is the correctness contract: what renders is the occurrence-
        // validated reading (一泊 → いっぱく), the same one the words panel,
        // Anki fields, and sentence TTS quote — never a provisional reading
        // that gets corrected later. Bail if a newer applyFurigana superseded
        // us (toggle-off / re-render → token), or the displayed text changed
        // out from under us (new result → text guard).
        scope.launch {
            val engine = SourceLanguageEngines.get(ctx.applicationContext, prefs.sourceLangId)
            val annotations = engine.annotate(plainText).hintAnnotations()
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
        // Text size and Show on screen survive the hide, in the Original
        // header: the size range governs the SOURCE text too, and the boxes
        // present the whole result. The eye stays put either way (pinned at
        // the end).
        targetEye.icon = if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility
        targetEye.label = ctx.getString(
            if (hidden) R.string.header_action_show_translation else R.string.header_action_hide_translation,
        )
        targetRow.labelDimmed = hidden
        syncResultActions()
    }

    /** Wire the "show on screen" toggle. On the in-app results page while
     *  live on two screens it flips the hide-overlays-during-auto setting;
     *  the host gates availability via [setShowOnScreenAvailable]. */
    fun setShowOnScreenAction(onClick: () -> Unit) {
        for (pill in listOf(showOnScreen, showOnScreenInSource)) pill.onClick = { onClick() }
    }

    /** Whether the show-on-screen toggle currently has something to act on.
     *  Unavailable when not, so surfaces that never enable it lose no header
     *  space. Independent of the Translation section's hidden state: the
     *  toggle presents the whole result over the game, not that card, so
     *  with the card hidden it is offered from the Original header
     *  ([syncResultActions]). */
    fun setShowOnScreenAvailable(available: Boolean) {
        showOnScreenOffered = available
        syncResultActions()
    }

    /** The show-on-screen toggle's state: while the boxes are ON the inline
     *  pill fills with the accent (and its ⋯ menu row's icon turns accent). */
    fun setShowOnScreenToggled(on: Boolean) {
        showOnScreen.active = on
        showOnScreenInSource.active = on
    }

    /** Flip a section's hidden pref, re-apply its visibility, and notify the host
     *  so it can re-layout. The section's eye, which stays in its header
     *  while the card is hidden, routes through these both ways. */
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

    /** Bind a Ready result: source + attribution row + target + labels +
     *  the persisted section visibilities. [canReOcr]: whether a switch of
     *  OCR tool can act on this result (the host still holds the capture);
     *  the panel's default is "a cached screenshot exists". */
    fun bindResult(result: TranslationResult, canReOcr: Boolean = result.screenshotPath != null) {
        bindSource(result.segments)
        bindSourceOcr(result.ocrProvenance, canReOcr = canReOcr)
        bindTargetReady(result)
        updateLabels()
        applyOriginalVisibility()
        applyTranslationVisibility()
    }

    /** Bind a Translating placeholder (drag sentence / edit commit / a
     *  capture whose OCR finished): the source is final now, the target
     *  shows "Translating…". A capture placeholder carries OCR provenance —
     *  "Scanned by …" shows with the source before the translation lands —
     *  but its gear stays hidden: re-OCR can't act on a transient state, so
     *  a gear here would be a dead control. */
    fun bindTranslating(
        segments: List<com.playtranslate.model.TextSegment>,
        ocrProvenance: OcrProvenance? = null,
    ) {
        bindSource(segments)
        bindSourceOcr(ocrProvenance, canReOcr = false)
        setTargetTranslatingPlaceholder()
        updateLabels()
        applyOriginalVisibility()
        applyTranslationVisibility()
    }

    /** The results page's fit to its scroll viewport, [viewportHeightPx]
     *  tall. Stacked, each text tries to fit within half of it. [paired]
     *  (Original and Translation side by side), each text has the viewport's
     *  height to itself, so each is fitted to what its own header and card
     *  chrome leave of it: the pair fills the viewport at most. The source
     *  is fitted to its PLAIN text either way: ruby lands before the Ready
     *  fit and the scroll absorbs its extra height, so measuring ruby would
     *  shrink the source font the moment the translation lands (see
     *  [fitText]). */
    fun fitToViewport(viewportHeightPx: Int, paired: Boolean) {
        if (!paired) {
            val half = viewportHeightPx / 2
            fitText(translationTargetPx = half, sourceTargetPx = half, sourceMeasuresRuby = false)
            return
        }
        fitText(
            translationTargetPx =
                (viewportHeightPx - targetRow.height - targetContentOverhead()).coerceAtLeast(1),
            sourceTargetPx =
                (viewportHeightPx - sourceRow.height - sourceContentOverhead()).coerceAtLeast(1),
            sourceMeasuresRuby = false,
        )
    }

    // ── The compact page ─────────────────────────────────────────────────

    /**
     * The compact look, for a host with a bar of its own to hold the
     * headers' actions (the detached window): the two language headers are
     * gone and the Original and the Translation are plain text on the
     * surface, their cards' frames and inner side padding dropped. Nothing
     * else changes: the same views, the same models, the same eyes. Applied
     * once, when the page is built.
     */
    fun applyCompactLook() {
        compact = true
        // The host lists the eyes by name before there is any result, so
        // their names and where Text size lives are read now, as the first
        // result would read them.
        applyOriginalVisibility()
        applyTranslationVisibility()
        sourceRow.visibility = View.GONE
        targetRow.visibility = View.GONE
        val v = (COMPACT_TEXT_V_PAD_DP * ctx.resources.displayMetrics.density).toInt()
        for ((card, content) in listOf(cardOriginal to originalContent, cardTranslation to translationContent)) {
            card.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
            card.strokeWidth = 0
            card.radius = 0f
            content.setPadding(0, v, 0, v)
        }
    }

    /** Whether [applyCompactLook] was applied. */
    private var compact = false

    /**
     * The headers' actions for a host that lists them itself, the compact
     * page's with its headers gone ([applyCompactLook]): the same models the
     * headers hold, so each runs, reads and comes and goes exactly as its
     * button would. [speakAction] is Read aloud; [textActions] are the ones
     * about how the texts show, in the order a list gives them: the ruby
     * switch where the game language has one (read now), the two eyes, Text
     * size. An action that is not [HeaderAction.available] there has nothing
     * to act on for now (the Original hidden, both texts hidden); the host
     * says so in its own way.
     */
    val speakAction: HeaderAction get() = speak
    fun textActions(): List<HeaderAction> {
        val hasRuby = SourceLanguageProfiles[prefs.sourceLangId].hintTextKind != HintTextKind.NONE
        return listOfNotNull(furigana.takeIf { hasRuby }, sourceEye, targetEye, textSize)
    }

    /** The two header rows, which own those models: what follows the
     *  actions' changes listens here. */
    fun actionRows(): List<FoldingActionRow> = listOf(sourceRow, targetRow)

    /** The language names the two headers show, read now. */
    val sourceLanguageName: CharSequence get() = sourceLangLocalizedDisplayName()
    val targetLanguageName: CharSequence get() = targetLangDisplayName()

    /**
     * The compact page's fit to its scroll viewport, [viewportHeightPx]
     * tall: stacked, the texts on show share it, an equal part each of what
     * their own chrome (padding, the "Translated by" and "Scanned by" rows)
     * leaves; [paired], side by side, each has the viewport's height less
     * its own chrome to itself. Either way the two fit in view together down
     * to the smallest text size. The source is measured with its ruby, as
     * the capture sheet does: here the point is that it fits.
     */
    fun fitToRoom(viewportHeightPx: Int, paired: Boolean) {
        val showTarget = !prefs.hideTranslationSection
        val showSource = !prefs.hideOriginalSection
        val shown = (if (showTarget) 1 else 0) + (if (showSource) 1 else 0)
        if (shown == 0) return
        if (paired) {
            fitText(
                translationTargetPx = (viewportHeightPx - targetContentOverhead()).coerceAtLeast(1),
                sourceTargetPx = (viewportHeightPx - sourceContentOverhead()).coerceAtLeast(1),
            )
            return
        }
        val chrome = (if (showTarget) targetContentOverhead() else 0) +
            (if (showSource) sourceContentOverhead() else 0)
        val each = ((viewportHeightPx - chrome) / shown).coerceAtLeast(1)
        fitText(translationTargetPx = each, sourceTargetPx = each)
    }

    // ── Buttons + text fitting ───────────────────────────────────────────

    /** Wire the header actions: Edit, the eyes, the furigana toggle and
     *  speak. [onEdit] is what the source's Edit action does — the surface
     *  decides what editing means (an Activity overlay in-app, an in-place
     *  IME over the game). Copying lives on a long-press of each text; Anki
     *  is each surface's floating pill ([PillAnkiButton]), not a header
     *  action. */
    fun setupSectionButtons(onEdit: () -> Unit) {
        // TODO(device): tvOriginal is a ClickableTextView whose onTouchEvent
        // routes through a GestureDetector and always consumes the event. Long-
        // press copy relies on View.onTouchEvent's own long-press timer firing
        // via super.onTouchEvent; verify on-device that the word-tap path doesn't
        // suppress this long-click. Do NOT remove the word-tap handling.
        tvOriginal.setOnLongClickListener {
            copyToClipboard(tvOriginal.text?.toString() ?: return@setOnLongClickListener true); true
        }
        tvTranslation.setOnLongClickListener {
            copyToClipboard(tvTranslation.text?.toString() ?: return@setOnLongClickListener true); true
        }
        edit.onClick = { onEdit() }
        targetEye.onClick = { toggleTranslationHidden() }
        sourceEye.onClick = { toggleOriginalHidden() }
        furigana.onClick = {
            prefs.showFuriganaInline = !prefs.showFuriganaInline
            applyFurigana()
        }
        val s = OriginalSpeaker(scope, alertTarget, onSpeakingChanged = { speak.active = it }) {
            val text = displayedSourceText()
            if (text.isBlank()) null
            else OriginalSpeaker.Request(text, prefs.sourceLangId)
        }
        speaker = s
        speak.onClick = { s.toggle() }
    }

    /** A new capture's original [text] has arrived ([captureOriginalToRead]):
     *  reads it aloud by itself when the user asked for that
     *  ([Prefs.autoPlayOriginal]) and the section's Read aloud is on offer.
     *  A hidden Original offers none ([applyOriginalVisibility]), so nothing
     *  would show the read or stop it. The text is given, not read from the
     *  view: the in-app page binds a result after the state that carries
     *  it. Spoken in the language the Read aloud action speaks. */
    fun autoPlayOriginal(text: String) {
        if (!prefs.autoPlayOriginal || prefs.hideOriginalSection || text.isBlank()) return
        speaker?.play(OriginalSpeaker.Request(text, prefs.sourceLangId))
    }

    /** Side-by-side / results-page: fit each text to its own target height with a
     *  CONTINUOUS size (so the load animation + resize scale smoothly instead of
     *  in 1sp steps). */
    fun fitText(translationTargetPx: Int, sourceTargetPx: Int, sourceMeasuresRuby: Boolean = true) {
        tvTranslation.setTextSize(TypedValue.COMPLEX_UNIT_SP, fitSize(tvTranslation, translationTargetPx))
        // The results page lets ruby overflow into its scroll, so it fits the source to
        // the PLAIN text (sourceMeasuresRuby = false): furigana lands before the Ready
        // fit, so measuring the taller ruby text would shrink the source font exactly
        // when the translation appears — which it must not visibly do. The panel pins
        // card heights and has no scroll, so it measures ruby (default) to size the card
        // tall enough for the reading.
        val sourceMeasure: CharSequence =
            if (sourceMeasuresRuby) tvOriginal.text else tvOriginal.text.toString()
        tvOriginal.setTextSize(TypedValue.COMPLEX_UNIT_SP, fitSize(tvOriginal, sourceTargetPx, sourceMeasure))
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
    fun sourceTextHeightAtMax(): Int = textHeightAt(tvOriginal, fitMaxSp)
    fun targetTextHeightAtMax(): Int = textHeightAt(tvTranslation, fitMaxSp)

    fun sourceHeaderHeight(): Int = sourceRow.height
    fun targetHeaderHeight(): Int = targetRow.height

    // The card's non-text vertical space splits into two parts the panel sums:
    //  • contentOverhead — the content holder's height minus the MAIN text:
    //    padding + (for the target) the "Translated by" note row. Measured LIVE
    //    (the holder wraps its content even when the card is pinned to a fill
    //    height), so the note is always counted even if it laid out a frame late.
    //  • cardInset — the card frame's own inset around its content holder, which
    //    is the card's padding. Read as that, not as card height minus content
    //    height: a stretched card (the pair's shared height, the panel's fill) is
    //    taller than its content by more than its frame.
    fun sourceContentOverhead(): Int {
        val live = originalContent.height - tvOriginal.height
        // Same pre-measure guard as [targetContentOverhead]: the "Scanned by…" row is
        // set VISIBLE on bind but contributes 0 to originalContent's height until the
        // next layout pass, so a fit run synchronously on bind would under-count the
        // overhead and the source text would visibly resize a frame later. Measure the
        // row (a container — the gear can exceed the text height) so the first fit
        // already accounts for it. (Furigana is not a factor: it grows tvOriginal,
        // which is subtracted out here.)
        if (sourceNoteRow.visibility == View.VISIBLE && sourceNoteRow.height == 0) {
            val w = (originalContent.width - originalContent.paddingLeft - originalContent.paddingRight)
                .coerceAtLeast(0)
            sourceNoteRow.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            // Footprint = measuredHeight + top + bottom margins. The row carries a
            // NEGATIVE bottom margin (its vertical padding is purely tap area, not
            // layout height), so both margins must be counted or this over-estimates
            // the source overhead and shrinks the source text.
            val lp = sourceNoteRow.layoutParams as? android.view.ViewGroup.MarginLayoutParams
            val vMargins = (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)
            return live + sourceNoteRow.measuredHeight + vMargins
        }
        return live
    }
    /**
     * Target content overhead = padding + the "Translated by…" note row. The row is
     * set VISIBLE only when a result binds, and contributes 0 to the holder height
     * until the next layout pass — so a fit run synchronously on bind (before that
     * pass) under-counts the overhead, sizes the translation too large, and it then
     * visibly resizes a frame later once the row measures (the post-translation
     * "flash"). While the row is shown with text but not yet laid out, measure it
     * here so the very first fit already counts it and the translation lands at its
     * final size. The Translating placeholder leaves the row GONE, so this never
     * touches it — only the real translation, which is the one that flashed.
     */
    fun targetContentOverhead(): Int {
        val live = translationContent.height - tvTranslation.height
        if (tvTranslationNote.visibility == View.VISIBLE && tvTranslationNote.height == 0 &&
            tvTranslationNote.text.isNotEmpty()
        ) {
            val w = (translationContent.width - translationContent.paddingLeft - translationContent.paddingRight)
                .coerceAtLeast(0)
            tvTranslationNote.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val topMargin = (tvTranslationNote.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.topMargin ?: 0
            return live + tvTranslationNote.measuredHeight + topMargin
        }
        return live
    }
    fun sourceCardInset(): Int = cardOriginal.paddingTop + cardOriginal.paddingBottom
    fun targetCardInset(): Int = cardTranslation.paddingTop + cardTranslation.paddingBottom

    /** Whether each section's card is on screen, which is what "shown"
     *  means everywhere (a hidden section is its header alone). */
    val isOriginalShown: Boolean get() = cardOriginal.visibility != View.GONE
    val isTranslationShown: Boolean get() = cardTranslation.visibility != View.GONE

    /** Set each section card's MINIMUM height, so its background fills the column
     *  (sized by the view, not the text). The card still wraps content TALLER than
     *  this — so an imperfect text fit, or text already at min size, grows the card
     *  (and the scroll takes over) instead of clipping the bottom line / note row. */
    fun setCardMinHeights(sourcePx: Int, targetPx: Int) {
        cardOriginal.minimumHeight = sourcePx
        cardTranslation.minimumHeight = targetPx
    }

    /** Tint both text cards' fill to [alpha] (0–1) of ptCard — used by the overlay
     *  to let the frosted backdrop show faintly through; the results page leaves
     *  them opaque. */
    fun setCardFillAlpha(alpha: Float) {
        val c = sectionCardFill(ctx, alpha)
        cardOriginal.setCardBackgroundColor(c)
        cardTranslation.setCardBackgroundColor(c)
    }

    /** Largest float size in [[fitMinSp], [fitMaxSp]] whose text fits [targetPx]
     *  (binary search → continuous, not 1sp steps). Text that won't fit even at
     *  the min renders at the min and grows its card — the min is a floor the
     *  user chose, not a licence to clip. */
    private fun fitSize(tv: TextView, targetPx: Int, measureText: CharSequence = tv.text): Float {
        val minSp = fitMinSp
        val maxSp = fitMaxSp
        if (tv.width <= 0) return maxSp
        if (textHeightAt(tv, maxSp, measureText) <= targetPx) return maxSp
        if (textHeightAt(tv, minSp, measureText) > targetPx) return minSp
        var lo = minSp
        var hi = maxSp
        repeat(BISECT_STEPS) {
            val mid = (lo + hi) / 2f
            if (textHeightAt(tv, mid, measureText) <= targetPx) lo = mid else hi = mid
        }
        return lo
    }

    /** Height [tv]'s text would occupy at [sizeSp] and its current width, without
     *  touching the live view — a paint copy + StaticLayout configured to match
     *  the live TextView exactly, so measure and render agree (a mismatch makes the
     *  card height track its content instead of the panel → wobble). */
    private fun textHeightAt(tv: TextView, sizeSp: Float, measureText: CharSequence = tv.text): Int {
        val widthPx = (tv.width - tv.compoundPaddingLeft - tv.compoundPaddingRight)
            .takeIf { it > 0 } ?: return 0
        val paint = TextPaint(tv.paint)
        paint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, sizeSp, ctx.resources.displayMetrics,
        )
        val layoutHeight = StaticLayout.Builder
            .obtain(measureText, 0, measureText.length, paint, widthPx)
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
        speaker?.release()
        speaker = null
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

    // ── Fit bounds ───────────────────────────────────────────────────────
    // Read from [prefs] at fit time, not cached: the text-size popover writes
    // the pair mid-drag and expects the very next fit to use it. Prefs settles
    // a crossed/out-of-range pair, so lo <= hi always holds for the bisect.

    private val fitMinSp: Float get() = prefs.resultsFontMinSp.toFloat()
    private val fitMaxSp: Float get() = prefs.resultsFontMaxSp.toFloat()

    private companion object {
        /** Binary-search iterations for the continuous text fit. Over the widest
         *  selectable span (16sp) that resolves to ~0.06sp — still continuous
         *  enough for the panel's height animation to scale text smoothly. */
        const val BISECT_STEPS = 8

        /** The room above and below each text of the compact page. */
        const val COMPACT_TEXT_V_PAD_DP = 6f
    }
}
