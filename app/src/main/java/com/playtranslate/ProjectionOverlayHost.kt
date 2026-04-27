package com.playtranslate

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.playtranslate.language.HintTextKind
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.ui.FloatingIconMenu
import com.playtranslate.ui.FloatingOverlayIcon
import com.playtranslate.ui.TranslationOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume

private const val TAG = "ProjectionOverlay"

/**
 * Owns the Share-Screen ("MediaProjection") session: a [MediaProjection]
 * instance, a [VirtualDisplay] mirroring the primary display into an
 * [ImageReader], and the floating icon / translation overlay / region
 * indicator views — all using `TYPE_APPLICATION_OVERLAY` (which only
 * requires `SYSTEM_ALERT_WINDOW`, not the Accessibility permission).
 *
 * Implements both [OverlayHost] (UI surface) and [ScreenshotProvider]
 * (capture surface) so [CaptureService] can route through a single object
 * regardless of whether the user is in accessibility or projection mode.
 *
 * Lifecycle:
 *  1. [CaptureService.startProjection] passes `(resultCode, data)` from
 *     the system screen-capture consent dialog.
 *  2. We obtain the [MediaProjection] and create a [VirtualDisplay].
 *  3. The host stays alive until [stop] is called (or the projection is
 *     revoked by the system, in which case the registered callback also
 *     calls [stop]).
 */
class ProjectionOverlayHost private constructor(
    private val service: CaptureService,
    private val projection: MediaProjection,
    private var captureWidth: Int,
    private var captureHeight: Int,
    private var captureDpi: Int,
) : OverlayHost, ScreenshotProvider {

    private val context: Context = service
    private val main = Handler(Looper.getMainLooper())
    private val displayManager = context.getSystemService(DisplayManager::class.java)

    // ── Capture (VirtualDisplay + ImageReader) ───────────────────────────

    private val captureThread = HandlerThread("ProjCapture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)
    private val bitmapExecutor = Executors.newSingleThreadExecutor()

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var stopped = false

    /** Latest captured image (raw RGBA, owned by [imageReader]). */
    @Volatile private var latestImage: Image? = null
    private val imageLock = Any()

    /** Soft mirror of [Image] — converted to Bitmap on demand to avoid blocking the reader thread. */
    @Volatile private var lastFrameTimeNs: Long = 0L
    @Volatile private var appInitiatedStop: Boolean = false

    /** Re-fires automatically when MediaProjection is revoked (e.g. user
     *  taps "Stop sharing" in the system notification). */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system")
            main.post {
                if (instance !== this@ProjectionOverlayHost) {
                    stop(skipProjectionStop = true)
                    return@post
                }
                if (appInitiatedStop) {
                    stop(skipProjectionStop = true)
                } else {
                    service.stopProjection()
                }
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) main.post { handlePrimaryDisplayChanged() }
        }
    }

    init {
        // Android 14+ requires the callback to be registered BEFORE
        // createVirtualDisplay or it throws SecurityException.
        projection.registerCallback(projectionCallback, main)
        setupVirtualDisplay()
        displayManager?.registerDisplayListener(displayListener, main)
    }

    private fun createImageReader(width: Int, height: Int): ImageReader {
        val reader = ImageReader.newInstance(
            width, height, PixelFormat.RGBA_8888, /* maxImages = */ 2,
        )
        reader.setOnImageAvailableListener({ r ->
            // Acquire the latest image and discard the previous one to avoid backpressure.
            val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            synchronized(imageLock) {
                latestImage?.close()
                latestImage = img
                lastFrameTimeNs = System.nanoTime()
            }
        }, captureHandler)
        return reader
    }

    private fun setupVirtualDisplay() {
        val reader = createImageReader(captureWidth, captureHeight)
        imageReader = reader

        try {
            virtualDisplay = projection.createVirtualDisplay(
                "PlayTranslate-Capture",
                captureWidth, captureHeight, captureDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                /* callback = */ null,
                captureHandler,
            )
        } catch (e: Exception) {
            imageReader = null
            try { reader.close() } catch (_: Exception) {}
            throw e
        }
    }

    private fun recreateVirtualDisplay(width: Int, height: Int, dpi: Int): Boolean {
        if (width <= 0 || height <= 0 || dpi <= 0) return false
        val vd = virtualDisplay ?: return false
        val oldReader = imageReader
        val reader = try {
            createImageReader(width, height)
        } catch (e: Exception) {
            Log.e(TAG, "ImageReader resize failed", e)
            return false
        }
        synchronized(imageLock) {
            latestImage?.close()
            latestImage = null
        }
        try {
            vd.resize(width, height, dpi)
            vd.setSurface(reader.surface)
        } catch (e: Exception) {
            Log.e(TAG, "VirtualDisplay resize failed", e)
            try { vd.resize(captureWidth, captureHeight, captureDpi) } catch (_: Exception) {}
            try { oldReader?.let { vd.setSurface(it.surface) } } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}
            return false
        }
        imageReader = reader
        try { oldReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { oldReader?.close() } catch (_: Exception) {}
        captureWidth = width
        captureHeight = height
        captureDpi = dpi
        lastCaptureTimeMs = 0L
        return true
    }

    private fun handlePrimaryDisplayChanged() {
        if (stopped) return
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val (w, h, dpi) = primaryDisplayMetrics(display)
        if (w <= 0 || h <= 0 || dpi <= 0) {
            main.postDelayed({ handlePrimaryDisplayChanged() }, 120L)
            return
        }
        val sizeChanged = w != captureWidth || h != captureHeight || dpi != captureDpi
        if (sizeChanged) {
            Log.i(TAG, "Primary display changed: ${captureWidth}x$captureHeight@$captureDpi -> ${w}x$h@$dpi")
            if (recreateVirtualDisplay(w, h, dpi)) {
                hideTranslationOverlay()
                service.refreshLiveOverlay()
            }
        }
        if (Prefs(context).showOverlayIcon) {
            showFloatingIconInternal(display, Prefs(context))
        }
    }

    // ── ScreenshotProvider ───────────────────────────────────────────────

    override var lastCleanPath: String? = null
        private set

    /** Minimum interval between clean captures so the compositor can
     *  flush an overlay-free frame after our hide(). */
    private val MIN_CLEAN_FRAME_DELAY_MS = 120L
    private var lastCaptureTimeMs = 0L

    override suspend fun requestClean(displayId: Int): Bitmap? {
        val state = withContext(Dispatchers.Main) { prepareForCleanCapture() }
        // Wait for the compositor to draw a frame without our overlays.
        // ImageReader picks frames at ~refresh-rate; 2 vsyncs is the
        // minimum, plus a safety margin.
        waitVsync(2)
        delay(40)
        val bmp = grabLatestBitmap()
        withContext(Dispatchers.Main) { restoreAfterCapture(state) }
        lastCaptureTimeMs = System.currentTimeMillis()
        if (bmp == null) DetectionLog.log("Projection clean capture failed")
        return bmp
    }

    override suspend fun requestRaw(displayId: Int, onCaptured: (() -> Unit)?): Bitmap? {
        val bmp = grabLatestBitmap()
        if (bmp != null) onCaptured?.invoke()
        else DetectionLog.log("Projection raw capture failed")
        lastCaptureTimeMs = System.currentTimeMillis()
        return bmp
    }

    /** Convert the most recent [Image] frame from the ImageReader to a software Bitmap. */
    private suspend fun grabLatestBitmap(): Bitmap? = withContext(Dispatchers.Default) {
        val deadline = System.currentTimeMillis() + 1500L
        while (System.currentTimeMillis() < deadline) {
            val img = synchronized(imageLock) { latestImage }
            if (img != null) {
                try {
                    return@withContext imageToBitmap(img)
                } catch (e: Exception) {
                    Log.w(TAG, "imageToBitmap failed: ${e.message}")
                    return@withContext null
                }
            }
            delay(20)
        }
        null
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width
        // Width may need to include row padding so Bitmap.createBitmap()
        // copies the buffer at the right stride. Then crop down.
        val bmp = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888,
        )
        bmp.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) bmp else {
            val cropped = Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
            bmp.recycle()
            cropped
        }
    }

    override fun saveToCache(bitmap: Bitmap): String? {
        return try {
            val dir = File(context.cacheDir, "screenshots").apply { mkdirs() }
            val file = File(dir, "capture.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            lastCleanPath = file.absolutePath
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "saveToCache failed: ${e.message}")
            null
        }
    }

    // ── Continuous loop (live mode) ──────────────────────────────────────

    private var loopJob: Job? = null
    @Volatile private var cleanRequested = false

    override fun startLoop(
        displayId: Int,
        scope: CoroutineScope,
        onCleanFrame: (Bitmap) -> Unit,
        onRawFrame: (Bitmap) -> Unit,
    ) {
        stopLoop()
        loopJob = scope.launch {
            while (isActive) {
                val pollMs = maxOf(Prefs(context).captureIntervalMs, MIN_CLEAN_FRAME_DELAY_MS)
                val elapsed = System.currentTimeMillis() - lastCaptureTimeMs
                val waitMs = pollMs - elapsed
                if (waitMs > 0) delay(waitMs)
                val isClean = cleanRequested
                if (isClean) {
                    cleanRequested = false
                    val bmp = requestClean(displayId)
                    if (bmp != null) onCleanFrame(bmp)
                } else {
                    val bmp = grabLatestBitmap()
                    lastCaptureTimeMs = System.currentTimeMillis()
                    if (bmp != null) onRawFrame(bmp)
                }
            }
        }
    }

    override fun requestCleanCapture() { cleanRequested = true }

    override fun stopLoop() {
        loopJob?.cancel()
        loopJob = null
    }

    override val isLoopRunning: Boolean get() = loopJob?.isActive == true

    override fun destroy() {
        destroy(stopProjection = true)
    }

    private fun destroy(stopProjection: Boolean) {
        stopLoop()
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Throwable) {}
        imageReader = null
        synchronized(imageLock) {
            try { latestImage?.close() } catch (_: Throwable) {}
            latestImage = null
        }
        try { displayManager?.unregisterDisplayListener(displayListener) } catch (_: Throwable) {}
        try { projection.unregisterCallback(projectionCallback) } catch (_: Throwable) {}
        if (stopProjection) {
            appInitiatedStop = true
            try { projection.stop() } catch (_: Throwable) {}
        }
        try { captureThread.quitSafely() } catch (_: Throwable) {}
        try { bitmapExecutor.shutdown() } catch (_: Throwable) {}
    }

    // ── OverlayHost: floating icon ───────────────────────────────────────

    override var floatingIcon: FloatingOverlayIcon? = null
        private set

    private var floatingIconWm: WindowManager? = null
    private var floatingIconDisplayId: Int = -1

    override fun ensureFloatingIcon() {
        val prefs = Prefs(context)
        if (!prefs.showOverlayIcon) {
            hideFloatingIcon("pref_disabled")
            return
        }
        // Projection mode supports primary display only for the icon.
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        if (floatingIcon != null && floatingIconDisplayId == display.displayId) {
            val icon = floatingIcon ?: return
            icon.setPosition(prefs.overlayIconEdge, prefs.overlayIconFraction)
            try { floatingIconWm?.updateViewLayout(icon, icon.params) } catch (_: Exception) {}
            return
        }
        showFloatingIconInternal(display, prefs)
    }

    private fun showFloatingIconInternal(display: Display, prefs: Prefs) {
        hideFloatingIcon("recreating")
        val displayCtx = context.createDisplayContext(display)
        val wm = displayCtx.getSystemService(WindowManager::class.java) ?: return

        val icon = FloatingOverlayIcon(displayCtx).apply {
            this.wm = wm
            windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            compactMode = prefs.compactOverlayIcon
        }

        val params = WindowManager.LayoutParams(
            icon.viewSizePx, icon.viewSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        icon.params = params

        icon.onPositionChanged = { edge, fraction ->
            prefs.overlayIconEdge = edge
            prefs.overlayIconFraction = fraction
        }
        // Tap → show the floating icon menu (Settings / Auto Translate /
        // Capture Region / Hide), mirroring the accessibility-mode UX.
        // Hold still triggers a one-shot via service.holdStart/End below.
        icon.onTap = {
            showFloatingMenu(display)
        }
        icon.onHoldStart = { service.holdStart() }
        icon.onHoldEnd = { service.holdEnd() }
        icon.onHoldCancel = { service.holdCancel() }

        try {
            wm.addView(icon, params)
            icon.setPosition(prefs.overlayIconEdge, prefs.overlayIconFraction)
            wm.updateViewLayout(icon, params)
            floatingIconWm = wm
            floatingIcon = icon
            floatingIconDisplayId = display.displayId
            // Mirror of accessibility-mode side-effects so foreground state
            // and live-icon synchronization stay correct.
            service.updateForegroundState()
            service.syncIconState()
        } catch (e: Exception) {
            Log.e(TAG, "showFloatingIcon: addView failed", e)
        }
    }

    override fun bringFloatingIconToFront() {
        val icon = floatingIcon ?: return
        val wm = floatingIconWm ?: return
        if (icon.inDragMode) return
        try {
            wm.removeView(icon)
            wm.addView(icon, icon.params)
        } catch (_: Exception) {}
    }

    override fun hideFloatingIcon(reason: String) {
        Log.i(TAG, "hideFloatingIcon: $reason")
        dismissFloatingMenu()
        floatingIcon?.destroy()
        try { floatingIcon?.let { floatingIconWm?.removeView(it) } } catch (_: Exception) {}
        floatingIcon = null
        floatingIconWm = null
        floatingIconDisplayId = -1
        if (reason != "recreating" && reason != "projection_stop") service.updateForegroundState()
    }

    override fun getFloatingIconRect(): Rect? {
        val icon = floatingIcon ?: return null
        val p = icon.params ?: return null
        return Rect(p.x, p.y, p.x + icon.viewSizePx, p.y + icon.viewSizePx)
    }

    // ── Floating icon menu ───────────────────────────────────────────────
    //
    // Mirrors PlayTranslateAccessibilityService.showFloatingMenu, but
    // re-hosts the [FloatingIconMenu] view as a TYPE_APPLICATION_OVERLAY
    // window (we don't have the accessibility-overlay window type in
    // Share-Screen mode) and routes its callbacks through [CaptureService]
    // and back into this host. The menu still drag-selects regions; we
    // just translate them via a one-shot or refresh the live overlay.

    private var floatingMenu: FloatingIconMenu? = null
    private var floatingMenuWm: WindowManager? = null

    private fun showFloatingMenu(display: Display) {
        val icon = floatingIcon ?: return
        dismissFloatingMenu()
        val displayCtx = context.createDisplayContext(display)
        val wm = displayCtx.getSystemService(WindowManager::class.java) ?: return
        val prefs = Prefs(context)
        val themeRes = when (prefs.themeIndex) {
            1    -> R.style.Theme_PlayTranslate_White
            2    -> R.style.Theme_PlayTranslate_Rainbow
            3    -> R.style.Theme_PlayTranslate_Purple
            else -> R.style.Theme_PlayTranslate
        }
        val themedCtx = android.view.ContextThemeWrapper(displayCtx, themeRes)
        val menu = FloatingIconMenu(themedCtx)
        menu.isSingleScreen = Prefs.isSingleScreen(context)

        // Suppress live captures while the menu is up so the dimmed
        // overlay doesn't get repainted with a stale frame.
        service.holdActive = true
        hideTranslationOverlay()

        val hintKind = SourceLanguageProfiles[prefs.sourceLangId].hintTextKind
        menu.hintModeLabel = if (prefs.overlayMode == OverlayMode.FURIGANA && hintKind != HintTextKind.NONE) {
            when (hintKind) { HintTextKind.PINYIN -> "Pinyin"; else -> "Furigana" }
        } else null
        menu.isLiveMode = service.isLive
        menu.activeRegion = service.activeRegion

        menu.onHideIcon = {
            dismissFloatingMenu()
            prefs.showOverlayIcon = false
            openMainSettings()
            service.stopProjection()
        }
        menu.onHideTemporary = {
            dismissFloatingMenu()
            openMainSettings()
            service.stopProjection()
        }
        menu.onCloseRequested = {
            dismissFloatingMenu()
            prefs.showOverlayIcon = false
            openMainSettings()
            service.stopProjection()
        }
        menu.onDismiss = {
            val needsRefresh = floatingMenu != null && service.isLive
            dismissFloatingMenu()
            if (needsRefresh) service.refreshLiveOverlay()
        }
        menu.onToggleLive = {
            dismissFloatingMenu()
            if (service.isLive) {
                service.stopLive()
            } else {
                if (!service.isConfigured) {
                    val entry = prefs.getSelectedRegion()
                    service.configureSaved(displayId = prefs.captureDisplayId, region = entry)
                }
                service.startLive()
            }
        }
        menu.onRegionSelected = { region ->
            dismissFloatingMenu()
            service.configureOverride(region)
            if (service.isLive) {
                hideTranslationOverlay()
                service.refreshLiveOverlay()
            } else {
                service.translateOnceOnScreen()
            }
        }
        menu.onClearRegion = {
            prefs.selectedRegionId = Prefs.DEFAULT_REGION_LIST[0].id
            if (service.isConfigured) {
                val entry = Prefs.DEFAULT_REGION_LIST[0]
                service.configureSaved(displayId = prefs.captureDisplayId, region = entry)
            }
        }
        menu.onCaptureRegion = {
            // Custom region authoring needs the in-app sheet UI; route
            // through MainActivity. Drag-to-select inside the menu still
            // works for ad-hoc one-shot regions without leaving the game.
            dismissFloatingMenu()
            val launch = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_ADD_CUSTOM_REGION
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(launch)
        }
        menu.onSettings = {
            dismissFloatingMenu()
            val launch = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_SETTINGS
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(launch)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Touch-modal: the menu is the user's only interaction surface
            // while it's up. NOT_FOCUSABLE keeps the IME from popping up.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        try {
            wm.addView(menu, params)
        } catch (e: Exception) {
            Log.e(TAG, "showFloatingMenu: addView failed", e)
            service.holdActive = false
            return
        }
        floatingMenuWm = wm
        floatingMenu = menu

        // Compute icon center in screen coords for the popup anchor.
        val p = icon.params
        val iconCx = (p?.x ?: 0) + icon.viewSizePx / 2
        val iconCy = (p?.y ?: 0) + icon.viewSizePx / 2
        val (sw, sh) = primaryDisplaySize(display)
        menu.positionNearIcon(iconCx, iconCy, icon.currentEdge, sw, sh)
    }

    private fun dismissFloatingMenu() {
        val wasShowing = floatingMenu != null
        try { floatingMenu?.let { floatingMenuWm?.removeView(it) } } catch (_: Exception) {}
        floatingMenu = null
        floatingMenuWm = null
        if (wasShowing) service.holdActive = false
    }

    private fun openMainSettings() {
        val launch = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_SETTINGS
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try { context.startActivity(launch) } catch (_: Exception) {}
    }

    private fun primaryDisplaySize(display: Display): Pair<Int, Int> {
        val size = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        return size.x to size.y
    }

    // ── OverlayHost: translation overlay ─────────────────────────────────

    private var translationOverlayView: TranslationOverlayView? = null
    private var translationOverlayWm: WindowManager? = null
    private var translationOverlayDisplayId: Int = -1

    override fun showTranslationOverlay(
        display: Display,
        boxes: List<TranslationOverlayView.TextBox>,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int,
        pinholeMode: Boolean,
    ) {
        floatingIcon?.showLoading = false

        val existing = translationOverlayView
        if (existing != null
            && translationOverlayDisplayId == display.displayId
            && existing.pinholeMode == pinholeMode) {
            existing.setBoxes(boxes, cropLeft, cropTop, screenshotW, screenshotH)
            return
        }
        hideTranslationOverlay()
        val displayCtx = context.createDisplayContext(display)
        val themedCtx = android.view.ContextThemeWrapper(displayCtx, android.R.style.Theme_DeviceDefault)
        val wm = displayCtx.getSystemService(WindowManager::class.java) ?: return
        val view = TranslationOverlayView(themedCtx, pinholeMode = pinholeMode).apply {
            setBoxes(boxes, cropLeft, cropTop, screenshotW, screenshotH)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_TOUCHABLE on its own is supposed to be enough to pass
            // touches through, but some OEM Android builds (e.g. MIUI /
            // HyperOS) only honor pass-through when NOT_TOUCH_MODAL is
            // also set on a MATCH_PARENT window. Setting both is safe and
            // matches the behavior we want everywhere: the overlay is
            // visual-only, every touch goes to whatever app sits below.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { windowAnimations = 0 }
        try { wm.addView(view, params) } catch (e: Exception) {
            Log.e(TAG, "showTranslationOverlay: addView failed", e); return
        }
        translationOverlayWm = wm
        translationOverlayView = view
        translationOverlayDisplayId = display.displayId

        // The accessibility-mode pinhole live mode relies on a second
        // "dirty" overlay window for its change-detection algorithm. The
        // projection-mode polled live mode doesn't use pinhole detection
        // at all, so we deliberately skip adding the dirty overlay here:
        // a second MATCH_PARENT window is just one more chance for an
        // OEM input router to misbehave, and showing it gains nothing.
    }

    override fun hideTranslationOverlay() {
        try { translationOverlayView?.let { translationOverlayWm?.removeView(it) } } catch (_: Exception) {}
        translationOverlayView = null
        translationOverlayWm = null
        translationOverlayDisplayId = -1
    }

    override fun removeOverlayBoxes(toRemove: List<TranslationOverlayView.TextBox>) {
        translationOverlayView?.removeBoxesByContent(toRemove)
    }

    // ── OverlayHost: region indicator ────────────────────────────────────

    private var regionIndicatorView: View? = null
    private var regionIndicatorWm: WindowManager? = null
    private var regionIndicatorPersistent = false
    private val regionIndicatorHandler = Handler(Looper.getMainLooper())

    override fun showRegionIndicator(display: Display, region: RegionEntry, persistent: Boolean) {
        hideRegionIndicator(force = true)
        if (region.isFullScreen) return

        val ctx = context.createDisplayContext(display)
        val wm = ctx.getSystemService(WindowManager::class.java) ?: return
        val dp = ctx.resources.displayMetrics.density
        val displayLabel = region.label

        val accentColor = OverlayColors.accent(context)
        val bgColor = OverlayColors.bg(context)

        val view = object : View(ctx) {
            private val dimPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(200, 0, 0, 0)
                style = android.graphics.Paint.Style.FILL
            }
            private val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = accentColor
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2f * dp
            }
            private val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = bgColor
                textSize = 12f * dp
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            }
            private val labelBgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = accentColor
                style = android.graphics.Paint.Style.FILL
            }
            private val labelPadH = 10f * dp
            private val labelPadV = 4f * dp
            private val labelRadius = 6f * dp
            private val labelMargin = 8f * dp

            override fun onDraw(canvas: android.graphics.Canvas) {
                val w = width.toFloat()
                val h = height.toFloat()
                val l = w * region.left
                val t = h * region.top
                val r = w * region.right
                val b = h * region.bottom
                if (t > 0f) canvas.drawRect(0f, 0f, w, t, dimPaint)
                if (b < h) canvas.drawRect(0f, b, w, h, dimPaint)
                if (l > 0f) canvas.drawRect(0f, t, l, b, dimPaint)
                if (r < w) canvas.drawRect(r, t, w, b, dimPaint)
                val half = borderPaint.strokeWidth / 2f
                canvas.drawRect(l - half, t - half, r + half, b + half, borderPaint)
                val cx = (l + r) / 2f
                val textW = textPaint.measureText(displayLabel)
                val textH = textPaint.descent() - textPaint.ascent()
                val pillW = textW + labelPadH * 2
                val pillH = textH + labelPadV * 2
                val aboveY = t - labelMargin - pillH
                val labelTop = if (aboveY >= 0) aboveY else b + labelMargin
                val labelBottom = labelTop + pillH
                canvas.drawRoundRect(cx - pillW / 2, labelTop, cx + pillW / 2, labelBottom,
                    labelRadius, labelRadius, labelBgPaint)
                val textY = labelTop + labelPadV - textPaint.ascent()
                canvas.drawText(displayLabel, cx, textY, textPaint)
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        try { wm.addView(view, params) } catch (_: Exception) { return }
        regionIndicatorView = view
        regionIndicatorWm = wm
        regionIndicatorPersistent = persistent

        if (!persistent) {
            regionIndicatorHandler.postDelayed({
                view.animate().alpha(0f).setDuration(600L)
                    .withEndAction { hideRegionIndicator(force = true) }
                    .start()
            }, 400L)
        }
    }

    override fun hideRegionIndicator(force: Boolean) {
        if (!force && regionIndicatorPersistent) return
        regionIndicatorHandler.removeCallbacksAndMessages(null)
        val view = regionIndicatorView
        if (view != null) {
            view.animate().cancel()
            view.visibility = View.INVISIBLE
            try { regionIndicatorWm?.removeView(view) } catch (_: Exception) {}
        }
        regionIndicatorView = null
        regionIndicatorWm = null
        regionIndicatorPersistent = false
    }

    // ── OverlayHost: clean-capture state ─────────────────────────────────

    override fun prepareForCleanCapture(): OverlayState {
        val state = OverlayState(
            hadTranslation = translationOverlayView != null,
            hadDebug = false,
            hadRegionIndicator = regionIndicatorView != null,
        )
        if (state.hadRegionIndicator) regionIndicatorView?.visibility = View.INVISIBLE
        if (state.hadTranslation) translationOverlayView?.visibility = View.INVISIBLE
        return state
    }

    override fun restoreAfterCapture(state: OverlayState) {
        if (state.hadTranslation) translationOverlayView?.visibility = View.VISIBLE
        if (state.hadRegionIndicator && floatingIcon?.inDragMode != true) {
            regionIndicatorView?.visibility = View.VISIBLE
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    private suspend fun waitVsync(frames: Int) = withContext(Dispatchers.Main) {
        repeat(frames) {
            suspendCancellableCoroutine<Unit> { cont ->
                Choreographer.getInstance().postFrameCallback {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    /** Tear down the host. Idempotent — safe to call multiple times. */
    fun stop(skipProjectionStop: Boolean = false) {
        fun doStop() {
            if (stopped) return
            stopped = true
            if (!skipProjectionStop) appInitiatedStop = true
            if (instance === this) instance = null
            dismissFloatingMenu()
            hideTranslationOverlay()
            hideRegionIndicator(force = true)
            hideFloatingIcon("projection_stop")
            destroy(stopProjection = !skipProjectionStop)
            service.updateForegroundState()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            doStop()
        } else {
            main.post { doStop() }
        }
    }

    companion object {
        @Volatile
        var instance: ProjectionOverlayHost? = null
            private set

        /**
         * Create the projection host using the consent-grant tuple from
         * [MediaProjectionManager.createScreenCaptureIntent]. Replaces any
         * existing instance.
         *
         * Call from main thread. The display-size lookup uses
         * [Display.DEFAULT_DISPLAY] (primary screen only — multi-display
         * is intentionally out of scope for projection mode).
         */
        fun start(
            service: CaptureService,
            resultCode: Int,
            data: Intent,
        ): ProjectionOverlayHost? {
            val mpm = service.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            val projection = try {
                mpm.getMediaProjection(resultCode, data)
            } catch (e: Throwable) {
                Log.e(TAG, "getMediaProjection failed", e)
                null
            } ?: return null

            val display = service.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY) ?: run {
                projection.stop()
                return null
            }
            val (w, h, dpi) = primaryDisplayMetrics(display)

            val host = try {
                ProjectionOverlayHost(service, projection, w, h, dpi)
            } catch (e: Throwable) {
                Log.e(TAG, "ProjectionOverlayHost init failed", e)
                try { projection.stop() } catch (_: Throwable) {}
                return null
            }
            instance = host
            return host
        }

        @Suppress("DEPRECATION")
        private fun primaryDisplayMetrics(display: Display): Triple<Int, Int, Int> {
            // Prefer Display.getRealSize for raw pixel size; falls back to
            // displayMetrics for DPI. Both APIs are stable since API 17+.
            val size = Point()
            display.getRealSize(size)
            val dm = android.util.DisplayMetrics()
            display.getRealMetrics(dm)
            return Triple(size.x, size.y, dm.densityDpi)
        }
    }
}
