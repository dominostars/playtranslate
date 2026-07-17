package com.playtranslate.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import com.playtranslate.OverlayMode
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.applyEdgeToEdge
import com.playtranslate.applyTheme
import com.playtranslate.language.HintTextKind
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.ui.buildPillToggle

/**
 * Camera tool (Settings → Tools → Camera): a full-bleed live camera view that
 * translates text the camera is pointed at, or shows furigana/pinyin reading
 * hints, per the shared [Prefs.overlayMode].
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
            modeToggle = findViewById(R.id.cameraModeToggle),
            freezeFrame = findViewById(R.id.cameraFreezeFrame),
            panelHost = findViewById(R.id.cameraPanelHost),
            modeToggleSupported = {
                SourceLanguageProfiles[prefs.sourceLangId].hintTextKind != HintTextKind.NONE
            },
            onExit = { finish() },
        )
        onBackPressedDispatcher.addCallback(this) {
            val controller = snapshotController
            if (controller?.isFrozen == true) controller.unfreeze() else finish()
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
        snapshotController?.release()
        snapshotController = null
        session?.shutdown()
        session = null
        super.onDestroy()
    }

    /** Language configuration fingerprint; a change invalidates cached OCR. */
    private fun langKey(): String =
        "${prefs.sourceLangId}|${prefs.targetLang}|${prefs.targetChineseVariant}"

    override fun onResume() {
        super.onResume()
        bindModeToggle()
        // bindModeToggle unconditionally shows the toggle for languages with
        // reading support; re-derive control visibility so a resume while
        // frozen doesn't resurrect it.
        snapshotController?.syncControls()
        if (sessionLangKey != null && sessionLangKey != langKey()) {
            session?.reset()
            sessionLangKey = langKey()
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

    /** Tap the preview to drive AF/AE metering at that point — continuous AF
     *  alone won't reliably lock close-range text on budget modules, which
     *  leaves OCR reading defocused frames. */
    private fun installTapToFocus() {
        previewView.setOnTouchListener { v, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                camera?.cameraControl?.startFocusAndMetering(
                    FocusMeteringAction.Builder(point).build()
                )
                v.performClick()
            }
            true
        }
    }

    // ── Mode toggle ────────────────────────────────────────────────────────

    /** (Re)build the Translation / Furigana-Pinyin toggle. Idempotent — rerun
     *  on every resume so a source-language change made elsewhere is picked
     *  up. Hidden entirely when the source language has no reading support. */
    private fun bindModeToggle() {
        val container = findViewById<FrameLayout>(R.id.cameraModeToggle)
        val hintKind = SourceLanguageProfiles[prefs.sourceLangId].hintTextKind
        if (hintKind == HintTextKind.NONE) {
            container.isVisible = false
            return
        }
        container.isVisible = true
        val hintLabel = when (hintKind) {
            HintTextKind.PINYIN -> getString(R.string.overlay_mode_option_pinyin)
            else -> getString(R.string.overlay_mode_option_furigana)
        }
        buildPillToggle(
            container = container,
            options = listOf(
                getString(R.string.overlay_mode_option_translation) to OverlayMode.TRANSLATION,
                hintLabel to OverlayMode.FURIGANA,
            ),
            selected = prefs.overlayMode,
            onSelect = { mode ->
                prefs.overlayMode = mode
                // Same contract as the settings toggle: a running screen-capture
                // live session is built for one flavor, so switching stops it.
                if (CaptureService.instance?.isLive == true) {
                    CaptureService.instance?.stopLive()
                }
                onOverlayModeChanged(mode)
            },
        )
    }

    /** Re-flavor the camera overlays from the cached OCR result — the scene
     *  didn't change, so no re-OCR. */
    private fun onOverlayModeChanged(@Suppress("UNUSED_PARAMETER") mode: OverlayMode) {
        session?.onOverlayModeChanged()
    }
}
