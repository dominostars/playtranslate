package com.playtranslate.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.mlkit.nl.translate.TranslateLanguage
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.TranslationManager
import com.playtranslate.language.DownloadProgress
import com.playtranslate.language.InstallResult
import com.playtranslate.language.LanguagePackStore
import com.playtranslate.language.PreloadResult
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.themeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class LanguageSetupActivity : AppCompatActivity() {

    private enum class Page { SOURCE_LIST, TARGET_LIST }

    private val pageStack = mutableListOf<Page>()
    private var selectedSource: SourceLangId? = null

    private lateinit var toolbar: MaterialToolbar
    private lateinit var contentFrame: FrameLayout
    private var activeJob: Job? = null
    // Shared so the installer's single-flight guard engages across rapid
    // repeated row taps.
    private val targetInstaller by lazy {
        TargetPackInstaller(this, lifecycleScope)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_language_setup)

        toolbar = findViewById(R.id.toolbar)
        contentFrame = findViewById(R.id.contentFrame)
        // Back chevron is always available. In onboarding mode, backing out
        // returns to the welcome page (via MainActivity.onResume re-check);
        // in normal mode, it finishes / pops the page stack.
        toolbar.setNavigationOnClickListener { handleBack() }

        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_TARGET -> {
                selectedSource = Prefs(this).sourceLangId
                pushPage(Page.TARGET_LIST)
            }
            else -> pushPage(Page.SOURCE_LIST)
        }
    }

    override fun onDestroy() {
        activeJob?.cancel()
        super.onDestroy()
    }

    @Deprecated("Deprecated in API level 33")
    override fun onBackPressed() {
        handleBack()
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
            pageStack.removeLast()
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
        when (page) {
            Page.SOURCE_LIST -> showSourceList()
            Page.TARGET_LIST -> showTargetList()
        }
    }

    // ── Source list ───────────────────────────────────────────────────────

    private fun showSourceList() {
        toolbar.title = getString(R.string.lang_translate_from)
        val view = LayoutInflater.from(this).inflate(R.layout.page_language_list, contentFrame, false)
        val root = view.findViewById<LinearLayout>(R.id.languageListRoot)

        val allIds = SourceLangId.entries.toList()
        val currentId = Prefs(this).sourceLangId

        fun toRow(id: SourceLangId): LangRow {
            val installed = LanguagePackStore.isInstalled(this, id)
            val isSelected = id == currentId
            // Deleting any variant that shares the selected pack (ZH ↔ ZH_HANT)
            // would pull files out from under the current engine, so treat the
            // sibling as non-deletable too — its trash just stays hidden.
            val sharesPackWithSelection = id.packId == currentId.packId
            return LangRow(
                title = id.displayName(),
                isSelected = isSelected,
                canDelete = installed && !sharesPackWithSelection,
                onRowClick = { onSourceSelected(id) },
                onTrashClick = { handleSourceDeleteTap(id) },
            )
        }

        // Suggested: any source whose pack is already installed — bundled
        // (JA) or downloaded (ZH / ZH_HANT share the same pack, EN, ES).
        // Unlike the target picker this does NOT include the device locale,
        // per user request.
        val suggested = allIds.filter { LanguagePackStore.isInstalled(this, it) }

        if (suggested.isNotEmpty()) {
            addLanguageSection(root, title = "Suggested", rows = suggested.map(::toRow))
        }
        addLanguageSection(root, title = "All", rows = allIds.map(::toRow))

        contentFrame.addView(view)
    }

    private fun onSourceSelected(id: SourceLangId) {
        val needsDownload = !LanguagePackStore.isInstalled(this, id)

        val sourceLoadAction: suspend () -> Unit = {
            val preloadResult = SourceLanguageEngines.get(applicationContext, id).preload()
            when (preloadResult) {
                is PreloadResult.Success -> { /* proceed */ }
                is PreloadResult.PackMissing -> throw IllegalStateException(
                    "Pack for ${id.code} missing after download — install flow did not persist files"
                )
                is PreloadResult.PackCorrupt -> {
                    // Roll back the partial install so the user is re-prompted
                    // to download on the next attempt rather than stuck with a
                    // broken pack that every engine access crashes against.
                    LanguagePackStore.uninstall(applicationContext, id)
                    throw IllegalStateException(
                        "Pack for ${id.code} is corrupt: ${preloadResult.reason}"
                    )
                }
                is PreloadResult.TokenizerInitFailed -> {
                    // Don't uninstall — the pack is on disk and its dict is
                    // fine. Tokenizer library threw during warm-up (likely
                    // transient OOM). Surface as a retryable error; the
                    // user can tap the language again and we'll warm up
                    // again from the still-installed pack.
                    throw IllegalStateException(
                        "Tokenizer init failed for ${id.code}: ${preloadResult.reason}. " +
                            "Pack is installed; try again."
                    )
                }
            }
            // Also download the ML Kit translation model for newSource → currentTarget
            // so translations work offline after switching.
            val currentTarget = Prefs(applicationContext).targetLang
            val tm = TranslationManager(SourceLanguageProfiles[id].translationCode, currentTarget)
            try { tm.ensureModelReady() } finally { tm.close() }
            // EN→target model for definition translation fallback
            if (currentTarget != "en") {
                val enTm = TranslationManager("en", currentTarget)
                try { enTm.ensureModelReady() } finally { enTm.close() }
            }
        }
        val onDone: () -> Unit = {
            Prefs(this).sourceLang = id.code
            selectionDelegate?.onSourceSelectionDone(id)
            finish()
        }

        if (needsDownload) {
            showDownloadAndLoadPopup(
                langName = id.displayName(),
                downloadAction = { onProgress -> LanguagePackStore.install(applicationContext, id, onProgress) },
                loadAction = sourceLoadAction,
                onSuccess = onDone,
            )
        } else {
            showLoadingPopup(
                langName = id.displayName(),
                loadAction = sourceLoadAction,
                onSuccess = onDone,
            )
        }
    }

    // ── Target list ──────────────────────────────────────────────────────

    private fun showTargetList() {
        toolbar.title = getString(R.string.lang_translate_to)
        val view = LayoutInflater.from(this).inflate(R.layout.page_language_list, contentFrame, false)
        val root = view.findViewById<LinearLayout>(R.id.languageListRoot)

        val allLangs = TranslateLanguage.getAllLanguages()
            .map { code -> code to targetDisplayName(code) }
            .sortedBy { it.second }

        val currentTarget = Prefs(this).targetLang

        fun toRow(code: String, displayName: String): LangRow {
            // English has no gloss pack to manage, so trash never applies.
            val installed = code != "en" && LanguagePackStore.isTargetInstalled(this, code)
            val isSelected = code == currentTarget
            return LangRow(
                title = displayName,
                isSelected = isSelected,
                canDelete = installed && !isSelected,
                onRowClick = { onTargetSelected(code) },
                onTrashClick = { handleTargetDeleteTap(code, displayName) },
            )
        }

        // Suggested: device-locale language (if supported) + any target packs
        // already installed. Surfaces the likely target(s) without removing
        // them from the canonical alphabetical list below.
        val deviceLang = Locale.getDefault().language
        val suggested = allLangs.filter { (code, _) ->
            code == deviceLang || LanguagePackStore.isTargetInstalled(this, code)
        }

        if (suggested.isNotEmpty()) {
            addLanguageSection(root, title = "Suggested", rows = suggested.map { (c, n) -> toRow(c, n) })
        }
        addLanguageSection(root, title = "All", rows = allLangs.map { (c, n) -> toRow(c, n) })

        contentFrame.addView(view)
    }

    private fun onTargetSelected(code: String) {
        val sourceId = selectedSource ?: Prefs(this).sourceLangId
        val sourceLangCode = SourceLanguageProfiles[sourceId].translationCode
        targetInstaller.installAndLoad(
            sourceLangCode = sourceLangCode,
            targetCode = code,
            onSuccess = {
                Prefs(this@LanguageSetupActivity).targetLang = code
                selectionDelegate?.onTargetSelectionDone(code)
                finish()
            },
        )
    }

    // ── Download + load popup (overlay-styled dark card) ────────────────

    private fun buildPopupDialog(): Triple<Dialog, TextView, ProgressBar> {
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_language_progress, null)

        // Center the card in a dim scrim matching overlay popup feel.
        val wrapper = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.argb(128, 0, 0, 0))
            addView(view, FrameLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.82f).toInt(),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
        }

        dialog.setContentView(wrapper)
        dialog.setCancelable(false)

        val tvStatus = view.findViewById<TextView>(R.id.tvPopupStatus)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBarPopup)
        val btnCancel = view.findViewById<Button>(R.id.btnPopupCancel)

        btnCancel.setOnClickListener {
            activeJob?.cancel()
            dialog.dismiss()
        }

        return Triple(dialog, tvStatus, progressBar)
    }

    private fun showDownloadAndLoadPopup(
        langName: String,
        downloadAction: suspend ((DownloadProgress) -> Unit) -> InstallResult,
        loadAction: suspend () -> Unit,
        onSuccess: () -> Unit,
    ) {
        val (dialog, tvStatus, progressBar) = buildPopupDialog()
        tvStatus.text = "Downloading $langName"
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        dialog.show()

        activeJob = lifecycleScope.launch {
            val result = downloadAction { progress ->
                if (progress is DownloadProgress.Downloading && progress.totalBytes > 0) {
                    val pct = (progress.bytesReceived * 100L / progress.totalBytes).toInt()
                    runOnUiThread { progressBar.progress = pct }
                }
            }
            when (result) {
                is InstallResult.Success -> {
                    runOnUiThread {
                        tvStatus.text = "Loading $langName"
                        progressBar.isIndeterminate = true
                        dialog.findViewById<Button>(R.id.btnPopupCancel)?.visibility = View.GONE
                    }
                    try {
                        withContext(Dispatchers.IO) { loadAction() }
                        dialog.dismiss()
                        onSuccess()
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        // User tapped Cancel — dialog already dismissed.
                    } catch (e: Exception) {
                        Log.e(TAG, "loadAction threw after install succeeded", e)
                        dialog.dismiss()
                        showErrorPopup(e.message ?: "Loading failed")
                    }
                }
                is InstallResult.Failed -> {
                    dialog.dismiss()
                    showErrorPopup(result.reason)
                }
                is InstallResult.Cancelled -> {
                    dialog.dismiss()
                }
            }
        }
    }

    private fun showLoadingPopup(
        langName: String,
        loadAction: suspend () -> Unit,
        onSuccess: () -> Unit,
    ) {
        val (dialog, tvStatus, progressBar) = buildPopupDialog()
        tvStatus.text = "Loading $langName"
        progressBar.isIndeterminate = true
        dialog.findViewById<Button>(R.id.btnPopupCancel)?.visibility = View.GONE
        dialog.show()

        activeJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { loadAction() }
                dialog.dismiss()
                onSuccess()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                // User tapped Cancel — dialog already dismissed, nothing to report.
            } catch (e: Exception) {
                Log.e(TAG, "loadAction threw", e)
                dialog.dismiss()
                showErrorPopup(e.message ?: "Loading failed")
            }
        }
    }

    private fun showErrorPopup(reason: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.lang_download_error_title)
            .setMessage(reason)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** One row in a language picker section. See [addLanguageSection].
     *
     *  The trailing icon is determined by these two booleans:
     *    - [isSelected] → accent-tinted checkmark (non-interactive status mark)
     *    - [canDelete]  → trash icon that opens the delete-confirm dialog
     *    - neither      → no trailing icon (not installed, or sibling of the
     *                     currently-selected pack)
     *
     *  [isSelected] takes precedence when both would be true; in practice they
     *  can't be because `canDelete` excludes the selection and its siblings.
     */
    private data class LangRow(
        val title: String,
        val isSelected: Boolean,
        val canDelete: Boolean,
        val onRowClick: () -> Unit,
        val onTrashClick: () -> Unit,
    )

    /**
     * Adds one grouped-card section to [root]: an optional uppercase group
     * header followed by a MaterialCardView containing [rows] separated by
     * inset dividers. Skips entirely if [rows] is empty.
     */
    private fun addLanguageSection(
        root: LinearLayout,
        title: String?,
        rows: List<LangRow>,
    ) {
        if (rows.isEmpty()) return
        val inflater = LayoutInflater.from(this)

        if (title != null) {
            val header = inflater.inflate(R.layout.settings_group_header, root, false)
            header.findViewById<TextView>(R.id.tvGroupTitle).text = title
            root.addView(header)
        }

        val card = inflater.inflate(R.layout.language_list_section, root, false) as MaterialCardView
        val rowContainer = card.findViewById<LinearLayout>(R.id.sectionRows)
        // Read the corner radius off the card itself so the selected-row
        // highlight's corners track whatever the card is using — no magic
        // numbers that silently drift if the card's radius changes.
        val cardRadius = card.radius
        val lastIdx = rows.lastIndex
        rows.forEachIndexed { idx, row ->
            if (idx > 0) rowContainer.addView(inflateLanguageListDivider(rowContainer))
            val topRadius = if (idx == 0) cardRadius else 0f
            val bottomRadius = if (idx == lastIdx) cardRadius else 0f
            rowContainer.addView(buildLanguageListRow(rowContainer, row, topRadius, bottomRadius))
        }
        root.addView(card)
    }

    private fun buildLanguageListRow(
        container: ViewGroup,
        row: LangRow,
        topCornerRadius: Float,
        bottomCornerRadius: Float,
    ): View {
        val view = LayoutInflater.from(this)
            .inflate(R.layout.language_list_row, container, false)
        view.findViewById<TextView>(R.id.tvRowTitle).text = row.title
        view.setOnClickListener { row.onRowClick() }

        // The XML layout gives the trailing slot its default state: hidden,
        // clickable, focusable, borderless ripple, trash drawable. We only
        // tweak what differs from that per row-type.
        val trailing = view.findViewById<FrameLayout>(R.id.btnDelete)
        val trailingIcon = view.findViewById<ImageView>(R.id.ivDeleteIcon)
        when {
            row.isSelected -> {
                trailing.visibility = View.VISIBLE
                trailingIcon.setImageResource(R.drawable.ic_check)
                trailingIcon.imageTintList = ColorStateList.valueOf(themeColor(R.attr.ptAccent))
                // Status indicator — not tappable, no ripple.
                trailing.isClickable = false
                trailing.isFocusable = false
                trailing.foreground = null
            }
            row.canDelete -> {
                trailing.visibility = View.VISIBLE
                trailingIcon.imageTintList = ColorStateList.valueOf(themeColor(R.attr.ptTextMuted))
                trailing.setOnClickListener { row.onTrashClick() }
            }
            // else: stays GONE from XML default.
        }

        if (row.isSelected) {
            view.background = buildSelectedRowBackground(topCornerRadius, bottomCornerRadius)
        }
        return view
    }

    /** Selected-row background: 50% accent blended into the card fill, with a
     *  1dp stroke made from the accent blended 50% into the divider color.
     *  Top/bottom corner radii are passed in so the drawable matches the
     *  parent card's rounded corners on the first/last row. */
    private fun buildSelectedRowBackground(topRadius: Float, bottomRadius: Float): GradientDrawable {
        val dp = resources.displayMetrics.density
        val accent = themeColor(R.attr.ptAccent)
        val card = themeColor(R.attr.ptCard)
        // ptDivider is a low-alpha hairline (e.g. #12FFFFFF on dark) — blending
        // its raw RGB would treat it as pure white/black. Composite it over the
        // card so we blend against the color that actually renders on screen.
        val effectiveDivider = compositeOver(themeColor(R.attr.ptDivider), card)
        val fill = blendColors(accent, card, 0.10f)
        val stroke = blendColors(accent, effectiveDivider, 0.10f)
        return GradientDrawable().apply {
            setColor(fill)
            setStroke((1 * dp).toInt(), stroke)
            // Order: top-left, top-right, bottom-right, bottom-left (each x,y).
            cornerRadii = floatArrayOf(
                topRadius, topRadius,
                topRadius, topRadius,
                bottomRadius, bottomRadius,
                bottomRadius, bottomRadius,
            )
        }
    }

    /** Linearly blends [a] into [b] at the given ratio of [a] (0..1). Ignores
     *  alpha — callers should hand in opaque colors (see [compositeOver]). */
    private fun blendColors(a: Int, b: Int, ratio: Float): Int {
        val inv = 1f - ratio
        return Color.rgb(
            (Color.red(a) * ratio + Color.red(b) * inv).toInt(),
            (Color.green(a) * ratio + Color.green(b) * inv).toInt(),
            (Color.blue(a) * ratio + Color.blue(b) * inv).toInt(),
        )
    }

    /** Composites translucent [fg] over opaque [bg] — the color that will
     *  actually render where [fg] is painted on [bg]. */
    private fun compositeOver(fg: Int, bg: Int): Int {
        val a = Color.alpha(fg) / 255f
        val inv = 1f - a
        return Color.rgb(
            (Color.red(fg) * a + Color.red(bg) * inv).toInt(),
            (Color.green(fg) * a + Color.green(bg) * inv).toInt(),
            (Color.blue(fg) * a + Color.blue(bg) * inv).toInt(),
        )
    }

    private fun inflateLanguageListDivider(container: ViewGroup): View =
        LayoutInflater.from(this)
            .inflate(R.layout.settings_row_divider, container, false)

    // ── Delete flow ─────────────────────────────────────────────────────
    // Only reachable on rows where `canDelete=true`, so no selection /
    // sibling checks are needed here — those rows don't render a trash.

    private fun handleSourceDeleteTap(id: SourceLangId) {
        val chineseShared = id.packId == SourceLangId.ZH
        val message = if (chineseShared) {
            "This removes both Chinese (Simplified) and Chinese (Traditional) data from this device. You can redownload later."
        } else {
            "This removes ${id.displayName()} data from this device. You can redownload later."
        }
        showDeleteConfirm(
            title = "Delete ${id.displayName()}?",
            message = message,
        ) {
            LanguagePackStore.uninstall(applicationContext, id)
            showCurrentPage()
        }
    }

    private fun handleTargetDeleteTap(code: String, displayName: String) {
        showDeleteConfirm(
            title = "Delete $displayName?",
            message = "This removes $displayName dictionary data from this device. You can redownload later.",
        ) {
            LanguagePackStore.uninstallTarget(applicationContext, code)
            showCurrentPage()
        }
    }

    private fun showDeleteConfirm(title: String, message: String, onConfirm: () -> Unit) {
        OverlayAlert.Builder(this)
            .hideIcon()
            .setTitle(title)
            .setMessage(message)
            .addButton(
                "Delete",
                themeColor(R.attr.ptDanger),
                themeColor(R.attr.ptAccentOn),
            ) { onConfirm() }
            .addCancelButton()
            .showInActivity(this)
    }

    private fun langDisplayName(code: String): String =
        Locale(code).getDisplayLanguage(Locale.getDefault())
            .replaceFirstChar { it.uppercase(Locale.getDefault()) }

    /** Display name for target languages — shows the native language name. */
    private fun targetDisplayName(code: String): String {
        val locale = Locale(code)
        return locale.getDisplayLanguage(locale)
            .replaceFirstChar { it.uppercase(locale) }
    }

    interface Delegate {
        fun onSourceSelectionDone(sourceId: SourceLangId)
        fun onTargetSelectionDone(targetCode: String)
    }

    companion object {
        private const val TAG = "LangSetup"
        const val EXTRA_MODE = "mode"
        const val EXTRA_ONBOARDING = "onboarding"
        const val MODE_SOURCE = "source"
        const val MODE_TARGET = "target"

        var selectionDelegate: Delegate? = null

        fun launch(context: Context, mode: String) {
            context.startActivity(
                Intent(context, LanguageSetupActivity::class.java)
                    .putExtra(EXTRA_MODE, mode)
            )
        }
    }
}
