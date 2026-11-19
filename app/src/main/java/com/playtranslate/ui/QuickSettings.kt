package com.playtranslate.ui

import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.playtranslate.R
import com.playtranslate.themeColor

/**
 * One row of the floating controls' quick settings. The panel behind the
 * quick menu's gear ([FloatingIconMenu]) and the detached window's Settings
 * popover list the same rows from the same models, which
 * [com.playtranslate.OverlayUiController] builds once for both, so a row
 * reads and acts the same wherever it is shown.
 *
 * A row with a [value] shows it at its end, and setting [value] updates the
 * row on show (the Overlays row cycling, MangaOCR flipping) with no rebuild.
 * A row without one is a link with [icon] at its end.
 */
class QuickSetting(
    val id: Id,
    val title: CharSequence,
    value: CharSequence? = null,
    @DrawableRes val icon: Int? = null,
    private val onClick: (QuickSetting) -> Unit,
) {
    enum class Id { LANGUAGE, OCR, MANGA_OCR, OVERLAYS, OPEN_APP, DETACH }

    var value: CharSequence? = value
        set(v) {
            field = v
            valueView?.text = v
        }

    /** The row's value text while a host shows it ([QuickSettingsRows]). */
    internal var valueView: TextView? = null

    /** What a tap on the row does. */
    fun click() = onClick(this)
}

/** The quick settings' rows as views: one implementation for both hosts. */
object QuickSettingsRows {

    /** The height of one row. */
    const val CELL_HEIGHT_DP = 52

    private const val ROW_H_PAD_DP = 12

    /**
     * Fills [container] with [rows], a hairline between neighbours: a value
     * row is title + right-aligned value (chevron hidden), a link row title
     * + its icon tinted [iconTint]. `app:tint` in the layouts is
     * AppCompat-only and ignored under an overlay context, hence the tint
     * in code.
     */
    fun populate(container: LinearLayout, rows: List<QuickSetting>, iconTint: Int) {
        val ctx = container.context
        val dp = ctx.resources.displayMetrics.density
        val inflater = LayoutInflater.from(ctx)
        container.removeAllViews()
        rows.forEachIndexed { i, setting ->
            if (i > 0) {
                container.addView(
                    View(ctx).apply { setBackgroundColor(ctx.themeColor(R.attr.ptDivider)) },
                    // Inset to match the compacted row's horizontal padding.
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * dp).toInt())
                        .apply { marginStart = (ROW_H_PAD_DP * dp).toInt() },
                )
            }
            val row = if (setting.icon == null) {
                inflater.inflate(R.layout.settings_row_value, container, false).also { v ->
                    setting.valueView = v.findViewById<TextView>(R.id.tvRowValue).apply { text = setting.value }
                    v.findViewById<View>(R.id.ivRowChevron).visibility = View.GONE
                }
            } else {
                inflater.inflate(R.layout.settings_row_link, container, false).also { v ->
                    v.findViewById<ImageView>(R.id.ivRowIcon).apply {
                        setImageResource(setting.icon)
                        imageTintList = ColorStateList.valueOf(iconTint)
                    }
                }
            }
            // Fit the settings row into a narrow panel: lighter horizontal
            // padding and a single-line title (ellipsized) so it never wraps
            // into the short cell.
            val hPad = (ROW_H_PAD_DP * dp).toInt()
            row.setPaddingRelative(hPad, row.paddingTop, hPad, row.paddingBottom)
            row.findViewById<TextView>(R.id.tvRowTitle).apply {
                text = setting.title
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            row.setTag(R.id.quickSettingRow, setting.id)
            row.setOnClickListener { setting.click() }
            container.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (CELL_HEIGHT_DP * dp).toInt()),
            )
        }
    }
}
