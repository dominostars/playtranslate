package com.playtranslate.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import com.playtranslate.R
import com.playtranslate.themeColor

/**
 * The design-system grouped card (ptCard fill, 1dp ptDivider stroke,
 * pt_radius corners, children clipped to the rounding) as a code-built plain
 * view — replacing the `language_list_section.xml` MaterialCardView, whose
 * `app:` attributes a plain [android.view.LayoutInflater] silently drops.
 * One implementation for every host: the settings-style pickers inside
 * Activities AND overlay surfaces (the floating [OverlayWorkspace]), so the
 * grouped-card look can't drift between them.
 *
 * The card IS the row container: add rows (and inset dividers) directly.
 * [radiusPx] replaces the old `card.radius` reads that kept a selected row's
 * first/last corner rounding in step with the card's.
 */
class PtGroupCard(ctx: Context) : LinearLayout(ctx) {

    val radiusPx: Float = ctx.resources.getDimension(R.dimen.pt_radius)

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(ctx.themeColor(R.attr.ptCard))
            setStroke(
                (1 * ctx.resources.displayMetrics.density).toInt(),
                ctx.themeColor(R.attr.ptDivider),
            )
            cornerRadius = radiusPx
        }
        // Clip rows (and their rectangular ripples) to the rounded corners —
        // the Material card left first/last rows to fake their own rounding.
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }
}
