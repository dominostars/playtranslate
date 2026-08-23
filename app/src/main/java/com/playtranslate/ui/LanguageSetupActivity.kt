package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.applyEdgeToEdge
import com.playtranslate.applyTheme
import com.playtranslate.language.SourceLangId
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Activity shell for the language pickers. The pages themselves live in
 * [LanguagePickerBinder] (shared with the floating [OverlayWorkspace]'s
 * over-game presentation); this shell keeps what is Activity work: the
 * toolbar + insets chrome, the hand-rolled page stack, onboarding back
 * semantics, the [MODE_PICK_SOURCE] result contract, and the Settings
 * [selectionDelegate].
 */
class LanguageSetupActivity : AppCompatActivity() {

    private enum class Page { SOURCE_LIST, TARGET_LIST, SOURCE_PICK }

    private val pageStack = mutableListOf<Page>()

    private lateinit var toolbar: MaterialToolbar
    private lateinit var contentFrame: FrameLayout

    // Shared installer so the single-flight guard engages across rapid
    // repeated row taps; the (activity, scope) constructor keeps the old
    // progress/error/toast presentation.
    private val targetInstaller by lazy {
        TargetPackInstaller(this, lifecycleScope)
    }

    private val binder by lazy {
        LanguagePickerBinder(this, lifecycleScope, targetInstaller, ActivityUi())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate so the first inflation resolves
        // ?attr/pt* against the user's selected palette + accent instead of
        // the manifest's Theme.PlayTranslate default.
        applyTheme(this)
        applyEdgeToEdge(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_language_setup)
        // Pad for system chrome only; pass IME-and-friends through to
        // contentFrame children (currently page_language_list, whose etSearch
        // sits at the top of its ScrollView and isn't covered by the
        // keyboard, but the architecture stays correct for any future
        // mid-scroll editable field). Strip the inset types we consumed so
        // children don't re-apply them. Matches MainActivity.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            WindowInsetsCompat.Builder(insets)
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.NONE)
                .build()
        }

        toolbar = findViewById(R.id.toolbar)
        contentFrame = findViewById(R.id.contentFrame)
        // Back chevron is always available. In onboarding mode, backing out
        // returns to the welcome page (via MainActivity.onResume re-check);
        // in normal mode, it finishes / pops the page stack.
        toolbar.setNavigationOnClickListener { handleBack() }
        onBackPressedDispatcher.addCallback(this) { handleBack() }

        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_TARGET -> pushPage(Page.TARGET_LIST)
            MODE_PICK_SOURCE -> pushPage(Page.SOURCE_PICK)
            else -> pushPage(Page.SOURCE_LIST)
        }
    }

    override fun onDestroy() {
        binder.cancel()
        super.onDestroy()
    }

    private fun handleBack() {
        val isOnboarding = intent.getBooleanExtra(EXTRA_ONBOARDING, false)
        if (isOnboarding) {
            // In onboarding mode there's no "go back a page" — finishing hands
            // control back to MainActivity, which re-launches this activity
            // on the next gap (same page or next step).
            finish()
            return
        }
        if (pageStack.size <= 1) finish()
        else {
            pageStack.removeAt(pageStack.lastIndex)
            showCurrentPage()
        }
    }

    private fun pushPage(page: Page) {
        pageStack.add(page)
        showCurrentPage()
    }

    private fun showCurrentPage() {
        val page = pageStack.lastOrNull() ?: return
        contentFrame.removeAllViews()
        val view = when (page) {
            Page.SOURCE_LIST -> binder.buildSourcePage(contentFrame)
            Page.TARGET_LIST -> binder.buildTargetPage(contentFrame)
            Page.SOURCE_PICK -> binder.buildPickSourcePage(
                contentFrame,
                currentCode = intent.getStringExtra(EXTRA_PICK_CURRENT),
                title = intent.getStringExtra(EXTRA_PICK_TITLE),
            ) { code ->
                setResult(RESULT_OK, Intent().putExtra(EXTRA_PICKED_CODE, code.orEmpty()))
                finish()
            }
        }
        contentFrame.addView(view)
    }

    /** The Activity host's side of the binder seam. */
    private inner class ActivityUi : LanguagePickerBinder.Ui {
        override fun setTitle(title: String) {
            toolbar.title = title
        }

        override fun progress(title: String, onDismiss: (DismissReason) -> Unit): OverlayProgress =
            OverlayProgress.Builder(this@LanguageSetupActivity)
                .setTitle(title)
                .setOnDismiss(onDismiss)
                .show()

        override fun error(reason: String) {
            AlertDialog.Builder(this@LanguageSetupActivity)
                .setTitle(R.string.lang_download_error_title)
                .setMessage(reason)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        override fun toast(text: String) {
            Toast.makeText(this@LanguageSetupActivity, text, Toast.LENGTH_LONG).show()
        }

        override fun present(builder: OverlayAlert.Builder) {
            builder.show()
        }

        override fun refresh() = showCurrentPage()

        override fun onSourceDone(id: SourceLangId) {
            selectionDelegate?.onSourceSelectionDone(id)
            finish()
        }

        override fun onTargetDone(code: String) {
            selectionDelegate?.onTargetSelectionDone(code)
            finish()
        }
    }

    interface Delegate {
        fun onSourceSelectionDone(sourceId: SourceLangId)
        fun onTargetSelectionDone(targetCode: String)
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_ONBOARDING = "onboarding"
        const val MODE_SOURCE = "source"
        const val MODE_TARGET = "target"

        /** Standalone result-returning picker. Stays Activity-only: its sole
         *  caller (YomitanDictionaryDetailActivity) is an in-app screen using
         *  the activity-result contract. */
        const val MODE_PICK_SOURCE = "pick_source"
        private const val EXTRA_PICK_CURRENT = "pick_current"
        private const val EXTRA_PICK_TITLE = "pick_title"

        /** Result extra of [MODE_PICK_SOURCE]: the picked [SourceLangId.code],
         *  or "" when the user chose Any. Absent on cancel (back). */
        const val EXTRA_PICKED_CODE = "picked_code"

        var selectionDelegate: Delegate? = null

        fun launch(context: Context, mode: String) {
            context.startActivity(
                Intent(context, LanguageSetupActivity::class.java)
                    .putExtra(EXTRA_MODE, mode)
            )
        }

        /** Intent for [MODE_PICK_SOURCE]: a pure pick-a-source-language page
         *  that returns [EXTRA_PICKED_CODE] instead of committing anything
         *  globally. [currentCode] draws the checkmark (null/blank = the Any
         *  row); [title] is the toolbar title. */
        fun pickSourceIntent(context: Context, currentCode: String?, title: String): Intent =
            Intent(context, LanguageSetupActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_PICK_SOURCE)
                .putExtra(EXTRA_PICK_CURRENT, currentCode)
                .putExtra(EXTRA_PICK_TITLE, title)
    }
}
