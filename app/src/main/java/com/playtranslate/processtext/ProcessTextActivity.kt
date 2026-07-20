package com.playtranslate.processtext

import android.content.Intent
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.applyEdgeToEdge
import com.playtranslate.camera.CameraTranslator
import com.playtranslate.isEffectivelyDark
import com.playtranslate.model.TranslationLangContext
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.ui.ActivitySheetHost
import com.playtranslate.ui.CaptureResultOverlay
import com.playtranslate.ui.LanguageSetupActivity
import com.playtranslate.ui.TtsAlertTarget
import com.playtranslate.ui.showAnkiNotInstalledDialog

/**
 * ACTION_PROCESS_TEXT entry point: the system text-selection toolbar hands us
 * the selected string and we present the capture panel over the calling app.
 * The window is transparent — the caller stays visible where the frozen
 * screenshot would normally sit — so the panel runs with firmer fills
 * ([CaptureResultOverlay.opaqueBackgroundBoost]). No OCR, no screenshot:
 * [ProcessTextSession] drives the panel straight from the string, which also
 * keeps the "Show on screen" toggle hidden and Anki cards image-less.
 *
 * Runs in the CALLER's task (PROCESS_TEXT launches via startActivityForResult),
 * so every dismissal path lands the user back in the app they selected from.
 * We never return replacement text — EXTRA_PROCESS_TEXT_READONLY is ignored.
 */
class ProcessTextActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var text: String
    private val translator by lazy { CameraTranslator(this) }
    private var overlay: CaptureResultOverlay? = null

    /** The language triple the current session translated under — onResume
     *  re-runs the SAME text when the picker round trip changed it. Null
     *  until the panel is up (the first onResume precedes doOnLayout). */
    private var sessionLangContext: TranslationLangContext? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Transparent variant of the app theme + the user's accent, BEFORE
        // super.onCreate. Not applyTheme(): its opaque windowBackground
        // would hide the calling app.
        setTheme(
            if (isEffectivelyDark(this)) R.style.Theme_PlayTranslate_Transparent
            else R.style.Theme_PlayTranslate_White_Transparent,
        )
        theme.applyStyle(Prefs(this).accent.overlay, true)
        applyEdgeToEdge(this)
        super.onCreate(savedInstanceState)
        val selected = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        if (selected.isNullOrBlank()) {
            finish()
            return
        }
        text = selected
        prefs = Prefs(this)
        // The IME overlays the window; the sheet lifts its edit field itself
        // via its ime-inset bottom margin (mirrors ImageImportActivity).
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        val host = FrameLayout(this)
        setContentView(host)
        host.doOnLayout { buildPanel(host, it.width, it.height) }
    }

    /** Build the panel with every over-game behavior overridden (mirrors
     *  FrozenReviewPanel.startReview minus the image machinery). */
    private fun buildPanel(host: FrameLayout, w: Int, h: Int) {
        if (isFinishing || overlay != null) return
        val o = CaptureResultOverlay(
            this,
            windowManager,
            Display.DEFAULT_DISPLAY,
            // Dead parameter in this configuration: every path that would
            // spawn an overlay window is overridden below. Constructed only
            // to satisfy the shared signature.
            OverlayHost(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY),
            sheetHost = ActivitySheetHost(host),
        )
        o.opaqueBackgroundBoost = true
        // No frame to park over: never sliver-start, never offer boxes.
        o.presentationPrefs = object : CaptureResultOverlay.PresentationPrefs {
            override var boxesEnabled: Boolean
                get() = false
                set(_) {}
            override var startCollapsed: Boolean
                get() = false
                set(_) {}
        }
        o.ttsAlertTarget = TtsAlertTarget.InActivity(this)
        // Tap-a-word lens hosted as an activity window (the panel's wm IS
        // the activity's WindowManager) — no overlay permission involved.
        o.wordLensInActivity = true
        o.showAnkiNotInstalled = { showAnkiNotInstalledDialog(this) }
        // Anki review launches on top of this activity; the panel stays
        // behind it and restores when the user backs out.
        o.dismissOnActivityLaunch = false
        // Unlike the camera/import reviews there is nothing to preserve
        // outside the sheet — outside taps and swipe-downs exit to the
        // calling app.
        o.dismissOnGesture = true
        o.retranslate = { t ->
            translator.translateDetailed(listOf(t)).firstOrNull()
                ?.takeIf { it.text.isNotEmpty() }
                ?.let { CaptureResultOverlay.PanelTranslation(it.text, it.note, it.backendDisplayName) }
        }
        // Keep the panel up across the picker round trip — the selected text
        // is this flow's whole input, and the default (dismiss + relaunch)
        // would lose it. onResume re-runs it under the new prefs.
        o.chooseLanguage = { isSource ->
            LanguageSetupActivity.selectionDelegate = null
            LanguageSetupActivity.launch(
                this,
                if (isSource) LanguageSetupActivity.MODE_SOURCE
                else LanguageSetupActivity.MODE_TARGET,
            )
        }
        o.onDismiss = { finish() }
        overlay = o
        o.show(w, h)
        observeFreshSession()
    }

    /** Run [text] through a fresh session under the CURRENT prefs; observe()
     *  cancels the previous session and re-drives the attached panel. */
    private fun observeFreshSession() {
        val langContext = prefs.langContext()
        val session = ProcessTextSession.build(
            text, langContext, lifecycleScope,
            getString(R.string.process_text_translation_failed),
        ) { translator.translateDetailed(listOf(it)).firstOrNull() }
        sessionLangContext = langContext
        overlay?.observe(session)
    }

    override fun onResume() {
        super.onResume()
        val prev = sessionLangContext ?: return
        if (prev != prefs.langContext()) observeFreshSession()
    }

    override fun onDestroy() {
        overlay?.let {
            // A system-driven destroy (rotation, back-stack teardown) must
            // not re-enter finish(); dismiss() is idempotent, so the normal
            // gesture path (already dismissed) makes this a no-op.
            it.onDismiss = null
            it.dismiss()
        }
        overlay = null
        super.onDestroy()
    }
}
