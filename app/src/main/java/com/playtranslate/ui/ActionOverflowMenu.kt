package com.playtranslate.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.playtranslate.R
import com.playtranslate.themeColor

/**
 * The table behind a row's ⋯ ([FoldingActionRow.moreAnchor]; a section header's
 * [SectionHeaderRow], the detached window's bar): one
 * row per action the header folded, in the header's order, each with the
 * action's icon at the start and its short name, and its value at the end
 * when it has one ([HeaderAction.detail]). A row that offers more than its
 * folded actions lists them in groups, a hairline between and a heading
 * over each that has one ([FoldingActionRow.menuGroups]). A row runs exactly
 * what the inline button runs, from the same [HeaderAction]: a tap closes
 * the menu and then runs [HeaderAction.onClick], with ⋯ as the action's
 * anchor, so a popover it opens (the text-size picker) points there. Two
 * things are the row's to say of each action: one that cannot run now is
 * dimmed in its place and takes no tap ([FoldingActionRow.menuEnabled]), and
 * a switch leaves the menu up ([FoldingActionRow.menuStaysOpen]).
 *
 * Shown by the surface's [PopoverHost], so it inherits the in-window,
 * modal, one-at-a-time rules. While open it follows its row: a row
 * re-paints when its action's look changes (a speak that ends drops its
 * accent, a result that arrives undims its rows), and the menu closes when
 * the list itself changes under it (the header's folded set). A row does
 * nothing unless its menu is still the one showing, so a click that lands
 * after dismissal can't run an action twice.
 *
 * Built in code (the over-game panel's plain inflater drops `app:`
 * attributes). A new instance per open.
 */
class ActionOverflowMenu(private val row: FoldingActionRow) : PopoverContent, FoldingActionRow.Listener {

    private var host: PopoverHost? = null
    private var items: List<HeaderAction> = emptyList()
    private val rowViews = LinkedHashMap<HeaderAction, View>()

    override fun createView(ctx: Context, host: PopoverHost): View {
        this.host = host
        val groups = row.menuGroups().filter { it.actions.isNotEmpty() }
        items = listed(groups)
        row.addListener(this)
        val density = ctx.resources.displayMetrics.density
        val padV = (LIST_V_PAD_DP * density).toInt()
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, padV, 0, padV)
            for ((i, group) in groups.withIndex()) {
                // A hairline rules one group off from the next.
                if (i > 0) {
                    addView(
                        View(ctx).apply { setBackgroundColor(ctx.themeColor(R.attr.ptDivider)) },
                        LinearLayout.LayoutParams(MATCH, maxOf(1, density.toInt())).apply {
                            topMargin = (RULE_V_MARGIN_DP * density).toInt()
                            bottomMargin = (RULE_V_MARGIN_DP * density).toInt()
                        },
                    )
                }
                group.title?.let { addView(buildHeading(ctx, it), LinearLayout.LayoutParams(MATCH, WRAP)) }
                for (a in group.actions) {
                    val v = buildRow(ctx, a)
                    rowViews[a] = v
                    addView(v, LinearLayout.LayoutParams(MATCH, WRAP))
                }
            }
        }
        // The host caps the card at what it can show (a split-screen or
        // freeform window can be shorter than four rows): the rows scroll
        // rather than clip, so every folded action stays reachable.
        return ScrollView(ctx).apply {
            accessibilityPaneTitle = ctx.getString(R.string.cd_more_actions)
            addView(list, FrameLayout.LayoutParams(MATCH, WRAP))
        }
    }

    override fun cardWidth(ctx: Context, natural: Int): Int {
        val density = ctx.resources.displayMetrics.density
        return natural.coerceIn((MIN_W_DP * density).toInt(), (MAX_W_DP * density).toInt())
    }

    override fun navActions(): List<NavAction> = rowViews.values.map { NavAction(it) }

    override fun onDismissed() {
        row.removeListener(this)
        host = null
    }

    /** The rows a menu of [groups] lists, in order. */
    private fun listed(groups: List<MenuGroup>): List<HeaderAction> = groups.flatMap { it.actions }

    override fun onFoldChanged(row: FoldingActionRow) {
        // This menu's own row, whichever row told of the change: the
        // detached window's menu lists more than its bar folded.
        if (listed(this.row.menuGroups()) != items) {
            host?.takeIf { it.content === this }?.dismiss()
            return
        }
        // The same rows: which of them can be tapped may be what changed.
        for ((a, v) in rowViews) bind(v, a)
    }

    override fun onActionChanged(action: HeaderAction) {
        rowViews[action]?.let { bind(it, action) }
    }

    /** This menu is the popover showing right now. */
    private val isCurrent: Boolean get() = host?.content === this

    /** A group's heading: small and muted, starting where the rows' icons
     *  start. */
    private fun buildHeading(ctx: Context, title: CharSequence): View {
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        return TextView(ctx).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HEADING_SP)
            setTextColor(ctx.themeColor(R.attr.ptTextMuted))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            isAccessibilityHeading = true
            setPaddingRelative(dp(ROW_H_PAD_DP), dp(HEADING_TOP_PAD_DP), dp(ROW_H_PAD_DP), dp(HEADING_BOTTOM_PAD_DP))
        }
    }

    private fun buildRow(ctx: Context, a: HeaderAction): View {
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        val icon = ImageView(ctx).apply { id = R.id.overflowRowIcon }
        val label = TextView(ctx).apply {
            id = R.id.overflowRowLabel
            setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_SP)
            setTextColor(ctx.themeColor(R.attr.ptText))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val v = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ROW_H_DP)
            setPaddingRelative(dp(ROW_H_PAD_DP), 0, dp(ROW_H_PAD_DP), 0)
            val tv = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            isClickable = true
            isFocusable = true
            addView(icon, LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)))
            addView(
                label,
                LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(ICON_GAP_DP) },
            )
            // The action's value, at the row's end ([HeaderAction.detail]).
            addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f))
            addView(
                TextView(ctx).apply {
                    id = R.id.overflowRowDetail
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, DETAIL_SP)
                    setTextColor(ctx.themeColor(R.attr.ptTextMuted))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(ICON_GAP_DP) },
            )
        }
        v.setOnClickListener {
            val h = host ?: return@setOnClickListener
            if (!isCurrent || !row.menuEnabled(a)) return@setOnClickListener
            if (!row.menuStaysOpen(a)) h.dismiss()
            a.onClick?.invoke(row.moreAnchor)
        }
        bind(v, a)
        return v
    }

    private fun bind(v: View, a: HeaderAction) {
        val ctx = v.context
        val enabled = row.menuEnabled(a)
        v.isEnabled = enabled
        v.alpha = if (enabled) 1f else DISABLED_ALPHA
        v.findViewById<ImageView>(R.id.overflowRowIcon).apply {
            // No icon: its place stays, so the name lines up with the rest.
            if (a.icon != 0) setImageResource(a.icon) else setImageDrawable(null)
            imageTintList = ColorStateList.valueOf(
                ctx.themeColor(if (a.active) R.attr.ptAccent else R.attr.ptTextMuted),
            )
        }
        v.findViewById<TextView>(R.id.overflowRowLabel).text = a.label
        v.findViewById<TextView>(R.id.overflowRowDetail).apply {
            text = a.detail
            visibility = if (a.detail.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        const val ROW_H_DP = 48f
        const val ROW_H_PAD_DP = 16f
        const val ICON_DP = 24f
        const val ICON_GAP_DP = 16f
        const val LABEL_SP = 15f
        const val DETAIL_SP = 13f
        const val RULE_V_MARGIN_DP = 4f
        const val HEADING_SP = 12f
        const val HEADING_TOP_PAD_DP = 8f
        const val HEADING_BOTTOM_PAD_DP = 4f
        const val DISABLED_ALPHA = 0.38f
        const val LIST_V_PAD_DP = 8f
        const val MIN_W_DP = 160f
        const val MAX_W_DP = 280f
    }
}
