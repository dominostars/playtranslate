package com.playtranslate.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import com.playtranslate.language.LanguagePackCatalogLoader
import com.playtranslate.language.LanguagePackStore
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_language_setup)

        toolbar = findViewById(R.id.toolbar)
        contentFrame = findViewById(R.id.contentFrame)

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
        val container = view.findViewById<LinearLayout>(R.id.languageListContainer)

        SourceLangId.entries.forEach { id ->
            val profile = SourceLanguageProfiles[id]
            container.addView(buildLanguageListRow(profile.displayName) {
                onSourceSelected(id)
            })
        }

        contentFrame.addView(view)
    }

    private fun onSourceSelected(id: SourceLangId) {
        val needsDownload = id != SourceLangId.JA && !LanguagePackStore.isInstalled(this, id)

        val sourceLoadAction: suspend () -> Unit = {
            SourceLanguageEngines.get(applicationContext, id).preload()
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
                langName = SourceLanguageProfiles[id].displayName,
                downloadAction = { onProgress -> LanguagePackStore.install(applicationContext, id, onProgress) },
                loadAction = sourceLoadAction,
                onSuccess = onDone,
            )
        } else {
            showLoadingPopup(
                langName = SourceLanguageProfiles[id].displayName,
                loadAction = sourceLoadAction,
                onSuccess = onDone,
            )
        }
    }

    // ── Target list ──────────────────────────────────────────────────────

    private fun showTargetList() {
        toolbar.title = getString(R.string.lang_translate_to)
        val view = LayoutInflater.from(this).inflate(R.layout.page_language_list, contentFrame, false)
        val container = view.findViewById<LinearLayout>(R.id.languageListContainer)

        val allLangs = TranslateLanguage.getAllLanguages()
            .map { code -> code to langDisplayName(code) }
            .sortedBy { it.second }

        for ((code, displayName) in allLangs) {
            container.addView(buildLanguageListRow(displayName) {
                onTargetSelected(code)
            })
        }

        contentFrame.addView(view)
    }

    private fun onTargetSelected(code: String) {
        val sourceId = selectedSource ?: Prefs(this).sourceLangId
        val sourceLangCode = SourceLanguageProfiles[sourceId].translationCode
        val targetName = langDisplayName(code)

        val saveAndFinish = {
            Prefs(this@LanguageSetupActivity).targetLang = code
            selectionDelegate?.onTargetSelectionDone(code)
            finish()
        }

        // Check if a target gloss pack exists in the catalog and isn't installed yet
        val needsTargetPack = code != "en"
            && LanguagePackCatalogLoader.entryForKey(this, "target-$code") != null
            && !LanguagePackStore.isTargetInstalled(this, code)

        if (needsTargetPack) {
            // Download target pack first, then ML Kit model
            showDownloadAndLoadPopup(
                langName = "$targetName definitions",
                downloadAction = { onProgress ->
                    LanguagePackStore.installTarget(applicationContext, code, onProgress)
                },
                loadAction = {
                    val tm = TranslationManager(sourceLangCode, code)
                    try { tm.ensureModelReady() } finally { tm.close() }
                    // EN→target model for definition translation fallback
                    if (code != "en") {
                        val enTm = TranslationManager("en", code)
                        try { enTm.ensureModelReady() } finally { enTm.close() }
                    }
                },
                onSuccess = saveAndFinish,
            )
        } else {
            // No target pack needed/available — just download ML Kit model
            val (dialog, tvStatus, progressBar) = buildPopupDialog()
            tvStatus.text = "Downloading $targetName"
            progressBar.isIndeterminate = true
            dialog.show()

            activeJob = lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val tm = TranslationManager(sourceLangCode, code)
                        try { tm.ensureModelReady() } finally { tm.close() }
                        // EN→target model for definition translation fallback
                        if (code != "en") {
                            val enTm = TranslationManager("en", code)
                            try { enTm.ensureModelReady() } finally { enTm.close() }
                        }
                    }
                    dialog.dismiss()
                    saveAndFinish()
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    // User tapped Cancel — dialog already dismissed.
                } catch (e: Exception) {
                    dialog.dismiss()
                    showErrorPopup(e.message ?: "Failed to download translation model")
                }
            }
        }
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

    private fun buildLanguageListRow(name: String, onClick: () -> Unit): View {
        val dp = resources.displayMetrics.density
        return TextView(this).apply {
            text = name
            textSize = 16f
            setTypeface(null, Typeface.NORMAL)
            setTextColor(themeColor(R.attr.colorTextPrimary))
            setPadding(
                (16 * dp).toInt(), (14 * dp).toInt(),
                (16 * dp).toInt(), (14 * dp).toInt()
            )
            background = GradientDrawable().apply {
                setColor(themeColor(R.attr.colorBgSurface))
                setStroke((1 * dp).toInt(), themeColor(R.attr.colorBgDivider))
                cornerRadius = 8 * dp
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (6 * dp).toInt() }
            setOnClickListener { onClick() }
        }
    }

    private fun langDisplayName(code: String): String =
        Locale(code).getDisplayLanguage(Locale.ENGLISH)
            .replaceFirstChar { it.uppercase() }

    interface Delegate {
        fun onSourceSelectionDone(sourceId: SourceLangId)
        fun onTargetSelectionDone(targetCode: String)
    }

    companion object {
        const val EXTRA_MODE = "mode"
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
