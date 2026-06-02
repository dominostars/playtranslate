package com.playtranslate.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.mlkit.nl.translate.TranslateLanguage
import com.playtranslate.translation.OfflineModelReclaimer
import com.playtranslate.PlayTranslateApplication
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.language.DownloadProgress
import com.playtranslate.language.InstallResult
import com.playtranslate.language.LanguagePackStore
import com.playtranslate.language.PreloadResult
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.translation.llm.humanSize
import com.playtranslate.language.TargetGlossDatabaseProvider
import com.playtranslate.applyEdgeToEdge
import com.playtranslate.blendColors
import com.playtranslate.compositeOver
import com.playtranslate.applyTheme
import com.playtranslate.themeColor
import com.playtranslate.preloadMlKitFallbackModels
import com.playtranslate.translation.bergamot.BergamotModelManager
import com.playtranslate.translation.bergamot.BergamotWarmup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.text.Normalizer
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
        when (page) {
            Page.SOURCE_LIST -> showSourceList()
            Page.TARGET_LIST -> showTargetList()
        }
    }

    // ── Source list ───────────────────────────────────────────────────────

    private fun showSourceList() {
        // Sort by the system-locale display name so the order matches what
        // the user actually reads (and is meaningful in their locale's
        // collation rules — Collator handles accented chars / non-Latin scripts).
        val collator = Collator.getInstance(Locale.getDefault())
        val allIds = SourceLangId.entries
            .sortedWith(compareBy(collator) { it.displayName() })
        // Treat the stored id as "no selection" when its pack isn't installed:
        // Prefs defaults to JA even on a fresh onboarding where the user hasn't
        // chosen anything yet, so without this check JA would render with a
        // checkmark before any download.
        val storedId = Prefs(this).sourceLangId
        val currentId = storedId.takeIf { LanguagePackStore.isInstalled(this, it) }

        fun toRow(id: SourceLangId): LangRow {
            val installed = LanguagePackStore.isInstalled(this, id)
            val isSelected = currentId != null && id == currentId
            // Deleting any variant that shares the selected pack (ZH ↔ ZH_HANT)
            // would pull files out from under the current engine, so treat the
            // sibling as non-deletable too — its trash just stays hidden.
            val sharesPackWithSelection = currentId != null && id.packId == currentId.packId
            return LangRow(
                titleNorm = normalizeWithMap(id.displayName()),
                endonymNorm = normalizeWithMap(id.displayName(id.locale)),
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

        bindLanguagePage(
            LanguagePageData(
                toolbarTitle = getString(R.string.lang_translate_from),
                allRows = allIds.map(::toRow),
                suggestedRows = suggested.map(::toRow),
            )
        )
    }

    private fun onSourceSelected(id: SourceLangId) {
        val needsDownload = !LanguagePackStore.isInstalled(this, id)
        // Best-effort ML Kit fallback warm-up result: written on IO inside
        // sourceLoadAction, read on Main in onDone (the withContext boundary
        // provides the happens-before). See [preloadMlKitFallbackModels].
        var mlKitReady = true

        val sourceLoadAction: suspend ((Int, Int, Long, Long) -> Unit) -> Unit = { warmupProgress ->
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
            // Warm the ML Kit fallback translation model(s) for newSource →
            // currentTarget (plus EN → target for the definition-translation
            // path). Best-effort: ML Kit is the degraded last-resort backend
            // and its GMS-mediated download fails on some devices, but the
            // dictionary / OCR / online backends don't need it — a miss must
            // not block adding the language.
            val currentTarget = Prefs(applicationContext).targetLang
            val src = SourceLanguageProfiles[id].translationCode
            mlKitReady =
                if (BergamotWarmup.ensureForPair(applicationContext, src, currentTarget, warmupProgress)) true
                else preloadMlKitFallbackModels(src, currentTarget)
        }
        val onDone: () -> Unit = {
            Prefs(this).sourceLang = id.code
            // Auto-provision this source's default OCR engine: record the choice
            // now (ML Kit floor covers OCR until the pack lands) + best-effort
            // download it on the app scope — survives finish(), Wi-Fi-gated and
            // non-blocking so it never holds up setup completion.
            OcrModelManager.setDefaultBackendIfUnset(applicationContext, id)
            (applicationContext as PlayTranslateApplication).appScope.launch {
                runCatching { OcrModelManager.ensureDefaultForSource(applicationContext, id) }
            }
            if (!mlKitReady) {
                Toast.makeText(
                    this, R.string.lang_setup_offline_model_unavailable, Toast.LENGTH_LONG,
                ).show()
            }
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
        val collator = Collator.getInstance(Locale.getDefault())
        val allLangs = TranslateLanguage.getAllLanguages()
            .map { code -> code to targetDisplayName(code) }
            .sortedWith(compareBy(collator) { it.second })

        val currentTarget = Prefs(this).targetLang

        fun toRow(code: String, displayName: String): LangRow {
            // English has no gloss pack to manage, so trash never applies.
            val installed = code != "en" && LanguagePackStore.isTargetInstalled(this, code)
            val isSelected = code == currentTarget
            return LangRow(
                titleNorm = normalizeWithMap(displayName),
                endonymNorm = normalizeWithMap(targetDisplayName(code, Locale.forLanguageTag(code))),
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

        bindLanguagePage(
            LanguagePageData(
                toolbarTitle = getString(R.string.lang_translate_to),
                allRows = allLangs.map { (c, n) -> toRow(c, n) },
                suggestedRows = suggested.map { (c, n) -> toRow(c, n) },
            )
        )
    }

    private fun onTargetSelected(code: String) {
        val sourceId = selectedSource ?: Prefs(this).sourceLangId
        val sourceLangCode = SourceLanguageProfiles[sourceId].translationCode
        // Capture the previous target before installAndLoad runs so we can
        // evict its cached FST after the new one is in place. Eviction is
        // gated on installation success — we never drop the previous pack
        // until the new one is fully downloaded and loaded, so a failed
        // switch keeps the prior selection working.
        val previousTarget = Prefs(this).targetLang
        targetInstaller.installAndLoad(
            sourceLangCode = sourceLangCode,
            targetCode = code,
            onSuccess = {
                Prefs(this@LanguageSetupActivity).targetLang = code
                if (previousTarget.isNotBlank() && previousTarget != code) {
                    TargetGlossDatabaseProvider.release(previousTarget)
                }
                selectionDelegate?.onTargetSelectionDone(code)
                finish()
            },
        )
    }

    // ── Shared list page: search field + filtered render ─────────────────

    /** Pre-built, mode-agnostic data for one picker page. */
    private class LanguagePageData(
        val toolbarTitle: String,
        /** Every language, once — the source the filter runs against. */
        val allRows: List<LangRow>,
        /** Subset shown under "Suggested" when the query is blank. */
        val suggestedRows: List<LangRow>,
    )

    /** Inflates the picker page, wires the search field, and renders [data].
     *  Shared by both modes so the search behaves identically for each. */
    private fun bindLanguagePage(data: LanguagePageData) {
        toolbar.title = data.toolbarTitle

        val view = LayoutInflater.from(this)
            .inflate(R.layout.page_language_list, contentFrame, false)
        val listRoot = view.findViewById<LinearLayout>(R.id.languageListRoot)
        val tvNoResults = view.findViewById<TextView>(R.id.tvNoResults)
        val etSearch = view.findViewById<EditText>(R.id.etSearch)
        val ivClear = view.findViewById<ImageView>(R.id.ivSearchClear)
        val ivSearchIcon = view.findViewById<ImageView>(R.id.ivSearchIcon)
        val underline = view.findViewById<View>(R.id.searchUnderline)

        // The search/clear vectors carry baked-in path colours — tint at runtime.
        ivClear.imageTintList = ColorStateList.valueOf(themeColor(R.attr.ptTextMuted))
        ivSearchIcon.imageTintList = ColorStateList.valueOf(themeColor(R.attr.ptTextMuted))

        // Rebuilds languageListRoot only — the search field is a sibling and
        // survives, so focus + IME are kept. [rawQuery] is normalized here,
        // the single normalization point.
        fun renderList(rawQuery: String) {
            listRoot.removeAllViews()
            val q = normalize(rawQuery)
            if (q.isEmpty()) {
                tvNoResults.visibility = View.GONE
                listRoot.visibility = View.VISIBLE
                if (data.suggestedRows.isNotEmpty()) {
                    addLanguageSection(
                        listRoot,
                        getString(R.string.lang_section_suggested),
                        data.suggestedRows,
                    )
                }
                addLanguageSection(
                    listRoot,
                    getString(R.string.lang_section_all),
                    data.allRows,
                )
            } else {
                val matches = data.allRows.filter { it.matches(q) }
                if (matches.isEmpty()) {
                    listRoot.visibility = View.GONE
                    tvNoResults.visibility = View.VISIBLE
                } else {
                    tvNoResults.visibility = View.GONE
                    listRoot.visibility = View.VISIBLE
                    val header = resources.getQuantityString(
                        R.plurals.lang_search_match_count, matches.size, matches.size,
                    )
                    addLanguageSection(listRoot, header, matches, q)
                }
            }
        }

        wireSearchField(etSearch, ivClear, ivSearchIcon, underline) { raw -> renderList(raw) }

        renderList("")
        contentFrame.addView(view)
    }

    /** Wires the inline search field: debounced live filter, clear button,
     *  IME "search" action, and the focus-driven underline/icon colours. */
    private fun wireSearchField(
        etSearch: EditText,
        ivClear: ImageView,
        ivSearchIcon: ImageView,
        underline: View,
        onQueryChanged: (String) -> Unit,
    ) {
        val debounce = Handler(Looper.getMainLooper())
        var pending: Runnable? = null

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                // Clear button tracks content immediately — not debounced.
                ivClear.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                pending?.let { debounce.removeCallbacks(it) }
                val runnable = Runnable { onQueryChanged(text) }
                pending = runnable
                debounce.postDelayed(runnable, 120L)
            }
        })

        ivClear.setOnClickListener { etSearch.setText("") }

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                // Filtering is already live — the action just dismisses the IME.
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
                true
            } else {
                false
            }
        }

        etSearch.setOnFocusChangeListener { _, hasFocus ->
            animateUnderline(underline, hasFocus)
            val iconAttr = when {
                hasFocus -> R.attr.ptAccent
                etSearch.text.isNotEmpty() -> R.attr.ptText
                else -> R.attr.ptTextMuted
            }
            ivSearchIcon.imageTintList = ColorStateList.valueOf(themeColor(iconAttr))
        }
    }

    /** Cross-fades the search underline between ptDivider and ptAccent. */
    private fun animateUnderline(underline: View, focused: Boolean) {
        val from = themeColor(if (focused) R.attr.ptDivider else R.attr.ptAccent)
        val to = themeColor(if (focused) R.attr.ptAccent else R.attr.ptDivider)
        ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
            duration = 120L
            addUpdateListener { underline.setBackgroundColor(it.animatedValue as Int) }
            start()
        }
    }

    // ── Download + load popup (OverlayProgress) ─────────────────────────

    private fun buildPopupDialog(title: String): OverlayProgress =
        OverlayProgress.Builder(this)
            .setTitle(title)
            .setOnDismiss { activeJob?.cancel() }
            .show()

    private fun showDownloadAndLoadPopup(
        langName: String,
        downloadAction: suspend ((DownloadProgress) -> Unit) -> InstallResult,
        loadAction: suspend ((Int, Int, Long, Long) -> Unit) -> Unit,
        onSuccess: () -> Unit,
    ) {
        val dialog = buildPopupDialog(langName)
        dialog.setMessage(getString(R.string.install_downloading_generic))
        dialog.setProgress(0)

        activeJob = lifecycleScope.launch {
            val result = downloadAction { progress ->
                if (progress is DownloadProgress.Downloading && progress.totalBytes > 0) {
                    val pct = (progress.bytesReceived * 100L / progress.totalBytes).toInt()
                    runOnUiThread {
                        dialog.setProgress(pct)
                        dialog.setMessage(
                            getString(
                                R.string.install_downloading_with_bytes,
                                humanSize(progress.bytesReceived),
                                humanSize(progress.totalBytes)
                            )
                        )
                    }
                }
            }
            when (result) {
                is InstallResult.Success -> {
                    runOnUiThread {
                        dialog.setMessage(getString(R.string.lang_setup_preloading_message))
                        dialog.setIndeterminate(true)
                    }
                    try {
                        withContext(Dispatchers.IO) {
                            loadAction { i, n, recv, total ->
                                runOnUiThread {
                                    dialog.showBergamotWarmupProgress(
                                        this@LanguageSetupActivity, i, n, recv, total,
                                    )
                                }
                            }
                        }
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
        loadAction: suspend ((Int, Int, Long, Long) -> Unit) -> Unit,
        onSuccess: () -> Unit,
    ) {
        val dialog = buildPopupDialog(langName)
        dialog.setMessage(getString(R.string.lang_setup_preloading_message))
        dialog.setIndeterminate(true)

        activeJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    loadAction { i, n, recv, total ->
                        runOnUiThread {
                            dialog.showBergamotWarmupProgress(
                                this@LanguageSetupActivity, i, n, recv, total,
                            )
                        }
                    }
                }
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
        /** Display name in the user's UI locale, plus its normalization map. */
        val titleNorm: NormalizedText,
        /** Name in the language's own locale (endonym), plus its map. */
        val endonymNorm: NormalizedText,
        val isSelected: Boolean,
        val canDelete: Boolean,
        val onRowClick: () -> Unit,
        val onTrashClick: () -> Unit,
    ) {
        /** True when [normalizedQuery] (already normalized) is a substring of
         *  the title or the endonym. */
        fun matches(normalizedQuery: String): Boolean =
            titleNorm.normalized.contains(normalizedQuery) ||
                endonymNorm.normalized.contains(normalizedQuery)
    }

    /**
     * Adds one grouped-card section to [root]: an optional uppercase group
     * header followed by a MaterialCardView containing [rows] separated by
     * inset dividers. Skips entirely if [rows] is empty.
     */
    private fun addLanguageSection(
        root: LinearLayout,
        title: String?,
        rows: List<LangRow>,
        query: String? = null,
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
            rowContainer.addView(buildLanguageListRow(rowContainer, row, topRadius, bottomRadius, query))
        }
        root.addView(card)
    }

    private fun buildLanguageListRow(
        container: ViewGroup,
        row: LangRow,
        topCornerRadius: Float,
        bottomCornerRadius: Float,
        query: String?,
    ): View {
        val view = LayoutInflater.from(this)
            .inflate(R.layout.language_list_row, container, false)
        view.findViewById<TextView>(R.id.tvRowTitle).text = highlighted(row.titleNorm, query)

        // Endonym subtitle — hidden when it would merely repeat the title
        // (e.g. the UI language is this row's own language).
        val tvEndonym = view.findViewById<TextView>(R.id.tvRowEndonym)
        if (row.endonymNorm.original.isBlank() ||
            row.endonymNorm.original.equals(row.titleNorm.original, ignoreCase = true)
        ) {
            tvEndonym.visibility = View.GONE
        } else {
            tvEndonym.visibility = View.VISIBLE
            tvEndonym.text = highlighted(row.endonymNorm, query)
        }

        view.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
            row.onRowClick()
        }

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

    private fun inflateLanguageListDivider(container: ViewGroup): View =
        LayoutInflater.from(this)
            .inflate(R.layout.settings_row_divider, container, false)

    // ── Delete flow ─────────────────────────────────────────────────────
    // Only reachable on rows where `canDelete=true`, so no selection /
    // sibling checks are needed here — those rows don't render a trash.

    private fun handleSourceDeleteTap(id: SourceLangId) {
        val chineseShared = id.packId == SourceLangId.ZH
        val title: String
        val message: String
        if (chineseShared) {
            // ZH and ZH_HANT share one on-disk pack, so the confirm has to
            // name both variants that will go at once.
            title = "Delete Languages?"
            message = "This will remove both:\n\n" +
                "${SourceLangId.ZH.displayName()}\n" +
                "${SourceLangId.ZH_HANT.displayName()}\n\n" +
                "You can redownload later."
        } else {
            title = "Delete ${id.displayName()}?"
            message = "This removes ${id.displayName()} data from this device. You can redownload later."
        }
        showDeleteConfirm(title = title, message = message) {
            LanguagePackStore.uninstall(applicationContext, id)
            // Reclaim the Bergamot source→English model(s) too. Chinese variants
            // share a pack, so cover both (only zh-en exists in the catalog).
            val sources = if (chineseShared) {
                listOf(SourceLangId.ZH, SourceLangId.ZH_HANT)
            } else {
                listOf(id)
            }
            lifecycleScope.launch {
                val mgr = BergamotModelManager(applicationContext)
                sources.forEach { mgr.deleteForSource(SourceLanguageProfiles[it].translationCode) }
                // Reclaim this language's ML Kit model too, unless it's still
                // needed as a target (or is the selected pair). Both ZH variants
                // share translationCode CHINESE, so this resolves to one call.
                OfflineModelReclaimer.reclaimMlKitIfUnneeded(
                    applicationContext,
                    SourceLanguageProfiles[id].translationCode,
                )
                // Forget the removed language(s)' chosen OCR engine + reclaim any
                // OCR pack no remaining language needs. The pack is already gone
                // from installedCodes (uninstall ran above), so it's an orphan;
                // sweepOrphans self-guards — a pack with a live capture session is
                // skipped now and swept at the next quiescent teardown.
                sources.forEach { Prefs(applicationContext).clearOcrBackendToken(it) }
                withContext(Dispatchers.IO) { OcrModelManager.sweepOrphans(applicationContext) }
            }
            showCurrentPage()
        }
    }

    private fun handleTargetDeleteTap(code: String, displayName: String) {
        showDeleteConfirm(
            title = "Delete $displayName?",
            message = "This removes $displayName dictionary data from this device. You can redownload later.",
        ) {
            LanguagePackStore.uninstallTarget(applicationContext, code)
            // Reclaim the Bergamot English→target model for this target too.
            lifecycleScope.launch {
                BergamotModelManager(applicationContext).deleteForTarget(code)
                // Reclaim this target's ML Kit model too, unless it's still needed
                // as a source (or is the selected pair).
                OfflineModelReclaimer.reclaimMlKitIfUnneeded(applicationContext, code)
            }
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
            .show()
    }

    /** [norm].original with the (already-normalized) [query] match spanned
     *  accent + bold, or the plain original when [query] is null/empty or
     *  doesn't occur in this string. */
    private fun highlighted(norm: NormalizedText, query: String?): CharSequence {
        if (query.isNullOrEmpty()) return norm.original
        val matchStart = norm.normalized.indexOf(query)
        if (matchStart < 0) return norm.original
        val origStart = norm.normToOrig[matchStart]
        val lastCp = norm.normToOrig[matchStart + query.length - 1]
        val origEnd = lastCp + Character.charCount(norm.original.codePointAt(lastCp))
        return SpannableString(norm.original).apply {
            setSpan(
                ForegroundColorSpan(themeColor(R.attr.ptAccent)),
                origStart, origEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            setSpan(
                StyleSpan(Typeface.BOLD),
                origStart, origEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    /** Display name for a target language [code], rendered in [locale]
     *  (default: the system locale — e.g. Japanese shows as "Japanese" on an
     *  English device). Pass `Locale.forLanguageTag(code)` to get the endonym ("日本語"). */
    private fun targetDisplayName(code: String, locale: Locale = Locale.getDefault()): String =
        Locale.forLanguageTag(code).getDisplayLanguage(locale)
            .replaceFirstChar { it.uppercase(locale) }

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

// ── Search-text normalization ────────────────────────────────────────────
// One shared transform powers both matching and highlight, so the matched
// string and the searched-for string can never disagree.

private val COMBINING_MARKS = Regex("\\p{Mn}+")

/** A display string, its normalized form, and an index map: normToOrig[i] is
 *  the start index, in [original], of the code point that produced
 *  normalized[i]. Lets a match found in [normalized] map back to [original]. */
private class NormalizedText(
    val original: String,
    val normalized: String,
    val normToOrig: IntArray,
)

/** NFKD-decomposes, strips combining marks, and lowercases [original] one
 *  code point at a time, recording where each normalized char came from.
 *  NOT trimmed — trimming would desync the index map. */
private fun normalizeWithMap(original: String): NormalizedText {
    val sb = StringBuilder()
    val map = ArrayList<Int>(original.length + 4)
    var i = 0
    while (i < original.length) {
        val cp = original.codePointAt(i)
        val piece = Normalizer
            .normalize(String(Character.toChars(cp)), Normalizer.Form.NFKD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
        for (c in piece) {
            sb.append(c)
            map.add(i)
        }
        i += Character.charCount(cp)
    }
    return NormalizedText(original, sb.toString(), map.toIntArray())
}

/** Query-side normalization: the same transform as [normalizeWithMap], then
 *  trimmed (the query needs no index map, and soft keyboards tack on stray
 *  trailing spaces). */
private fun normalize(s: String): String = normalizeWithMap(s).normalized.trim()
