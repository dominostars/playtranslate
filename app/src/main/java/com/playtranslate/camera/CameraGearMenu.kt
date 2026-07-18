package com.playtranslate.camera

import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.animation.DecelerateInterpolator
import com.playtranslate.OverlayMode
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.language.HintTextKind
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.ocr.registry.ocrLabel
import com.playtranslate.themeColor

/**
 * The camera control pill's gear menu: tapping the settings gear grows the
 * pill downward (and leftward to the floating icon menu's panel width) to
 * reveal the rows mirroring that menu's gear panel — source language, OCR
 * engine, and (for reading-capable languages) Overlays. Rows carry the
 * floating panel's title/value shape ([R.layout.settings_row_value],
 * compacted, chevron hidden) recolored for the camera's white-on-scrim
 * chrome. The gear takes the accent tint while open; tapping it again or
 * anywhere outside (a full-screen scrim under the pill) animates closed.
 *
 * Row taps close the menu first, then fire the caller's action — the
 * caller owns the pickers and the refresh-with-new-selections routing.
 *
 * Main thread only.
 */
class CameraGearMenu(
    private val activity: Activity,
    private val pill: LinearLayout,
    private val iconRow: View,
    private val menuHost: LinearLayout,
    private val gearButton: ImageButton,
    private val controlsHost: ViewGroup,
    private val onLanguageRow: () -> Unit,
    private val onOcrRow: () -> Unit,
    /** Cycle the overlay flavor (Translation ↔ Furigana/Pinyin). The row
     *  mirrors the floating menu's Overlays row: cycles IN PLACE, updating
     *  its value without closing the menu. */
    private val onCycleOverlayMode: () -> Unit,
) {
    private val prefs = Prefs(activity)
    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    /** The floating icon menu's settings-panel row width: its card expands
     *  to 300dp, minus the card chrome (2 × 11dp padding + 48dp secondary
     *  lane + 11dp lane gap) = 219dp of row space. Matching it keeps the
     *  two gear panels reading as the same surface. */
    private val expandedWidthPx = dp(300 - (2 * 11 + 48 + 11))

    private var expanded = false
    private var animator: ValueAnimator? = null
    private var scrim: View? = null

    /** True while the menu is expanded — the activity's back handling closes
     *  an open menu before any crop/frozen/exit behavior (it's modal). */
    val isOpen: Boolean get() = expanded

    init {
        gearButton.setOnClickListener { toggle() }
        // Clip children to the background's own outline so the rows' ripples
        // follow the pill's rounded corners (visible on the bottom row).
        // Derived from the drawable, not a duplicated radius — restyle the
        // background and the clip follows.
        pill.clipToOutline = true
        // The controller hides the whole pill for crop mode (and anything
        // else that takes the screen) — an open menu must not survive into
        // a hidden pill and reappear expanded later.
        pill.viewTreeObserver.addOnGlobalLayoutListener {
            if (expanded && !pill.isShown) closeInstant()
        }
    }

    private fun toggle() {
        if (expanded) close() else open()
    }

    private fun open() {
        if (expanded) return
        expanded = true
        buildRows()
        gearButton.imageTintList =
            ColorStateList.valueOf(activity.themeColor(R.attr.ptAccent))
        addScrim()
        menuHost.visibility = View.VISIBLE
        menuHost.measure(
            View.MeasureSpec.makeMeasureSpec(expandedWidthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        animatePill(
            fromW = pill.width, fromH = pill.height,
            toW = expandedWidthPx, toH = iconRow.height + menuHost.measuredHeight,
        )
    }

    fun close() {
        if (!expanded) return
        expanded = false
        gearButton.imageTintList = ColorStateList.valueOf(Color.WHITE)
        removeScrim()
        animatePill(
            fromW = pill.width, fromH = pill.height,
            toW = iconRow.width, toH = iconRow.height,
        ) {
            menuHost.visibility = View.GONE
            wrapPill()
        }
    }

    /** Snap closed with no animation — the pill just went invisible. */
    private fun closeInstant() {
        expanded = false
        animator?.cancel()
        gearButton.imageTintList = ColorStateList.valueOf(Color.WHITE)
        removeScrim()
        menuHost.visibility = View.GONE
        wrapPill()
    }

    fun destroy() = closeInstant()

    /** Back to intrinsic sizing so flavor-slot changes re-wrap naturally. */
    private fun wrapPill() {
        val lp = pill.layoutParams
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        pill.layoutParams = lp
    }

    private fun animatePill(
        fromW: Int, fromH: Int, toW: Int, toH: Int, onEnd: (() -> Unit)? = null,
    ) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val lp = pill.layoutParams
                lp.width = (fromW + (toW - fromW) * f).toInt()
                lp.height = (fromH + (toH - fromH) * f).toInt()
                pill.layoutParams = lp
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled) onEnd?.invoke()
                }
            })
            start()
        }
    }

    /** Rebuilt on every open so the values reflect the CURRENT selections. */
    private fun buildRows() {
        menuHost.removeAllViews()
        val inflater = LayoutInflater.from(activity)
        val languageName = prefs.sourceLangId.displayName()
        val ocrName = OcrModelManager.selectedBackend(activity, prefs.sourceLangId)
            ?.ocrLabel(activity) ?: "ML Kit"
        menuHost.addView(divider())
        addRow(inflater, activity.getString(R.string.floating_menu_panel_language), languageName) {
            close()
            onLanguageRow()
        }
        menuHost.addView(divider())
        addRow(inflater, activity.getString(R.string.floating_menu_panel_ocr), ocrName) {
            close()
            onOcrRow()
        }
        // Overlays row (the floating panel's): shown when the language has a
        // reading mode to switch to; tapping cycles the flavor IN PLACE —
        // the menu stays open and the value text follows.
        val hintKind = SourceLanguageProfiles[prefs.sourceLangId].hintTextKind
        if (hintKind != HintTextKind.NONE) {
            menuHost.addView(divider())
            val row = addRow(
                inflater,
                activity.getString(R.string.floating_menu_panel_overlays),
                overlayModeLabel(hintKind),
            ) {}
            val value = row.findViewById<TextView>(R.id.tvRowValue)
            row.setOnClickListener {
                onCycleOverlayMode()
                value.text = overlayModeLabel(hintKind)
            }
        }
    }

    /** User-facing name of the CURRENT overlay mode, mirroring the floating
     *  panel's labeling (the reading mode reads Pinyin for Pinyin languages,
     *  Furigana otherwise). */
    private fun overlayModeLabel(hintKind: HintTextKind): String =
        when {
            prefs.overlayMode == OverlayMode.TRANSLATION ->
                activity.getString(R.string.overlay_mode_option_translation)
            hintKind == HintTextKind.PINYIN ->
                activity.getString(R.string.overlay_mode_option_pinyin)
            else -> activity.getString(R.string.overlay_mode_option_furigana)
        }

    /** The floating panel's value-row shape, recolored for white-on-scrim. */
    private fun addRow(
        inflater: LayoutInflater, title: String, value: String, onClick: () -> Unit,
    ): View {
        val row = inflater.inflate(R.layout.settings_row_value, menuHost, false)
        row.setPaddingRelative(dp(12), row.paddingTop, dp(12), row.paddingBottom)
        row.findViewById<TextView>(R.id.tvRowTitle).apply {
            text = title
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
        }
        row.findViewById<TextView>(R.id.tvRowValue).apply {
            text = value
            setTextColor(Color.argb(200, 255, 255, 255))
        }
        row.findViewById<ImageView>(R.id.ivRowChevron).visibility = View.GONE
        row.setOnClickListener { onClick() }
        menuHost.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)),
        )
        return row
    }

    private fun divider(): View = View(activity).apply {
        setBackgroundColor(Color.argb(56, 255, 255, 255))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1.coerceAtLeast((1 * density).toInt()),
        ).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
        }
    }

    /** Full-screen tap-catcher: any tap outside the pill closes the menu.
     *  Appended above every other control (so the shutter and back button
     *  can't act under an open menu), with the pill brought above IT so the
     *  menu rows and icon row stay interactive. */
    private fun addScrim() {
        if (scrim != null) return
        val v = View(activity).apply {
            isClickable = true
            setOnClickListener { close() }
        }
        controlsHost.addView(
            v,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        pill.bringToFront()
        scrim = v
    }

    private fun removeScrim() {
        scrim?.let { controlsHost.removeView(it) }
        scrim = null
    }
}
