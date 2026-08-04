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
    private val btnShowOnScreen: TextView = root.findViewById(R.id.btnShowOnScreen)
    private val btnEditOriginal: ImageButton = root.findViewById(R.id.btnEditOriginal)
    private val btnSpeakOriginal: ImageButton = root.findViewById(R.id.btnSpeakOriginal)
    private val btnToggleTranslation: ImageButton = root.findViewById(R.id.btnToggleTranslation)
    private val btnToggleOriginal: ImageButton = root.findViewById(R.id.btnToggleOriginal)
    private val btnToggleFurigana: ImageButton = root.findViewById(R.id.btnToggleFurigana)
    private val btnFontSize: ImageButton = root.findViewById(R.id.btnFontSize)
    // "Scanned by <engine>" + gear, under the source text (mirror of tvTranslationNote).
    private val sourceNoteRow: View = root.findViewById(R.id.sourceNoteRow)
    private val tvSourceNote: TextView = root.findViewById(R.id.tvSourceNote)
    private val btnSourceOcr: ImageView = root.findViewById(R.id.btnSourceOcr)

    init {
        // The layouts declare app:tint on these icons, but that attribute is
        // applied by AppCompat's layout inflater — which the in-app results
        // screen has and the capture overlay does not. Tint explicitly so both
        // surfaces render the results-screen style (muted icons, hint-toned
        // gear). The speak button re-tints itself per state (accent while
        // speaking, muted when idle) on top of this base.
        val muted = ColorStateList.valueOf(ctx.themeColor(R.attr.ptTextMuted))
        for (btn in listOf(
            btnCopyOriginal, btnCopyTranslation, btnEditOriginal, btnSpeakOriginal,
            btnToggleTranslation, btnToggleOriginal, btnToggleFurigana, btnFontSize,
        )) {
            btn.imageTintList = muted
        }
        btnSourceOcr.imageTintList =
            ColorStateList.valueOf(ctx.themeColor(R.attr.ptTextHint))
    }

    private var speakButton: OriginalSpeakButton? = null

    /** True once [setupSectionButtons] repurposed the copy buttons to Anki with
     *  a one-tap long-press — the controller then maps hold-A to it. */
    private var ankiOneTapOnCopy = false

    /** The header action buttons the controller cursor can reach, in render
     *  order, filtered to what's currently on screen. Deliberately excludes the
     *  language labels and the OCR row — both open picker windows the
     *  controller can't drive (and the language path dismisses the sheet). */
    fun navigableActions(): List<NavAction> = buildList {
        for (v in listOf(
            btnToggleFurigana, btnSpeakOriginal, btnEditOriginal, btnCopyOriginal,
            btnToggleOriginal,
            btnFontSize, btnCopyTranslation, btnShowOnScreen, btnToggleTranslation,
        )) {
            if (!v.isShown || !v.isEnabled) continue
            val hold = ankiOneTapOnCopy && (v === btnCopyOriginal || v === btnCopyTranslation)
            add(NavAction(v, holdActivates = hold))
        }
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

    /** Invoked when the user taps the text-size button in the target header. Each
     *  surface shows [FontSizeRangePopover] in its own host view (the popover must
     *  be a CHILD of the surface, not a new window — see the class doc there), then
     *  re-fits on change. Null (the default) → the button stays GONE, so a surface
     *  that can't host the popover never renders a dead control. */
    var onChooseFontSize: (() -> Unit)? = null
        set(value) {
            field = value
            btnFontSize.visibility = if (value == null) View.GONE else View.VISIBLE
        }

    /** The text-size button, for surfaces to anchor their popover on. */
    val fontSizeAnchor: View get() = btnFontSize

    init {
        sourceNoteRow.setOnClickListener { onChooseOcr?.invoke() }
        labelOriginal.setOnClickListener { onChooseLanguage?.invoke(true) }
        labelTranslation.setOnClickListener { onChooseLanguage?.invoke(false) }
        btnFontSize.setOnClickListener { onChooseFontSize?.invoke() }
        // Tint the gear in CODE, not via XML app:tint: the over-game overlay inflates
        // these views with a plain (non-AppCompat) LayoutInflater, which silently drops
        // app:tint, so the white ic_settings would render white there while the in-app
        // (AppCompat) surface tints it. Setting imageTintList works on a plain ImageView
        // too, so both surfaces match. (Same reason the Anki button is tinted in code.)
        btnSourceOcr.imageTintList = ColorStateList.valueOf(ctx.themeColor(R.attr.ptTextHint))
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
        // GONE, not INVISIBLE: the header's weighted Space right-aligns this
        // button group, so collapsing the add-to-Anki slot slides the text-size
        // and show-on-screen buttons RIGHT into the freed width instead of
        // leaving a hole. The eye stays put either way — it's the last child, so
        // the expanding Space keeps it pinned to the end. Direction-agnostic:
        // the reflow mirrors itself under RTL.
        btnCopyTranslation.visibility = if (hidden) View.GONE else View.VISIBLE
        // btnFontSize deliberately survives the hide: the size range it edits
        // governs the SOURCE text too, so it stays usable with this card closed.
        btnToggleTranslation.setImageResource(if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
    }

    /** Wire the "show on screen" toggle into the target header. The over-game
     *  capture panel paints its in-window boxes; the in-app results page
     *  (dual-screen) paints a standalone game-display window, or flips the
     *  hide-overlays-during-auto setting while live. Each host gates
     *  visibility via [setShowOnScreenAvailable]. */
    fun setShowOnScreenAction(onClick: () -> Unit) {
        btnShowOnScreen.setOnClickListener { onClick() }
    }

    /** Whether the show-on-screen toggle currently has something to show
     *  (overlay boxes exist for the bound result). GONE when not, so surfaces
     *  that never enable it lose no header space. Deliberately independent of
     *  the section's hidden state — the toggle presents the whole result over
     *  the game, not this card, and hiding the card while reading the boxes in
     *  place is a legitimate combination. ACCEPTED gap (2026-07-15): in
     *  SIDE-BY-SIDE mode, hiding the translation collapses the whole column —
     *  header and this button included — so that combo has no manual switch;
     *  the boxes keep whatever state the toggle last set. */
    fun setShowOnScreenAvailable(available: Boolean) {
        btnShowOnScreen.visibility = if (available) View.VISIBLE else View.GONE
    }

    /** The show-on-screen toggle's visual state: a filled accent pill with
     *  on-accent text while the boxes are ON; the drawable's stock look
     *  (card-colored fill, muted text) while OFF. Tinting recolors the
     *  drawable's solid fill, so one drawable serves both states. */
    fun setShowOnScreenToggled(on: Boolean) {
        if (on) {
            btnShowOnScreen.setTextColor(ctx.themeColor(R.attr.ptAccentOn))
            btnShowOnScreen.backgroundTintList =
                ColorStateList.valueOf(ctx.themeColor(R.attr.ptAccent))
        } else {
            btnShowOnScreen.setTextColor(ctx.themeColor(R.attr.ptTextMuted))
            btnShowOnScreen.backgroundTintList = null
        }
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
        bindSource(result.segments)
        bindSourceOcr(result.ocrProvenance, canReOcr = result.screenshotPath != null)
        bindTargetReady(result)
        updateLabels()
        applyOriginalVisibility()
        applyTranslationVisibility()
    }

    // ── Buttons + text fitting ───────────────────────────────────────────

    /** Wire copy / show-hide / furigana toggle / speak. [onEdit] is invoked by the
     *  source Edit button — the surface decides what editing means (an Activity
     *  overlay in-app, an in-place IME over the game). */
    fun setupSectionButtons(
        onEdit: () -> Unit,
        onAddToAnki: (() -> Unit)? = null,
        onAnkiOneTap: (() -> Unit)? = null,
    ) {
        if (onAddToAnki != null) {
            // Results page (and later the capture overlay): the copy button becomes
            // "add to Anki"; copy moves to a long-press on the text itself. The
            // muted tint from init survives setImageResource.
            for (btn in listOf(btnCopyOriginal, btnCopyTranslation)) {
                btn.setImageResource(R.drawable.ic_card_stack_add)
                btn.contentDescription = ctx.getString(R.string.cd_add_to_anki)
                btn.setOnClickListener { onAddToAnki() }
                if (onAnkiOneTap != null) {
                    btn.setOnLongClickListener { onAnkiOneTap(); true }
                }
            }
            ankiOneTapOnCopy = onAnkiOneTap != null
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
        } else {
            btnCopyOriginal.setOnClickListener {
                copyToClipboard(tvOriginal.text?.toString() ?: return@setOnClickListener)
            }
            btnCopyTranslation.setOnClickListener {
                copyToClipboard(tvTranslation.text?.toString() ?: return@setOnClickListener)
            }
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

    fun sourceHeaderHeight(): Int = (labelOriginal.parent as? View)?.height ?: 0
    fun targetHeaderHeight(): Int = (labelTranslation.parent as? View)?.height ?: 0

    // The card's non-text vertical space splits into two parts the panel sums:
    //  • contentOverhead — the content holder's height minus the MAIN text:
    //    padding + (for the target) the "Translated by" note row. Measured LIVE
    //    (the holder wraps its content even when the card is pinned to a fill
    //    height), so the note is always counted even if it laid out a frame late.
    //  • cardInset — the card frame's own inset (rounded-corner overlap + stroke).
    //    Constant; the panel measures it ONCE while the card is still wrap.
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

    /** Tint both text cards' fill to [alpha] (0–1) of ptCard — used by the overlay
     *  to let the frosted backdrop show faintly through; the results page leaves
     *  them opaque. */
    fun setCardFillAlpha(alpha: Float) {
        val c = withAlpha(ctx.themeColor(R.attr.ptCard), alpha)
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
    }
}
