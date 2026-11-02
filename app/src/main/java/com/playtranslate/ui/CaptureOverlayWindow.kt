package com.playtranslate.ui

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.playtranslate.OneShotOverlayData
import com.playtranslate.OverlayMode
import com.playtranslate.OverlaySurface
import com.playtranslate.Prefs
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.overlayThemedContext
import kotlinx.coroutines.CoroutineScope

/**
 * A capture's presentation on the game display when the app's results page
 * is the panel (two screens): the same game-screen half the single-screen
 * sheet hosts, with no panel. A full-screen touchable window holding the
 * [CaptureOverlayLayer] and the mode pill ([OverlayModePill]), created only
 * in a box mode. The gestures are the sheet's: a touch sequence that begins
 * on the pill is the pill's; any other tap hands the screen back
 * ([onDismissRequested]). When a mode lands with nothing to draw (readings
 * that came back empty, a fill whose every translation was blank) the host
 * is told ([onNothingToShow]) and takes the window down rather than leave a
 * touchable window with nothing in it over the game.
 */
internal class CaptureOverlayWindow(
    displayCtx: Context,
    scope: CoroutineScope,
    private val prefs: Prefs,
    private val onDismissRequested: () -> Unit,
    private val onNothingToShow: () -> Unit,
    onModePicked: (OverlayMode) -> Unit,
) : FrameLayout(overlayThemedContext(displayCtx)) {

    val layer = CaptureOverlayLayer(context, scope, prefs)
    private val pill = OverlayModePill(context)
    private var pillTouch = false
    private val dp = resources.displayMetrics.density

    init {
        addView(layer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            pill,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = (PILL_TOP_DP * dp).toInt()
                marginEnd = (PILL_END_DP * dp).toInt()
            },
        )
        pill.onSelect = onModePicked
        pill.onCollapsedChanged = { prefs.captureOverlayPillCollapsed = it }
        layer.onBoxesSettled = { has -> if (!has) onNothingToShow() }
        // Under the status bar, not behind it.
        setOnApplyWindowInsetsListener { _, insets ->
            val top = ContentInsets.of(insets).top + (PILL_TOP_DP * dp).toInt()
            (pill.layoutParams as? LayoutParams)?.let { lp ->
                if (lp.topMargin != top) {
                    lp.topMargin = top
                    pill.requestLayout()
                }
            }
            insets
        }
    }

    /** The mode the window paints in. */
    val mode: OverlayMode get() = layer.mode

    /** Nothing is drawn and nothing is on its way: a window the host should
     *  not keep over the game. */
    val showsNothing: Boolean get() = !layer.hasBoxes && !layer.pending

    fun bind(data: OneShotOverlayData, sourceLangId: SourceLangId?, mode: OverlayMode) {
        layer.bind(data, sourceLangId, mode)
        renderPill()
    }

    fun update(data: OneShotOverlayData) {
        layer.update(data)
        renderPill()
    }

    fun setMode(mode: OverlayMode) {
        layer.setMode(mode)
        renderPill()
    }

    fun release() = layer.release()

    private fun renderPill() {
        val hint = SourceLanguageProfiles[prefs.sourceLangId].hintTextKind
        val offered = OverlayMode.offered(OverlaySurface.CAPTURE, hint).filter { it in layer.supportedModes }
        pill.render(
            options = offered.map { context.getString(it.labelRes(hint)) to it },
            selected = layer.mode,
            collapsed = prefs.captureOverlayPillCollapsed,
        )
    }

    private fun hitsPill(ev: MotionEvent): Boolean =
        pill.visibility == View.VISIBLE &&
            ev.x >= pill.left && ev.x < pill.right && ev.y >= pill.top && ev.y < pill.bottom

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // A DOWN starts a sequence afresh, whatever an unfinished one claimed.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) pillTouch = hitsPill(ev)
        if (pillTouch) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                pillTouch = false
            }
            return super.dispatchTouchEvent(ev)
        }
        // Any other tap on the game screen hands it back.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) onDismissRequested()
        return true
    }

    companion object {
        const val PILL_TOP_DP = 12
        const val PILL_END_DP = 16
    }
}
