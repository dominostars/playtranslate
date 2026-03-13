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
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.gamelens.ui.DragLookupController
import com.gamelens.ui.FloatingIconMenu
import com.gamelens.ui.FloatingOverlayIcon
import com.gamelens.ui.OcrDebugOverlayView
import com.gamelens.ui.RegionDragView
import com.gamelens.ui.RegionOverlayView
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
    private var dragLookupController: DragLookupController? = null
    private var floatingMenu: FloatingIconMenu? = null
    private var floatingMenuWm: WindowManager? = null
    private var debugOverlayView: OcrDebugOverlayView? = null
    private var debugOverlayWm: WindowManager? = null
    private var debugOcrManager: OcrManager? = null
    private val debugHandler = Handler(Looper.getMainLooper())
    private var debugRunning = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onServiceConnected() {
        instance = this
        ensureFloatingIcon()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopDebugOcrLoop()
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
    private val OVERLAY_HIDE_DELAY_MS = 50L

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

    // ── Floating overlay icon ─────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && dragLookupController?.isPopupShowing == true) {
            val src = event.source
            if (src and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
                || src and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD
                || KeyEvent.isGamepadButton(event.keyCode)
            ) {
                dragLookupController?.dismiss()
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
        popup.onDismiss = { controller.onPopupDismissed() }
        icon.onDragStart = { controller.onDragStart() }
        icon.onDragMove = { rawX, rawY -> controller.onDragMove(rawX, rawY) }
        icon.onDragEnd = { controller.onDragEnd() }
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

        menu.onHideIcon = {
            dismissFloatingMenu()
            Prefs(this).showOverlayIcon = false
            hideFloatingIcon()
        }
        menu.onDismiss = { dismissFloatingMenu() }
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
     * Routes a drag-selected region to the appropriate activity:
     * - If effectively single screen (or app not in foreground), launches TranslationResultActivity
     * - Otherwise, sends ACTION_REGION_CAPTURE to MainActivity
     */
    private fun handleRegionSelection(displayId: Int, top: Float, bottom: Float, left: Float, right: Float) {
        val effectivelySingleScreen = Prefs.isSingleScreen(this) || !MainActivity.isInForeground
        if (effectivelySingleScreen) {
            val intent = Intent(this, com.gamelens.ui.TranslationResultActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(com.gamelens.ui.TranslationResultActivity.EXTRA_TOP_FRAC, top)
                putExtra(com.gamelens.ui.TranslationResultActivity.EXTRA_BOTTOM_FRAC, bottom)
                putExtra(com.gamelens.ui.TranslationResultActivity.EXTRA_LEFT_FRAC, left)
                putExtra(com.gamelens.ui.TranslationResultActivity.EXTRA_RIGHT_FRAC, right)
            }
            startActivity(intent)
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

    fun captureDisplay(displayId: Int, onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "takeScreenshot requires API 30+")
            onResult(null)
            return
        }

        // Hide the debug overlay so it doesn't appear in the screenshot.
        // The floating icon is NOT hidden — toggling its visibility mid-drag
        // breaks touch event delivery and makes it undraggable.
        val hadDebugOverlay = debugOverlayView != null
        if (hadDebugOverlay) debugOverlayView?.visibility = android.view.View.INVISIBLE

        // Wait for the compositor to process the visibility change before capturing
        val delay = if (hadDebugOverlay) OVERLAY_HIDE_DELAY_MS else 0L
        debugHandler.postDelayed({
            takeScreenshot(
                displayId,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        if (hadDebugOverlay) debugOverlayView?.visibility = android.view.View.VISIBLE
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
                        Log.e(TAG, "takeScreenshot failed on display $displayId, code=$errorCode")
                        onResult(null)
                    }
                }
            )
        }, delay)
    }

    companion object {
        private const val TAG = "PlayTranslateA11y"

        /** Non-null while the service is connected (i.e. user has it enabled). */
        var instance: PlayTranslateAccessibilityService? = null

        val isEnabled: Boolean get() = instance != null
    }
}
