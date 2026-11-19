package com.playtranslate.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import com.playtranslate.R
import com.playtranslate.themeColor

/**
 * The detached window's top bar: the icon menu's actions, and the page's
 * Add to Anki, as one row. From the start: Exit, the more button, then
 * Minimize; a gap; then Settings, Edit region, Anki, Auto and Capture at the
 * end. Capture and Auto are icon-and-label pills, the rest icons.
 *
 * The actions are [HeaderAction] models, the same ones a section header's
 * row is built from, and what does not fit folds behind the more button by
 * the same pure rule ([HeaderFit]), in [Actions.foldOrder]: Minimize gives
 * way first and Capture last; Exit never folds. The more button is always
 * there: its menu also holds what the page's headers held, which the window
 * adds to the folded actions. It is the section headers'
 * [ActionOverflowMenu], through [FoldingActionRow].
 *
 * Capture and Auto never fold while they can stay as icons: where a pill
 * with its label would fold, both drop their labels and stand as round icon
 * buttons, so the window's one essential action stays on the bar down to
 * the narrowest page.
 *
 * Neither pill draws a press effect. A repaint of ours just before a
 * capture can be served as the capture's clean frame on whole-screen
 * MediaProjection (the drag lens's icon ring, 2026-10-09), and Capture's tap
 * is exactly that moment.
 *
 * Like the section header, the row is the only writer of its action views'
 * visibility, and the fold runs in [onMeasure] with change-only writes.
 */
internal class DetachedWindowBar(context: Context) : LinearLayout(context), FoldingActionRow {

    /** The bar's seven actions. */
    class Actions(
        val capture: HeaderAction,
        val auto: HeaderAction,
        val anki: HeaderAction,
        val editRegion: HeaderAction,
        val settings: HeaderAction,
        val minimize: HeaderAction,
        val exit: HeaderAction,
    ) {
        /** The foldable actions, the one that stays inline longest first. */
        val foldOrder: List<HeaderAction> get() = listOf(capture, auto, anki, editRegion, settings, minimize)
        val all: List<HeaderAction> get() = foldOrder + exit
    }

    /** Whether Capture and Auto stand without their labels (class doc). */
    var pillsIconOnly = false
        private set

    private val dp = resources.displayMetrics.density
    private val itemPx = (ITEM_DP * dp).toInt()
    private val gapPx = (GAP_DP * dp).toInt()

    /** Tapped the more button (the window opens or closes the menu). */
    var onMoreClick: ((DetachedWindowBar) -> Unit)? = null

    /** The more button: always shown. */
    lateinit var moreButton: ImageButton
        private set

    override val moreAnchor: View get() = moreButton

    private var actions: Actions? = null
    private val viewOf = HashMap<HeaderAction, View>()
    private var folded: List<HeaderAction> = emptyList()
    private val listeners = ArrayList<FoldingActionRow.Listener>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // Every item brings its own start gap; the row closes the end.
        setPaddingRelative((EDGE_PAD_DP * dp).toInt() - gapPx, 0, (EDGE_PAD_DP * dp).toInt(), 0)
    }

    /** Build the row once. */
    fun setActions(actions: Actions) {
        check(this.actions == null) { "the bar's actions are set once" }
        this.actions = actions
        addItem(actions.exit, iconButton(actions.exit))
        moreButton = iconButton(null).apply {
            id = R.id.detachedBarMore
            setImageResource(R.drawable.ic_more_horiz)
            imageTintList = ColorStateList.valueOf(context.themeColor(R.attr.ptTextMuted))
            contentDescription = context.getString(R.string.cd_more_actions)
            setOnClickListener { onMoreClick?.invoke(this@DetachedWindowBar) }
        }
        addView(moreButton, itemParams(itemPx))
        addItem(actions.minimize, iconButton(actions.minimize))
        addView(Space(context), LayoutParams(0, 0, 1f))
        addItem(actions.settings, iconButton(actions.settings))
        addItem(actions.editRegion, iconButton(actions.editRegion))
        addItem(actions.anki, iconButton(actions.anki))
        addItem(actions.auto, pill(actions.auto, filled = false))
        addItem(actions.capture, pill(actions.capture, filled = true))
        for (a in actions.all) {
            a.observer = ::onActionChanged
            render(a)
        }
    }

    override fun foldedActions(): List<HeaderAction> = folded.filter { it.available }

    override fun addListener(listener: FoldingActionRow.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: FoldingActionRow.Listener) {
        listeners -= listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        actions?.let { applyFit(it, widthMeasureSpec) }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun applyFit(actions: Actions, widthSpec: Int) {
        val content = if (MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED) null
        else MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight
        val foldable = actions.foldOrder.filter { it.available }
        // Exit and the more button always stand: what folds gives the more
        // button nothing up, since it is there already.
        val pinned = listOf(itemPx) + listOf(actions.exit).filter { it.available }.map(::widthOf)
        fun planWith(iconOnly: Boolean): HeaderFit.Plan {
            setPillsIconOnly(iconOnly)
            return HeaderFit.plan(
                content, labelNatural = 0, gap = gapPx,
                foldable = foldable.map(::widthOf), pinned = pinned, moreWidth = 0, overflow = false,
            )
        }
        // The pills lead the fold order, so fewer inline than there are
        // pills means one of them would fold with its label on.
        val pills = foldable.count { viewOf.getValue(it) !is ImageButton }
        var plan = planWith(iconOnly = false)
        if (plan.inlineCount < pills) plan = planWith(iconOnly = true)
        val inline = foldable.subList(0, plan.inlineCount)
        for (a in actions.all) setShown(viewOf.getValue(a), a.available && (a === actions.exit || a in inline))
        val nowFolded = foldable.subList(plan.inlineCount, foldable.size).toList()
        if (nowFolded != folded) {
            folded = nowFolded
            post { for (l in listeners.toList()) l.onFoldChanged(this) }
        }
    }

    private fun widthOf(a: HeaderAction): Int {
        val v = viewOf.getValue(a)
        if (v is ImageButton) return itemPx
        v.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(itemPx, MeasureSpec.EXACTLY),
        )
        return v.measuredWidth
    }

    private fun setShown(v: View, shown: Boolean) {
        val want = if (shown) View.VISIBLE else View.GONE
        if (v.visibility != want) v.visibility = want
    }

    /** Capture and Auto with their labels, or as round icon buttons the
     *  size of the others. Change-only, like every write of the fit. */
    private fun setPillsIconOnly(iconOnly: Boolean) {
        if (pillsIconOnly == iconOnly) return
        pillsIconOnly = iconOnly
        val start = (PILL_START_PAD_DP * dp).toInt()
        val end = if (iconOnly) start else (PILL_END_PAD_DP * dp).toInt()
        for (v in viewOf.values) {
            if (v !is LinearLayout) continue
            v.getChildAt(1).visibility = if (iconOnly) View.GONE else View.VISIBLE
            v.setPaddingRelative(start, 0, end, 0)
        }
    }

    /** [onHold] runs on a long press of [a]'s button (Anki's send at once).
     *  A folded action has no hold: its menu row is a tap. */
    fun setOnHold(a: HeaderAction, onHold: () -> Unit) {
        viewOf.getValue(a).setOnLongClickListener {
            onHold()
            true
        }
    }

    private fun itemParams(width: Int) = LayoutParams(width, itemPx).apply { marginStart = gapPx }

    private fun addItem(a: HeaderAction, v: View) {
        v.id = a.viewId
        v.setOnClickListener { a.onClick?.invoke(it) }
        addView(v, itemParams(if (v is ImageButton) itemPx else LayoutParams.WRAP_CONTENT))
        viewOf[a] = v
    }

    /** An icon-only action (or, with no action, the more button). */
    private fun iconButton(a: HeaderAction?): ImageButton = ImageButton(context).apply {
        val pad = (ICON_PAD_DP * dp).toInt()
        setPadding(pad, pad, pad, pad)
        scaleType = ImageView.ScaleType.FIT_CENTER
        // The press ripple of every other icon button over the game; Capture
        // and Auto are pills with none (class doc).
        val tv = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        setBackgroundResource(tv.resourceId)
    }

    /** An icon-and-label action: [filled] on the accent (Capture), else an
     *  outline (Auto). */
    private fun pill(a: HeaderAction, filled: Boolean): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPaddingRelative((PILL_START_PAD_DP * dp).toInt(), 0, (PILL_END_PAD_DP * dp).toInt(), 0)
        background = GradientDrawable().apply {
            cornerRadius = itemPx / 2f
            if (filled) {
                setColor(context.themeColor(R.attr.ptAccent))
            } else {
                setColor(Color.TRANSPARENT)
                setStroke((1 * dp).toInt(), context.themeColor(R.attr.ptOutline))
            }
        }
        val content = context.themeColor(if (filled) R.attr.ptAccentOn else R.attr.ptText)
        addView(
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                imageTintList = ColorStateList.valueOf(content)
            },
            LayoutParams((PILL_ICON_DP * dp).toInt(), (PILL_ICON_DP * dp).toInt()),
        )
        addView(
            TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, PILL_LABEL_SP)
                setTextColor(content)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = (PILL_ICON_GAP_DP * dp).toInt()
            },
        )
        isClickable = true
        isFocusable = true
    }

    private fun onActionChanged(a: HeaderAction, affectsWidth: Boolean) {
        render(a)
        // A pill's width follows its label, whatever the model's own
        // presentation says.
        if (affectsWidth || viewOf[a] !is ImageButton) requestLayout()
        for (l in listeners.toList()) l.onActionChanged(a)
    }

    /** Paint [a]'s inline view from the model. Availability only ever HIDES
     *  here; whether an available action shows inline is the fit's call. */
    private fun render(a: HeaderAction) {
        when (val v = viewOf.getValue(a)) {
            is ImageButton -> {
                v.setImageResource(a.icon)
                v.imageTintList = ColorStateList.valueOf(
                    context.themeColor(if (a.active) R.attr.ptAccent else R.attr.ptTextMuted),
                )
            }
            is LinearLayout -> {
                (v.getChildAt(0) as ImageView).setImageResource(a.icon)
                (v.getChildAt(1) as TextView).text = a.label
            }
        }
        viewOf.getValue(a).contentDescription = a.contentDescription
        if (!a.available) setShown(viewOf.getValue(a), false)
    }

    companion object {
        /** The bar's height. */
        const val HEIGHT_DP = 52f

        /** An item's height, and an icon button's width. */
        private const val ITEM_DP = 40f
        private const val GAP_DP = 4f
        private const val EDGE_PAD_DP = 6f
        private const val ICON_PAD_DP = 9f
        private const val PILL_ICON_DP = 18f
        private const val PILL_ICON_GAP_DP = 6f
        private const val PILL_START_PAD_DP = 11f
        private const val PILL_END_PAD_DP = 14f
        private const val PILL_LABEL_SP = 14f
    }
}
