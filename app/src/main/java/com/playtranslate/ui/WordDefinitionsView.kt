package com.playtranslate.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isNotEmpty
import com.playtranslate.R
import com.playtranslate.themeColor

/**
 * Renders the dictionary body shared by the magnifying lens
 * ([MagnifierLens]) and the translation-result word cell ([WordResultCell]):
 * a meta row (Common pill · frequency stars · Anki deck pill), an optional
 * warning label, and the numbered senses with per-sense POS headers.
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
    private val warnColor = context.themeColor(R.attr.ptWarning)

    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /**
     * Fill for the neutral data chips (the Anki deck pill and the frequency
     * chips): ptSurface by default — one step off the ptCard list surface
     * the result cell sits on. The lens panel is ITSELF ptSurface, so that
     * host overrides this to ptCard (one step lighter) — with the default
     * fill the chips vanish into the panel.
     */
    var metaChipFill: Int = context.themeColor(R.attr.ptSurface)

    /**
     * Opt-in empty-state copy. When non-null AND [bind] receives a [data]
     * with no senses, the body renders this single muted line (the "no
     * dictionary entry" placeholder) instead of nothing. Left null by
     * default, so callers that legitimately bind empty senses (e.g.
     * [WordResultCell]) keep rendering nothing.
     *
     * The view can't see the source entry — [WordDefinitionData] drops it
     * (see `toLensData`) — so "no senses" is the only available "nothing to
     * show" signal. A malformed entry that yields zero renderable senses
     * would therefore also surface this; accepted, since "no senses"
     * honestly reads as "no definitions."
     */
    var emptyPlaceholder: String? = null

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
    fun bind(data: WordDefinitionData, label: String?, scale: Float, showMisc: Boolean = true) {
        removeAllViews()

        val hasMetaContent = data.isCommon || data.freqScore > 0 ||
            data.frequencies.isNotEmpty() || data.ankiDecks.isNotEmpty()

        if (hasMetaContent) addView(buildMetaRow(data, scale), fullWidth())

        label?.takeIf { it.isNotBlank() }?.let { warning ->
            val view = TextView(context).apply {
                text = warning
                setTextColor(warnColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * scale)
            }
            addView(view, fullWidth(topMargin = if (isNotEmpty()) dp(8f * scale) else 0))
        }

        // Gap between the meta/warning sections and the first sense.
        val sensesTop = if (isNotEmpty()) dp(9f * scale) else 0
        var previousPos: List<String>? = null
        var firstSenseBlock = true
        data.senses.forEachIndexed { i, sense ->
            // A new POS header is emitted only when the POS actually changes
            // (or is the first non-empty POS seen). Imported rows carry a
            // verbatim dictionary-name header; everything else localizes.
            if (sense.pos.isNotEmpty() && sense.pos != previousPos) {
                val label =
                    if (sense.imported) sense.pos.joinToString(" · ")
                    else context.localizePos(sense.pos)
                val header = TextView(context).apply {
                    text = label.uppercase()
                    // Imported rows with a per-dict accent override tint the
                    // title; everything else uses the muted secondary color.
                    setTextColor(sense.accentColor ?: secondaryText)
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
            addView(
                buildDefinitionRow(i + 1, sense.definition, scale, clamp = sense.imported),
                fullWidth(topMargin = top),
            )
            // Register-tag line under the gloss. renderMisc is the render-side
            // cleanliness authority (localizes known tags, passes domain/region
            // through, drops noise). Suppressed on the drag lens (showMisc=false)
            // so its compact layout is unchanged — the tap-through detail sheet
            // still shows misc (it re-resolves independently).
            if (showMisc) {
                context.renderMiscText(sense.misc)?.let { miscText ->
                    addView(
                        buildMiscRow(miscText, scale),
                        fullWidth(topMargin = dp(2f * scale)).also { it.marginStart = dp(25f * scale) },
                    )
                }
            }
            firstSenseBlock = false
        }

        // Opt-in empty-state: a word with no renderable senses shows the
        // muted placeholder (the "no dictionary entry" case) instead of a
        // blank body. Callers that don't set [emptyPlaceholder] render
        // nothing here, as before.
        if (data.senses.isEmpty()) emptyPlaceholder?.let { placeholder ->
            addView(
                TextView(context).apply {
                    text = placeholder
                    setTextColor(secondaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * scale)
                },
                fullWidth(topMargin = if (isNotEmpty()) dp(8f * scale) else 0),
            )
        }
    }

    /** Common pill · stars · frequency chips · Anki deck pill, wrapping. */
    private fun buildMetaRow(data: WordDefinitionData, scale: Float): FlowLayout {
        val row = FlowLayout(context).apply { lineSpacingPx = dp(7f * scale) }
        val gap = dp(8f * scale)
        fun add(view: View) {
            val lp = ViewGroup.MarginLayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            if (row.isNotEmpty()) lp.marginStart = gap
            row.addView(view, lp)
        }
        if (data.isCommon) {
            add(
                BadgeChips.commonPill(
                    context,
                    textSizeSp = 11.5f * scale,
                    horizontalPadPx = dp(8f * scale),
                    verticalPadPx = dp(3f * scale),
                )
            )
        }
        if (data.freqScore > 0) {
            // Kept as the app's filled-star system (0–5), not the handoff's
            // 0–3 filled/empty design.
            add(BadgeChips.filledStars(context, data.freqScore, 13f * scale, secondaryText))
        }
        val ink = BadgeChips.onAccentInk(context)
        for (tag in data.frequencies) {
            val colors = freqChipColors(tag.accentColor, metaChipFill, secondaryText, ink)
            add(
                BadgeChips.freqChip(
                    context,
                    tag,
                    textColor = colors.text,
                    background = chipBackground(colors.fill),
                    textSizeSp = 11.5f * scale,
                    horizontalPadPx = dp(8f * scale),
                    verticalPadPx = dp(2f * scale),
                )
            )
        }
        if (data.ankiDecks.isNotEmpty()) {
            AnkiDeckBadge.buildPill(
                ctx = context,
                deckNames = data.ankiDecks,
                textColor = secondaryText,
                background = chipBackground(),
                textSizeSp = 11.5f * scale,
                horizontalPadPx = dp(8f * scale),
                verticalPadPx = dp(2f * scale),
            )?.let { add(it) }
        }
        return row
    }

    /** [fill] as the lightly-rounded data-chip shape (the programmatic
     *  equivalent of bg_meta_chip). Fresh instance per chip — drawables
     *  can't be shared across views. */
    private fun chipBackground(fill: Int = metaChipFill): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(4f).toFloat()
    }

    /** A right-aligned number column + the gloss text. [clamp] caps the
     *  gloss at a few lines — imported (often monolingual, paragraph-length)
     *  definitions get the cap on this compact surface; the word detail
     *  page shows them in full. */
    private fun buildDefinitionRow(
        number: Int,
        definition: String,
        scale: Float,
        clamp: Boolean,
    ): LinearLayout =
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
                if (clamp) {
                    maxLines = 4
                    ellipsize = TextUtils.TruncateAt.END
                }
            }, LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(9f * scale) })
        }

    /** The small italic register-tag line shown under a sense's gloss (e.g.
     *  "Honorific · Colloquial"). Muted hint color, sized just below the gloss. */
    private fun buildMiscRow(text: String, scale: Float): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(hintText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f * scale)
            setTypeface(null, android.graphics.Typeface.ITALIC)
        }

    private fun fullWidth(topMargin: Int = 0, bottomMargin: Int = 0): LayoutParams =
        LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            this.topMargin = topMargin
            this.bottomMargin = bottomMargin
        }
}
