package com.playtranslate.ui

import android.content.Context
import android.content.res.ColorStateList
import android.text.Layout
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import com.playtranslate.R
import com.playtranslate.themeColor
import kotlin.math.ceil

/**
 * A results section header: the language name, then the section's actions
 * ([HeaderAction]), with the ones that don't fit folded behind a ⋯ button
 * whose menu ([ActionOverflowMenu]) runs them. It is the header row of
 * section_source.xml / section_target.xml, and stays a [LinearLayout], so
 * padding, gravity, RTL mirroring and the row's height behave exactly as
 * the plain row did. Its one XML child is the label; [setActions] builds
 * the rest: a weighted [Space] (the actions sit at the end), one view per
 * foldable action in priority order, ⋯, then the pinned actions (the eye),
 * each separated from what precedes it by [GAP_DP] of start margin.
 *
 * The row is the ONLY writer of its action views' visibility. An
 * unavailable action goes GONE as soon as its model says so; the fold
 * ([HeaderFit]) runs in [onMeasure], before the LinearLayout measures, so it
 * plans against the width being laid out. Every write there is change-only
 * (the [WordResultCell] precedent): a pass that plans the fold it already
 * shows writes nothing, so a settled row never re-requests layout. The
 * label is capped only when the plan cuts it; otherwise it measures itself,
 * so a rounding difference between the plan's width and the TextView's own
 * can never ellipsize a name that fits.
 */
class SectionHeaderRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** What an open overflow menu follows. */
    interface Listener {
        /** The folded set may have changed. Posted from the measure pass
         *  that changed it, so a listener never mutates views mid-measure;
         *  compare [foldedActions] against what you showed. */
        fun onFoldChanged(row: SectionHeaderRow) {}

        /** An action's look changed (icon, accent, name). Synchronous. */
        fun onActionChanged(action: HeaderAction) {}
    }

    private lateinit var label: TextView

    /** The ⋯ button: shown while anything is folded, and the anchor for
     *  the menu and for any popover a menu action opens. */
    lateinit var moreButton: ImageButton
        private set

    /** Tapped ⋯ (the binder opens or closes the menu). */
    var onMoreClick: ((SectionHeaderRow) -> Unit)? = null

    private var actions: List<HeaderAction> = emptyList()
    private val viewOf = HashMap<HeaderAction, View>()
    private val actionOf = HashMap<View, HeaderAction>()
    private val renderedIcon = HashMap<HeaderAction, Int>()
    private var folded: List<HeaderAction> = emptyList()
    private val listeners = ArrayList<Listener>()

    private val gapPx = (GAP_DP * resources.displayMetrics.density).toInt()

    override fun onFinishInflate() {
        super.onFinishInflate()
        label = getChildAt(0) as? TextView
            ?: error("SectionHeaderRow's first XML child must be the language label")
    }

    /** Build the row's actions once: [actions] in priority order, pinned
     *  ones last; [moreId] is the ⋯ button's id. */
    fun setActions(actions: List<HeaderAction>, @IdRes moreId: Int) {
        check(this.actions.isEmpty()) { "a header's actions are set once" }
        this.actions = actions
        addView(Space(context), LayoutParams(0, 0, 1f))
        for (a in actions) if (!a.pinned) addActionView(a)
        moreButton = (inflateItem(R.layout.section_header_icon) as ImageButton).apply {
            id = moreId
            setImageResource(R.drawable.ic_more_horiz)
            imageTintList = ColorStateList.valueOf(context.themeColor(R.attr.ptTextMuted))
            contentDescription = context.getString(R.string.cd_more_actions)
            visibility = View.GONE
            setOnClickListener { onMoreClick?.invoke(this@SectionHeaderRow) }
        }
        addView(moreButton)
        for (a in actions) if (a.pinned) addActionView(a)
        for (a in actions) {
            a.observer = ::onActionChanged
            render(a)
        }
    }

    /** The available actions the last fit folded behind ⋯, in order. */
    fun foldedActions(): List<HeaderAction> = folded.filter { it.available }

    /** What a controller cursor can reach here, in visual order: the inline
     *  actions, ⋯, then the eye. A hold (the Anki one-tap) wherever the
     *  action has a long-press. */
    fun navActions(): List<NavAction> = buildList {
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v === label || v is Space || !v.isShown) continue
            add(NavAction(v, holdActivates = actionOf[v]?.onLongClick != null))
        }
    }

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (actions.isNotEmpty()) applyFit(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun applyFit(widthSpec: Int) {
        val content = if (MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED) null
        else MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight
        val foldable = actions.filter { !it.pinned && it.available }
        val pinned = actions.filter { it.pinned && it.available }
        val labelNatural = labelNaturalWidth()
        val plan = HeaderFit.plan(
            content, labelNatural, gapPx,
            foldable.map(::widthOf), pinned.map(::widthOf), moreButton.layoutParams.width,
        )
        val inline = foldable.subList(0, plan.inlineCount)
        for (a in actions) setShown(viewOf.getValue(a), a.available && (a.pinned || a in inline))
        setShown(moreButton, plan.showMore)
        val maxWidth = if (plan.labelWidth >= labelNatural) Int.MAX_VALUE else plan.labelWidth
        if (label.maxWidth != maxWidth) label.maxWidth = maxWidth
        val nowFolded = foldable.subList(plan.inlineCount, foldable.size).toList()
        if (nowFolded != folded) {
            folded = nowFolded
            post { for (l in listeners.toList()) l.onFoldChanged(this) }
        }
    }

    /** The label's single-line width, the way TextView measures it: its
     *  displayed (all-caps) text on its own paint, plus compound padding. */
    private fun labelNaturalWidth(): Int {
        val text = label.text ?: ""
        val shown = label.transformationMethod?.getTransformation(text, label) ?: text
        return ceil(Layout.getDesiredWidth(shown, label.paint)).toInt() +
            label.compoundPaddingLeft + label.compoundPaddingRight
    }

    private fun widthOf(a: HeaderAction): Int {
        val v = viewOf.getValue(a)
        return when (a.presentation) {
            HeaderAction.Presentation.ICON -> v.layoutParams.width
            HeaderAction.Presentation.TEXT_PILL -> {
                v.measure(
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(v.layoutParams.height, MeasureSpec.EXACTLY),
                )
                v.measuredWidth
            }
        }
    }

    private fun setShown(v: View, shown: Boolean) {
        val want = if (shown) View.VISIBLE else View.GONE
        if (v.visibility != want) v.visibility = want
    }

    private fun addActionView(a: HeaderAction) {
        val v = inflateItem(
            when (a.presentation) {
                HeaderAction.Presentation.ICON -> R.layout.section_header_icon
                HeaderAction.Presentation.TEXT_PILL -> R.layout.section_header_pill
            },
        )
        v.id = a.viewId
        v.setOnClickListener { a.onClick?.invoke(it) }
        v.setOnLongClickListener { view -> a.onLongClick?.let { it(view); true } ?: false }
        addView(v)
        viewOf[a] = v
        actionOf[v] = a
    }

    /** One header item, with its start gap. */
    private fun inflateItem(@LayoutRes layout: Int): View =
        LayoutInflater.from(context).inflate(layout, this, false).also {
            (it.layoutParams as LayoutParams).marginStart = gapPx
        }

    private fun onActionChanged(a: HeaderAction, affectsWidth: Boolean) {
        render(a)
        if (affectsWidth) requestLayout()
        for (l in listeners.toList()) l.onActionChanged(a)
    }

    /** Paint [a]'s inline view from the model. Availability only ever
     *  HIDES here; whether an available action shows inline is the fit's
     *  call, made on the layout pass this triggers. */
    private fun render(a: HeaderAction) {
        val v = viewOf.getValue(a)
        when (a.presentation) {
            HeaderAction.Presentation.ICON -> {
                val b = v as ImageButton
                if (renderedIcon[a] != a.icon) {
                    b.setImageResource(a.icon)
                    renderedIcon[a] = a.icon
                }
                b.imageTintList = ColorStateList.valueOf(
                    context.themeColor(if (a.active) R.attr.ptAccent else R.attr.ptTextMuted),
                )
            }
            HeaderAction.Presentation.TEXT_PILL -> {
                val t = v as TextView
                t.text = a.label
                // ON: a filled accent pill with on-accent text; OFF: the
                // drawable's stock look. Tinting recolors the drawable's
                // solid fill, so one drawable serves both states.
                if (a.active) {
                    t.setTextColor(context.themeColor(R.attr.ptAccentOn))
                    t.backgroundTintList = ColorStateList.valueOf(context.themeColor(R.attr.ptAccent))
                } else {
                    t.setTextColor(context.themeColor(R.attr.ptTextMuted))
                    t.backgroundTintList = null
                }
            }
        }
        v.contentDescription = a.contentDescription
        v.isLongClickable = a.onLongClick != null
        if (!a.available) setShown(v, false)
    }

    private companion object {
        /** Space between neighbours: label to first action, and every
         *  action to the next. The header's historic button spacing. */
        const val GAP_DP = 16f
    }
}
