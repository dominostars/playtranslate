package com.playtranslate.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
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

        // Suggested: any source whose pack is already installed — bundled
        // (JA) or downloaded (ZH / ZH_HANT share the same pack, EN, ES).
        // Unlike the target picker this does NOT include the device locale,
        // per user request.
        val suggested = allIds.filter { LanguagePackStore.isInstalled(this, it) }

        if (suggested.isNotEmpty()) {
            val suggestedRows = suggested.map { id ->
                id.displayName() to { onSourceSelected(id) }
            }
            addLanguageSection(root, title = "Suggested", rows = suggestedRows)
        }
        val allRows = allIds.map { id ->
            id.displayName() to { onSourceSelected(id) }
        }
        addLanguageSection(root, title = "All", rows = allRows)

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

        // Suggested: device-locale language (if supported) + any target packs
        // already installed. Surfaces the likely target(s) without removing
        // them from the canonical alphabetical list below.
        val deviceLang = Locale.getDefault().language
        val suggested = allLangs.filter { (code, _) ->
            code == deviceLang || LanguagePackStore.isTargetInstalled(this, code)
        }

        if (suggested.isNotEmpty()) {
            val suggestedRows = suggested.map { (code, displayName) ->
                displayName to { onTargetSelected(code) }
            }
            addLanguageSection(root, title = "Suggested", rows = suggestedRows)
        }
        val allRows = allLangs.map { (code, displayName) ->
            displayName to { onTargetSelected(code) }
        }
        addLanguageSection(root, title = "All", rows = allRows)

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

    /**
     * Adds one grouped-card section to [root]: an optional uppercase group
     * header followed by a MaterialCardView containing [rows] separated by
     * inset dividers. Skips entirely if [rows] is empty.
     */
    private fun addLanguageSection(
        root: LinearLayout,
        title: String?,
        rows: List<Pair<String, () -> Unit>>,
    ) {
        if (rows.isEmpty()) return
        val inflater = LayoutInflater.from(this)

        if (title != null) {
            val header = inflater.inflate(R.layout.settings_group_header, root, false)
            header.findViewById<TextView>(R.id.tvGroupTitle).text = title
            root.addView(header)
        }

        val card = inflater.inflate(R.layout.language_list_section, root, false)
        val rowContainer = card.findViewById<LinearLayout>(R.id.sectionRows)
        rows.forEachIndexed { idx, (name, onClick) ->
            if (idx > 0) rowContainer.addView(inflateLanguageListDivider(rowContainer))
            rowContainer.addView(buildLanguageListRow(rowContainer, name, onClick))
        }
        root.addView(card)
    }

    private fun buildLanguageListRow(container: android.view.ViewGroup, name: String, onClick: () -> Unit): View {
        val row = LayoutInflater.from(this)
            .inflate(R.layout.settings_row_value, container, false)
        row.findViewById<TextView>(R.id.tvRowTitle).text = name
        row.findViewById<TextView>(R.id.tvRowValue).text = ""
        row.setOnClickListener { onClick() }
        return row
    }

    private fun inflateLanguageListDivider(container: android.view.ViewGroup): View =
        LayoutInflater.from(this)
            .inflate(R.layout.settings_row_divider, container, false)

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
