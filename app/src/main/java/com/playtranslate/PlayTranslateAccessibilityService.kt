package com.playtranslate

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.ui.DimController
import com.playtranslate.ui.OverlayAlert
import com.playtranslate.ui.DragLookupController
import com.playtranslate.language.HintTextKind
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.ui.FloatingIconMenu
import com.playtranslate.ui.FloatingOverlayIcon
import com.playtranslate.ui.MagnifierLens
import com.playtranslate.ui.OcrDebugOverlayView
import com.playtranslate.ui.RegionDragView
import com.playtranslate.ui.TranslationOverlayView
import com.playtranslate.ui.WordLookupPopup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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

    private var debugOverlayView: OcrDebugOverlayView? = null
    private val debugOcrManager get() = OcrManager.instance
    private val debugHandler = Handler(Looper.getMainLooper())
    private var debugRunning = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Centralized screenshot manager — all takeScreenshot calls go through here. */
    var screenshotManager: ScreenshotManager? = null
        private set

    /** Repositions floating icons when display properties change (e.g.
     *  rotation), and reconciles the icon registry when displays come or
     *  go. The registry-reconcile arm is necessary because MainActivity's
     *  own DisplayListener only runs while it's foregrounded — when the
     *  user is gaming with the app backgrounded (the typical floating-icon
     *  case), MainActivity isn't around to react to a hot-plug, so a
     *  reconnected external display's stale [iconHandles] entry would
     *  short-circuit the next reconcile and leave that display without a
     *  working icon. */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            overlayUiController.reconcileFloatingIcons()
        }
        override fun onDisplayRemoved(displayId: Int) {
            overlayUiController.reconcileFloatingIcons()
        }
        override fun onDisplayChanged(displayId: Int) {
            overlayUiController.repositionIconForDisplay(displayId)
        }
    }

    /** Stops live mode when the screen turns off. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                CaptureService.instance?.let { if (it.isLive) it.stopLive() }
                overlayUiController.hideTranslationOverlay()
            }
        }
    }

    override fun onServiceConnected() {
        Log.i(TAG, "onServiceConnected")
        logInputDeviceCensus()
        instance = this
        screenshotManager = ScreenshotManager(this)
        serviceInfo = serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        // Wire OverlayUiController's own DisplayListener now that the
        // service has a usable base context. (Construction at field-init
        // time runs before attachBaseContext, when context-touching calls
        // NPE — see [OverlayUiController.attach].)
        overlayUiController.attach()
        // Pick up the backend swap the moment the a11y service binds — the
        // user may have enabled it while the app was backgrounded, where no
        // MainActivity / QS-tile reresolve() runs. onServiceConnected only
        // fires because the service is enabled, so this resolves to the
        // accessibility backend (or no-ops when already there); reresolve
        // tears down any outgoing MediaProjection session + overlays.
        CaptureBackendResolver.reresolve(this)
        overlayUiController.reconcileFloatingIcons()
        registerHotkeyCallbacks()
        PlayTranslateTileService.TileSync.refresh(this)
    }

    /** Wire hotkey callbacks to CaptureService. Safe to call multiple times. */
    fun registerHotkeyCallbacks() {
        val svc = CaptureService.instance ?: return
        onHotkeyActivated = { mode -> svc.hotkeyHoldStart(mode) }
        onHotkeyReleased = { svc.hotkeyHoldEnd() }
    }

    /**
     * One-shot dump of every connected [InputDevice] at service connect. Fires
     * once per bind. Field reports of controller / D-pad / IME issues hinge on
     * how the device's HID is classified (source mask, keyboard type, vendor /
     * product); without this dump we have to ask the reporter to run adb.
     */
    private fun logInputDeviceCensus() {
        val im = getSystemService(InputManager::class.java) ?: return
        val ids = im.inputDeviceIds
        Log.i(TAG, "InputDevice census: ${ids.size} device(s)")
        for (id in ids) {
            val d = im.getInputDevice(id) ?: continue
            Log.i(
                TAG,
                "InputDevice id=$id name='${d.name}' " +
                    "sources=0x${d.sources.toString(16)} " +
                    "vendor=0x${d.vendorId.toString(16)} product=0x${d.productId.toString(16)} " +
                    "keyboardType=${d.keyboardType} virtual=${d.isVirtual} external=${d.isExternal}"
            )
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG, "onUnbind: tearing down overlays and cancelling scope", Throwable("onUnbind callsite"))
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .unregisterDisplayListener(displayListener)
        stopInputMonitoring()
        stopDebugOcrLoop()
        overlayUiController.destroy()
        overlayHost.removeAll()
        screenshotManager?.destroy()
        screenshotManager = null
        serviceScope.cancel()
        instance = null
        // Mirror onServiceConnected: re-resolve so the backend leaves
        // accessibility the moment the service unbinds (e.g. the user
        // disabled it from system Settings without returning to the app).
        // A no-op if the OS hasn't flushed the setting yet — MainActivity /
        // the tile then catch up, same as before.
        CaptureBackendResolver.reresolve(this)
        PlayTranslateTileService.TileSync.refresh(this)
        return super.onUnbind(intent)
    }


    // ── Overlay window registry ──────────────────────────────────────────
    //
    // Every accessibility-overlay window the service owns goes through
    // [addOverlayWindow] / [removeOverlayWindow]. [prepareForCleanCapture]
    // walks the registry and blanks each window via window-level alpha so
    // none of them appear in the captured frame. This replaces a previous
    // patchwork of per-overlay flags + View.alpha tweaks that left newly
    // added windows (e.g. the magnifier) silently in the screenshot.

    /** Backend-neutral overlay-window host (registry + clean-capture
     *  blanking). This service supplies the accessibility window type;
     *  MediaProjection mode uses its own host. Kept public so the capture
     *  backend resolver and [ScreenshotManager] can reach it. */
    val overlayHost = OverlayHost(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)

    /** Game-screen overlay UI (floating icon, menu, translation overlay,
     *  region UI). Backend-neutral; this service supplies the accessibility
     *  window type via [overlayHost]. CaptureService owns a separate instance
     *  for MediaProjection mode. */
    val overlayUiController = OverlayUiController(this, overlayHost)

    /** Register + add an overlay window. See [OverlayHost.addOverlayWindow]. */
    fun addOverlayWindow(
        view: View,
        wm: WindowManager,
        params: WindowManager.LayoutParams,
        displayId: Int,
    ): Boolean = overlayHost.addOverlayWindow(view, wm, params, displayId)

    /** Unregister + remove an overlay window. See [OverlayHost.removeOverlayWindow]. */
    fun removeOverlayWindow(view: View): Boolean = overlayHost.removeOverlayWindow(view)

    /** Blank this service's overlays on [displayId] for a clean capture. */
    fun prepareForCleanCapture(displayId: Int): OverlayHost.OverlayState =
        overlayHost.prepareForCleanCapture(displayId)

    /** Restore overlays blanked by [prepareForCleanCapture]. */
    fun restoreAfterCapture(state: OverlayHost.OverlayState) =
        overlayHost.restoreAfterCapture(state)

    // ── Self-contained OCR debug overlay ─────────────────────────────────

    private val DEBUG_INTERVAL_MS = 2000L

    /**
     * Starts a self-contained loop: capture → OCR → draw bounding boxes.
     * Completely independent of the translation pipeline.
     */
    fun startDebugOcrLoop() {
        if (debugRunning) return
        debugRunning = true
        scheduleDebugCapture()
    }

    fun stopDebugOcrLoop() {
        debugRunning = false
        debugHandler.removeCallbacksAndMessages(null)
        hideDebugOverlay()
    }

    private fun scheduleDebugCapture() {
        if (!debugRunning) return
        debugHandler.postDelayed({ runDebugCapture() }, DEBUG_INTERVAL_MS)
    }

    private fun runDebugCapture() {
        if (!debugRunning) return
        val prefs = Prefs(this)
        val displayId = prefs.captureDisplayIds.firstOrNull()
            ?: android.view.Display.DEFAULT_DISPLAY
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(displayId) ?: run { scheduleDebugCapture(); return }

        serviceScope.launch {
            val raw = screenshotManager?.requestClean(displayId)
            if (raw == null || !debugRunning) {
                raw?.recycle()
                scheduleDebugCapture()
                return@launch
            }
            val screenshotW = raw.width
            val screenshotH = raw.height

            // Mirror the production OCR pipeline so the debug overlay shows
            // what runOcrPipeline would see — same active region, same
            // status-bar clamp, same floating-icon blackout. Falls back to
            // a full-screen unclamped crop if the capture service isn't bound.
            val captureSvc = CaptureService.instance
            val region = captureSvc?.activeRegionForDisplay(displayId)
                ?: RegionEntry("", 0f, 1f, 0f, 1f)
            val statusBarHeight = captureSvc?.getStatusBarHeightForDisplay(displayId) ?: 0
            val crop = OverlayToolkit.computeOcrCrop(raw.width, raw.height, region, statusBarHeight)
            val needsCrop = crop.top > 0 || crop.left > 0 ||
                crop.bottom < raw.height || crop.right < raw.width
            val cropped = if (needsCrop) Bitmap.createBitmap(
                raw, crop.left, crop.top,
                (crop.right - crop.left).coerceAtLeast(1),
                (crop.bottom - crop.top).coerceAtLeast(1),
            ) else raw

            val ocr = debugOcrManager
            val result = try {
                kotlinx.coroutines.withContext(Dispatchers.Default) {
                    // No pre-OCR icon blackout — the floating icon is always
                    // compact and doesn't bleed into the OCR region.
                    ocr.recognise(
                        cropped,
                        SourceLanguageProfiles[prefs.sourceLangId].translationCode,
                        collectDebugBoxes = true,
                        screenshotWidth = raw.width,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Debug OCR failed: ${e.message}")
                null
            } finally {
                if (cropped !== raw && !cropped.isRecycled) cropped.recycle()
                raw.recycle()
            }

            val boxes = result?.debugBoxes
            if (boxes != null && debugRunning) {
                showDebugOverlay(display, boxes, crop.left, crop.top, screenshotW, screenshotH)
            } else {
                hideDebugOverlay()
            }
            scheduleDebugCapture()
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
        addOverlayWindow(view, wm, params, display.displayId)
        debugOverlayView = view
    }

    fun hideDebugOverlay() {
        debugOverlayView?.let { removeOverlayWindow(it) }
        debugOverlayView = null
    }

    // ── Input monitoring for live mode ──────────────────────────────────

    /**
     * Per-display input callbacks. Each LiveMode registers a callback for its
     * own displayId; the dispatch is per-source:
     *  - Touch (sentinel) is display-bound — only the touched display's
     *    callback fires (see [fireOnGameInputForDisplay]).
     *  - Gamepad / D-pad is NOT display-bound — controller focus is
     *    independent of touch focus, so a button press on a controller paired
     *    to display A must reach A's callback even if the user just touched
     *    display B for an unrelated reason. Fan-out is correct here (see
     *    [fireOnGameInput]).
     */
    private val onGameInputs: MutableMap<Int, () -> Unit> = mutableMapOf()
    private var lastKeyEventTime = 0L
    /** Derived from [heldKeyCodes] so a multi-key release pattern
     *  (press A → press B → release A) reports B as still held instead
     *  of incorrectly flipping to false on A's UP event. Single source
     *  of truth: only the key event handler mutates heldKeyCodes. */
    private val buttonHeld: Boolean get() = heldKeyCodes.isNotEmpty()
    private var touchActive = false
    private val TOUCH_HOLD_TIMEOUT_MS = 2000L
    private val touchTimeoutRunnable = Runnable { touchActive = false }

    /** Fan an input event out to every registered listener. Used by the
     *  gamepad/D-pad path in [onKeyEvent], where the input source isn't
     *  bound to a specific display. */
    private fun fireOnGameInput() {
        if (onGameInputs.isEmpty()) return
        onGameInputs.values.forEach { it.invoke() }
    }

    /** Dispatch an input event to a single display's listener. Used by the
     *  touch sentinel path, where the touched display is unambiguous and
     *  invalidating other displays' overlays would cause spurious flicker. */
    private fun fireOnGameInputForDisplay(displayId: Int) {
        onGameInputs[displayId]?.invoke()
    }

    /**
     * True while any input source is actively being used (button held,
     * touch down, or joystick held). CaptureService checks this to avoid
     * showing the overlay during active interaction.
     */
    val isInputActive: Boolean
        get() = buttonHeld || touchActive

    /**
     * Start monitoring gamepad buttons and screen touches on [displayId].
     * The [callback] fires on the main thread for every detected input;
     * multiple displays can have callbacks registered concurrently and all
     * will fire on each input event (see [fireOnGameInput]).
     */
    fun startInputMonitoring(displayId: Int, callback: () -> Unit) {
        onGameInputs[displayId] = callback
        lastKeyEventTime = 0L
        heldKeyCodes.clear()
        touchActive = false
        addTouchSentinel(displayId)
    }

    /** Stop monitoring input for a single display. Tears down THIS display's
     *  touch sentinel; global state (heldKeyCodes, touchActive) only
     *  resets when the last listener goes away. */
    fun stopInputMonitoring(displayId: Int) {
        onGameInputs.remove(displayId)
        overlayHost.removeTouchSentinel(displayId)
        if (onGameInputs.isEmpty()) {
            heldKeyCodes.clear()
            touchActive = false
            debugHandler.removeCallbacks(touchTimeoutRunnable)
        }
    }

    /** Stop input monitoring across every display (e.g. on stopLive). */
    fun stopInputMonitoring() {
        onGameInputs.clear()
        heldKeyCodes.clear()
        touchActive = false
        debugHandler.removeCallbacks(touchTimeoutRunnable)
        overlayHost.removeAllTouchSentinels()
    }

    // ── Touch sentinel ──────────────────────────────────────────────────

    /**
     * Host a touch sentinel for [displayId] — see [OverlayHost.addTouchSentinel].
     * Its ACTION_OUTSIDE marks touch active, records the interacted display for
     * hotkey routing, and dispatches to that display's input listener.
     */
    private fun addTouchSentinel(displayId: Int) {
        overlayHost.addTouchSentinel(displayId) {
            // We can't see touch-up from the sentinel, so a timeout assumes lift.
            touchActive = true
            // Track which display the user touched so hotkey routing lands on
            // the right place (P5).
            CaptureService.instance?.lastInteractedDisplayId = displayId
            debugHandler.removeCallbacks(touchTimeoutRunnable)
            debugHandler.postDelayed(touchTimeoutRunnable, TOUCH_HOLD_TIMEOUT_MS)
            // Display-bound dispatch — only the touched display's overlay is
            // invalidated; other displays keep their own scene detection.
            fireOnGameInputForDisplay(displayId)
        }
    }


    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt")
    }

    /** Temporary listener for key event capture (e.g., hotkey setup dialog). Takes priority over normal handling. */
    var onKeyEventListener: ((KeyEvent) -> Boolean)? = null

    /**
     * True when the user has some visible indication the app is listening:
     * either the floating icon is on screen or MainActivity is foregrounded.
     * Used to gate hotkey activation so a user who has hidden the icon and
     * backgrounded the app doesn't get "ghost" hotkey triggers with no
     * feedback. Differs from the foreground-notification rule
     * ([CaptureService.updateForegroundState]) which intentionally omits
     * `foregrounded` — the notification is redundant while the app is on
     * screen, but hotkeys obviously must still work then.
     */
    fun isUserReachable(): Boolean =
        overlayUiController.hasAnyFloatingIcon || MainActivity.isInForeground

    // ── Hotkey combo detection ──────────────────────────────────────────

    /**
     * Window to wait on a "shadowed" combo (one that is a proper subset of
     * another configured combo) before firing it. Prevents chord presses
     * like A+B from being misread as just A when A arrives a few ms before
     * B. Humans pressing two buttons simultaneously land within ~20-40ms of
     * each other; 60ms is comfortably above that and still below the point
     * at which players typically feel input latency.
     */
    private val HOTKEY_COMBO_WINDOW_MS = 60L

    private val heldKeyCodes = mutableSetOf<Int>()
    private var activeHotkeyMode: OverlayMode? = null
    private var pendingActivationMode: OverlayMode? = null

    /** Callback when a hotkey combo becomes fully held. */
    var onHotkeyActivated: ((OverlayMode) -> Unit)? = null
    /** Callback when the active hotkey combo is released. */
    var onHotkeyReleased: (() -> Unit)? = null

    private val pendingActivationRunnable = Runnable {
        val mode = pendingActivationMode ?: return@Runnable
        pendingActivationMode = null
        // Re-check reachability: the gate may have closed during the
        // deferral window (user backgrounded the app while mid-chord).
        if (!isUserReachable()) {
            android.util.Log.d("HotkeyDbg", "DEFERRED cancelled (gate closed): $mode")
            return@Runnable
        }
        activeHotkeyMode = mode
        android.util.Log.d("HotkeyDbg", "ACTIVATED (deferred): $mode")
        onHotkeyActivated?.invoke(mode)
    }

    private fun parseCombo(stored: String): Set<Int> {
        if (stored.isBlank()) return emptySet()
        return stored.split("+").mapNotNull { it.toIntOrNull() }.toSet()
    }

    private fun checkHotkeyCombos() {
        val prefs = Prefs(this)
        val combos = listOf(
            HotkeyCombo(parseCombo(prefs.hotkeyTranslation), OverlayMode.TRANSLATION),
            HotkeyCombo(parseCombo(prefs.hotkeyFurigana), OverlayMode.FURIGANA),
        ).filter { it.keys.isNotEmpty() }

        val state = HotkeyState(activeHotkeyMode, pendingActivationMode)
        val action = decideHotkeyAction(
            held = heldKeyCodes,
            state = state,
            combos = combos,
            reachable = isUserReachable(),
        )

        android.util.Log.d(
            "HotkeyDbg",
            "checkCombos: held=$heldKeyCodes combos=$combos state=$state → $action"
        )

        when (action) {
            is HotkeyAction.NoChange -> Unit

            is HotkeyAction.ActivateNow -> {
                debugHandler.removeCallbacks(pendingActivationRunnable)
                pendingActivationMode = null
                activeHotkeyMode = action.mode
                android.util.Log.d("HotkeyDbg", "ACTIVATED: ${action.mode}")
                onHotkeyActivated?.invoke(action.mode)
            }

            is HotkeyAction.DeferActivation -> {
                debugHandler.removeCallbacks(pendingActivationRunnable)
                pendingActivationMode = action.mode
                android.util.Log.d(
                    "HotkeyDbg",
                    "DEFERRED: ${action.mode} (waiting ${HOTKEY_COMBO_WINDOW_MS}ms for possible superset)"
                )
                debugHandler.postDelayed(pendingActivationRunnable, HOTKEY_COMBO_WINDOW_MS)
            }

            HotkeyAction.Release -> {
                val released = activeHotkeyMode
                activeHotkeyMode = null
                android.util.Log.d("HotkeyDbg", "RELEASED: $released")
                onHotkeyReleased?.invoke()
            }

            HotkeyAction.ClearPending -> {
                debugHandler.removeCallbacks(pendingActivationRunnable)
                val cleared = pendingActivationMode
                pendingActivationMode = null
                android.util.Log.d("HotkeyDbg", "PENDING CLEARED: $cleared")
            }
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        android.util.Log.d("HotkeyDbg", "onKeyEvent: keyCode=${event.keyCode} action=${event.action} source=0x${event.source.toString(16)}")

        // If a key event listener is active (e.g., hotkey setup), let it handle first
        onKeyEventListener?.let { listener ->
            if (listener(event)) return true
        }

        val src = event.source
        val isGameInput = src and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
            || src and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD
            || KeyEvent.isGamepadButton(event.keyCode)
        if (isGameInput) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    lastKeyEventTime = System.currentTimeMillis()
                    heldKeyCodes.add(event.keyCode)
                    if (overlayUiController.isAnyDragLookupPopupShowing) {
                        overlayUiController.dismissAllDragLookupPopups()
                    }
                    fireOnGameInput()
                    checkHotkeyCombos()
                }
                KeyEvent.ACTION_UP -> {
                    heldKeyCodes.remove(event.keyCode)
                    lastKeyEventTime = System.currentTimeMillis()
                    fireOnGameInput()
                    checkHotkeyCombos()
                }
            }
        }
        return false // pass through to the game
    }

    companion object {
        private const val TAG = "PlayTranslateA11y"

        /** Non-null while the service is connected (i.e. user has it enabled).
         *  `@Volatile` because the field is written from the main thread
         *  (`onServiceConnected` / `onUnbind`) and read from many others —
         *  drag controllers, hotkey callbacks, ML Kit worker threads. Without
         *  the visibility barrier a stale non-null read could survive a
         *  service teardown. Mirrors [CaptureService.instance]'s annotation. */
        @Volatile
        var instance: PlayTranslateAccessibilityService? = null

        /** True only when the service has bound to this process — i.e. methods
         *  on [instance] will actually do something. Distinct from [isEnabled]:
         *  the user can have the service enabled in system Settings while
         *  Android has not yet bound it to our process (cold start, post-unbind
         *  rebinding). Action gates that need a working service (capture,
         *  drag mode, region edits) must use this; display-state gates
         *  ("does the user have permission") should use [isEnabled]. */
        val isConnected: Boolean get() = instance != null

        /** Whether the user has enabled this app's accessibility service in
         *  system Settings. Fast-paths to `instance != null` once the service
         *  has bound to our process; falls back to the authoritative system
         *  setting otherwise, so a cold-started activity (or the QS tile in a
         *  fresh process) doesn't see a stale "disabled" state during the
         *  window before `onServiceConnected` fires. */
        fun isEnabled(ctx: Context): Boolean {
            if (instance != null) return true
            val enabled = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val component = ComponentName(ctx, PlayTranslateAccessibilityService::class.java)
            val full = component.flattenToString()
            val short = component.flattenToShortString()
            return enabled.split(':').any { it.equals(full, ignoreCase = true) || it.equals(short, ignoreCase = true) }
        }

        /** Single source of truth for "PlayTranslate is going inactive". Writes
         *  the pref, stops live mode if running, hides the floating icon, and
         *  refreshes the QS tile. Safe to call when the service isn't bound —
         *  the icon-hide is a no-op (no icon to hide). */
        fun disable(ctx: Context, reason: String) {
            Prefs(ctx).showOverlayIcon = false
            CaptureService.instance?.let { if (it.isLive) it.stopLive() }
            CaptureBackendResolver.activeOverlayUi?.hideFloatingIcon(reason)
            PlayTranslateTileService.TileSync.refresh(ctx)
        }

        /**
         * Static convenience for overlay owners that don't have a service
         * reference handy (MagnifierLens, WordLookupPopup, etc.). When the
         * service isn't connected the window is added without registration —
         * it just won't participate in clean-capture blanking.
         *
         * [displayId] must be the display the window will appear on so
         * [prepareForCleanCapture] can scope its blanking. There is no
         * default — silently falling back to [Display.DEFAULT_DISPLAY] for
         * a window actually shown on a secondary display would leak the
         * window into clean screenshots of that display.
         */
        fun addOverlay(
            view: View,
            wm: WindowManager,
            params: WindowManager.LayoutParams,
            displayId: Int,
        ): Boolean {
            instance?.let { return it.overlayHost.addOverlayWindow(view, wm, params, displayId) }
            OverlayHost.applyFullScreenOverlayDefaults(params)
            return try { wm.addView(view, params); true } catch (_: Exception) { false }
        }

        fun removeOverlay(view: View, wm: WindowManager) {
            // If the service is connected and the view is in the registry,
            // removeOverlayWindow handles both unregister + removeView. If
            // the view was added via the no-service fallback path of
            // [addOverlay] (service connected later), it's not in the
            // registry — fall through to a direct removeView so the window
            // doesn't leak.
            if (instance?.overlayHost?.removeOverlayWindow(view) == true) return
            try { wm.removeView(view) } catch (_: Exception) {}
        }
    }
}
