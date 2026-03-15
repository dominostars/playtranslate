package com.gamelens

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.gamelens.ui.DragLookupController
import com.gamelens.ui.FloatingIconMenu
import com.gamelens.ui.FloatingOverlayIcon
import com.gamelens.ui.OcrDebugOverlayView
import com.gamelens.ui.RegionDragView
import com.gamelens.ui.RegionOverlayView
import com.gamelens.ui.TranslationOverlayView
import com.gamelens.ui.WordLookupPopup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Minimal AccessibilityService whose only purpose is to call
 * [takeScreenshot] on a specific display.
 *
 * WHY AN ACCESSIBILITY SERVICE?
 * ─────────────────────────────
 * MediaProjection captures the display the requesting Activity runs on.
 * Launching a bridge Activity on the game display caused the whole app
 * to move there. AccessibilityService.takeScreenshot(displayId) captures
 * any display by ID with no UI, no focus change, and no app relocation.
 *
 * SETUP (one-time)
 * ─────────────────
 * Settings → Accessibility → Installed apps → PlayTranslate → Enable
 * The app detects the enabled state via [isEnabled].
 */
class PlayTranslateAccessibilityService : AccessibilityService() {

    private var overlayView: RegionOverlayView? = null
    private var overlayWm: WindowManager? = null
    private var dragView: RegionDragView? = null
    private var dragWm: WindowManager? = null
    private var floatingIcon: FloatingOverlayIcon? = null
    private var floatingIconWm: WindowManager? = null
    private var floatingIconDisplayId: Int = -1
    var dragLookupController: DragLookupController? = null
        private set
    private var floatingMenu: FloatingIconMenu? = null
    private var floatingMenuWm: WindowManager? = null
    private var debugOverlayView: OcrDebugOverlayView? = null
    private var debugOverlayWm: WindowManager? = null
    private var translationOverlayView: TranslationOverlayView? = null
    private var translationOverlayWm: WindowManager? = null
    private var translationOverlayDisplayId: Int = -1
    private var touchSentinelView: View? = null
    private var touchSentinelWm: WindowManager? = null
    private var debugOcrManager: OcrManager? = null
    private val debugHandler = Handler(Looper.getMainLooper())
    private var debugRunning = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onServiceConnected() {
        instance = this
        // Ensure we can query the window list (needed for nav bar detection
        // during joystick polling). Setting this at runtime avoids needing
        // the user to re-toggle the service after an app update.
        serviceInfo = serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        ensureFloatingIcon()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopInputMonitoring()
        stopDebugOcrLoop()
        hideTranslationOverlay()
        hideRegionOverlay()
        hideRegionDragOverlay()
        dismissFloatingMenu()
        hideFloatingIcon()
        serviceScope.cancel()
        instance = null
        return super.onUnbind(intent)
    }

    fun showRegionOverlay(
        display: Display,
        topFraction: Float, bottomFraction: Float,
        leftFraction: Float = 0f, rightFraction: Float = 1f
    ) {
        hideRegionOverlay()
        val wm = createDisplayContext(display).getSystemService(WindowManager::class.java) ?: return
        val view = RegionOverlayView(this).apply {
            updateRegion(topFraction, bottomFraction, leftFraction, rightFraction)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(view, params)
        overlayWm = wm
        overlayView = view
    }

    fun updateRegionOverlay(
        topFraction: Float, bottomFraction: Float,
        leftFraction: Float = 0f, rightFraction: Float = 1f
    ) {
        overlayView?.updateRegion(topFraction, bottomFraction, leftFraction, rightFraction)
    }

    fun hideRegionOverlay() {
        try { overlayView?.let { overlayWm?.removeView(it) } } catch (_: Exception) {}
        overlayView = null
        overlayWm = null
    }

    fun showRegionDragOverlay(display: Display, onRegionChanged: (Float, Float, Float, Float) -> Unit) {
        hideRegionDragOverlay()
        val wm = createDisplayContext(display).getSystemService(WindowManager::class.java) ?: return
        val view = RegionDragView(this).apply {
            setRegion(0.25f, 0.75f, 0.25f, 0.75f)
            this.onRegionChanged = onRegionChanged
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(view, params)
        dragWm = wm
        dragView = view
    }

    fun hideRegionDragOverlay() {
        try { dragView?.let { dragWm?.removeView(it) } } catch (_: Exception) {}
        dragView = null
        dragWm = null
    }

    fun getDragRegion(): Array<Float> = dragView?.getFullRegion() ?: arrayOf(0.25f, 0.75f, 0.25f, 0.75f)

    // ── Self-contained OCR debug overlay ─────────────────────────────────

    private val DEBUG_INTERVAL_MS = 2000L

    /**
     * Starts a self-contained loop: capture → OCR → draw bounding boxes.
     * Completely independent of the translation pipeline.
     */
    fun startDebugOcrLoop() {
        if (debugRunning) return
        debugRunning = true
        if (debugOcrManager == null) debugOcrManager = OcrManager()
        scheduleDebugCapture()
    }

    fun stopDebugOcrLoop() {
        debugRunning = false
        debugHandler.removeCallbacksAndMessages(null)
        hideDebugOverlay()
        debugOcrManager?.close()
        debugOcrManager = null
    }

    private fun scheduleDebugCapture() {
        if (!debugRunning) return
        debugHandler.postDelayed({ runDebugCapture() }, DEBUG_INTERVAL_MS)
    }

    private fun runDebugCapture() {
        if (!debugRunning) return
        val prefs = Prefs(this)
        val displayId = prefs.captureDisplayId
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(displayId) ?: run { scheduleDebugCapture(); return }

        captureDisplay(displayId) { bitmap ->
            if (bitmap == null || !debugRunning) {
                bitmap?.recycle()
                scheduleDebugCapture()
                return@captureDisplay
            }
            val screenshotW = bitmap.width
            val screenshotH = bitmap.height

            serviceScope.launch {
                val ocr = debugOcrManager ?: run { bitmap.recycle(); scheduleDebugCapture(); return@launch }
                val result = try {
                    kotlinx.coroutines.withContext(Dispatchers.Default) {
                        ocr.recognise(bitmap, prefs.sourceLang, collectDebugBoxes = true)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Debug OCR failed: ${e.message}")
                    null
                } finally {
                    bitmap.recycle()
                }

                val boxes = result?.debugBoxes
                if (boxes != null && debugRunning) {
                    showDebugOverlay(display, boxes, 0, 0, screenshotW, screenshotH)
                } else {
                    hideDebugOverlay()
                }
                scheduleDebugCapture()
            }
        }
    }

    private fun showDebugOverlay(
        display: Display,
        boxes: OcrManager.OcrDebugBoxes,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int
    ) {
        hideDebugOverlay()
        val wm = createDisplayContext(display).getSystemService(WindowManager::class.java) ?: return
        val view = OcrDebugOverlayView(this).apply {
            setBoxes(boxes, cropLeft, cropTop, screenshotW, screenshotH)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(view, params)
        debugOverlayWm = wm
        debugOverlayView = view
    }

    fun hideDebugOverlay() {
        try { debugOverlayView?.let { debugOverlayWm?.removeView(it) } } catch (_: Exception) {}
        debugOverlayView = null
        debugOverlayWm = null
    }

    // ── Live translation overlay ─────────────────────────────────────────

    fun showTranslationOverlay(
        display: Display,
        boxes: List<TranslationOverlayView.TextBox>,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int
    ) {
        // Overlay is appearing — dismiss loading spinner
        floatingIcon?.showLoading = false

        // Reuse existing view if on the same display; otherwise recreate
        if (translationOverlayView != null && translationOverlayDisplayId == display.displayId) {
            translationOverlayView?.setBoxes(boxes, cropLeft, cropTop, screenshotW, screenshotH)
            return
        }
        hideTranslationOverlay()
        val wm = createDisplayContext(display).getSystemService(WindowManager::class.java) ?: return
        val view = TranslationOverlayView(createDisplayContext(display)).apply {
            setBoxes(boxes, cropLeft, cropTop, screenshotW, screenshotH)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(view, params)
        translationOverlayWm = wm
        translationOverlayView = view
        translationOverlayDisplayId = display.displayId
    }

    fun hideTranslationOverlay() {
        try { translationOverlayView?.let { translationOverlayWm?.removeView(it) } } catch (_: Exception) {}
        translationOverlayView = null
        translationOverlayWm = null
        translationOverlayDisplayId = -1
    }

    // ── Floating overlay icon ─────────────────────────────────────────────

    // ── Input monitoring for live mode ──────────────────────────────────

    private var onGameInput: (() -> Unit)? = null
    private var lastKeyEventTime = 0L
    private var buttonHeld = false
    private var touchActive = false
    private val TOUCH_HOLD_TIMEOUT_MS = 2000L
    private val touchTimeoutRunnable = Runnable { touchActive = false }

    /**
     * True while any input source is actively being used (button held,
     * touch down, or joystick held). CaptureService checks this to avoid
     * showing the overlay during active interaction.
     */
    val isInputActive: Boolean
        get() = buttonHeld || touchActive

    /**
     * Start monitoring gamepad buttons and screen touches on [displayId].
     * If [joystick] is true, also adds a joystick sentinel that polls for
     * analog stick / d-pad movement (uses focus cycling — slight risk of
     * eating key events). On API 34+ with MediaProjection this is disabled
     * since timer-based polling handles everything without focus tricks.
     *
     * [callback] fires on the main thread for every detected input.
     */
    fun startInputMonitoring(displayId: Int, callback: () -> Unit) {
        onGameInput = callback
        lastKeyEventTime = 0L
        buttonHeld = false
        touchActive = false
        addTouchSentinel(displayId)
    }

    fun stopInputMonitoring() {
        onGameInput = null
        buttonHeld = false
        touchActive = false
        debugHandler.removeCallbacks(touchTimeoutRunnable)
        removeTouchSentinel()
    }

    // ── Touch sentinel ──────────────────────────────────────────────────

    /**
     * A 1×1 transparent overlay on the game display. With FLAG_WATCH_OUTSIDE_TOUCH
     * every touch elsewhere on the screen delivers ACTION_OUTSIDE without consuming
     * the event, so the game still receives normal input.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun addTouchSentinel(displayId: Int) {
        if (touchSentinelView != null) return
        val dm = getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(displayId) ?: return
        val ctx = createDisplayContext(display)
        val wm = ctx.getSystemService(WindowManager::class.java) ?: return
        val view = View(ctx).apply {
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    // Mark touch as active. We can't detect touch-up from
                    // the sentinel, so use a timeout to assume lift.
                    touchActive = true
                    debugHandler.removeCallbacks(touchTimeoutRunnable)
                    debugHandler.postDelayed(touchTimeoutRunnable, TOUCH_HOLD_TIMEOUT_MS)
                    onGameInput?.invoke()
                }
                false
            }
        }
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(view, params)
        touchSentinelView = view
        touchSentinelWm = wm
    }

    private fun removeTouchSentinel() {
        try { touchSentinelView?.let { touchSentinelWm?.removeView(it) } } catch (_: Exception) {}
        touchSentinelView = null
        touchSentinelWm = null
    }


    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val src = event.source
        val isGameInput = src and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
            || src and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD
            || KeyEvent.isGamepadButton(event.keyCode)
        if (isGameInput) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    lastKeyEventTime = System.currentTimeMillis()
                    buttonHeld = true
                    if (dragLookupController?.isPopupShowing == true) {
                        dragLookupController?.dismiss()
                    }
                    onGameInput?.invoke()
                }
                KeyEvent.ACTION_UP -> {
                    buttonHeld = false
                    lastKeyEventTime = System.currentTimeMillis()
                    onGameInput?.invoke()
                }
            }
        }
        return false // pass through to the game
    }

    /**
     * Single entry point for all icon visibility updates.
     * Called from: onServiceConnected, settings toggle, capture display change.
     *
     * Shows, hides, or relocates the icon based on current state.
     */
    fun ensureFloatingIcon() {
        val prefs = Prefs(this)
        if (!prefs.showOverlayIcon) {
            hideFloatingIcon()
            return
        }
        val display = findIconDisplay(prefs) ?: return
        if (floatingIcon != null && floatingIconDisplayId == display.displayId) return
        Log.d(TAG, "ensureFloatingIcon: showing on display ${display.displayId} (current=$floatingIconDisplayId, captureId=${prefs.captureDisplayId})")
        showFloatingIcon(display, prefs)
    }

    private fun showFloatingIcon(display: Display, prefs: Prefs) {
        hideFloatingIcon()
        val displayCtx = createDisplayContext(display)
        val wm = displayCtx.getSystemService(WindowManager::class.java) ?: return

        val screenSize = getDisplaySize(display)
        val icon = FloatingOverlayIcon(displayCtx).apply {
            this.wm = wm
            screenW = screenSize.x
            screenH = screenSize.y
        }

        val params = WindowManager.LayoutParams(
            icon.viewSizePx, icon.viewSizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }

        icon.params = params
        icon.setPosition(prefs.overlayIconEdge, prefs.overlayIconFraction)

        icon.onPositionChanged = { edge, fraction ->
            prefs.overlayIconEdge = edge
            prefs.overlayIconFraction = fraction
        }
        icon.onTap = {
            showFloatingMenu(display, icon)
        }

        // Drag-to-lookup: OCR + dictionary popup while dragging
        val popup = WordLookupPopup(displayCtx, wm)
        val controller = DragLookupController(
            displayId = display.displayId,
            screenW = screenSize.x,
            screenH = screenSize.y,
            popup = popup
        )
        // Track whether live mode was running when the popup appeared
        var liveWasPausedForPopup = false
        // True when transitioning from hold to drag (overlays need time to clear)
        var holdTransition = false

        popup.onDismiss = {
            controller.onPopupDismissed()
            // Resume live mode only if drag is finished (not mid-drag word change).
            // If the user is still dragging, resumption waits for onDragEnd.
            if (liveWasPausedForPopup && !icon.inDragMode) {
                liveWasPausedForPopup = false
                val effectivelySingleScreen = Prefs.isSingleScreen(this) || !MainActivity.isInForeground
                if (effectivelySingleScreen) {
                    toggleLiveDirect(true)
                } else {
                    sendMainActivityIntent(MainActivity.ACTION_START_LIVE)
                }
            }
        }
        icon.onDragStart = {
            // Pause live mode while dragging for definitions
            if (MainActivity.isLiveModeActive) {
                liveWasPausedForPopup = true
                val effectivelySingleScreen = Prefs.isSingleScreen(this) || !MainActivity.isInForeground
                if (effectivelySingleScreen) {
                    toggleLiveDirect(false)
                } else {
                    sendMainActivityIntent(MainActivity.ACTION_STOP_LIVE)
                }
            }
            hideTranslationOverlay()
            if (holdTransition) {
                // Overlays were just visible — wait for compositor to clear
                holdTransition = false
                debugHandler.postDelayed({ controller.onDragStart() }, 100)
            } else {
                controller.onDragStart()
            }
        }
        icon.onDragMove = { rawX, rawY -> controller.onDragMove(rawX, rawY) }
        icon.onDragEnd = {
            val popupShowing = controller.onDragEnd()
            // If no popup visible on lift, resume live mode immediately
            if (!popupShowing && liveWasPausedForPopup) {
                liveWasPausedForPopup = false
                val effectivelySingleScreen = Prefs.isSingleScreen(this) || !MainActivity.isInForeground
                if (effectivelySingleScreen) {
                    toggleLiveDirect(true)
                } else {
                    sendMainActivityIntent(MainActivity.ACTION_START_LIVE)
                }
            }
            popupShowing
        }
        icon.onHoldCancel = {
            // Hold cancelled because user started dragging — clean up hold
            // state and transition to drag mode, reusing the existing
            // screenshot so the drag controller doesn't capture our overlays.
            val svc = CaptureService.instance
            icon.showLoading = false
            if (MainActivity.isLiveModeActive) {
                svc?.holdActive = false
            } else {
                svc?.cancelOneShot()
                hideTranslationOverlay()
            }
            holdTransition = true
        }
        icon.onHoldStart = {
            val svc = CaptureService.instance
            if (MainActivity.isLiveModeActive) {
                // Live mode: temporarily hide overlays, suppress new ones
                svc?.holdActive = true
                hideTranslationOverlay()
            } else {
                // Not live: one-shot capture + translate + show overlay
                icon.showLoading = true
                svc?.showOneShotOverlay()
            }
        }
        icon.onHoldEnd = {
            val svc = CaptureService.instance
            icon.showLoading = false
            if (MainActivity.isLiveModeActive) {
                // Live mode: allow overlays again, refresh
                svc?.holdActive = false
                svc?.refreshLiveOverlay()
            } else {
                // Not live: remove overlays and invalidate any in-flight one-shot
                svc?.cancelOneShot()
                hideTranslationOverlay()
            }
        }
        dragLookupController = controller

        try {
            wm.addView(icon, params)
            floatingIconWm = wm
            floatingIcon = icon
            floatingIconDisplayId = display.displayId
        } catch (e: Exception) {
            Log.e(TAG, "showFloatingIcon: addView failed", e)
        }
    }

    fun hideFloatingIcon() {
        dragLookupController?.destroy()
        dragLookupController = null
        try { floatingIcon?.let { floatingIconWm?.removeView(it) } } catch (_: Exception) {}
        floatingIcon = null
        floatingIconWm = null
        floatingIconDisplayId = -1
    }

    // ── Floating icon menu ────────────────────────────────────────────────

    private fun showFloatingMenu(display: Display, icon: FloatingOverlayIcon) {
        dismissFloatingMenu()
        val wm = createDisplayContext(display).getSystemService(WindowManager::class.java) ?: return
        val screenSize = getDisplaySize(display)
        val menu = FloatingIconMenu(createDisplayContext(display))
        menu.isSingleScreen = Prefs.isSingleScreen(this)

        menu.isLiveMode = MainActivity.isLiveModeActive
        menu.onHideIcon = {
            dismissFloatingMenu()
            Prefs(this).showOverlayIcon = false
            hideFloatingIcon()
        }
        menu.onHideTemporary = {
            dismissFloatingMenu()
            hideFloatingIcon()
        }
        menu.onDismiss = { dismissFloatingMenu() }
        menu.onToggleLive = {
            dismissFloatingMenu()
            val effectivelySingleScreen = Prefs.isSingleScreen(this) || !MainActivity.isInForeground
            if (MainActivity.isLiveModeActive) {
                if (effectivelySingleScreen) {
                    toggleLiveDirect(false)
                } else {
                    sendMainActivityIntent(MainActivity.ACTION_STOP_LIVE)
                }
            } else {
                if (effectivelySingleScreen) {
                    toggleLiveDirect(true)
                } else {
                    sendMainActivityIntent(MainActivity.ACTION_START_LIVE)
                }
            }
        }
        menu.onRegionSelected = { top, bottom, left, right ->
            dismissFloatingMenu()
            handleRegionSelection(display.displayId, top, bottom, left, right)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        wm.addView(menu, params)
        floatingMenuWm = wm
        floatingMenu = menu

        // Compute icon center in screen coords
        val p = icon.params
        val iconCx = (p?.x ?: 0) + icon.viewSizePx / 2
        val iconCy = (p?.y ?: 0) + icon.viewSizePx / 2
        menu.positionNearIcon(iconCx, iconCy, icon.currentEdge, screenSize.x, screenSize.y)
    }

    private fun dismissFloatingMenu() {
        try { floatingMenu?.let { floatingMenuWm?.removeView(it) } } catch (_: Exception) {}
        floatingMenu = null
        floatingMenuWm = null
    }

    /**
     * Returns the floating icon's bounding rect in screen coordinates, or null
     * if the icon is not showing. Used by CaptureService to black out the icon
     * area before OCR so it doesn't interfere with text recognition.
     */
    fun getFloatingIconRect(): android.graphics.Rect? {
        val icon = floatingIcon ?: return null
        val p = icon.params ?: return null
        return android.graphics.Rect(p.x, p.y, p.x + icon.viewSizePx, p.y + icon.viewSizePx)
    }

    /**
     * Start/stop live mode directly without bringing MainActivity to the
     * foreground. Used on single-screen devices where showing the Activity
     * would cover the game.
     */
    private fun toggleLiveDirect(start: Boolean) {
        val svc = CaptureService.instance ?: return
        if (start) {
            // On API 34+, request MediaProjection via a transparent Activity
            // so the game stays visible and the user can select it in the picker.
            if (Build.VERSION.SDK_INT >= 34 && !svc.hasMediaProjection) {
                MediaProjectionConsentActivity.launch(this) { granted ->
                    if (granted) toggleLiveDirect(true)
                }
                return
            }
            MainActivity.isLiveModeActive = true
            // Dismiss any definition popup when entering live mode.
            val hadPopup = dragLookupController?.isPopupShowing == true
            dragLookupController?.dismiss()
            if (!svc.isConfigured) {
                val prefs = Prefs(this)
                val entry = prefs.getRegionList().getOrElse(prefs.captureRegionIndex) {
                    Prefs.DEFAULT_REGION_LIST[0]
                }
                svc.configure(
                    displayId             = prefs.captureDisplayId,
                    sourceLang            = prefs.sourceLang,
                    targetLang            = prefs.targetLang,
                    captureTopFraction    = entry.top,
                    captureBottomFraction = entry.bottom,
                    captureLeftFraction   = entry.left,
                    captureRightFraction  = entry.right,
                    regionLabel           = entry.label
                )
            }
            // Delay start if a popup was just dismissed so the compositor
            // has time to remove it before the first screenshot.
            if (hadPopup) {
                debugHandler.postDelayed({ svc.startLive() }, 100)
            } else {
                svc.startLive()
            }
        } else {
            MainActivity.isLiveModeActive = false
            svc.stopLive()
        }
    }

    private fun sendMainActivityIntent(action: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            this.action = action
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    /**
     * Routes a drag-selected region to the appropriate activity:
     * - If effectively single screen (or app not in foreground), captures the
     *   screenshot NOW (while the game is still visible) and passes the path
     *   to TranslationResultActivity so it can OCR the saved image.
     * - Otherwise, sends ACTION_REGION_CAPTURE to MainActivity (which is on a
     *   different display and doesn't cover the game).
     */
    private fun handleRegionSelection(displayId: Int, top: Float, bottom: Float, left: Float, right: Float) {
        val effectivelySingleScreen = Prefs.isSingleScreen(this) || !MainActivity.isInForeground
        if (effectivelySingleScreen) {
            // Capture BEFORE launching the activity — once the activity appears
            // on a single-screen device it would cover the game content.
            captureDisplay(displayId) { bitmap ->
                val intent = Intent(this, com.gamelens.ui.TranslationResultActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(com.gamelens.ui.TranslationResultActivity.EXTRA_TOP_FRAC, top)
                    putExtra(com.gamelens.ui.TranslationResultActivity.EXTRA_BOTTOM_FRAC, bottom)
                    putExtra(com.gamelens.ui.TranslationResultActivity.EXTRA_LEFT_FRAC, left)
                    putExtra(com.gamelens.ui.TranslationResultActivity.EXTRA_RIGHT_FRAC, right)
                }
                if (bitmap != null) {
                    // Save to a known path so the activity can load it
                    val path = savePreCapturedScreenshot(bitmap)
                    bitmap.recycle()
                    intent.putExtra(com.gamelens.ui.TranslationResultActivity.EXTRA_SCREENSHOT_PATH, path)
                }
                Handler(Looper.getMainLooper()).post { startActivity(intent) }
            }
        } else {
            val intent = Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_REGION_CAPTURE
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_TOP_FRAC, top)
                putExtra(MainActivity.EXTRA_BOTTOM_FRAC, bottom)
                putExtra(MainActivity.EXTRA_LEFT_FRAC, left)
                putExtra(MainActivity.EXTRA_RIGHT_FRAC, right)
            }
            startActivity(intent)
        }
    }

    /**
     * Saves a pre-captured screenshot to the app's cache directory so it can
     * be loaded by TranslationResultActivity on single-screen devices.
     */
    private fun savePreCapturedScreenshot(bitmap: Bitmap): String? {
        return try {
            val dir = java.io.File(cacheDir, "screenshots").apply { mkdirs() }
            val file = java.io.File(dir, "precapture_${System.currentTimeMillis()}.png")
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "savePreCapturedScreenshot failed: ${e.message}")
            null
        }
    }

    /** Returns the capture display (game screen), or the only display on single-screen. */
    private fun findIconDisplay(prefs: Prefs): Display? {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = dm.displays
        if (displays.size <= 1) return displays.firstOrNull()
        return dm.getDisplay(prefs.captureDisplayId) ?: displays.firstOrNull()
    }

    @Suppress("DEPRECATION")
    private fun getDisplaySize(display: Display): Point {
        val size = Point()
        display.getRealSize(size)
        return size
    }

    // ── Screenshot ────────────────────────────────────────────────────────

    /**
     * Takes a screenshot of [displayId] and returns the result as a software
     * [Bitmap] via [onResult]. Returns null on failure or if API < 30.
     *
     * Must be called on a thread that has a Looper (e.g. main thread).
     */
    /** Single background thread for bitmap copies to keep the main thread free. */
    private val bitmapExecutor = Executors.newSingleThreadExecutor()

    /**
     * Takes a screenshot WITHOUT hiding overlays. Used for pixel-diff
     * scene-change detection where we compare non-overlay regions.
     */
    fun captureDisplayRaw(displayId: Int, onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { onResult(null); return }
        takeScreenshot(
            displayId, mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    bitmapExecutor.execute {
                        val bitmap = Bitmap
                            .wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        screenshot.hardwareBuffer.close()
                        onResult(bitmap)
                    }
                }
                override fun onFailure(errorCode: Int) { onResult(null) }
            }
        )
    }

    fun captureDisplay(displayId: Int, onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "takeScreenshot requires API 30+")
            onResult(null)
            return
        }

        // Hide overlays so they don't appear in the screenshot.
        // The floating icon uses FLAG_SECURE so the compositor excludes it automatically.
        val hadDebugOverlay = debugOverlayView != null
        val hadTranslationOverlay = translationOverlayView != null
        if (hadDebugOverlay) debugOverlayView?.visibility = android.view.View.INVISIBLE
        if (hadTranslationOverlay) translationOverlayView?.visibility = android.view.View.INVISIBLE

        val doCapture = {
            takeScreenshot(
                displayId,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        if (hadDebugOverlay) debugOverlayView?.visibility = android.view.View.VISIBLE
                        if (hadTranslationOverlay) translationOverlayView?.visibility = android.view.View.VISIBLE
                        // Copy HardwareBuffer → software Bitmap off the main thread
                        bitmapExecutor.execute {
                            val bitmap = Bitmap
                                .wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                            screenshot.hardwareBuffer.close()
                            onResult(bitmap)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (hadDebugOverlay) debugOverlayView?.visibility = android.view.View.VISIBLE
                        if (hadTranslationOverlay) translationOverlayView?.visibility = android.view.View.VISIBLE
                        Log.e(TAG, "takeScreenshot failed on display $displayId, code=$errorCode")
                        onResult(null)
                    }
                }
            )
        }

        if (hadDebugOverlay || hadTranslationOverlay) {
            // Wait two vsync frames for the compositor to flush the overlay-free
            // frame (~32 ms at 60 Hz). Frame-accurate and shorter than a fixed delay.
            val choreographer = Choreographer.getInstance()
            choreographer.postFrameCallback {
                choreographer.postFrameCallback { doCapture() }
            }
        } else {
            doCapture()
        }
    }

    companion object {
        private const val TAG = "PlayTranslateA11y"

        /** Non-null while the service is connected (i.e. user has it enabled). */
        var instance: PlayTranslateAccessibilityService? = null

        val isEnabled: Boolean get() = instance != null
    }
}
