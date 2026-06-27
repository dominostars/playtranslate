package com.playtranslate.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
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
            definitionsView,
            LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { marginEnd = dp(12f) },
        )
    }

    /**
     * Bind [data] at [scale]. [onCellTap] opens Word Detail; [onSpeak] /
     * [onAnki] are the (propagation-stopping) action handlers.
     */
    fun bind(
        data: WordDefinitionData,
        scale: Float,
        inflectedForms: List<InflectedForm>,
        onCellTap: () -> Unit,
        onSpeak: () -> Unit,
        onAnki: () -> Unit,
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
        definitionsView.bind(data, label = null, scale = scale)
        (definitionsView.layoutParams as LayoutParams).topMargin = dp(10f * scale)
        definitionsView.requestLayout()

        setOnClickListener { onCellTap() }
        speakButton.setOnClickListener { onSpeak() }
        ankiButton.setOnClickListener { onAnki() }
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
        definitionsView.bind(next, label = null, scale = boundScale)
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
    }
}
