package com.playtranslate.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import com.playtranslate.R
import com.playtranslate.language.SourceLangId

/**
 * The language pickers as floating-workspace pages — the over-game
 * presentation of [LanguagePickerBinder]'s source/target lists, so changing
 * a language from the floating menu or the capture sheet no longer leaves
 * the game for [LanguageSetupActivity]. Downloads run on the workspace
 * scope with in-window progress; delete confirms and errors render in the
 * workspace's modal layer. Selection completes by dismissing the workspace
 * (no [LanguageSetupActivity.selectionDelegate] — that static stays an
 * in-app Settings concern).
 *
 * [MODE_PICK_SOURCE][LanguageSetupActivity.MODE_PICK_SOURCE] deliberately
 * has no page here: its only caller is an in-app screen using the
 * activity-result contract.
 */
sealed class LanguageListPage(private val isSource: Boolean) : WorkspacePage {

    private var wrapper: FrameLayout? = null
    private var binder: LanguagePickerBinder? = null
    private var hostRef: WorkspaceHost? = null

    override fun title(ctx: Context): CharSequence =
        ctx.getString(if (isSource) R.string.lang_translate_from else R.string.lang_translate_to)

    override fun onCreateView(ctx: Context, parent: ViewGroup, host: WorkspaceHost): View {
        hostRef = host
        val ui = WorkspaceUi(ctx, host)
        binder = LanguagePickerBinder(
            ctx,
            host.scope,
            TargetPackInstaller(ctx, host.scope, WorkspaceInstallerUi(ctx, host)),
            ui,
        )
        val w = FrameLayout(ctx)
        wrapper = w
        rebuild()
        return w
    }

    /** Rebuild the page content in place — the binder's [LanguagePickerBinder.Ui.refresh]
     *  after a delete changed the row set (the Activity's showCurrentPage analogue). */
    private fun rebuild() {
        val w = wrapper ?: return
        val b = binder ?: return
        w.removeAllViews()
        w.addView(if (isSource) b.buildSourcePage(w) else b.buildTargetPage(w))
        hostRef?.invalidateNav()
    }

    /** Every clickable view on the page is a controller target: language rows,
     *  their trash buttons, the search field + its clear button. Collected per
     *  keypress, so a filter rebuild can't leave a stale registry. */
    override fun navActions(): List<NavAction> {
        val out = ArrayList<NavAction>()
        fun walk(v: View) {
            if (v.isClickable && v.isShown) out.add(NavAction(v))
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        wrapper?.let { walk(it) }
        return out
    }

    override fun scrollView(): ViewGroup? = wrapper?.getChildAt(0) as? ViewGroup

    override fun onBack(): Boolean {
        // B while typing cancels the search-field edit, not the page — the
        // focus listener drops the window's IME mode.
        val et = wrapper?.findViewById<EditText>(R.id.etSearch) ?: return false
        if (!et.hasFocus()) return false
        et.clearFocus()
        return true
    }

    override fun onDestroy() {
        binder?.cancel()
        binder = null
        wrapper = null
        hostRef = null
    }

    /** The workspace host's side of the binder seam. */
    private inner class WorkspaceUi(
        private val ctx: Context,
        private val host: WorkspaceHost,
    ) : LanguagePickerBinder.Ui {
        override fun setTitle(title: String) = host.setTitle(title)

        override fun progress(title: String, onDismiss: (DismissReason) -> Unit): OverlayProgress =
            host.showProgress(title, onDismiss)

        override fun error(reason: String) {
            host.alert()
                .setTitle(ctx.getString(R.string.lang_download_error_title))
                .setMessage(reason)
                .addCancelButton(ctx.getString(android.R.string.ok))
                .showInParent(host.modalLayer)
        }

        override fun toast(text: String) {
            Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
        }

        override fun present(builder: OverlayAlert.Builder) {
            builder.showInParent(host.modalLayer)
        }

        override fun refresh() = rebuild()

        override fun onSourceDone(id: SourceLangId) = host.dismiss()

        override fun onTargetDone(code: String) = host.dismiss()

        // The EditText's focus drives the window's focus/IME flags: an A/tap
        // into the field raises the keyboard; clearing focus drops it and
        // hands key focus back to controller nav.
        override fun onSearchFocus(hasFocus: Boolean) = host.setImeMode(hasFocus)
    }

    /** In-window install surfaces for [TargetPackInstaller]. */
    private class WorkspaceInstallerUi(
        private val ctx: Context,
        private val host: WorkspaceHost,
    ) : TargetPackInstaller.InstallerUi {
        override fun progress(title: String, onDismiss: (DismissReason) -> Unit): OverlayProgress =
            host.showProgress(title, onDismiss)

        override fun error(reason: String) {
            host.alert()
                .setTitle(ctx.getString(R.string.lang_download_error_title))
                .setMessage(reason)
                .addCancelButton(ctx.getString(android.R.string.ok))
                .showInParent(host.modalLayer)
        }

        override fun toast(text: String) {
            Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
        }
    }
}

/** Translate-from picker (the floating menu's Language row, the capture
 *  sheet's source-language header). */
class SourceListPage : LanguageListPage(isSource = true)

/** Translate-to picker (the capture sheet's target-language header). */
class TargetListPage : LanguageListPage(isSource = false)
