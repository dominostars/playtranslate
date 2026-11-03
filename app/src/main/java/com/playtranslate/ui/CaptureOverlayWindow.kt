package com.playtranslate.ui

import android.content.Context
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import com.playtranslate.OneShotOverlayData
import com.playtranslate.OverlayMode
import com.playtranslate.Prefs
import com.playtranslate.language.SourceLangId
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlayThemedContext
import kotlinx.coroutines.CoroutineScope

/**
 * A capture's presentation on the game display when the app's results page
 * is the panel (two screens): the same game-screen half the single-screen
 * sheet hosts, with no panel. A full-screen touchable window holding the
 * [CaptureOverlayLayer], created only in a box mode, with no mode control:
 * the app's results page carries the row that picks the mode, and the
 * window is up only while that page is (the app takes it down when it
 * stops). The gestures are the sheet's: a touch the layer claims (a word
 * of Definitions, a card to close) is the layer's, and any other tap hands
 * the screen back ([onDismissRequested]). When a mode lands with nothing to
 * draw (readings that came back empty, a fill whose every translation was
 * blank) the host is told ([onNothingToShow]) and takes the window down
 * rather than leave a touchable window with nothing in it over the game.
 *
 * In Definitions the window holds key focus ([wantsFocus]; the host sets
 * the window's flags): the D-pad and A drive the words, Back closes a card
 * and with none hands the screen back, acted on at the UP of a DOWN this
 * window saw, or through the back-invoked callback where the platform no
 * longer sends the key. A key still held when the window goes keeps it as
 * an invisible key sink until the release ([remove]), so the release does
 * not land in the game.
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
) : FrameLayout(overlayThemedContext(displayCtx)) {

    val layer = CaptureOverlayLayer(
        context, scope, prefs,
        lookupHost = CaptureOverlayLayer.LookupHost(displayCtx, wm, overlayHost, displayId),
    )

    /** Pair bookkeeping and the removal linger ([remove]). */
    private val keyGuard = WindowKeyPairGuard()
    private var backDownSeen = false
    private var backCallback: Any? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        addView(layer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        layer.onBoxesSettled = { has -> if (!has) onNothingToShow() }
        // A card that ended by its own means hands the screen back, as the
        // sheet closes for it.
        layer.onSessionEnded = { onDismissRequested() }
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
    }

    fun update(data: OneShotOverlayData) {
        layer.update(data)
    }

    /** The capture's result landed after its boxes went up: the frame its
     *  cards cite ([CaptureOverlayLayer.setFrame]). */
    fun setFrame(screenshotPath: String?, capturedAtMs: Long) = layer.setFrame(screenshotPath, capturedAtMs)

    fun setMode(mode: OverlayMode) {
        layer.setMode(mode)
    }

    fun release() = layer.release()

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (keyGuard.isLingering) return true
        // The layer's own (a word of Definitions, a card to close).
        if (layer.onTouch(ev)) return true
        // Any other tap on the game screen hands it back.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) onDismissRequested()
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
        if (!layer.onBack()) onDismissRequested()
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
