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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
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
        // preview underneath stays full-bleed.
        val controls = findViewById<FrameLayout>(R.id.cameraControls)
        ViewCompat.setOnApplyWindowInsetsListener(controls) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }

        findViewById<ImageButton>(R.id.cameraBack).setOnClickListener { finish() }

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
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

        if (com.playtranslate.BuildConfig.DEBUG) {
            val pill = findViewById<TextView>(R.id.cameraDebugPill)
            pill.isVisible = true
            session?.statusSink = { pill.text = it }
        }
    }

    override fun onDestroy() {
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

    private fun showCamera() {
        permissionGate.isVisible = false
        if (cameraBound) return
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (isDestroyed || isFinishing) return@addListener
            val provider = providerFuture.get()
            val selector = when {
                provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ->
                    CameraSelector.DEFAULT_BACK_CAMERA
                provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ->
                    CameraSelector.DEFAULT_FRONT_CAMERA
                else -> {
                    Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_LONG).show()
                    finish()
                    return@addListener
                }
            }
            // 16:9 to match the analysis stream — same aspect ⇒ same FOV ⇒ the
            // FILL_CENTER mapping in CameraCoordinates is exact.
            val aspect = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .build()
            val preview = Preview.Builder()
                .setResolutionSelector(aspect)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            provider.unbindAll()
            val analysis = session?.buildAnalysisUseCase()
            if (analysis != null) {
                provider.bindToLifecycle(this, selector, preview, analysis)
            } else {
                provider.bindToLifecycle(this, selector, preview)
            }
            cameraBound = true
        }, ContextCompat.getMainExecutor(this))
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
