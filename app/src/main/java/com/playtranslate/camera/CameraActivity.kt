package com.playtranslate.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.CameraCaptureSession
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.playtranslate.CaptureService
import com.playtranslate.DetectionLog
import com.playtranslate.OverlayMode
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.applyEdgeToEdge
import com.playtranslate.applyTheme
import com.playtranslate.language.HintTextKind
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.ocr.registry.selectionToken
import com.playtranslate.themeColor
import com.playtranslate.ui.DismissReason
import com.playtranslate.ui.OverlayAlert

/**
 * Camera tool (Settings → Tools → Camera): a full-bleed live camera view that
 * translates text the camera is pointed at, or shows furigana/pinyin reading
 * hints, per the camera's own [Prefs.cameraOverlayMode] (inheriting the
 * global flavor until the pill gear first sets it).
 *
 * Deliberately NOT a [com.playtranslate.ui.SettingsSubPageActivity]: that
 * scaffold pads the whole content view with system-bar insets, which would
 * letterbox the preview. Here the preview draws edge-to-edge and only the
 * floating controls receive inset padding.
 *
 * Phase 0: preview + permission gate + mode toggle only — no OCR pipeline yet.
 */
class CameraActivity : AppCompatActivity() {

    private val prefs by lazy { Prefs(this) }

    /** Pipeline orchestrator; created once the layout exists. */
    private var session: CameraSession? = null

    /** Play/pause + snapshot UI state; created alongside the session. */
    private var snapshotController: CameraSnapshotController? = null
    private var gearMenu: CameraGearMenu? = null

    /** Language config the session was last built/reset against — a change
     *  made in settings while we're paused must drop the cached OCR state. */
    private var sessionLangKey: String? = null

    private lateinit var previewView: PreviewView
    private lateinit var permissionGate: android.view.View
    private lateinit var permissionText: TextView
    private lateinit var permissionButton: Button

    /** True once the CAMERA request has come back denied with rationale
     *  suppressed — the gate then routes to system settings instead of
     *  re-launching a request that Android will silently swallow. */
    private var permissionPermanentlyDenied = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showCamera()
        } else {
            permissionPermanentlyDenied =
                !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            showPermissionGate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme(this)
        applyEdgeToEdge(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.cameraPreview)
        permissionGate = findViewById(R.id.cameraPermissionGate)
        permissionText = findViewById(R.id.cameraPermissionText)
        permissionButton = findViewById(R.id.cameraPermissionButton)
        orientShutter()

        // Only the floating controls avoid the system bars / cutout; the
        // preview underneath stays full-bleed. Bottom padding too — the
        // controls layer is match_parent now so the shutter sits at the true
        // screen bottom, above the nav bar.
        val controls = findViewById<FrameLayout>(R.id.cameraControls)
        ViewCompat.setOnApplyWindowInsetsListener(controls) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // Back camera specifically, and ENUMERATED rather than declared —
        // handheld ROMs built from phone BSPs declare FEATURE_CAMERA with no
        // camera device behind it. The tool is "point the device at text",
        // which a front camera can't aim — and PreviewView mirrors
        // front-camera preview while ImageAnalysis frames stay unmirrored,
        // so every overlay would render at the horizontally flipped
        // position. Gating here also skips the CAMERA permission prompt on
        // devices the tool can never work on.
        if (!CameraAvailability.hasBackCamera(this)) {
            Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        session = CameraSession(
            context = this,
            scope = lifecycleScope,
            overlayHost = findViewById(R.id.cameraOverlayHost),
            onSlowOcr = { maybeShowSlowOcrPrompt() },
        )
        sessionLangKey = langKey()

        // The snapshot panel's in-place edit needs the IME to overlay the
        // frozen frame, not resize the camera layout: the sheet lifts itself
        // via its ime-inset bottom margin, same as its over-game window mode.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        snapshotController = CameraSnapshotController(
            activity = this,
            session = session!!,
            backButton = findViewById(R.id.cameraBack),
            playPauseButton = findViewById(R.id.cameraPlayPause),
            shutterButton = findViewById(R.id.cameraShutter),
            regionButton = findViewById(R.id.cameraRegionSelect),
            controlPill = findViewById(R.id.cameraControlPill),
            freezeFrame = findViewById(R.id.cameraFreezeFrame),
            panelHost = findViewById(R.id.cameraPanelHost),
            regionUi = CameraRegionUi(
                activity = this,
                fullBleedHost = findViewById(R.id.cameraOverlayHost),
                controlsHost = controls,
            ),
            onExit = { finish() },
        )
        gearMenu = CameraGearMenu(
            activity = this,
            pill = findViewById(R.id.cameraControlPill),
            iconRow = findViewById(R.id.cameraPillIconRow),
            menuHost = findViewById(R.id.cameraPillMenu),
            gearButton = findViewById(R.id.cameraSettings),
            controlsHost = controls,
            onLanguageRow = {
                // Same target as the floating menu's Language row: the source
                // picker screen. Return lands in onResume, whose langKey diff
                // routes the refresh (frozen re-read / live reset).
                com.playtranslate.ui.LanguageSetupActivity.selectionDelegate = null
                startActivity(
                    android.content.Intent(this, com.playtranslate.ui.LanguageSetupActivity::class.java)
                        .putExtra(
                            com.playtranslate.ui.LanguageSetupActivity.EXTRA_MODE,
                            com.playtranslate.ui.LanguageSetupActivity.MODE_SOURCE,
                        )
                )
            },
            onOcrRow = { showPillOcrPicker() },
            onCycleOverlayMode = { cycleOverlayFlavor() },
        )
        onBackPressedDispatcher.addCallback(this) {
            val controller = snapshotController
            when {
                // The gear menu is modal (full-screen tap catcher): back
                // closes IT, never the snapshot or the screen beneath it.
                gearMenu?.isOpen == true -> gearMenu?.close()
                controller?.isCropActive == true -> controller.cancelCrop()
                controller?.isFrozen == true -> controller.unfreeze()
                else -> finish()
            }
        }

        val hint = findViewById<TextView>(R.id.cameraHint)
        hint.setText(R.string.camera_no_text_hint)
        session?.hintSink = { show -> hint.isVisible = show }

        if (com.playtranslate.BuildConfig.DEBUG) {
            val pill = findViewById<TextView>(R.id.cameraDebugPill)
            pill.isVisible = true
            session?.statusSink = { pill.text = it }
        }
    }

    override fun onDestroy() {
        gearMenu?.destroy()
        gearMenu = null
        snapshotController?.release()
        snapshotController = null
        session?.shutdown()
        session = null
        super.onDestroy()
    }

    /** Read-settings fingerprint — language configuration AND the selected
     *  OCR engine; a change invalidates cached OCR. The engine is the
     *  CAMERA-scoped resolution (the camera's own token, inheriting the
     *  global until set). The token matters for the pickers'
     *  not-yet-downloaded picks: the download screen persists the camera
     *  token (forCamera deep-link, on verified success only) while this
     *  activity is paused, and without the token in this diff a kept-behind
     *  frozen snapshot (or a live anchor, until its next re-acquire) would
     *  keep showing old-engine results while the gear menu reports the new
     *  one. */
    private fun langKey(): String =
        "${prefs.sourceLangId}|${prefs.targetLang}|${prefs.targetChineseVariant}|" +
            (
                OcrModelManager.selectedBackend(
                    this, prefs.sourceLangId, prefs.cameraOcrBackendToken(prefs.sourceLangId),
                )?.selectionToken ?: ""
            )

    override fun onPause() {
        super.onPause()
        // Modal menus don't survive leaving the screen — and a collapse
        // animation caught by the outgoing window transition (language row,
        // OCR download, home) reads as the pill deforming mid-morph.
        gearMenu?.closeInstant()
    }

    override fun onResume() {
        super.onResume()
        snapshotController?.syncControls()
        if (sessionLangKey != null && sessionLangKey != langKey()) {
            refreshAfterReadSettingsChange()
        }
        if (hasCameraPermission()) {
            showCamera()
        } else if (permissionGate.isVisible) {
            // Coming back from system settings: refresh the gate copy in case
            // the denial state changed while we were away.
            showPermissionGate()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    // ── Permission gate ────────────────────────────────────────────────────

    private fun showPermissionGate() {
        permissionGate.isVisible = true
        if (permissionPermanentlyDenied) {
            permissionText.setText(R.string.camera_permission_denied)
            permissionButton.setText(R.string.camera_open_settings)
            permissionButton.setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", packageName, null))
                )
            }
        } else {
            permissionText.setText(R.string.camera_permission_rationale)
            permissionButton.setText(R.string.camera_permission_grant)
            permissionButton.setOnClickListener {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    // ── Camera ─────────────────────────────────────────────────────────────

    private var cameraBound = false
    private var camera: Camera? = null

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun showCamera() {
        permissionGate.isVisible = false
        if (cameraBound) return
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (isDestroyed || isFinishing) return@addListener
            val provider = providerFuture.get()
            // Back camera ONLY — no front fallback. The overlay path maps
            // analysis-space coordinates to the view with a plain FILL_CENTER
            // scale+offset (CameraCoordinates); a front camera breaks that
            // contract because PreviewView mirrors its preview while analysis
            // frames stay unmirrored, flipping every overlay horizontally.
            // Mirror-compensating would also have to keep the rendered TEXT
            // unmirrored per region — permanent warp-path complexity for a
            // configuration the tool can't physically be aimed with.
            if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_LONG).show()
                finish()
                return@addListener
            }
            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            // 16:9 to match the analysis stream — same aspect ⇒ same FOV ⇒ the
            // FILL_CENTER mapping in CameraCoordinates is exact.
            val aspect = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .build()
            val previewBuilder = Preview.Builder().setResolutionSelector(aspect)
            // AF-state feed: an acquire fired mid-scan OCRs a defocused frame
            // (observed as consistent ~0.4-confidence garbage reads). The
            // session vetoes acquires while a scan runs.
            Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        s: CameraCaptureSession,
                        r: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        val af = result.get(CaptureResult.CONTROL_AF_STATE) ?: return
                        session?.afScanning =
                            af == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN ||
                            af == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN
                    }
                }
            )
            val preview = previewBuilder.build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            provider.unbindAll()
            val analysis = session?.buildAnalysisUseCase()
            camera = if (analysis != null) {
                provider.bindToLifecycle(this, selector, preview, analysis)
            } else {
                provider.bindToLifecycle(this, selector, preview)
            }
            cameraBound = true
            installTapToFocus()
        }, ContextCompat.getMainExecutor(this))
    }

    /** Anchor the shutter to the device's PHYSICAL bottom edge, like a
     *  camera app: rotated to landscape, the natural bottom sits on a side
     *  of the screen and the shutter follows it there, vertically centered
     *  (ROTATION_90 = natural bottom at screen-right). Physical LEFT/RIGHT
     *  gravities on purpose — this tracks device edges, not RTL reading
     *  direction. The no-text hint keeps its bottom-center spot, but its
     *  portrait lift exists only to clear the shutter — with the shutter
     *  on a side edge, halve it. Rotation recreates the activity, so once
     *  per create. */
    private fun orientShutter() {
        val shutter = findViewById<ImageButton>(R.id.cameraShutter)
        val lp = shutter.layoutParams as FrameLayout.LayoutParams
        val margin = (28 * resources.displayMetrics.density).toInt()
        lp.setMargins(0, 0, 0, 0)
        // Context.getDisplay() is API 30+; minSdk is 29 (Android 10), where
        // the deprecated windowManager route is the only rotation source.
        val rotation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            display?.rotation ?: android.view.Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
        }
        when (rotation) {
            android.view.Surface.ROTATION_90 -> {
                lp.gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                lp.rightMargin = margin
            }
            android.view.Surface.ROTATION_270 -> {
                lp.gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
                lp.leftMargin = margin
            }
            android.view.Surface.ROTATION_180 -> {
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                lp.topMargin = margin
            }
            else -> {
                lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                lp.bottomMargin = margin
            }
        }
        shutter.layoutParams = lp
        if (rotation == android.view.Surface.ROTATION_90 ||
            rotation == android.view.Surface.ROTATION_270
        ) {
            val hint = findViewById<TextView>(R.id.cameraHint)
            (hint.layoutParams as FrameLayout.LayoutParams).let {
                it.bottomMargin /= 2
                hint.layoutParams = it
            }
        }
    }

    /** Tap the preview to drive AF/AE metering at that point — continuous AF
     *  alone won't reliably lock close-range text on budget modules, which
     *  leaves OCR reading defocused frames. */
    private fun installTapToFocus() {
        previewView.setOnTouchListener { v, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                val future = camera?.cameraControl?.startFocusAndMetering(
                    FocusMeteringAction.Builder(point).build()
                )
                // When the sweep completes (focused OR failed — either way
                // the image changed), kick the session: a defocused-frame
                // anchor or no-text verdict must not sit out the 30 s
                // staleness age when the user just fixed the focus.
                future?.addListener(
                    { session?.onDeliberateRefocus() },
                    ContextCompat.getMainExecutor(this),
                )
                v.performClick()
            }
            true
        }
    }

    /** The read settings changed — source language (LanguageSetupActivity
     *  return, detected via the langKey diff) or OCR engine (the pill's
     *  picker persisted a new selection): re-read under them. While FROZEN
     *  that means re-running the snapshot on the SAME retained frame; live
     *  and paused reset the session so the next settle acquires fresh.
     *  langKey is stamped here so onResume's diff doesn't double-fire. */
    private fun refreshAfterReadSettingsChange() {
        sessionLangKey = langKey()
        val controller = snapshotController
        if (controller?.isFrozen == true) {
            controller.refreshSnapshot()
        } else {
            session?.reset()
        }
    }

    /** The pill gear menu's OCR row: the shared picker in its activity form.
     *  A changed, downloaded pick re-reads in place; a not-downloaded pick
     *  deep-links the OCR download screen (the frozen snapshot, if any,
     *  stays behind it and restores on back). */
    private fun showPillOcrPicker() {
        val srcId = prefs.sourceLangId
        val token = OcrModelManager
            .selectedBackend(this, srcId, prefs.cameraOcrBackendToken(srcId))
            ?.selectionToken ?: ""
        com.playtranslate.ui.OcrPicker.populate(
            com.playtranslate.ui.OverlayAlert.Builder(this),
            this,
            srcId,
            token,
            onReOcr = { refreshAfterReadSettingsChange() },
            onDownload = { backend ->
                // forCamera: the download screen persists the CAMERA token,
                // only on verified success — an aborted download changes
                // nothing, and the global/live engine never moves on a
                // camera-only action. The onResume langKey diff picks the
                // new resolution up on return.
                startActivity(
                    com.playtranslate.ui.CaptureOverlaySettingsActivity.downloadIntent(
                        this, srcId, backend.selectionToken, forCamera = true,
                    )
                )
            },
            // The camera's OCR choice is its own per-flow setting.
            applyToken = { backend ->
                prefs.setCameraOcrBackendToken(srcId, backend.selectionToken)
            },
        ).show()
    }

    // ── Overlay flavor (the gear menu's Overlays row) ──────────────────────

    /** Cycle the CAMERA's own Translation ↔ Furigana/Pinyin flavor. Fired by
     *  the gear menu's Overlays row, which mirrors the floating icon menu's
     *  (cycles in place, menu stays open) — but writes the camera-scoped
     *  flavor, so it never touches the over-game surfaces (no stopping a
     *  running screen-capture live session, which is built on the GLOBAL
     *  flavor this setting no longer moves). */
    private fun cycleOverlayFlavor() {
        val next = if (prefs.cameraOverlayMode == OverlayMode.TRANSLATION) {
            OverlayMode.FURIGANA
        } else {
            OverlayMode.TRANSLATION
        }
        prefs.cameraOverlayMode = next
        onOverlayModeChanged(next)
    }

    /** Re-flavor the camera overlays from the cached OCR result — the scene
     *  didn't change, so no re-OCR. While FROZEN (the switcher is visible in
     *  the snapshot's overlays presentation) the frozen render path is used
     *  instead: it reads the new flavor and never calls the translator, so a
     *  toggle mid-load can't duplicate the snapshot pipeline's in-flight
     *  backend batch — translation boxes show skeletons until the pipeline's
     *  own results land. */
    private fun onOverlayModeChanged(@Suppress("UNUSED_PARAMETER") mode: OverlayMode) {
        val s = session ?: return
        if (snapshotController?.isFrozen == true) {
            // Repaint only when boxes are actually the current presentation.
            // The old flavor toggle could only fire while boxes owned the
            // frame (its visibility gate); the gear's Overlays row fires
            // from any presentation, and painting here from an expanded
            // panel would resurrect boxes it didn't ask for. The pref is
            // written either way — the new flavor applies whenever boxes
            // next show.
            if (s.hasLiveOverlays()) s.showFrozenOverlays()
        } else {
            s.onOverlayModeChanged()
        }
    }

    // ── Slow-OCR rescue prompt ─────────────────────────────────────────────

    /** The rescue alert currently up, if any — the gate against re-offers
     *  while one shows. The in-activity [OverlayAlert] detaches itself on
     *  activity pause (LIFECYCLE_PAUSE), which records no answer — the next
     *  slow session may offer again, same contract as live mode. */
    private var slowOcrAlert: OverlayAlert? = null

    /**
     * A live camera acquire's OCR ran past the shared slow threshold
     * ([com.playtranslate.LiveSessionFeedback.OCR_SLOW_PROMPT_MS]) — offer
     * the one-tap switch to the rescue engine, live mode's offer
     * ([CaptureService.maybeShowSlowOcrPrompt]) minus its overlay-window and
     * multi-display machinery: the camera is an activity, so the plain
     * in-activity alert form serves. Fully CAMERA-scoped state: the switch
     * writes the camera's engine token (the slowness was observed here;
     * live mode's own engine is untouched) and the answered latch is the
     * camera's own — the decision scopes with the selection it changes, so
     * answering here never silences live mode's offer for its still-slow
     * global engine. Gated out while FROZEN (the
     * timer only wraps live acquires, but a freeze can race the callback;
     * the snapshot panel has its own gear picker) and when nothing faster
     * exists — the slowness IS the fast option. Cycles are deliberately NOT
     * paused under the alert: a completing acquire just paints behind the
     * scrim, and the switch action resets the pipeline anyway.
     */
    private fun maybeShowSlowOcrPrompt() {
        if (isFinishing || isDestroyed) return
        if (slowOcrAlert?.isShowing == true) return
        if (snapshotController?.isFrozen == true) return
        val id = prefs.sourceLangId
        if (prefs.cameraSlowOcrPromptAnswered(id)) return
        val selected =
            OcrModelManager.selectedBackend(this, id, prefs.cameraOcrBackendToken(id)) ?: return
        val rescue = OcrModelManager.slowOcrRescue(
            available = OcrModelManager.availableBackends(this, id),
            selected = selected,
            mlKitFloor = SourceLanguageProfiles[id].mlKitFloor,
        ) ?: return

        slowOcrAlert = OverlayAlert.Builder(this)
            .setTitle(getString(R.string.slow_ocr_prompt_title))
            // Camera-worded body: the change-your-mind path is the pill
            // gear's OCR row (the switch below writes the CAMERA-scoped
            // engine, which the app-wide Settings screen doesn't move).
            .setMessage(getString(R.string.slow_ocr_prompt_message_camera))
            .addButton(
                getString(R.string.slow_ocr_prompt_switch),
                themeColor(R.attr.ptAccent),
            ) {
                slowOcrAlert = null
                // Camera-scoped, BOTH pieces: the slowness was observed HERE,
                // so only the camera's own engine switches — and the answered
                // latch scopes with it, so this decision can't silence live
                // mode's own offer for its still-slow global engine.
                prefs.setCameraSlowOcrPromptAnswered(id)
                prefs.setCameraOcrBackendToken(id, rescue.selectionToken)
                DetectionLog.log("camera slow-OCR prompt: ${id.code} switched to ${rescue.selectionToken}")
                // Cancel the still-grinding pass and purge the anchor LRU —
                // cached scenes carry old-engine OCR payloads and would
                // re-lock without re-OCR. The next settled frame acquires
                // on the rescue engine (recognise resolves per pass).
                session?.reset()
                // The reset above IS this switch's refresh — stamp the
                // read-settings fingerprint (now carrying the OCR token)
                // so the next onResume doesn't refresh a second time.
                sessionLangKey = langKey()
            }
            .addCancelButton(getString(R.string.slow_ocr_prompt_keep)) { reason ->
                slowOcrAlert = null
                // Only an explicit user dismissal (button, scrim, back) is
                // a decision; a lifecycle detach may offer again.
                if (reason == DismissReason.USER) prefs.setCameraSlowOcrPromptAnswered(id)
            }
            .show()
        DetectionLog.log(
            "camera slow-OCR prompt shown for ${id.code} (selected=${selected.selectionToken})"
        )
    }
}
