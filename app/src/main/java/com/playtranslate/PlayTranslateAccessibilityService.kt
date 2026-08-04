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
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyCharacterMap
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

    /** Wire hotkey callbacks to CaptureService. Safe to call multiple times.
     *
     *  HOLD combos drive the momentary hold-to-preview on [CaptureService];
     *  TAP combos are routed by [fireTapOnMain] — the auto/live toggle for the
     *  translation/furigana taps, a one-shot Capture for [HotkeyAssignment
     *  .CAPTURE_TAP] — through the active [OverlayUiController] (single- vs
     *  dual-screen / InAppOnly) just like the floating menu's buttons. The
     *  release leg only matters for HOLD (end the preview); a TAP already did
     *  its work on activation, except that a quick release of a hold sharing
     *  the tap's keys fires that tap too (one key = preview + action). */
    fun registerHotkeyCallbacks() {
        val svc = CaptureService.instance ?: return
        onHotkeyActivated = { assignment ->
            when (assignment.trigger) {
                HotkeyTrigger.HOLD -> {
                    // Stamp the hold-start so a quick release can be recognised
                    // as a tap when this combo is also bound to Tap.
                    hotkeyHoldActivatedAtMs = SystemClock.elapsedRealtime()
                    svc.hotkeyHoldStart(assignment.mode)
                }
                HotkeyTrigger.TAP -> fireTapOnMain(assignment)
            }
        }
        onHotkeyReleased = { assignment ->
            if (assignment.trigger == HotkeyTrigger.HOLD) {
                svc.hotkeyHoldEnd()
                // Instant-hold + tap-on-quick-release: if the same combo also
                // carries a Tap and the press was brief, fire that tap now —
                // so one key can both preview (hold) and run its tap action.
                // heldMs is measured here, at release, not at toggle time.
                val heldMs = SystemClock.elapsedRealtime() - hotkeyHoldActivatedAtMs
                val tap = tapOnQuickRelease(
                    releasedHold = assignment,
                    heldDurationMs = heldMs,
                    thresholdMs = TAP_HOLD_THRESHOLD_MS,
                    combos = currentHotkeyCombos(),
                )
                if (tap != null) fireTapOnMain(tap)
            }
        }
    }

    /**
     * Route a TAP-triggered [assignment] to its action. [HotkeyAssignment
     * .CAPTURE_TAP] runs a one-shot Capture (toggle-dismissing a showing
     * capture result); every other tap toggles that mode's auto/live session.
     * Shared by on-press activation and the hold's quick-release tap so the
     * capture binding is honored on both paths.
     */
    private fun fireTapOnMain(assignment: HotkeyAssignment) {
        if (assignment == HotkeyAssignment.CAPTURE_TAP) captureScreenOnMain()
        else toggleAutoModeOnMain(assignment.mode)
    }

    /**
     * Toggle the auto/live session for [mode], on the main thread. Hotkey
     * callbacks can fire off the main thread (onKeyEvent — see
     * [HotkeySetupDialog]), but the live-mode path asserts main
     * ([CaptureService.setLiveDisplays]). Re-checks [isUserReachable] at run
     * time so a press landing after the icon is hidden / app backgrounded
     * doesn't fire a ghost toggle — mirroring the activation gate in
     * [decideHotkeyAction].
     */
    private fun toggleAutoModeOnMain(mode: OverlayMode) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            debugHandler.post { toggleAutoModeOnMain(mode) }
            return
        }
        if (isUserReachable()) {
            (CaptureBackendResolver.activeOverlayUi ?: overlayUiController).toggleAutoMode(mode)
        }
    }

    /**
     * Run the "Capture screen" hotkey on the main thread. Like
     * [toggleAutoModeOnMain], hotkey callbacks can fire off the main thread but
     * the capture path shows an overlay and must run on main; and it re-checks
     * [isUserReachable] so a press landing after the icon is hidden / app
     * backgrounded doesn't fire a ghost capture. Targets the primary game
     * display (the user's last-interacted display when several are selected),
     * matching where the hotkey one-shots and the in-app panel already land.
     */
    private fun captureScreenOnMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            debugHandler.post { captureScreenOnMain() }
            return
        }
        if (!isUserReachable()) return
        val displayId = CaptureService.instance?.primaryGameDisplayId() ?: Display.DEFAULT_DISPLAY
        (CaptureBackendResolver.activeOverlayUi ?: overlayUiController)
            .toggleCaptureScreenForDisplay(displayId)
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
            val raw = screenshotManager?.requestClean(displayId)?.bitmap
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
            // Sanctioned manual crop: this debug path OCRs ACCESSIBILITY
            // frames only, which always contain the status bar.
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
                    // No pre-OCR icon blackout: this frame is a CLEAN capture
                    // (requestClean above blanks our windows pre-grab), so
                    // the floating icon is structurally absent — filling its
                    // rect would hide real content from the debug overlay.
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
    /** Derived from [heldGameKeys] so a multi-key release pattern
     *  (press A → press B → release A) reports B as still held instead
     *  of incorrectly flipping to false on A's UP event. Single source
     *  of truth: only the key event handler mutates heldGameKeys.
     *
     *  Reads [heldGameKeys], NOT the wider [heldKeyCodes]: "is the user
     *  mid-interaction" must stay a question about the game controls, so
     *  holding a keyboard key does not suppress overlay presentation. */
    private val buttonHeld: Boolean get() = heldGameKeys.isNotEmpty()
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
     * True while the player is actively working the controls (game button
     * held or touch down) — meant to keep the overlay from appearing mid-input.
     *
     * Currently unread: the CaptureService check this was written for is gone.
     * Kept wired (and split off [heldGameKeys] rather than the wider
     * [heldKeyCodes]) so that whoever re-adds it gets the gameplay-only answer
     * instead of counting keyboard typing as interaction.
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
        heldKeyCodes.clear()
        heldGameKeys.clear()
        touchActive = false
        addTouchSentinel(displayId)
    }

    /** Stop monitoring input for a single display. Tears down THIS display's
     *  touch sentinel; global state (held keys, touchActive) only
     *  resets when the last listener goes away. */
    fun stopInputMonitoring(displayId: Int) {
        onGameInputs.remove(displayId)
        overlayHost.removeTouchSentinel(displayId)
        if (onGameInputs.isEmpty()) {
            heldKeyCodes.clear()
            heldGameKeys.clear()
            touchActive = false
            debugHandler.removeCallbacks(touchTimeoutRunnable)
        }
    }

    /** Stop input monitoring across every display (e.g. on stopLive). */
    fun stopInputMonitoring() {
        onGameInputs.clear()
        heldKeyCodes.clear()
        heldGameKeys.clear()
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
            // Gated by "Touches refresh translation": when off, a screen touch
            // still updates touch/hotkey state above but no longer dismisses-
            // and-recaptures. Gamepad input is unaffected — it routes through
            // fireOnGameInput (not ForDisplay), not this sentinel.
            if (Prefs(this).touchesRefreshTranslation) {
                fireOnGameInputForDisplay(displayId)
            }
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

    /**
     * Longest a hold may last and still count as a "tap" when the same combo is
     * bound to both Hold and Tap. The hold preview shows immediately on press
     * (instant hold); releasing under this window also fires the tap toggle (so
     * the preview just "flashes"), while a longer press is a pure hold. Tunable
     * — lower it to make quick hold-peeks less likely to register as taps.
     */
    private val TAP_HOLD_THRESHOLD_MS = 350L

    /** Every hotkey-eligible key currently down — the input to
     *  [checkHotkeyCombos]. Includes keyboards (see [isHotkeySource]). */
    private val heldKeyCodes = mutableSetOf<Int>()

    /** The gameplay-sourced subset of [heldKeyCodes] (see [isGameInputSource]),
     *  feeding [buttonHeld] / [isInputActive]. Tracked separately so widening
     *  the hotkey gate to keyboards did not also widen "the player is actively
     *  using the controls" — the two questions share a key-event stream but
     *  not an answer. The live consumer of that second answer is
     *  [fireOnGameInput] (live-mode overlay invalidation); [isInputActive] is
     *  currently unread. */
    private val heldGameKeys = mutableSetOf<Int>()

    private var activeHotkeyAssignment: HotkeyAssignment? = null
    private var pendingActivationAssignment: HotkeyAssignment? = null

    /** [SystemClock.elapsedRealtime] when the current hold preview started.
     *  Used to classify a quick release as a tap (see [registerHotkeyCallbacks]
     *  and [tapOnQuickRelease]). Only meaningful while a hold is active. */
    private var hotkeyHoldActivatedAtMs = 0L

    /** Callback when a hotkey combo activates (HOLD: hold-start preview;
     *  TAP: toggle the auto session). */
    var onHotkeyActivated: ((HotkeyAssignment) -> Unit)? = null
    /** Callback when the active hotkey combo is released (HOLD: hold-end;
     *  TAP: no-op — the toggle already fired on activation). */
    var onHotkeyReleased: ((HotkeyAssignment) -> Unit)? = null

    private val pendingActivationRunnable = Runnable {
        val assignment = pendingActivationAssignment ?: return@Runnable
        pendingActivationAssignment = null
        // Re-check reachability: the gate may have closed during the
        // deferral window (user backgrounded the app while mid-chord).
        if (!isUserReachable()) {
            android.util.Log.d("HotkeyDbg", "DEFERRED cancelled (gate closed): $assignment")
            return@Runnable
        }
        activeHotkeyAssignment = assignment
        android.util.Log.d("HotkeyDbg", "ACTIVATED (deferred): $assignment")
        onHotkeyActivated?.invoke(assignment)
    }

    /** The active hotkey combos for the *current* source language: read from
     *  prefs, unset ones dropped, and — matching the Hotkeys page / digest —
     *  the Furigana/Pinyin combos excluded when the source has no reading hint,
     *  so a stale binding from a prior language can't fire invisibly. */
    private fun currentHotkeyCombos(): List<HotkeyCombo> {
        val prefs = Prefs(this)
        return buildHotkeyCombos(
            translationHold = prefs.hotkeyTranslation,
            furiganaHold = prefs.hotkeyFurigana,
            translationTap = prefs.hotkeyTranslationTap,
            furiganaTap = prefs.hotkeyFuriganaTap,
            captureTap = prefs.hotkeyCaptureTap,
            hasReadingHint = SourceLanguageProfiles[prefs.sourceLangId].hintTextKind != HintTextKind.NONE,
        )
    }

    private fun checkHotkeyCombos() {
        val combos = currentHotkeyCombos()

        val state = HotkeyState(activeHotkeyAssignment, pendingActivationAssignment)
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
                pendingActivationAssignment = null
                // Latch as "active" (so a key-up releases it) only while the keys
                // are still held. A tap fired on quick-release of a shadowed combo
                // has its keys already up; latching it would swallow an immediate
                // re-tap as "still active".
                val activatedKeys = combos.firstOrNull { it.assignment == action.assignment }?.keys.orEmpty()
                activeHotkeyAssignment =
                    if (shouldLatchActive(activatedKeys, heldKeyCodes)) action.assignment else null
                android.util.Log.d(
                    "HotkeyDbg",
                    "ACTIVATED: ${action.assignment} (latched=${activeHotkeyAssignment != null})"
                )
                onHotkeyActivated?.invoke(action.assignment)
            }

            is HotkeyAction.DeferActivation -> {
                debugHandler.removeCallbacks(pendingActivationRunnable)
                pendingActivationAssignment = action.assignment
                android.util.Log.d(
                    "HotkeyDbg",
                    "DEFERRED: ${action.assignment} (waiting ${HOTKEY_COMBO_WINDOW_MS}ms for possible superset)"
                )
                debugHandler.postDelayed(pendingActivationRunnable, HOTKEY_COMBO_WINDOW_MS)
            }

            HotkeyAction.Release -> {
                val released = activeHotkeyAssignment
                activeHotkeyAssignment = null
                android.util.Log.d("HotkeyDbg", "RELEASED: $released")
                released?.let { onHotkeyReleased?.invoke(it) }
            }

            HotkeyAction.ClearPending -> {
                debugHandler.removeCallbacks(pendingActivationRunnable)
                val cleared = pendingActivationAssignment
                pendingActivationAssignment = null
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

        // Two questions, two answers. "Can this drive a hotkey?" admits
        // keyboards; "is the player working the controls?" does not — see
        // [isHotkeySource] / [isGameInputSource].
        val hotkeyInput = isHotkeyEligible(event)
        val gameInput = isGameInputSource(event.source) || KeyEvent.isGamepadButton(event.keyCode)

        // Gameplay input is a strict subset of hotkey-eligible input (narrower
        // source mask, same keycode escape hatch), so this early return cannot
        // drop a gameplay event. Preserve that containment if either widens.
        if (!hotkeyInput) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                heldKeyCodes.add(event.keyCode)
                if (gameInput) {
                    heldGameKeys.add(event.keyCode)
                    // "The player pressed a button → they're back in the game,
                    // clear the lookup" predates the lens taking window focus
                    // for controller navigation. While a focusable sticky lens
                    // is up, the nav keys (A/B/dpad/confirm) are DRIVING it —
                    // dismissing on them killed the lens on the very press
                    // meant to select its pill (field-reproduced: A over a
                    // drag lookup removed LensRoot in the same millisecond).
                    // Non-nav buttons keep the resume-play semantics.
                    val lensOwned = overlayUiController.isAnyDragLookupConsumingController &&
                        com.playtranslate.ui.ControllerKeys.consumesForNav(event.keyCode)
                    if (!lensOwned) {
                        if (overlayUiController.isAnyDragLookupPopupShowing) {
                            overlayUiController.dismissAllDragLookupPopups()
                        }
                        fireOnGameInput()
                    }
                }
                checkHotkeyCombos()
            }
            KeyEvent.ACTION_UP -> {
                heldKeyCodes.remove(event.keyCode)
                // Releases keep the held-set bookkeeping and hotkey logic
                // but do NOT fire game input: a press already fired on
                // DOWN (and held buttons auto-repeat DOWN), so firing on
                // UP dismissed the overlays twice per press and restarted
                // the overlay-return wait at release.
                if (gameInput) heldGameKeys.remove(event.keyCode)
                checkHotkeyCombos()
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

        /**
         * Whether [event] may drive a hotkey — the single composition site for
         * the source policy in [isHotkeySource] and the gamepad-*keycode*
         * escape hatch (a controller whose source mask advertises neither
         * GAMEPAD nor DPAD still reports BUTTON_A and friends).
         *
         * Shared by hotkey *dispatch* ([onKeyEvent]) and hotkey *capture*
         * ([HotkeySetupDialog]) so the two cannot disagree. They used to: the
         * dialog accepted any key, dispatch accepted only gamepad-sourced
         * ones, so binding a keyboard key appeared to succeed and then never
         * fired.
         *
         * Deliberately permissive about keys that type. Refusing them was
         * tried and reverted: every comparable tool (GameSentenceMiner,
         * LunaTranslator) lets any key be bound, and the cost of refusing is
         * paid by everyone while the cost of allowing is paid only by someone
         * who picks a letter and can rebind. [typesText] drives a warning at
         * setup instead of a veto here.
         */
        fun isHotkeyEligible(event: KeyEvent): Boolean =
            isHotkeySource(event.source) || KeyEvent.isGamepadButton(event.keyCode)

        /**
         * Keys that type but that [KeyEvent.isPrintingKey] calls non-printing.
         * It classifies by Unicode category, and space is a SPACE_SEPARATOR
         * rather than a printing glyph — so the SDK test alone would warn on
         * "T" and stay quiet on " ", which reads as arbitrary to anyone
         * binding one.
         *
         * Enter, Tab and the deletes are deliberately absent: they edit text
         * but are command keys in most UIs, and warning on them would nag
         * controller and TV-remote users about ordinary bindings.
         */
        private val TEXT_KEYS_MISSED_BY_PRINTING_TEST = setOf(KeyEvent.KEYCODE_SPACE)

        /**
         * Whether [event]'s key types a character on the device that sent it.
         * Feeds the setup warning (see [comboTakesTypingKey]); never blocks a
         * binding.
         */
        fun typesText(event: KeyEvent): Boolean =
            isKeyboardSource(event.source) &&
                (event.keyCode in TEXT_KEYS_MISSED_BY_PRINTING_TEST || producesGlyph(event))

        /**
         * [KeyEvent.isPrintingKey] resolves the sending device's key character
         * map and throws once that device is gone — a live possibility for a
         * USB keyboard unplugged mid-keystroke, and an uncaught throw in
         * [onKeyEvent] would take the service and every overlay down with it.
         * Unknown counts as printing: a hotkey that declines to fire is a much
         * better failure than a capture nobody asked for.
         */
        private fun producesGlyph(event: KeyEvent): Boolean = try {
            event.isPrintingKey
        } catch (e: KeyCharacterMap.UnavailableException) {
            Log.w(TAG, "isPrintingKey unavailable for keyCode=${event.keyCode}", e)
            true
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
