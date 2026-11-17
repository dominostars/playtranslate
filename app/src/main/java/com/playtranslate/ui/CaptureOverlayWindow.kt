package com.playtranslate.ui

import android.content.Context
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import com.playtranslate.OneShotOverlayData
import com.playtranslate.OverlayMode
import com.playtranslate.OverlaySurface
import com.playtranslate.Prefs
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlayThemedContext
import kotlinx.coroutines.CoroutineScope

/**
 * A capture's presentation on the game display when the app's results page
 * is the panel (two screens): the same game-screen half the single-screen
 * sheet hosts, with no panel. A full-screen touchable window holding the
 * [CaptureOverlayLayer] and, above it at the top end, the mode pill
 * ([OverlayModePill]), the one-screen sheet's control: its segments pick
 * the mode ([onModePicked]; the host re-flavors the window in place, and
 * None, which on two screens is Show on screen off, takes it down), its X
 * collapses it to a disc, remembered as on one screen. Created only in a
 * box mode, and up only while the app's results page is (the app takes it
 * down when it stops): the page puts it up as a capture lands while Show
 * on screen is on, or the capture asked for a mode of its own, and the
 * page's toggle takes it down and puts it back.
 * The gestures are the sheet's: a touch
 * sequence that begins on the pill is the pill's, a touch the layer claims
 * (a word of Definitions, a card to close) is the layer's, and any other
 * tap hands the screen back ([onDismissRequested]). When a mode lands with
 * nothing to draw (readings that came back empty, a fill whose every
 * translation was blank) the host is told ([onNothingToShow]) and takes the
 * window down rather than leave a touchable window over the game with
 * nothing drawn in it.
 *
 * In Definitions the window holds key focus ([wantsFocus]; the host sets
 * the window's flags): the D-pad and A drive the words, Back closes a card
 * and with none hands the screen back, acted on at the UP of a DOWN this
 * window saw, or through the back-invoked callback where the platform no
 * longer sends the key. A key still held when the window goes keeps it as
 * an invisible key sink until the release ([remove]), so the release does
 * not land in the game.
 *
 * A released window ([release], which every way its host disowns it calls
 * first) asks its host for nothing more: a segment's click is posted after
 * its UP and a window's removal lands after its host has moved on, so a
 * window already replaced could otherwise switch or dismiss the one that
 * replaced it.
 */
internal class CaptureOverlayWindow(
    displayCtx: Context,
    wm: WindowManager,
    overlayHost: OverlayHost,
    displayId: Int,
    scope: CoroutineScope,
    private val prefs: Prefs,
    private val onDismissRequested: () -> Unit,
    private val onNothingToShow: () -> Unit,
    private val onModePicked: (OverlayMode) -> Unit,
) : FrameLayout(overlayThemedContext(displayCtx)) {

    val layer = CaptureOverlayLayer(
        context, scope, prefs,
        lookupHost = CaptureOverlayLayer.LookupHost(displayCtx, wm, overlayHost, displayId),
    )

    /** The mode control, above the layer. */
    val pill = OverlayModePill(context)

    /** A touch sequence that began on the pill: the window's own rules
     *  stand down until it ends. */
    private var pillTouch = false

    /** Disowned by its host ([release]): nothing reaches the host, and no
     *  tap on its pill is remembered, any more. */
    private var released = false

    /** Pair bookkeeping and the removal linger ([remove]). */
    private val keyGuard = WindowKeyPairGuard()
    private var backDownSeen = false
    private var backCallback: Any? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        addView(layer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(pill, OverlayModePill.hostLayoutParams(context))
        pill.onSelect = { mode -> if (!released) onModePicked(mode) }
        pill.onCollapsedChanged = { collapsed -> if (!released) prefs.captureOverlayPillCollapsed = collapsed }
        layer.onBoxesSettled = { has -> if (!has) onNothingToShow() }
        // A card that ended by its own means hands the screen back, as the
        // sheet closes for it.
        layer.onSessionEnded = { requestDismiss() }
        // The pill sits under the status bar, not behind it.
        setOnApplyWindowInsetsListener { _, insets ->
            pill.placeUnder(ContentInsets.of(insets).top)
            insets
        }
    }

    /** The mode the window paints in. */
    val mode: OverlayMode get() = layer.mode

    /** Definitions holds key focus; the host sets the window's flags. */
    val wantsFocus: Boolean get() = layer.wantsFocus

    /** Nothing is drawn and nothing is on its way: a window the host should
     *  not keep over the game. */
    val showsNothing: Boolean get() = !layer.hasBoxes && !layer.pending

    fun bind(
        data: OneShotOverlayData,
        sourceLangId: SourceLangId?,
        mode: OverlayMode,
        screenshotPath: String?,
        capturedAtMs: Long,
    ) {
        layer.bind(data, sourceLangId, mode, screenshotPath, capturedAtMs)
        renderPill()
    }

    fun update(data: OneShotOverlayData) {
        layer.update(data)
        renderPill()
    }

    /** The capture's result landed after its boxes went up: the frame its
     *  cards cite ([CaptureOverlayLayer.setFrame]). */
    fun setFrame(screenshotPath: String?, capturedAtMs: Long) = layer.setFrame(screenshotPath, capturedAtMs)

    fun setMode(mode: OverlayMode) {
        layer.setMode(mode)
        renderPill()
    }

    /** The host disowns the window: the layer lets go, and nothing more is
     *  asked of the host. */
    fun release() {
        released = true
        layer.release()
    }

    /** The modes a capture offers that the layer paints, the window's own
     *  marked, as the one-screen sheet's pill shows them. */
    private fun renderPill() {
        val hint = SourceLanguageProfiles[prefs.sourceLangId].hintTextKind
        pill.renderModes(
            OverlayMode.offered(OverlaySurface.CAPTURE, hint).filter { it in layer.supportedModes },
            hint,
            selected = layer.mode,
            collapsed = prefs.captureOverlayPillCollapsed,
        )
    }

    /** A DOWN inside the pill's bounds (this window's coordinates). */
    private fun hitsPill(ev: MotionEvent): Boolean =
        pill.visibility == View.VISIBLE &&
            ev.x >= pill.left && ev.x < pill.right && ev.y >= pill.top && ev.y < pill.bottom

    /** The host is asked to take the window down; a released one asks nothing. */
    private fun requestDismiss() {
        if (!released) onDismissRequested()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (keyGuard.isLingering) return true
        // A DOWN starts a sequence afresh, whatever an unfinished one claimed.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) pillTouch = hitsPill(ev)
        if (pillTouch) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                pillTouch = false
            }
            return super.dispatchTouchEvent(ev)
        }
        // The layer's own (a word of Definitions, a card to close).
        if (layer.onTouch(ev)) return true
        // Any other tap on the game screen hands it back.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) requestDismiss()
        return true
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (keyGuard.isLingering) return keyGuard.lingerKey(ev)
        keyGuard.track(ev)
        if (ControllerKeys.isBack(ev.keyCode)) {
            when (ev.action) {
                KeyEvent.ACTION_DOWN -> if (ev.repeatCount == 0) backDownSeen = true
                KeyEvent.ACTION_UP -> if (backDownSeen) {
                    backDownSeen = false
                    onBack()
                }
            }
            return true
        }
        if (layer.onKey(ev)) return true
        return super.dispatchKeyEvent(ev)
    }

    /** Back: a Definitions card closes; with none the screen goes back. */
    private fun onBack() {
        if (!layer.onBack()) requestDismiss()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (wantsFocus) requestFocus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerBackCallback()
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) unregisterBackCallback()
        super.onDetachedFromWindow()
    }

    /** Back as the platform delivers it to an app given callback-based back
     *  (target 36 on Android 16): the key above never arrives then. The two
     *  paths are exclusive by the platform's mode, so a press runs [onBack]
     *  once. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun registerBackCallback() {
        val dispatcher = findOnBackInvokedDispatcher() ?: return
        val callback = OnBackInvokedCallback { if (!keyGuard.isLingering) onBack() }
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        backCallback = callback
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun unregisterBackCallback() {
        val callback = backCallback as? OnBackInvokedCallback ?: return
        backCallback = null
        findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(callback)
    }

    /**
     * Take the window down through [removeWindow]. While a key that went
     * down on this window is still held, it first lingers as an invisible,
     * untouchable key sink until that key comes up ([WindowKeyPairGuard]),
     * so the release does not land in the game.
     */
    fun remove(wm: WindowManager, params: WindowManager.LayoutParams, removeWindow: () -> Unit) {
        if (!keyGuard.hasPendingDown) {
            removeWindow()
            return
        }
        visibility = INVISIBLE
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { wm.updateViewLayout(this, params) } catch (_: Exception) {}
        keyGuard.beginLinger(removeWindow)
    }
}
