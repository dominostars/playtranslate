package com.playtranslate.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.nl.translate.TranslateLanguage
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.blendColors
import com.playtranslate.compositeOver
import com.playtranslate.language.ChineseScriptVariant
import com.playtranslate.language.DownloadProgress
import com.playtranslate.language.InstallResult
import com.playtranslate.language.LanguagePackStore
import com.playtranslate.language.PreloadResult
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageEngines
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.language.TargetGlossDatabaseProvider
import com.playtranslate.language.primeActivePair
import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.themeColor
import com.playtranslate.translation.OfflineModelReclaimer
import com.playtranslate.translation.bergamot.BergamotModelManager
import com.playtranslate.translation.llm.humanSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * The language pickers (translate-from, translate-to, and the standalone
 * pick-a-source list) as host-agnostic view builders — extracted from
 * [LanguageSetupActivity] so the SAME pages can run inside the Activity (the
 * in-app / onboarding / dual-screen presentation) and inside the floating
 * [OverlayWorkspace] over the game. Everything host-specific goes through
 * [Ui]; everything else — row building, search, the select→download→preload→
 * persist state machine, the delete flows — lives here, once.
 *
 * Threading: [scope] must be a Main-dispatched scope (lifecycleScope or the
 * workspace's). Download/preload work hops to IO itself; progress callbacks
 * that arrive on IO threads are trampolined through [mainHandler].
 */
class LanguagePickerBinder(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val installer: TargetPackInstaller,
    private val ui: Ui,
) {
    /** The host seam. An Activity host presents on itself and finishes on
     *  done; the workspace host presents in-window and dismisses. */
    interface Ui {
        /** Retitle the page chrome (toolbar / workspace header). */
        fun setTitle(title: String)

        /** Progress popup for downloads/preloads. [onDismiss] must cancel the
         *  in-flight work; [DismissReason] says whether the user bailed. */
        fun progress(title: String, onDismiss: (DismissReason) -> Unit): OverlayProgress

        /** Terminal error dialog (download/preload failure). */
        fun error(reason: String)

        fun toast(text: String)

        /** Present [builder] on this host's surface — `.show()` for an
         *  Activity, `.showInParent(modalLayer)` for the workspace. */
        fun present(builder: OverlayAlert.Builder)

        /** Rebuild the current page (a delete changed the row set). */
        fun refresh()

        /** Selection committed (prefs already written). The Activity notifies
         *  its Settings delegate and finishes; the workspace dismisses. */
        fun onSourceDone(id: SourceLangId)
        fun onTargetDone(code: String)

        /** The search field gained/lost focus — the workspace flips its
         *  window's IME policy here; the Activity needs nothing. */
        fun onSearchFocus(hasFocus: Boolean) {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeJob: Job? = null

    /** Cancel in-flight download/preload work (host teardown). */
    fun cancel() {
        activeJob?.cancel()
        installer.cancel()
    }

    // ── Source list ───────────────────────────────────────────────────────

    fun buildSourcePage(parent: ViewGroup): View {
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
        val storedId = Prefs(ctx).sourceLangId
        val currentId = storedId.takeIf { LanguagePackStore.isInstalled(ctx, it) }

        fun toRow(id: SourceLangId): LangRow {
            val isSelected = currentId != null && id == currentId
            // Deleting any variant that shares the selected pack (ZH ↔ ZH_HANT)
            // would pull files out from under the current engine, so treat the
            // sibling as non-deletable too — its trash just stays hidden.
            val sharesPackWithSelection = currentId != null && id.packId == currentId.packId
            // "Fully installed" = dictionary present AND required OCR available.
            // For a no-floor language (Russian) a missing Cyrillic pack reads as
            // not-installed → no trash; re-selecting re-downloads it (Concept A).
            val fullyInstalled = OcrModelManager.isFullyInstalled(ctx, id)
            // A no-floor language can't OCR on a 32-bit device (its arm64-only
            // recognizer can't run) — show the row disabled instead of dead-ending.
            val unavailable = OcrModelManager.isOcrUnavailableOnDevice(ctx, id)
            return LangRow(
                titleNorm = normalizeWithMap(id.displayName()),
                endonymNorm = normalizeWithMap(id.displayName(id.locale)),
                isSelected = isSelected && !unavailable,
                canDelete = fullyInstalled && !sharesPackWithSelection,
                disabled = unavailable,
                disabledReason = if (unavailable) ctx.getString(R.string.lang_setup_requires_64bit) else null,
                onRowClick = {
                    if (unavailable) {
                        ui.toast(ctx.getString(R.string.lang_setup_requires_64bit_msg, id.displayName()))
                    } else {
                        onSourceSelected(id)
                    }
                },
                onTrashClick = { handleSourceDeleteTap(id) },
            )
        }

        // Suggested: any source that is FULLY installed (dict + required OCR) —
        // bundled (JA) or downloaded. A no-floor language whose OCR pack is
        // missing is excluded so it isn't surfaced as ready. Unlike the target
        // picker this does NOT include the device locale, per user request.
        val suggested = allIds.filter { OcrModelManager.isFullyInstalled(ctx, it) }

        return bindLanguagePage(
            parent,
            LanguagePageData(
                toolbarTitle = ctx.getString(R.string.lang_translate_from),
                allRows = allIds.map(::toRow),
                suggestedRows = suggested.map(::toRow),
            ),
        )
    }

    // ── Standalone source picker (the Activity's MODE_PICK_SOURCE) ───────

    /** Pure pick-a-language page: same list/search/Suggested chrome as the
     *  source list, but selecting a row just reports its code via [onPicked] —
     *  no Prefs write, no pack download, no engine priming. A leading "Any"
     *  row (reported as null) clears the selection. Rows are never disabled
     *  or deletable: the result is a filter tag, so it needs no OCR
     *  capability or installed pack. */
    fun buildPickSourcePage(
        parent: ViewGroup,
        currentCode: String?,
        title: String?,
        onPicked: (String?) -> Unit,
    ): View {
        val collator = Collator.getInstance(Locale.getDefault())
        val allIds = SourceLangId.entries
            .sortedWith(compareBy(collator) { it.displayName() })
        val currentId = SourceLangId.fromCode(currentCode)

        fun toRow(id: SourceLangId) = LangRow(
            titleNorm = normalizeWithMap(id.displayName()),
            endonymNorm = normalizeWithMap(id.displayName(id.locale)),
            isSelected = id == currentId,
            canDelete = false,
            onRowClick = { onPicked(id.code) },
            onTrashClick = {},
        )

        // Deliberately kept out of allRows so a search never surfaces it —
        // it is a fixed leading affordance, not a language.
        val anyRow = LangRow(
            titleNorm = normalizeWithMap(ctx.getString(R.string.lang_pick_any)),
            endonymNorm = normalizeWithMap(""),
            isSelected = currentId == null,
            canDelete = false,
            onRowClick = { onPicked(null) },
            onTrashClick = {},
        )

        // Same "Suggested" notion as the source list: languages fully
        // installed on this device, i.e. the ones the user actually plays in.
        val suggested = allIds.filter { OcrModelManager.isFullyInstalled(ctx, it) }

        return bindLanguagePage(
            parent,
            LanguagePageData(
                toolbarTitle = title ?: ctx.getString(R.string.lang_translate_from),
                allRows = allIds.map(::toRow),
                suggestedRows = suggested.map(::toRow),
                leadingRows = listOf(anyRow),
            ),
        )
    }

    private fun onSourceSelected(id: SourceLangId) {
        // A forced-upgrade pack (e.g. the obsolete pre-Sudachi ja-v2) is
        // installed-but-non-functional. Re-selecting it must download a fresh
        // copy "as if never installed": uninstall first so needsDownload below
        // becomes true and the download+load path replaces it. uninstall also
        // releases the old dict/mmap handles (JapaneseEngine.close via
        // releaseForPack), so the reloaded engine binds to the new pack, not the
        // unlinked inode. This MUST run before the needsDownload val — captured
        // false otherwise, which would send us to showLoadingPopup and preload()
        // a just-deleted pack, crashing with PackMissing.
        if (LanguagePackStore.isForcedUpgrade(ctx, id)) {
            LanguagePackStore.uninstall(ctx, id)
        }
        val needsDownload = !LanguagePackStore.isInstalled(ctx, id)
        // Best-effort ML Kit fallback warm-up result: written on IO inside
        // sourceLoadAction, read on Main in onDone (the withContext boundary
        // provides the happens-before). See [primeActivePair].
        var mlKitReady = true

        val sourceLoadAction: suspend (
            (Int, Int, Long, Long) -> Unit,
            (Long, Long) -> Unit,
        ) -> Unit = { warmupProgress, ocrProgress ->
            val preloadResult = SourceLanguageEngines.get(ctx.applicationContext, id).preload()
            when (preloadResult) {
                is PreloadResult.Success -> { /* proceed */ }
                is PreloadResult.PackMissing -> throw IllegalStateException(
                    "Pack for ${id.code} missing after download — install flow did not persist files"
                )
                is PreloadResult.PackCorrupt -> {
                    // Roll back the partial install so the user is re-prompted
                    // to download on the next attempt rather than stuck with a
                    // broken pack that every engine access crashes against.
                    LanguagePackStore.uninstall(ctx.applicationContext, id)
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
            // Provision the rest of the active pair's offline assets — the
            // translation model(s) and this source's OCR recognizer — through
            // the shared helper so this flow and the pack-upgrade flow can't
            // drift on what a pair needs (the upgrade flow once skipped OCR).
            // Best-effort: a miss leaves the dictionary / online backends
            // working and only the offline tier degraded. mlKitReady gates the
            // "offline model unavailable" toast in onDone.
            mlKitReady = primeActivePair(
                ctx.applicationContext,
                id,
                Prefs(ctx.applicationContext).targetLang,
                onWarmup = warmupProgress,
                onOcr = ocrProgress,
                // No-floor languages (Russian) have no ML Kit OCR fallback, so the
                // recognizer download is mandatory: a failure throws here, the load
                // popup shows an error, and the selection is NOT persisted (the dict
                // stays; isFullyInstalled reports not-installed until a retry).
                ocrRequired = !OcrModelManager.hasMlKitFloor(id),
            )
        }
        val onDone: () -> Unit = {
            Prefs(ctx).sourceLang = id.code
            if (!mlKitReady) {
                ui.toast(ctx.getString(R.string.lang_setup_offline_model_unavailable))
            }
            ui.onSourceDone(id)
        }

        if (needsDownload) {
            showDownloadAndLoadPopup(
                langName = id.displayName(),
                downloadAction = { onProgress -> LanguagePackStore.install(ctx.applicationContext, id, onProgress) },
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

    fun buildTargetPage(parent: ViewGroup): View {
        val collator = Collator.getInstance(Locale.getDefault())
        val prefs = Prefs(ctx)
        val currentTarget = prefs.targetLang
        val currentVariant = prefs.targetChineseVariant
        val chineseSelected = ChineseScriptVariant.isChineseTarget(currentTarget)
        // All four Chinese variants share the single target-zh pack.
        val chineseInstalled =
            LanguagePackStore.isTargetInstalled(ctx, ChineseScriptVariant.BACKEND_CODE)

        fun plainRow(code: String, displayName: String): LangRow {
            // English has no gloss pack to manage, so trash never applies.
            val installed = code != "en" && LanguagePackStore.isTargetInstalled(ctx, code)
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

        fun chineseRow(variant: ChineseScriptVariant): LangRow = LangRow(
            titleNorm = normalizeWithMap(variant.displayLabel()),
            endonymNorm = normalizeWithMap(variant.displayLabel(Locale.forLanguageTag(variant.code))),
            isSelected = chineseSelected && variant == currentVariant,
            // The four variants share one pack, so deleting any one removes it:
            // hide trash across the whole group whenever a Chinese variant is the
            // selected target (mirrors the source ZH/ZH_HANT sharesPackWithSelection).
            canDelete = chineseInstalled && !chineseSelected,
            onRowClick = { onTargetSelected(ChineseScriptVariant.BACKEND_CODE, variant) },
            onTrashClick = { handleChineseTargetDeleteTap() },
        )

        // One row per ML Kit target, EXCEPT the bare "zh" — it expands into the
        // four Chinese script-variant rows, all backed by the single target-zh pack.
        val plain = TranslateLanguage.getAllLanguages()
            .filter { it != ChineseScriptVariant.BACKEND_CODE }
            .map { code -> val n = targetDisplayName(code); n to plainRow(code, n) }
        val chinese = ChineseScriptVariant.all.map { v -> v.displayLabel() to chineseRow(v) }
        val sorted = (plain + chinese).sortedWith(compareBy(collator) { it.first })

        // Suggested: device-locale language (if supported) + any installed target
        // packs. For Chinese, collapse to a single row (the selected variant, else
        // Simplified) so the four don't clutter the shortcut list.
        val deviceLang = Locale.getDefault().language
        val suggestedPlain = TranslateLanguage.getAllLanguages()
            .filter { it != ChineseScriptVariant.BACKEND_CODE }
            .filter { it == deviceLang || LanguagePackStore.isTargetInstalled(ctx, it) }
            .map { code -> val n = targetDisplayName(code); n to plainRow(code, n) }
        val suggestedChinese =
            if (deviceLang == ChineseScriptVariant.BACKEND_CODE || chineseInstalled) {
                val v = if (chineseSelected) currentVariant else ChineseScriptVariant.SIMPLIFIED
                listOf(v.displayLabel() to chineseRow(v))
            } else emptyList()
        val suggested = (suggestedPlain + suggestedChinese).sortedWith(compareBy(collator) { it.first })

        return bindLanguagePage(
            parent,
            LanguagePageData(
                toolbarTitle = ctx.getString(R.string.lang_translate_to),
                allRows = sorted.map { it.second },
                suggestedRows = suggested.map { it.second },
            ),
        )
    }

    private fun onTargetSelected(
        code: String,
        chineseVariant: ChineseScriptVariant = ChineseScriptVariant.SIMPLIFIED,
    ) {
        val sourceId = Prefs(ctx).sourceLangId
        val sourceLangCode = SourceLanguageProfiles[sourceId].translationCode
        // Capture the previous target before installAndLoad runs so we can
        // evict its cached FST after the new one is in place. Eviction is
        // gated on installation success — we never drop the previous pack
        // until the new one is fully downloaded and loaded, so a failed
        // switch keeps the prior selection working. (Switching between Chinese
        // variants keeps code=="zh", so previousTarget==code and the shared FST
        // is correctly NOT evicted.)
        val previousTarget = Prefs(ctx).targetLang
        installer.installAndLoad(
            sourceLangCode = sourceLangCode,
            targetCode = code,
            onSuccess = {
                val prefs = Prefs(ctx)
                prefs.targetLang = code
                // Persist the Chinese script choice atomically with the backend
                // code. For non-Chinese targets this resets any stale variant to
                // Simplified (a no-op for them); for Chinese it selects TW/HK/etc.
                prefs.targetChineseVariant = chineseVariant
                if (previousTarget.isNotBlank() && previousTarget != code) {
                    TargetGlossDatabaseProvider.release(previousTarget)
                }
                // LastSentenceCache stores ALREADY-LOCALIZED translation + gloss
                // output keyed only by sentence text. A target/variant change —
                // including Simplified⇄Traditional, which keeps targetLang=="zh" so
                // no language-code invalidation fires — would otherwise serve the
                // stale script when the panel/sheet reopens for the same sentence.
                // Drop it so the next view recomputes. Safe at this point: a
                // foreground picker success, navigating away, with no capture or
                // Anki sheet mid-await. (CaptureService's TranslationCache needs no
                // clear — it stores canonical Simplified and converts on read.)
                LastSentenceCache.clear()
                ui.onTargetDone(code)
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
        /** Rows pinned above everything in their own headerless card when the
         *  query is blank (e.g. the pick mode's "Any"); excluded from search. */
        val leadingRows: List<LangRow> = emptyList(),
    )

    /** Inflates the picker page, wires the search field, and renders [data].
     *  Shared by all modes so the search behaves identically for each.
     *  Returns the page view (not yet attached to [parent]). */
    private fun bindLanguagePage(parent: ViewGroup, data: LanguagePageData): View {
        ui.setTitle(data.toolbarTitle)

        val view = LayoutInflater.from(ctx)
            .inflate(R.layout.page_language_list, parent, false)
        val listRoot = view.findViewById<LinearLayout>(R.id.languageListRoot)
        val tvNoResults = view.findViewById<TextView>(R.id.tvNoResults)
        val etSearch = view.findViewById<EditText>(R.id.etSearch)
        val ivClear = view.findViewById<ImageView>(R.id.ivSearchClear)
        val ivSearchIcon = view.findViewById<ImageView>(R.id.ivSearchIcon)
        val underline = view.findViewById<View>(R.id.searchUnderline)

        // The search/clear vectors carry baked-in path colours — tint at runtime.
        ivClear.imageTintList = ColorStateList.valueOf(ctx.themeColor(R.attr.ptTextMuted))
        ivSearchIcon.imageTintList = ColorStateList.valueOf(ctx.themeColor(R.attr.ptTextMuted))

        // Rebuilds languageListRoot only — the search field is a sibling and
        // survives, so focus + IME are kept. [rawQuery] is normalized here,
        // the single normalization point.
        fun renderList(rawQuery: String) {
            listRoot.removeAllViews()
            val q = normalize(rawQuery)
            if (q.isEmpty()) {
                tvNoResults.visibility = View.GONE
                listRoot.visibility = View.VISIBLE
                if (data.leadingRows.isNotEmpty()) {
                    addLanguageSection(listRoot, null, data.leadingRows)
                }
                if (data.suggestedRows.isNotEmpty()) {
                    addLanguageSection(
                        listRoot,
                        ctx.getString(R.string.lang_section_suggested),
                        data.suggestedRows,
                    )
                }
                addLanguageSection(
                    listRoot,
                    ctx.getString(R.string.lang_section_all),
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
                    val header = ctx.resources.getQuantityString(
                        R.plurals.lang_search_match_count, matches.size, matches.size,
                    )
                    addLanguageSection(listRoot, header, matches, q)
                }
            }
        }

        wireSearchField(etSearch, ivClear, ivSearchIcon, underline) { raw -> renderList(raw) }

        renderList("")
        return view
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
                val imm = ctx.getSystemService(InputMethodManager::class.java)
                imm?.hideSoftInputFromWindow(etSearch.windowToken, 0)
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
            ivSearchIcon.imageTintList = ColorStateList.valueOf(ctx.themeColor(iconAttr))
            ui.onSearchFocus(hasFocus)
        }
    }

    /** Cross-fades the search underline between ptDivider and ptAccent. */
    private fun animateUnderline(underline: View, focused: Boolean) {
        val from = ctx.themeColor(if (focused) R.attr.ptDivider else R.attr.ptAccent)
        val to = ctx.themeColor(if (focused) R.attr.ptAccent else R.attr.ptDivider)
        ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
            duration = 120L
            addUpdateListener { underline.setBackgroundColor(it.animatedValue as Int) }
            start()
        }
    }

    // ── Download + load popup (OverlayProgress via Ui) ───────────────────

    private fun buildPopupDialog(title: String): OverlayProgress =
        ui.progress(title) { activeJob?.cancel() }

    private fun showDownloadAndLoadPopup(
        langName: String,
        downloadAction: suspend ((DownloadProgress) -> Unit) -> InstallResult,
        loadAction: suspend (
            (Int, Int, Long, Long) -> Unit,
            (Long, Long) -> Unit,
        ) -> Unit,
        onSuccess: () -> Unit,
    ) {
        val dialog = buildPopupDialog(langName)
        dialog.setMessage(ctx.getString(R.string.install_downloading_generic))
        dialog.setProgress(0)

        activeJob = scope.launch {
            val result = downloadAction { progress ->
                if (progress is DownloadProgress.Downloading && progress.totalBytes > 0) {
                    val pct = (progress.bytesReceived * 100L / progress.totalBytes).toInt()
                    mainHandler.post {
                        dialog.setProgress(pct)
                        dialog.setMessage(
                            ctx.getString(
                                R.string.install_downloading_with_bytes,
                                humanSize(ctx, progress.bytesReceived),
                                humanSize(ctx, progress.totalBytes)
                            )
                        )
                    }
                }
            }
            when (result) {
                is InstallResult.Success -> {
                    mainHandler.post {
                        dialog.setMessage(ctx.getString(R.string.lang_setup_preloading_message))
                        dialog.setIndeterminate(true)
                    }
                    try {
                        withContext(Dispatchers.IO) {
                            loadAction(
                                { i, n, recv, total ->
                                    mainHandler.post {
                                        dialog.showBergamotWarmupProgress(ctx, i, n, recv, total)
                                    }
                                },
                                { recv, total ->
                                    mainHandler.post {
                                        dialog.showOcrDownloadProgress(ctx, recv, total)
                                    }
                                },
                            )
                        }
                        dialog.dismiss()
                        onSuccess()
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        // User tapped Cancel — dialog already dismissed.
                    } catch (e: Exception) {
                        Log.e(TAG, "loadAction threw after install succeeded", e)
                        dialog.dismiss()
                        ui.error(e.message ?: "Loading failed")
                    }
                }
                is InstallResult.Failed -> {
                    dialog.dismiss()
                    ui.error(result.reason)
                }
                is InstallResult.Cancelled -> {
                    dialog.dismiss()
                }
            }
        }
    }

    private fun showLoadingPopup(
        langName: String,
        loadAction: suspend (
            (Int, Int, Long, Long) -> Unit,
            (Long, Long) -> Unit,
        ) -> Unit,
        onSuccess: () -> Unit,
    ) {
        val dialog = buildPopupDialog(langName)
        dialog.setMessage(ctx.getString(R.string.lang_setup_preloading_message))
        dialog.setIndeterminate(true)

        activeJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    loadAction(
                        { i, n, recv, total ->
                            mainHandler.post {
                                dialog.showBergamotWarmupProgress(ctx, i, n, recv, total)
                            }
                        },
                        { recv, total ->
                            mainHandler.post {
                                dialog.showOcrDownloadProgress(ctx, recv, total)
                            }
                        },
                    )
                }
                dialog.dismiss()
                onSuccess()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                // User tapped Cancel — dialog already dismissed, nothing to report.
            } catch (e: Exception) {
                Log.e(TAG, "loadAction threw", e)
                dialog.dismiss()
                ui.error(e.message ?: "Loading failed")
            }
        }
    }

    // ── Rows ──────────────────────────────────────────────────────────────

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
        /** Greyed-out, non-selectable (e.g. a no-floor language whose OCR can't
         *  run on this device); [onRowClick] explains why instead of selecting. */
        val disabled: Boolean = false,
        /** Shown in the endonym slot for a [disabled] row (e.g. "Requires a
         *  64-bit device"); overrides the endonym. */
        val disabledReason: String? = null,
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
     * header followed by a [PtGroupCard] containing [rows] separated by
     * inset dividers. Skips entirely if [rows] is empty.
     */
    private fun addLanguageSection(
        root: LinearLayout,
        title: String?,
        rows: List<LangRow>,
        query: String? = null,
    ) {
        if (rows.isEmpty()) return
        val inflater = LayoutInflater.from(ctx)

        if (title != null) {
            val header = inflater.inflate(R.layout.settings_group_header, root, false)
            header.findViewById<TextView>(R.id.tvGroupTitle).text = title
            root.addView(header)
        }

        val card = PtGroupCard(ctx)
        // Read the corner radius off the card itself so the selected-row
        // highlight's corners track whatever the card is using — no magic
        // numbers that silently drift if the card's radius changes.
        val cardRadius = card.radiusPx
        val lastIdx = rows.lastIndex
        rows.forEachIndexed { idx, row ->
            if (idx > 0) card.addView(inflateLanguageListDivider(card))
            val topRadius = if (idx == 0) cardRadius else 0f
            val bottomRadius = if (idx == lastIdx) cardRadius else 0f
            card.addView(buildLanguageListRow(card, row, topRadius, bottomRadius, query))
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
        val view = LayoutInflater.from(ctx)
            .inflate(R.layout.language_list_row, container, false)
        view.findViewById<TextView>(R.id.tvRowTitle).text = highlighted(row.titleNorm, query)

        // Endonym subtitle — hidden when it would merely repeat the title
        // (e.g. the UI language is this row's own language). A disabled row reuses
        // this slot for its reason text instead (see LangRow.disabledReason).
        val tvEndonym = view.findViewById<TextView>(R.id.tvRowEndonym)
        val reason = row.disabledReason
        if (reason != null) {
            tvEndonym.visibility = View.VISIBLE
            tvEndonym.text = reason
        } else if (row.endonymNorm.original.isBlank() ||
            row.endonymNorm.original.equals(row.titleNorm.original, ignoreCase = true)
        ) {
            tvEndonym.visibility = View.GONE
        } else {
            tvEndonym.visibility = View.VISIBLE
            tvEndonym.text = highlighted(row.endonymNorm, query)
        }

        // Disabled rows render dimmed; the row stays clickable so the tap can
        // explain why (onRowClick shows a toast rather than selecting).
        view.alpha = if (row.disabled) 0.5f else 1f

        view.setOnClickListener {
            val imm = ctx.getSystemService(InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
            row.onRowClick()
        }

        // The XML layout gives the trailing slot its default state: hidden,
        // clickable, focusable, borderless ripple, trash drawable. We only
        // tweak what differs from that per row-type. The trash tint is set in
        // code for every branch — the layout's app:tint is AppCompat-only and
        // silently dropped under the workspace's plain inflater.
        val trailing = view.findViewById<FrameLayout>(R.id.btnDelete)
        val trailingIcon = view.findViewById<ImageView>(R.id.ivDeleteIcon)
        trailingIcon.imageTintList = ColorStateList.valueOf(ctx.themeColor(R.attr.ptTextMuted))
        when {
            row.isSelected -> {
                trailing.visibility = View.VISIBLE
                trailingIcon.setImageResource(R.drawable.ic_check)
                trailingIcon.imageTintList = ColorStateList.valueOf(ctx.themeColor(R.attr.ptAccent))
                // Status indicator — not tappable, no ripple.
                trailing.isClickable = false
                trailing.isFocusable = false
                trailing.foreground = null
            }
            row.canDelete -> {
                trailing.visibility = View.VISIBLE
                trailing.setOnClickListener { row.onTrashClick() }
            }
            // else: stays GONE from XML default.
        }

        if (row.isSelected) {
            view.background = buildSelectedRowBackground(topCornerRadius, bottomCornerRadius)
        }
        return view
    }

    /** Selected-row background: 10% accent blended into the card fill, with a
     *  1dp stroke made from the accent blended 10% into the divider color.
     *  Top/bottom corner radii are passed in so the drawable matches the
     *  parent card's rounded corners on the first/last row. */
    private fun buildSelectedRowBackground(topRadius: Float, bottomRadius: Float): GradientDrawable {
        val dp = ctx.resources.displayMetrics.density
        val accent = ctx.themeColor(R.attr.ptAccent)
        val card = ctx.themeColor(R.attr.ptCard)
        // ptDivider is a low-alpha hairline (e.g. #12FFFFFF on dark) — blending
        // its raw RGB would treat it as pure white/black. Composite it over the
        // card so we blend against the color that actually renders on screen.
        val effectiveDivider = compositeOver(ctx.themeColor(R.attr.ptDivider), card)
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
        LayoutInflater.from(ctx)
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
            LanguagePackStore.uninstall(ctx.applicationContext, id)
            // Reclaim the Bergamot source→English model(s) too. Chinese variants
            // share a pack, so cover both (only zh-en exists in the catalog).
            val sources = if (chineseShared) {
                listOf(SourceLangId.ZH, SourceLangId.ZH_HANT)
            } else {
                listOf(id)
            }
            scope.launch {
                val mgr = BergamotModelManager(ctx.applicationContext)
                sources.forEach { mgr.deleteForSource(SourceLanguageProfiles[it].translationCode) }
                // Reclaim this language's ML Kit model too, unless it's still
                // needed as a target (or is the selected pair). Both ZH variants
                // share translationCode CHINESE, so this resolves to one call.
                OfflineModelReclaimer.reclaimMlKitIfUnneeded(
                    ctx.applicationContext,
                    SourceLanguageProfiles[id].translationCode,
                )
                // Forget the removed language(s)' chosen OCR engine. The OCR
                // pack(s) they leave orphaned are reclaimed at the next launch
                // sweep (MainActivity), not here: sweepOrphans must run only at
                // that quiescent point, never from an interactive flow that can
                // race a live capture resolving a pack. See its kdoc.
                sources.forEach { Prefs(ctx.applicationContext).clearOcrBackendToken(it) }
            }
            ui.refresh()
        }
    }

    private fun handleTargetDeleteTap(code: String, displayName: String) {
        showDeleteConfirm(
            title = "Delete $displayName?",
            message = "This removes $displayName dictionary data from this device. You can redownload later.",
        ) {
            LanguagePackStore.uninstallTarget(ctx.applicationContext, code)
            // Reclaim the Bergamot English→target model for this target too.
            scope.launch {
                BergamotModelManager(ctx.applicationContext).deleteForTarget(code)
                // Reclaim this target's ML Kit model too, unless it's still needed
                // as a source (or is the selected pair).
                OfflineModelReclaimer.reclaimMlKitIfUnneeded(ctx.applicationContext, code)
            }
            ui.refresh()
        }
    }

    /** Delete for any Chinese variant row. All four share the single target-zh
     *  pack, so the confirm names every variant that goes at once and the
     *  teardown runs once on "zh" (mirrors [handleSourceDeleteTap]'s ZH/ZH_HANT
     *  shared-pack branch). canDelete already hid the trash while a Chinese
     *  variant was the selected target, so nothing is pulled from under it. */
    private fun handleChineseTargetDeleteTap() {
        val variants = ChineseScriptVariant.all.joinToString("\n") { it.displayLabel() }
        showDeleteConfirm(
            title = "Delete Languages?",
            message = "This will remove the shared Chinese definition data:\n\n" +
                "$variants\n\nYou can redownload later.",
        ) {
            val code = ChineseScriptVariant.BACKEND_CODE
            LanguagePackStore.uninstallTarget(ctx.applicationContext, code)
            scope.launch {
                BergamotModelManager(ctx.applicationContext).deleteForTarget(code)
                OfflineModelReclaimer.reclaimMlKitIfUnneeded(ctx.applicationContext, code)
            }
            ui.refresh()
        }
    }

    private fun showDeleteConfirm(title: String, message: String, onConfirm: () -> Unit) {
        ui.present(
            OverlayAlert.Builder(ctx)
                .setTitle(title)
                .setMessage(message)
                .addButton(
                    "Delete",
                    ctx.themeColor(R.attr.ptDanger),
                    ctx.themeColor(R.attr.ptAccentOn),
                ) { onConfirm() }
                .addCancelButton(),
        )
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
                ForegroundColorSpan(ctx.themeColor(R.attr.ptAccent)),
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

    private companion object {
        const val TAG = "LangPicker"
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
