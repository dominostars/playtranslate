package com.playtranslate.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import com.playtranslate.R
import com.playtranslate.dictionary.Deinflector
import com.playtranslate.language.InflectedForm
import com.playtranslate.model.ReadingRow
import com.playtranslate.themeColor
import kotlinx.coroutines.Job

/**
 * A translation-result word entry: a headword row (word · reading · read-aloud
 * · add-to-Anki · chevron) above the shared [WordDefinitionsView] body. The
 * whole cell is the tap target (opens Word Detail); the speak and Anki buttons
 * are nested actions whose taps don't fall through to the cell.
 *
 * The add-to-Anki button is **always plain** — it never reflects in-deck
 * state. Deck membership surfaces as the meta-row pill (via
 * [WordDefinitionData.ankiDecks]) instead.
 */
class WordResultCell @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).toInt()

    private val mutedColor = context.themeColor(R.attr.ptTextMuted)
    private val hintColor = context.themeColor(R.attr.ptTextHint)
    private val textColor = context.themeColor(R.attr.ptText)
    private val accentColor = context.themeColor(R.attr.ptAccent)

    private val wordView: TextView
    private val readingView: TextView
    private val readingsFlow: FlowLayout
    private val inflectionView: TextView
    private val speakIcon: ImageView
    private val speakSpinner: ProgressBar
    private val speakButton: FrameLayout
    private val ankiButton: FrameLayout
    private val definitionsView = WordDefinitionsView(context)

    /** Styled (WebView) renderer for the imported Yomitan groups, built only
     *  for hosts that opt in through [bind]'s `styledImported` — today the
     *  word detail page's Words section, a static handful of cells under a
     *  block that renders styled, where the flat tier's tag runs read as a
     *  bug. The LIST surfaces (search results, the words panel) stay flat on
     *  purpose: they recycle, and a WebView per recycled row is not
     *  affordable. Null whenever the styled path can't run. */
    private var styledView: YomitanDefinitionsView? = null
    private val styledContainer = FrameLayout(context).apply { isGone = true }
    /** True while [styledView] holds the imported groups, so the flat body
     *  must not render them a second time. */
    private var styledActive = false

    /** Re-entrancy guard / spinner driver for this cell's speak action,
     *  owned by whoever launches the speak coroutine (the fragment). */
    var speakJob: Job? = null

    private var boundData: WordDefinitionData? = null
    private var boundScale: Float = 1f
    /** True when the title holds a single reading that may still need to drop
     *  below the title (decided in [onMeasure] from the available width); false
     *  for the multi-reading and no-reading cases, which are fixed in [bind]. */
    private var canInlineSingleReading = false

    init {
        orientation = VERTICAL
        // Right padding is only 4dp so the action row's rightmost slot lands on
        // the same column as each section header's rightmost button (which sits
        // 4dp in from the card edge). The body restores its 16dp inset below.
        setPadding(dp(16f), dp(14f), dp(4f), dp(14f))
        background = themedDrawable(android.R.attr.selectableItemBackground)
        isClickable = true
        isFocusable = true

        val headRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Word + reading, baseline-aligned (LinearLayout default).
        val titleGroup = LinearLayout(context).apply { orientation = HORIZONTAL }
        wordView = TextView(context).apply {
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = -0.02f
        }
        readingView = TextView(context).apply { setTextColor(mutedColor) }
        titleGroup.addView(wordView, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        titleGroup.addView(
            readingView,
            LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(10f) },
        )
        headRow.addView(titleGroup, LayoutParams(0, WRAP_CONTENT, 1f))

        // Action cluster: fixed 36dp-wide slots with 16dp gaps, right-aligned
        // and ending at the cell's 4dp right padding — matching the
        // Translation / Source section header buttons (36dp, 16dp gaps, 4dp in)
        // so the three rows share the same horizontal columns.
        speakIcon = iconView(R.drawable.ic_lens_speak, mutedColor)
        speakSpinner = ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(accentColor)
            isVisible = false
        }
        speakButton = actionSlot(clickable = true, marginStartDp = 0f).apply {
            addView(speakIcon, centerParams(22f))
            addView(speakSpinner, centerParams(20f))
        }
        headRow.addView(speakButton)

        // Add-to-Anki button — always plain.
        ankiButton = actionSlot(clickable = true, marginStartDp = 16f).apply {
            addView(iconView(R.drawable.ic_card_stack_add, mutedColor), centerParams(22f))
        }
        headRow.addView(ankiButton)

        // Chevron: a non-interactive "opens detail" affordance, in its own slot
        // so it aligns with each section's rightmost header button.
        val chevron = actionSlot(clickable = false, marginStartDp = 16f).apply {
            addView(
                iconView(R.drawable.ic_chevron_right, hintColor).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                centerParams(22f),
            )
        }
        headRow.addView(chevron)

        addView(headRow, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Full-width reading flow under the title: used when there's more than
        // one reading, or a single reading too wide to sit inline (decided in
        // onMeasure). GONE by default; populated in bind().
        readingsFlow = FlowLayout(context).apply {
            lineSpacingPx = dp(6f)
            isGone = true
        }
        addView(
            readingsFlow,
            LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(2f)
                marginEnd = dp(12f)
            },
        )

        // Conjugation line: the as-found surface + the grammar it expresses
        // (e.g. 言わせて · Causative, Te-form), under the dictionary headword.
        // GONE for uninflected words / non-JA sources; populated in bind().
        inflectionView = TextView(context).apply {
            setTextColor(mutedColor)
            isGone = true
        }
        addView(
            inflectionView,
            LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(2f)
                marginEnd = dp(12f)
            },
        )

        // Body keeps a 16dp right inset (4dp cell padding + 12dp) so its text
        // wraps in line with the Translation / Source cards, while the action
        // row above reaches the 4dp button column.
        addView(
            styledContainer,
            LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { marginEnd = dp(12f) },
        )
        addView(
            definitionsView,
            LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { marginEnd = dp(12f) },
        )
    }

    /**
     * Bind [data] at [scale]. [onCellTap] opens Word Detail; [onSpeak] /
     * [onAnki] are the (propagation-stopping) action handlers.
     *
     * [styledImported] opts this cell into the styled renderer for
     * [WordDefinitionData.importedGroups] (see [styledView]); it needs
     * [WordDefinitionData.styled] prefetched by the host. Default off — a
     * recycling list must not mint WebViews. Hosts that pass true MUST call
     * [releaseStyled] when the page goes away.
     */
    fun bind(
        data: WordDefinitionData,
        scale: Float,
        inflectedForms: List<InflectedForm>,
        onCellTap: () -> Unit,
        onSpeak: () -> Unit,
        onAnki: () -> Unit,
        styledImported: Boolean = false,
    ) {
        boundData = data
        boundScale = scale
        wordView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 27f * scale)
        // Readings: every reading of the entry in the shared common-use order
        // (orderedReadingRows), the occurrence bolded. A single reading sits
        // inline beside the title as before; more than one — or a single reading
        // too wide to fit (decided in onMeasure) — drops into the full-width
        // [readingsFlow] below the title.
        val readings = data.readingRows.ifEmpty {
            // Non-JA / pre-readingRows fallback: the lone reading, or the kana
            // headword itself when it's pure kana with pitch (the contour needs a
            // string to sit over and must never cover kanji).
            val inline = data.reading?.takeIf { it.isNotEmpty() }
                ?: data.word.takeIf { data.pitch.isNotEmpty() && data.word.all(Deinflector::isKana) }
            if (inline != null) listOf(ReadingRow(data.word, inline, data.pitch, false))
            else emptyList()
        }
        // Kana-only: the (single) reading just repeats the kana title, so draw the
        // pitch accent on the TITLE itself (with the [n] numbers) rather than show
        // a duplicate reading line.
        val kanaOnly = readings.size == 1 && readings[0].reading == data.word
        if (kanaOnly && readings[0].pitch.isNotEmpty()) {
            wordView.text = buildPitchAnnotatedReading(data.word, readings[0].pitch)
            wordView.setPadding(0, dp(8f * scale), 0, 0) // overline headroom
        } else {
            wordView.text = data.word
            wordView.setPadding(0, 0, 0, 0)
        }
        canInlineSingleReading = readings.size == 1 && !kanaOnly
        readingsFlow.removeAllViews()
        readings.forEach { row ->
            readingsFlow.addView(
                TextView(context).also {
                    styleReading(it, row, scale, bold = readings.size > 1 && row.bolded)
                },
                LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(12f) },
            )
        }
        when {
            kanaOnly -> {
                // Accent rides on the title above; no separate reading line.
                readingView.isGone = true
                readingsFlow.isGone = true
            }
            readings.isEmpty() -> {
                readingView.isGone = true
                readingsFlow.isGone = true
            }
            readings.size == 1 -> {
                // Inline beside the title; onMeasure drops it below if it won't fit.
                styleReading(readingView, readings[0], scale, bold = false)
                readingView.isGone = false
                readingsFlow.isGone = true
            }
            else -> {
                readingView.isGone = true
                readingsFlow.isGone = false
            }
        }
        // Conjugation lines: one per distinct form this lemma appeared as,
        // "surface · Tag, Tag", localized. Hidden when there's nothing to report.
        if (inflectedForms.isEmpty()) {
            inflectionView.isGone = true
        } else {
            inflectionView.isGone = false
            inflectionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * scale)
            // Cap the lines so a lemma seen in many forms (long OCR input) can't
            // expand the row off-screen; the rest collapse into a "+N more" line.
            val (shown, overflow) = capInflectionForms(inflectedForms)
            val lines = shown.map { form ->
                form.surface + " · " + form.tags.joinToString(", ") { context.getString(it.labelRes) }
            }
            inflectionView.text = (
                if (overflow > 0) lines + context.getString(R.string.inflection_more, overflow)
                else lines
            ).joinToString("\n")
        }
        // Styled body first: when it takes the imported groups, the flat body
        // renders only what's left (the pack senses), exactly as the word
        // detail page splits its imported block from its native rows.
        styledActive = styledImported && bindStyledImported(data, scale)
        bindFlatBody(if (styledActive) data.flatRemainder() else data, scale)

        setOnClickListener { onCellTap() }
        speakButton.setOnClickListener { onSpeak() }
        ankiButton.setOnClickListener { onAnki() }
    }

    /**
     * Bind the native body and recompute whether it has anything to show. The
     * styled path can legitimately leave it empty — an imported-only entry
     * has no pack senses once the groups move into the WebView — and an empty
     * view would contribute nothing but its top margin.
     *
     * EVERY flat bind goes through here. A raw `definitionsView.bind` call
     * repopulates the view without touching visibility, so a later bind could
     * fill a body an earlier one had hidden and show nothing: that is exactly
     * how the renderer-death fallback silently rendered a blank cell for
     * imported-only entries (both Codex passes, 2026-09-08).
     */
    private fun bindFlatBody(data: WordDefinitionData, scale: Float) {
        definitionsView.bind(data, label = null, scale = scale)
        definitionsView.isGone = definitionsView.childCount == 0
        (definitionsView.layoutParams as LayoutParams).topMargin = dp(10f * scale)
        definitionsView.requestLayout()
    }

    /** What the flat body renders while [styledView] is up: the pack's senses
     *  alone. The imported rows move into the styled document, and so does the
     *  meta row — the chips belong ABOVE the definitions, as they are on every
     *  other surface, and the styled document draws them at its top
     *  ([styledMetaChips]). Leaving them on the flat body would strand the
     *  Common pill underneath the styled block. */
    private fun WordDefinitionData.flatRemainder(): WordDefinitionData =
        copy(
            senses = senses.filterNot { it.imported },
            isCommon = false,
            freqScore = 0,
            frequencies = emptyList(),
            ankiDecks = emptyList(),
        )

    /**
     * Hand the imported groups to a fresh styled renderer. False — and the
     * flat body keeps them, unchanged — when there is no structured payload
     * (styling toggle off, nothing retained at import) or no WebView provider
     * on the device. The document carries the GROUPS only: senses stay empty
     * so the pack's rows keep rendering natively below, the same split the
     * word detail page makes for its own imported block.
     */
    private fun bindStyledImported(data: WordDefinitionData, scale: Float): Boolean {
        releaseStyled()
        val styled = data.styled
        if (styled == null || styled.structured.isEmpty() || data.importedGroups.isEmpty()) return false
        val v = YomitanDefinitionsView(context, styledTokens(scale))
        if (!v.isUsable()) return false
        v.setPadding(0, dp(4f * scale), 0, dp(2f * scale))
        // The whole cell is one tap target (it opens the word's detail page),
        // so the page must refuse the touch stream instead of swallowing taps
        // that land on the definition text. Links inside are deliberately
        // sacrificed to the row's click, the same trade the sentence sheet's
        // word rows make.
        v.passThroughTouches = true
        v.onContentHeight = { h -> applyStyledHeight(h, scale) }
        v.onRendererGone = {
            // destroy() already ran inside the view; rebuild the flat body
            // WITH its imported rows so the cell degrades to what every other
            // host shows rather than to nothing.
            styledView = null
            styledActive = false
            styledContainer.removeAllViews()
            styledContainer.foreground = null
            styledContainer.isGone = true
            boundData?.let { bindFlatBody(it, boundScale) }
        }
        styledView = v
        styledContainer.isGone = false
        // Same gap under the title block the flat body takes.
        (styledContainer.layoutParams as LayoutParams).topMargin = dp(10f * scale)
        // 1px until the page reports its painted height — never GONE, which
        // would leave the view unlaid-out and the page measuring against a
        // zero-width viewport (the height-ratchet bug the lens documents).
        styledContainer.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, 1))
        renderStyledContent(v, data)
        return true
    }

    /**
     * Draw [data]'s meta chips and imported groups into [v]. Chips first —
     * the styled document owns the meta row on this cell (see
     * [flatRemainder]) — then the groups; the pack's senses stay with the
     * native renderer below, unclamped, exactly as they are on a flat cell.
     */
    private fun renderStyledContent(v: YomitanDefinitionsView, data: WordDefinitionData) {
        val styled = data.styled ?: return
        v.setContent(
            DefinitionsDocument.contentHtml(
                WordDefinitionData(
                    word = "",
                    reading = null,
                    senses = emptyList(),
                    freqScore = 0,
                    isCommon = false,
                    importedGroups = data.importedGroups,
                ),
                styled.structured,
                localizePos = { context.localizePos(it) },
                metaChips = styledMetaChips(context, data),
            ),
            styled.dictStyles,
            styled.sourceLanguage,
        )
    }

    /** Panel token is [R.attr.ptCard]: this cell's opt-in host lays it on a
     *  card, and the page's own styled block uses the same ground. */
    private fun styledTokens(scale: Float) = DefinitionsDocument.Tokens(
        text = textColor,
        textMuted = mutedColor,
        textHint = hintColor,
        accent = accentColor,
        panel = context.themeColor(R.attr.ptCard),
        // The flat gloss size, so a styled cell and a flat one read alike.
        baseFontSizePx = 16.5f * scale,
    )

    /**
     * Size the styled body to its painted height, capped at the compact
     * surface's preview budget: this is a PREVIEW that taps through to the
     * word's own detail page, and the flat tier clamps imported rows to four
     * lines for exactly that reason. An uncapped WebView would let one
     * member's monolingual entry out-size the word the section belongs to.
     * Over-budget content keeps a bottom fade, so the cut reads as "more
     * below" rather than as a broken render.
     */
    private fun applyStyledHeight(contentPx: Int, scale: Float) {
        val v = styledView ?: return
        val wanted = contentPx + v.paddingTop + v.paddingBottom
        val max = dp(STYLED_PREVIEW_MAX_DP * scale)
        val lp = v.layoutParams ?: FrameLayout.LayoutParams(MATCH_PARENT, 1)
        lp.height = minOf(wanted, max)
        v.layoutParams = lp
        styledContainer.foreground = if (wanted > max) bottomFade(minOf(wanted, max)) else null
    }

    /** Transparent-to-card gradient over the last [FADE_DP] of a clipped
     *  styled body. */
    private fun bottomFade(heightPx: Int): Drawable {
        val card = context.themeColor(R.attr.ptCard)
        val gradient = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(card and 0x00FFFFFF, card),
        )
        return LayerDrawable(arrayOf(gradient)).apply {
            setLayerInset(0, 0, (heightPx - dp(FADE_DP)).coerceAtLeast(0), 0, 0)
        }
    }

    /** Destroy the styled renderer this cell owns, if any. Hosts that pass
     *  `styledImported = true` MUST call this on teardown: a dropped but
     *  undestroyed WebView keeps renderer resources and the context graph
     *  alive until GC. Idempotent. */
    fun releaseStyled() {
        styledView?.destroy()
        styledView = null
        styledActive = false
        styledContainer.removeAllViews()
        styledContainer.foreground = null
        styledContainer.isGone = true
    }

    /** Style a reading view: pitch contour + overline headroom when present;
     *  bold + full colour for the occurrence, muted otherwise. Shared by the
     *  inline reading and the below-title flow. */
    private fun styleReading(tv: TextView, row: ReadingRow, scale: Float, bold: Boolean) {
        if (row.pitch.isNotEmpty()) {
            tv.text = buildPitchAnnotatedReading(row.reading, row.pitch)
            tv.setPadding(0, dp(8f * scale), 0, 0)
        } else {
            tv.text = row.reading
            tv.setPadding(0, 0, 0, 0)
        }
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * scale)
        tv.setTextColor(if (bold) textColor else mutedColor)
        tv.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // A single reading renders inline; if it can't fit beside the title in
        // the width left by the action cluster (the old "scrunch"), drop it into
        // the full-width flow below instead. Re-evaluated every pass (width can
        // change), toggled only on a real change so it can't thrash.
        if (canInlineSingleReading) {
            val actionCluster = dp(36f * 3 + 16f * 2) // 3 slots + 2 gaps
            val titleAvail = View.MeasureSpec.getSize(widthMeasureSpec) -
                paddingLeft - paddingRight - actionCluster
            val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            wordView.measure(unspec, unspec)
            readingView.measure(unspec, unspec)
            val fits = wordView.measuredWidth + dp(10f) + readingView.measuredWidth <= titleAvail
            if (readingsFlow.isVisible != !fits) {
                readingView.isGone = !fits
                readingsFlow.isGone = fits
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** Re-render the body with refreshed Anki deck membership (the async
     *  "already in Anki" query resolves after the row is first bound). */
    fun updateAnkiDecks(decks: List<String>) {
        val data = boundData ?: return
        val next = data.copy(ankiDecks = decks)
        boundData = next
        val v = styledView
        if (styledActive && v != null) {
            renderStyledContent(v, next)
            return
        }
        bindFlatBody(next, boundScale)
    }

    /** Swap the speak icon for a spinner while a TTS request is in flight. */
    fun setSpeakLoading(loading: Boolean) {
        speakIcon.isInvisible = loading
        speakSpinner.isVisible = loading
    }

    private fun iconView(res: Int, tint: Int): ImageView =
        ImageView(context).apply {
            setImageResource(res)
            setColorFilter(tint)
        }

    private fun actionSlot(clickable: Boolean, marginStartDp: Float): FrameLayout =
        FrameLayout(context).apply {
            layoutParams = LayoutParams(dp(36f), dp(40f)).apply { marginStart = dp(marginStartDp) }
            if (clickable) {
                isClickable = true
                isFocusable = true
                background = themedDrawable(android.R.attr.selectableItemBackgroundBorderless)
            }
        }

    private fun centerParams(sizeDp: Float): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), Gravity.CENTER)

    private fun themedDrawable(attr: Int): Drawable? {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) AppCompatResources.getDrawable(context, tv.resourceId) else null
    }

    companion object {
        /** The dictionary handoff's "large" text-size factor — the default
         *  for the full-width result cell. */
        const val DEFAULT_SCALE = 1.12f

        /** Preview budget for a styled imported body, in dp before [bind]'s
         *  scale — roughly the flat tier's four clamped lines plus its group
         *  header. Past it the body clips under a fade and the reader taps
         *  through for the whole entry. */
        private const val STYLED_PREVIEW_MAX_DP = 132f

        /** Height of the fade over a clipped styled body. */
        private const val FADE_DP = 28f
    }
}
