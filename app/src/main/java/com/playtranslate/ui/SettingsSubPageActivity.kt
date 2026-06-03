package com.playtranslate.ui

import android.os.Bundle
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.playtranslate.R
import com.playtranslate.applyEdgeToEdge
import com.playtranslate.applyTheme

/**
 * Base for the Settings sub-page Activities (Appearance / Capture & overlay /
 * Translation services / Hotkeys / Anki). Factors the boilerplate that the
 * existing leaf sub-screens ([DeepLSettingsActivity] / [TtsVoiceActivity] /
 * [LlmBackendSettingsActivity] / [LlmModelPickerActivity]) all repeat:
 *
 *  - accent + mode theme applied **before** `super.onCreate`, so the first
 *    inflation resolves `?attr/pt*` against the user's palette;
 *  - edge-to-edge;
 *  - system-bars + display-cutout (all sides) + IME (bottom) inset padding on
 *    the content root;
 *  - a [MaterialToolbar] back button (`R.id.toolbar`) that finishes.
 *
 * Subclasses provide [layoutResId] and wire their views in [onContentCreated]
 * (the content view + toolbar-back are already set up). The base deliberately
 * does **not** set the toolbar title — the leaf screens variously set it in XML
 * or in code, so each subclass owns its title. Override [onContentCreated] /
 * [onDestroy], not [onCreate] (which is `final`).
 *
 * Not for [LanguageSetupActivity], whose insets contract differs (system-bars
 * only, IME passed through to children, not `CONSUMED`).
 */
abstract class SettingsSubPageActivity : AppCompatActivity() {

    @get:LayoutRes
    protected abstract val layoutResId: Int

    final override fun onCreate(savedInstanceState: Bundle?) {
        // Theme before super so the first inflation resolves ?attr/pt* against
        // the user's accent + mode (matches every existing sub-screen).
        applyTheme(this)
        applyEdgeToEdge(this)
        super.onCreate(savedInstanceState)
        setContentView(layoutResId)
        installInsetPadding()
        findViewById<MaterialToolbar?>(R.id.toolbar)?.setNavigationOnClickListener { finish() }
        onContentCreated(savedInstanceState)
    }

    /** Wire views here — content view and toolbar back are already in place. */
    protected open fun onContentCreated(savedInstanceState: Bundle?) {}

    private fun installInsetPadding() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val sys = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(sys.left, sys.top, sys.right, maxOf(sys.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
    }
}
