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

    init {
        orientation = VERTICAL
        setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
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

        // Read-aloud button: icon swapped for a spinner while speaking.
        speakIcon = iconView(R.drawable.ic_lens_speak, mutedColor)
        speakSpinner = ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(accentColor)
            isVisible = false
        }
        speakButton = actionFrame().apply {
            addView(speakIcon, centerParams(22f))
            addView(speakSpinner, centerParams(20f))
        }
        headRow.addView(speakButton)

        // Add-to-Anki button — always plain.
        ankiButton = actionFrame().apply {
            addView(iconView(R.drawable.ic_card_stack, mutedColor), centerParams(22f))
        }
        headRow.addView(ankiButton)

        // Chevron: a non-interactive "opens detail" affordance.
        val chevron = iconView(R.drawable.ic_chevron_right, hintColor).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        headRow.addView(
            chevron,
            LayoutParams(dp(22f), dp(22f)).apply { marginStart = dp(2f) },
        )

        addView(headRow, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(definitionsView, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    /**
     * Bind [data] at [scale]. [onCellTap] opens Word Detail; [onSpeak] /
     * [onAnki] are the (propagation-stopping) action handlers.
     */
    fun bind(
        data: WordDefinitionData,
        scale: Float,
        onCellTap: () -> Unit,
        onSpeak: () -> Unit,
        onAnki: () -> Unit,
    ) {
        boundData = data
        boundScale = scale
        wordView.text = data.word
        wordView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 27f * scale)
        readingView.text = data.reading ?: ""
        readingView.isGone = data.reading.isNullOrEmpty()
        readingView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * scale)
        definitionsView.bind(data, label = null, scale = scale)
        (definitionsView.layoutParams as LayoutParams).topMargin = dp(10f * scale)
        definitionsView.requestLayout()

        setOnClickListener { onCellTap() }
        speakButton.setOnClickListener { onSpeak() }
        ankiButton.setOnClickListener { onAnki() }
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

    private fun actionFrame(): FrameLayout =
        FrameLayout(context).apply {
            layoutParams = LayoutParams(dp(40f), dp(40f))
            isClickable = true
            isFocusable = true
            background = themedDrawable(android.R.attr.selectableItemBackgroundBorderless)
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
