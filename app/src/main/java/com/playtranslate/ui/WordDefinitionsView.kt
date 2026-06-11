package com.playtranslate.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import com.playtranslate.R
import com.playtranslate.themeColor

/**
 * Renders the dictionary body shared by the magnifying lens
 * ([MagnifierLens]) and the translation-result word cell ([WordResultCell]):
 * a meta row (Common pill · frequency stars · promoted part-of-speech · Anki
 * deck pill), an optional warning label, and the numbered senses with
 * per-sense POS headers.
 *
 * The whole body multiplies by a [bind] `scale` factor (text sizes and the
 * structural gaps), so the same renderer serves the small floating lens and
 * the full-width result cell. The design (sizes, the accent Common pill, the
 * uppercase tracked POS, the number-column definitions) follows the
 * dictionary-lookup handoff; the **frequency stars deliberately keep the
 * app's existing 0–5 filled-star system** rather than the handoff's 0–3
 * filled/empty design.
 *
 * Colours are resolved from the view's own (themed) context, so callers pass
 * a context carrying the right theme + accent (the lens via
 * `overlayThemedContext`, the cell via its activity).
 */
class WordDefinitionsView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).toInt()

    private val primaryText = context.themeColor(R.attr.ptText)
    private val secondaryText = context.themeColor(R.attr.ptTextMuted)
    private val hintText = context.themeColor(R.attr.ptTextHint)
    private val accentColor = context.themeColor(R.attr.ptAccent)
    private val accentTint = context.themeColor(R.attr.ptAccentTint)
    private val warnColor = context.themeColor(R.attr.ptWarning)

    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    init {
        orientation = VERTICAL
    }

    /**
     * Replace the body with [data]. [label] is an optional warning line
     * (carrying its own leading "⚠ " glyph) shown between the meta row and
     * the senses; pass null for none. [scale] multiplies every size — the
     * lens passes a small factor, the result cell the handoff's "large"
     * default.
     */
    fun bind(data: WordDefinitionData, label: String?, scale: Float) {
        removeAllViews()

        // If every non-blank POS across the senses is the same, the body
        // grouping collapses to one section — and a lone section header is
        // just a louder copy of what the meta row already carries, so promote
        // it into the meta row and skip the in-body headers.
        val distinctPos = data.senses.map { it.pos.trim() }.filter { it.isNotEmpty() }.distinct()
        val singlePos = distinctPos.singleOrNull()
        val hasMetaContent = data.isCommon || data.freqScore > 0 ||
            singlePos != null || data.ankiDecks.isNotEmpty()

        if (hasMetaContent) addView(buildMetaRow(data, singlePos, scale), fullWidth())

        label?.takeIf { it.isNotBlank() }?.let { warning ->
            val view = TextView(context).apply {
                text = warning
                setTextColor(warnColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * scale)
            }
            addView(view, fullWidth(topMargin = if (childCount > 0) dp(8f * scale) else 0))
        }

        // Gap between the meta/warning sections and the first sense.
        val sensesTop = if (childCount > 0) dp(9f * scale) else 0
        var previousPos: String? = null
        var firstSenseBlock = true
        data.senses.forEachIndexed { i, sense ->
            // A new POS header is emitted only when the POS actually changes
            // (or is the first non-blank POS seen). Suppressed entirely when
            // the meta row already carries the single promoted POS.
            if (singlePos == null && sense.pos.isNotBlank() && sense.pos != previousPos) {
                val header = TextView(context).apply {
                    text = sense.pos.uppercase()
                    setTextColor(secondaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f * scale)
                    typeface = medium
                    letterSpacing = 0.07f
                }
                val top = if (firstSenseBlock) sensesTop else dp(10f * scale)
                addView(header, fullWidth(topMargin = top, bottomMargin = dp(4f * scale)))
                previousPos = sense.pos
                firstSenseBlock = false
            }
            val top = if (firstSenseBlock) sensesTop else 0
            addView(buildDefinitionRow(i + 1, sense.definition, scale), fullWidth(topMargin = top))
            firstSenseBlock = false
        }
    }

    /** Common pill · stars · promoted POS · Anki deck pill, wrapping. */
    private fun buildMetaRow(data: WordDefinitionData, singlePos: String?, scale: Float): FlowLayout {
        val row = FlowLayout(context).apply { lineSpacingPx = dp(7f * scale) }
        val gap = dp(8f * scale)
        fun add(view: View) {
            val lp = ViewGroup.MarginLayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            if (row.childCount > 0) lp.marginStart = gap
            row.addView(view, lp)
        }
        if (data.isCommon) {
            add(TextView(context).apply {
                text = context.getString(R.string.word_detail_common)
                setTextColor(accentColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f * scale)
                typeface = medium
                setPadding(dp(8f * scale), dp(3f * scale), dp(8f * scale), dp(3f * scale))
                background = GradientDrawable().apply {
                    setColor(accentTint)
                    cornerRadius = dp(100f).toFloat()
                }
            })
        }
        if (data.freqScore > 0) {
            // Kept as the app's filled-star system (0–5), not the handoff's
            // 0–3 filled/empty design.
            add(TextView(context).apply {
                text = "★".repeat(data.freqScore.coerceAtMost(5))
                setTextColor(secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * scale)
            })
        }
        if (singlePos != null) {
            add(TextView(context).apply {
                text = singlePos.uppercase()
                setTextColor(secondaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f * scale)
                typeface = medium
                letterSpacing = 0.07f
            })
        }
        if (data.ankiDecks.isNotEmpty()) {
            AnkiDeckBadge.buildPill(
                ctx = context,
                deckNames = data.ankiDecks,
                textColor = secondaryText,
                background = AppCompatResources.getDrawable(context, R.drawable.bg_anki_meta_chip)
                    ?: GradientDrawable(),
                textSizeSp = 11.5f * scale,
                horizontalPadPx = dp(8f * scale),
                verticalPadPx = dp(2f * scale),
            )?.let { add(it) }
        }
        return row
    }

    /** A right-aligned number column + the gloss text. */
    private fun buildDefinitionRow(number: Int, definition: String, scale: Float): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(3f * scale), 0, dp(3f * scale))
            addView(TextView(context).apply {
                text = "$number."
                setTextColor(hintText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * scale)
                gravity = Gravity.END
                minWidth = dp(16f * scale)
            })
            addView(TextView(context).apply {
                text = definition
                setTextColor(primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f * scale)
            }, LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(9f * scale) })
        }

    private fun fullWidth(topMargin: Int = 0, bottomMargin: Int = 0): LayoutParams =
        LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            this.topMargin = topMargin
            this.bottomMargin = bottomMargin
        }
}
