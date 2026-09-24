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
 * The table behind a section header's ⋯ ([SectionHeaderRow.moreButton]): one
 * row per action the header folded, in the header's order, each with the
 * action's icon at the start and its short name. A row runs exactly what
 * the inline button runs, from the same [HeaderAction]: a tap closes the
 * menu and then runs [HeaderAction.onClick], a long-press closes it and runs
 * [HeaderAction.onLongClick] (the Anki one-tap). Either way the action's
 * anchor is ⋯, so a popover it opens (the text-size picker) points there.
 *
 * Shown by the surface's [PopoverHost], so it inherits the in-window,
 * modal, one-at-a-time rules. While open it follows its row: a row
 * re-paints when its action's look changes (a speak that ends drops its
 * accent), and the menu closes when the header's folded set changes under
 * it. A row does nothing unless its menu is still the one showing, so a
 * click that lands after dismissal (a controller hold's key-up on a row the
 * long-press already closed) can't run an action twice.
 *
 * Built in code (the over-game panel's plain inflater drops `app:`
 * attributes). A new instance per open.
 */
class ActionOverflowMenu(private val row: SectionHeaderRow) : PopoverContent, SectionHeaderRow.Listener {

    private var host: PopoverHost? = null
    private var items: List<HeaderAction> = emptyList()
    private val rowViews = LinkedHashMap<HeaderAction, View>()

    override fun createView(ctx: Context, host: PopoverHost): View {
        this.host = host
        items = row.foldedActions()
        row.addListener(this)
        val density = ctx.resources.displayMetrics.density
        val padV = (LIST_V_PAD_DP * density).toInt()
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, padV, 0, padV)
            for (a in items) {
                val v = buildRow(ctx, a)
                rowViews[a] = v
                addView(v, LinearLayout.LayoutParams(MATCH, WRAP))
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

    override fun navActions(): List<NavAction> =
        rowViews.map { (a, v) -> NavAction(v, holdActivates = a.onLongClick != null) }

    override fun onDismissed() {
        row.removeListener(this)
        host = null
    }

    override fun onFoldChanged(row: SectionHeaderRow) {
        if (row.foldedActions() != items) host?.takeIf { it.content === this }?.dismiss()
    }

    override fun onActionChanged(action: HeaderAction) {
        rowViews[action]?.let { bind(it, action) }
    }

    /** This menu is the popover showing right now. */
    private val isCurrent: Boolean get() = host?.content === this

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
        }
        v.setOnClickListener {
            val h = host ?: return@setOnClickListener
            if (!isCurrent) return@setOnClickListener
            h.dismiss()
            a.onClick?.invoke(row.moreButton)
        }
        if (a.onLongClick != null) {
            v.setOnLongClickListener {
                val h = host ?: return@setOnLongClickListener false
                if (!isCurrent) return@setOnLongClickListener false
                h.dismiss()
                a.onLongClick?.invoke(row.moreButton)
                true
            }
        }
        bind(v, a)
        return v
    }

    private fun bind(v: View, a: HeaderAction) {
        val ctx = v.context
        v.findViewById<ImageView>(R.id.overflowRowIcon).apply {
            setImageResource(a.icon)
            imageTintList = ColorStateList.valueOf(
                ctx.themeColor(if (a.active) R.attr.ptAccent else R.attr.ptTextMuted),
            )
        }
        v.findViewById<TextView>(R.id.overflowRowLabel).text = a.label
    }

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        const val ROW_H_DP = 48f
        const val ROW_H_PAD_DP = 16f
        const val ICON_DP = 24f
        const val ICON_GAP_DP = 16f
        const val LABEL_SP = 15f
        const val LIST_V_PAD_DP = 8f
        const val MIN_W_DP = 160f
        const val MAX_W_DP = 280f
    }
}
