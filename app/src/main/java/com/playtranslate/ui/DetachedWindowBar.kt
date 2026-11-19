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
 * The detached window's top bar. From the start: Minimize, the menu button;
 * a gap; then, at the end, Auto and Capture, each with its name on it. Those
 * four are all a new user has to read. Everything else the window can do is
 * in the menu, by name and under headings ([DetachedWindow]'s menu).
 *
 * Where the window is wide, up to two shortcuts stand before Auto, each also
 * with its name: Add to Anki, then (further from the end, the first to go)
 * the page's Read aloud ([setReadAloud]). A shortcut is a second way to a
 * menu row, never the only way: it shows only while there is a result for it
 * to act on ([shortcutsOffered]) and only where Capture and Auto keep their
 * names, and one that does not fit is simply not shown.
 *
 * Capture and Auto never give way to a shortcut, and they stay on the bar as
 * the window narrows: where a pill with its name would not fit, both drop
 * their names and stand as round icon buttons. Narrower than that Auto, and
 * last Capture, fold; the window's menu lists what folded
 * ([foldedActions]), through [FoldingActionRow]. Minimize and the menu
 * button always stand.
 *
 * The actions are [HeaderAction] models and the fit is the section headers'
 * pure rule ([HeaderFit]). The bar does not own Read aloud (its header row
 * on the page does), so it is told of that model's changes
 * ([actionChanged]) rather than observing it.
 *
 * Neither pill draws a press effect. A repaint of ours just before a
 * capture can be served as the capture's clean frame on whole-screen
 * MediaProjection (the drag lens's icon ring, 2026-10-09), and Capture's tap
 * is exactly that moment.
 *
 * Like the section header, the row is the only writer of its action views'
 * visibility, and the fit runs in [onMeasure] with change-only writes.
 */
internal class DetachedWindowBar(context: Context) : LinearLayout(context), FoldingActionRow {

    /** The bar's own actions. */
    class Actions(
        val capture: HeaderAction,
        val auto: HeaderAction,
        /** The Add to Anki shortcut. */
        val anki: HeaderAction,
        val minimize: HeaderAction,
    ) {
        /** The two that carry the window: named pills, icons where narrow. */
        val pills: List<HeaderAction> get() = listOf(capture, auto)
        val all: List<HeaderAction> get() = listOf(capture, auto, anki, minimize)
    }

    /** Whether Capture and Auto stand without their names (class doc). */
    var pillsIconOnly = false
        private set

    /** The page's Read aloud, the second shortcut; null with no page. */
    private var readAloud: HeaderAction? = null

    /** Whether the shortcuts are offered at all: a result is on show for
     *  them to act on. */
    var shortcutsOffered = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** [action] is the page's Read aloud from here (null takes it off). */
    fun setReadAloud(action: HeaderAction?) {
        val own = checkNotNull(actions) { "the bar's own actions come first" }
        readAloud?.let { viewOf.remove(it)?.let(::removeView) }
        readAloud = action
        if (action != null) {
            // Before Anki: the first shortcut to go is the one furthest
            // from the end, so nothing that stays moves.
            addItem(action, shortcut(), indexOfChild(viewOf.getValue(own.anki)))
            render(action)
        }
        requestLayout()
    }

    /** An action the bar does not own changed its look or whether it is
     *  available: Read aloud, or a row that is only the menu's. An open
     *  menu follows the bar for all of them. */
    fun actionChanged(a: HeaderAction) {
        if (a === readAloud) {
            render(a)
            requestLayout()
        }
        for (l in listeners.toList()) l.onActionChanged(a)
    }

    /** Which of the menu's rows can be tapped changed (a result came or
     *  went): an open menu reads its rows again. */
    fun menuChanged() {
        for (l in listeners.toList()) l.onFoldChanged(this)
    }

    private val dp = resources.displayMetrics.density
    private val itemPx = (ITEM_DP * dp).toInt()
    private val gapPx = (GAP_DP * dp).toInt()

    /** Tapped the menu button (the window opens or closes the menu). */
    var onMoreClick: ((DetachedWindowBar) -> Unit)? = null

    /** The menu button: always shown. */
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
        addItem(actions.minimize, iconButton())
        moreButton = iconButton().apply {
            id = R.id.detachedBarMore
            setImageResource(R.drawable.ic_more_horiz)
            imageTintList = ColorStateList.valueOf(context.themeColor(R.attr.ptTextMuted))
            contentDescription = context.getString(R.string.cd_more_actions)
            setOnClickListener { onMoreClick?.invoke(this@DetachedWindowBar) }
        }
        addView(moreButton, itemParams(itemPx))
        addView(Space(context), LayoutParams(0, 0, 1f))
        addItem(actions.anki, shortcut())
        addItem(actions.auto, pill(filled = false))
        addItem(actions.capture, pill(filled = true))
        for (a in actions.all) {
            a.observer = ::onActionChanged
            render(a)
        }
    }

    /** Capture and Auto where the bar is too narrow even for their icons. */
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
        val pills = actions.pills.filter { it.available }
        val shortcuts = listOfNotNull(actions.anki, readAloud).filter { shortcutsOffered && it.available }
        // Minimize and the menu button always stand.
        val pinned = listOf(actions.minimize).filter { it.available }.map(::widthOf) + itemPx
        fun planOf(candidates: List<HeaderAction>, iconOnly: Boolean): HeaderFit.Plan {
            setPillsIconOnly(iconOnly)
            return HeaderFit.plan(
                content, labelNatural = 0, gap = gapPx,
                foldable = candidates.map(::widthOf), pinned = pinned, moreWidth = 0, overflow = false,
            )
        }
        // The pills lead, so fewer inline than there are pills means one of
        // them would go with its name on: both stand as icons instead, and
        // a bar that narrow has no shortcuts.
        var candidates = pills + shortcuts
        var plan = planOf(candidates, iconOnly = false)
        if (plan.inlineCount < pills.size) {
            candidates = pills
            plan = planOf(candidates, iconOnly = true)
        }
        val inline = candidates.subList(0, plan.inlineCount)
        setShown(viewOf.getValue(actions.minimize), actions.minimize.available)
        for (a in actions.pills + listOfNotNull(actions.anki, readAloud)) setShown(viewOf.getValue(a), a in inline)
        val nowFolded = pills.filter { it !in inline }
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

    /** Capture and Auto with their names, or as round icon buttons the
     *  size of the others. Change-only, like every write of the fit. A
     *  shortcut always keeps its name. */
    private fun setPillsIconOnly(iconOnly: Boolean) {
        if (pillsIconOnly == iconOnly) return
        pillsIconOnly = iconOnly
        val start = (PILL_START_PAD_DP * dp).toInt()
        val end = if (iconOnly) start else (PILL_END_PAD_DP * dp).toInt()
        for (a in actions?.pills ?: return) {
            val v = viewOf.getValue(a) as LinearLayout
            v.getChildAt(1).visibility = if (iconOnly) View.GONE else View.VISIBLE
            v.setPaddingRelative(start, 0, end, 0)
        }
    }

    /** [onHold] runs on a long press of [a]'s button (Anki's send at once).
     *  The menu's row for the same thing is a tap only. */
    fun setOnHold(a: HeaderAction, onHold: () -> Unit) {
        viewOf.getValue(a).setOnLongClickListener {
            onHold()
            true
        }
    }

    private fun itemParams(width: Int) = LayoutParams(width, itemPx).apply { marginStart = gapPx }

    private fun addItem(a: HeaderAction, v: View, index: Int = -1) {
        v.id = a.viewId
        v.setOnClickListener { a.onClick?.invoke(it) }
        addView(v, index, itemParams(if (v is ImageButton) itemPx else LayoutParams.WRAP_CONTENT))
        viewOf[a] = v
    }

    /** The press ripple of every button over the game but Capture and Auto,
     *  which are pills with none (class doc). */
    private fun View.setRipple() {
        val tv = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        setBackgroundResource(tv.resourceId)
    }

    /** An icon-only button: Minimize, the menu button. */
    private fun iconButton(): ImageButton = ImageButton(context).apply {
        val pad = (ICON_PAD_DP * dp).toInt()
        setPadding(pad, pad, pad, pad)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setRipple()
    }

    /** A shortcut: an icon and its name with no outline, so it reads as
     *  lesser than Auto's outline and Capture's fill beside it. */
    private fun shortcut(): LinearLayout = iconAndLabel().apply {
        setPaddingRelative((SHORTCUT_H_PAD_DP * dp).toInt(), 0, (SHORTCUT_H_PAD_DP * dp).toInt(), 0)
        setRipple()
    }

    /** A pill: [filled] on the accent (Capture), else an outline (Auto). */
    private fun pill(filled: Boolean): LinearLayout = iconAndLabel().apply {
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
        setContentColor(this, context.themeColor(if (filled) R.attr.ptAccentOn else R.attr.ptText))
    }

    /** An icon, then its name ([render] fills and colours both). */
    private fun iconAndLabel(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER },
            LayoutParams((PILL_ICON_DP * dp).toInt(), (PILL_ICON_DP * dp).toInt()),
        )
        addView(
            TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, PILL_LABEL_SP)
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

    private fun setContentColor(v: LinearLayout, color: Int) {
        (v.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(color)
        (v.getChildAt(1) as TextView).setTextColor(color)
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
                // A pill keeps the colour it was built with; a shortcut
                // shows its action's state (Read aloud while it reads).
                if (actions?.pills?.contains(a) != true) {
                    setContentColor(v, context.themeColor(if (a.active) R.attr.ptAccent else R.attr.ptText))
                }
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
        private const val SHORTCUT_H_PAD_DP = 8f
    }
}
