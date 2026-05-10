package com.playtranslate.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.NestedScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.playtranslate.AnkiManager
import com.playtranslate.Prefs
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.capturableDisplays
import com.playtranslate.R
import com.playtranslate.applyAccentOverlay
import com.playtranslate.fullScreenDialogTheme
import com.playtranslate.themeColor
import kotlinx.coroutines.launch

/**
 * Full-screen settings dialog. Works in two modes:
 *
 * - **Dialog mode** (default): shown via FragmentTransaction.add(). Has toolbar + close button.
 * - **Inline mode** (setShowsDialog(false)): embedded in MainActivity's settingsContainer.
 *
 * All view ↔ pref wiring is delegated to [SettingsRenderer]. This class handles
 * lifecycle, scroll restore, display listeners, and permission results.
 */
class SettingsBottomSheet : DialogFragment() {

    // ── External callbacks (set by the host) ────────────────────────────
    var onDisplayChanged: (() -> Unit)? = null
    var onSourceLangChanged: (() -> Unit)? = null
    var onScreenModeChanged: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onThemeChanged: ((scrollY: Int) -> Unit)? = null
    var onOverlayModeChanged: (() -> Unit)? = null

    // ── Internal state ──────────────────────────────────────────────────
    private var renderer: SettingsRenderer? = null
    private var currentView: View? = null
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var lastDisplayIds: Set<Int> = emptySet()

    private val requestAnkiPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) renderer?.refreshAnkiSection()
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun getTheme(): Int = fullScreenDialogTheme(requireContext())

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        applyAccentOverlay(dialog.context.theme, requireContext())
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_settings, container, false)

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(R.style.AnimSlideRight)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        currentView = view
        setupViews(view)
    }

    override fun onDestroyView() {
        renderer?.displayThumbnails?.values?.forEach { it?.recycle() }
        renderer?.displayThumbnails?.clear()
        displayListener?.let {
            val dm = context?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            dm?.unregisterDisplayListener(it)
        }
        displayListener = null
        renderer = null
        currentView = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        renderer?.refreshAnkiSection()
        renderer?.refreshOverlayIconSwitch()
        renderer?.refreshAutoModeToggle()
        // Pick up backend toggle changes made while we were paused —
        // DeepLSettingsActivity flips deeplEnabled while the prefs listener
        // is unregistered, so onResume is the catch-up point.
        renderer?.refreshDeeplBackendSwitch()
        renderer?.refreshLingvaBackendSwitch()
        renderer?.refreshTranslategemmaSwitch()
        renderer?.refreshQwenSwitch()
        // Always re-render every backend's status line on resume — picks
        // up new DeepL keys, freshly toggled state, and triggers a usage
        // re-fetch (the call doesn't consume DeepL characters).
        renderer?.refreshAllBackendStatuses()
        // Reconcile the translation cache against any backend preference
        // changes made while we were paused (e.g. DeepLSettingsActivity
        // saving a key flips deeplEnabled on). Without this, cache-hit-only
        // translate batches could keep returning the previous backend's
        // results until some unrelated cache miss happened to trigger
        // reconciliation. The SP listener below handles the same path
        // when Settings is in the foreground; this is the "paused" twin.
        com.playtranslate.CaptureService.instance?.reconcileBackendPreference()

        val ctx = context ?: return
        val sp = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "show_overlay_icon" -> renderer?.refreshOverlayIconSwitch()
                "compact_overlay_icon" -> renderer?.refreshCompactIconSwitch()
                "auto_translation_mode" -> renderer?.refreshAutoModeToggle()
                Prefs.KEY_DEEPL_ENABLED -> {
                    renderer?.refreshDeeplBackendSwitch()
                    renderer?.refreshAllBackendStatuses()
                    com.playtranslate.CaptureService.instance?.reconcileBackendPreference()
                }
                Prefs.KEY_LINGVA_ENABLED -> {
                    renderer?.refreshLingvaBackendSwitch()
                    renderer?.refreshAllBackendStatuses()
                    com.playtranslate.CaptureService.instance?.reconcileBackendPreference()
                }
                Prefs.KEY_TRANSLATEGEMMA_ENABLED -> {
                    renderer?.refreshTranslategemmaSwitch()
                    renderer?.refreshAllBackendStatuses()
                    com.playtranslate.CaptureService.instance?.reconcileBackendPreference()
                    maybeUnloadOnDeviceLlmIfBothDisabled(ctx)
                }
                Prefs.KEY_QWEN_ENABLED -> {
                    renderer?.refreshQwenSwitch()
                    renderer?.refreshAllBackendStatuses()
                    com.playtranslate.CaptureService.instance?.reconcileBackendPreference()
                    maybeUnloadOnDeviceLlmIfBothDisabled(ctx)
                }
            }
        }
        sp.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    /** When the user disables BOTH on-device LLM backends, drop the loaded
     *  model from native memory. Frees ~300-400 MB of KV cache + scratch
     *  immediately and lets the kernel reclaim the mmap'd weight pages. If
     *  only one backend is disabled, the singleton stays loaded for the other.
     *
     *  Mutex-serialized inside [LlamaTranslator.unloadModel] — won't race with
     *  any in-flight translation triggered just before the toggle changed. */
    private fun maybeUnloadOnDeviceLlmIfBothDisabled(ctx: Context) {
        val prefs = Prefs(ctx)
        if (!prefs.translateGemmaEnabled && !prefs.qwenEnabled) {
            viewLifecycleOwner.lifecycleScope.launch {
                com.playtranslate.translation.translategemma.LlamaTranslator
                    .getInstance(ctx).unloadModel()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        val ctx = context ?: return
        val sp = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
        prefsListener?.let { sp.unregisterOnSharedPreferenceChangeListener(it) }
        prefsListener = null
    }

    // ── View setup ──────────────────────────────────────────────────────

    private fun setupViews(view: View) {
        val hideDismiss = arguments?.getBoolean(ARG_HIDE_DISMISS, false) ?: false
        val isDialog = showsDialog
        val prefs = Prefs(requireContext())

        // Toolbar (dialog mode only). Dialog mode is only entered from the
        // single-screen onboarding/main path (MainActivity.checkOnboardingState),
        // so the toolbar appears only in single-screen mode and the title is
        // the app name. Dual-screen flows use inline mode where the toolbar
        // stays GONE per its XML default.
        if (isDialog) {
            view.findViewById<View>(R.id.settingsToolbar).visibility = View.VISIBLE
            view.findViewById<android.widget.TextView>(R.id.tvSettingsTitle)
                .text = getString(R.string.app_name)
            val closeBtn = view.findViewById<View>(R.id.btnCloseSettings)
            if (hideDismiss) {
                closeBtn.visibility = View.GONE
                dialog?.setOnKeyListener { _, keyCode, event ->
                    if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                        event.action == android.view.KeyEvent.ACTION_UP) {
                        activity?.finish()
                        true
                    } else false
                }
            } else {
                closeBtn.setOnClickListener { dismiss() }
            }
        }

        // Scroll position restore after theme change
        val settingsScrollView = view.findViewById<NestedScrollView>(R.id.settingsScrollView)
        val savedScroll = prefs.settingsScrollY
        if (savedScroll > 0) {
            fun tryRestore() {
                if (settingsScrollView.height > 0) {
                    settingsScrollView.scrollTo(0, savedScroll)
                    prefs.settingsScrollY = 0
                } else {
                    settingsScrollView.postDelayed(::tryRestore, 16)
                }
            }
            settingsScrollView.post { tryRestore() }
        }

        // Create and bind the renderer
        val r = SettingsRenderer(
            root = view,
            prefs = prefs,
            ctx = requireContext(),
            lifecycleScope = viewLifecycleOwner.lifecycleScope,
            callbacks = object : SettingsRenderer.Callbacks {
                override fun onClose() { this@SettingsBottomSheet.onClose?.invoke() ?: dismiss() }
                override fun onThemeChanged(scrollY: Int) {
                    this@SettingsBottomSheet.onThemeChanged?.invoke(scrollY) ?: run {
                        prefs.settingsScrollY = scrollY
                        prefs.suppressNextTransition = true
                        activity?.recreate()
                    }
                }
                override fun onDisplayChanged() { this@SettingsBottomSheet.onDisplayChanged?.invoke() }
                override fun onSourceLangChanged() { this@SettingsBottomSheet.onSourceLangChanged?.invoke() }
                override fun onOverlayModeChanged() { this@SettingsBottomSheet.onOverlayModeChanged?.invoke() }
                override fun onScreenModeChanged() { this@SettingsBottomSheet.onScreenModeChanged?.invoke() }
                override fun requestAnkiPermission() {
                    requestAnkiPermission.launch(AnkiManager.PERMISSION)
                }
                override fun openLanguageSetup(mode: String) {
                    setLanguageDelegate()
                    LanguageSetupActivity.launch(requireContext(), mode)
                }
                override fun openDeepLSettings() {
                    startActivity(android.content.Intent(requireContext(), DeepLSettingsActivity::class.java))
                }
                override fun startTranslateGemmaDownload() {
                    showTranslateGemmaDownloadDialog()
                }
                override fun showTranslateGemmaDisableDialog() {
                    this@SettingsBottomSheet.showTranslateGemmaDisableDialog()
                }
                override fun startQwenDownload() {
                    showQwenDownloadDialog()
                }
                override fun showQwenDisableDialog() {
                    this@SettingsBottomSheet.showQwenDisableDialog()
                }
                override fun showHotkeyDialog(
                    title: String?, onSet: (List<Int>) -> Unit, onCancel: () -> Unit
                ) {
                    val dialog = HotkeySetupDialog.newInstance(title)
                    dialog.onHotkeySet = onSet
                    dialog.onCancelled = onCancel
                    dialog.show(childFragmentManager, "hotkey_setup")
                }
                override fun showAnkiDeckPicker(onDeckSelected: () -> Unit) {
                    val picker = AnkiDeckPickerDialog.newInstance()
                    picker.onDeckSelected = onDeckSelected
                    picker.show(childFragmentManager, AnkiDeckPickerDialog.TAG)
                }
                override fun getScrollY(): Int = settingsScrollView.scrollY
            }
        )
        renderer = r

        // Initialize display list and load thumbnails
        setupDisplays(view, r, prefs)

        // Bind all rows
        r.bind()
    }

    // ── Display management ──────────────────────────────────────────────

    private fun setupDisplays(view: View, r: SettingsRenderer, prefs: Prefs) {
        val displayManager = requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.capturableDisplays()
        lastDisplayIds = displays.mapTo(mutableSetOf()) { it.displayId }

        r.displayList = displays

        // Register display listener for hot-plug
        displayListener?.let { displayManager.unregisterDisplayListener(it) }
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) { reinflateIfDisplaysChanged(displayManager) }
            override fun onDisplayRemoved(displayId: Int) { reinflateIfDisplaysChanged(displayManager) }
            // capturableDisplays() filters on STATE_ON, so a fold/unfold or
            // monitor sleep/wake changes the picker's set without firing
            // add/remove. Same-count swaps (one panel off as another comes on)
            // would be missed by a count check, so compare the set of ids.
            override fun onDisplayChanged(displayId: Int) { reinflateIfDisplaysChanged(displayManager) }
        }
        displayManager.registerDisplayListener(displayListener, null)

        // Capture thumbnails asynchronously
        val myDisplayId = requireActivity().display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
        displays.forEach { display ->
            val mgr = PlayTranslateAccessibilityService.instance?.screenshotManager
            if (mgr != null) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val bitmap = mgr.requestClean(display.displayId)
                    if (bitmap != null) {
                        r.displayThumbnails[display.displayId] = scaleThumbnail(bitmap)
                        view.post { if (isAdded) r.refreshDisplayRows(Prefs(requireContext())) }
                    } else if (display.displayId == myDisplayId) {
                        captureActivityWindow { thumb ->
                            r.displayThumbnails[display.displayId] = thumb
                            if (isAdded) r.refreshDisplayRows(Prefs(requireContext()))
                        }
                    }
                }
            } else if (display.displayId == myDisplayId) {
                captureActivityWindow { thumb ->
                    r.displayThumbnails[display.displayId] = thumb
                    if (isAdded) r.refreshDisplayRows(Prefs(requireContext()))
                }
            }
        }
    }

    private fun reinflateIfDisplaysChanged(dm: DisplayManager) {
        val newIds = dm.capturableDisplays().mapTo(mutableSetOf()) { it.displayId }
        if (newIds != lastDisplayIds && isAdded) {
            lastDisplayIds = newIds
            reinflateContent()
        }
    }

    // ── Re-inflate (used for theme changes in dialog mode) ──────────────

    fun reinflateContent() {
        val old = currentView ?: return
        val parent = old.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(old)
        parent.removeView(old)
        val newView = LayoutInflater.from(requireActivity())
            .inflate(R.layout.dialog_settings, parent, false)
        parent.addView(newView, index)
        currentView = newView
        setupViews(newView)
        val ctx = requireActivity()
        val bgColor = ctx.themeColor(R.attr.ptBg)
        dialog?.window?.apply {
            statusBarColor = bgColor
            navigationBarColor = bgColor
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(
                ctx.themeColor(R.attr.ptSurface)))
        }
    }

    // ── Language delegate ────────────────────────────────────────────────

    private fun setLanguageDelegate() {
        LanguageSetupActivity.selectionDelegate = object : LanguageSetupActivity.Delegate {
            override fun onSourceSelectionDone(sourceId: com.playtranslate.language.SourceLangId) {
                renderer?.refreshLanguageRow()
                onSourceLangChanged?.invoke()
            }
            override fun onTargetSelectionDone(targetCode: String) {
                renderer?.refreshLanguageRow()
                onSourceLangChanged?.invoke()
            }
        }
    }

    // ── Thumbnail helpers ───────────────────────────────────────────────

    private fun scaleThumbnail(bitmap: Bitmap): Bitmap {
        val targetW = 192
        val scale = targetW.toFloat() / bitmap.width
        val scaled = Bitmap.createScaledBitmap(
            bitmap, targetW, (bitmap.height * scale).toInt(), true
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun captureActivityWindow(onReady: (Bitmap?) -> Unit) {
        val activity = activity ?: run { onReady(null); return }
        val decorView = activity.window.decorView
        val w = decorView.width.takeIf { it > 0 } ?: run { onReady(null); return }
        val h = decorView.height.takeIf { it > 0 } ?: run { onReady(null); return }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        PixelCopy.request(activity.window, bmp, { result ->
            if (result == PixelCopy.SUCCESS) onReady(scaleThumbnail(bmp))
            else { bmp.recycle(); onReady(null) }
        }, Handler(Looper.getMainLooper()))
    }

    // ── TranslateGemma flow ─────────────────────────────────────────────

    private var translategemmaDownloadJob: kotlinx.coroutines.Job? = null

    /** Show the modal download dialog (OverlayProgress).
     *  Drives a [com.playtranslate.translation.llm.OnDeviceLlmDownloader] configured
     *  for TG from the bottom sheet's lifecycle scope — dismissing the sheet
     *  cancels the coroutine but preserves the partial file (resume on next
     *  attempt). The Cancel button explicitly deletes the partial file. */
    private fun showTranslateGemmaDownloadDialog() {
        val ctx = context ?: return
        val backend = com.playtranslate.translation.TranslationBackendRegistry
            .byId("translategemma") as? com.playtranslate.translation.llm.OnDeviceLlmBackend
            ?: return
        val downloader = com.playtranslate.translation.llm.OnDeviceLlmDownloader(
            context = ctx,
            modelHelper = com.playtranslate.translation.translategemma.TranslateGemmaModel,
            totalMemFloorBytes = backend.totalMemFloorBytes,
        )

        // Metered-network warning before kicking off the multi-GB download.
        if (downloader.isCurrentNetworkMetered()) {
            val sizeStr = com.playtranslate.translation.translategemma
                .TranslateGemmaModel.humanSize(ctx)
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.translategemma_metered_warning_title)
                .setMessage(getString(R.string.translategemma_metered_warning_message, sizeStr))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    runTranslateGemmaDownload(ctx, downloader)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> }
                .show()
            return
        }
        runTranslateGemmaDownload(ctx, downloader)
    }

    private fun runTranslateGemmaDownload(
        ctx: Context,
        downloader: com.playtranslate.translation.llm.OnDeviceLlmDownloader,
    ) {
        val activity = activity ?: return
        val sizeStr = com.playtranslate.translation.translategemma
            .TranslateGemmaModel.humanSize(ctx)

        // Reference captured into the cancel callback below; the dialog is
        // assigned right after via the Builder, then mutated as the download
        // progresses.
        var dialog: OverlayProgress? = null
        dialog = OverlayProgress.Builder(ctx)
            .setTitle(getString(R.string.translategemma_display_name))
            .setMessage(getString(R.string.translategemma_status_downloading, "0 B", sizeStr))
            .setProgress(0)
            .setOnCancel {
                translategemmaDownloadJob?.cancel()
                // Explicit cancel deletes the partial file (no resume on next attempt).
                downloader.deletePartial()
                renderer?.refreshAllBackendStatuses()
            }
            .showInActivity(activity)

        translategemmaDownloadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val outcome = downloader.run { progress ->
                    requireActivity().runOnUiThread {
                        when (progress) {
                            is com.playtranslate.translation.llm
                                .OnDeviceLlmDownloader.Progress.Downloading -> {
                                val recv = com.playtranslate.translation.llm
                                    .humanSize(progress.received)
                                val total = com.playtranslate.translation.llm
                                    .humanSize(progress.total)
                                dialog?.setMessage(getString(
                                    R.string.translategemma_status_downloading,
                                    recv, total,
                                ))
                                if (progress.total > 0) {
                                    dialog?.setProgress(
                                        ((progress.received * 100) / progress.total).toInt()
                                    )
                                }
                            }
                            is com.playtranslate.translation.llm
                                .OnDeviceLlmDownloader.Progress.Verifying -> {
                                dialog?.setMessage(getString(R.string.translategemma_status_verifying))
                                dialog?.setProgress(100)
                            }
                        }
                    }
                }
                if (!isAdded) return@launch
                requireActivity().runOnUiThread {
                    dialog?.dismiss()
                    when (outcome) {
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Success -> {
                            // Flip the pref → SP listener fires → switch + status refresh + reconcile.
                            Prefs(ctx).translateGemmaEnabled = true
                        }
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Refused -> {
                            android.widget.Toast.makeText(
                                ctx, outcome.reason, android.widget.Toast.LENGTH_LONG
                            ).show()
                            renderer?.refreshAllBackendStatuses()
                        }
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Failed -> {
                            android.widget.Toast.makeText(
                                ctx,
                                getString(R.string.translategemma_download_failed, outcome.reason),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                            renderer?.refreshAllBackendStatuses()
                        }
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Cancelled -> {
                            // Partial file kept (lifecycle dismiss). Settings will say "Tap to download"
                            // because isInstalled() is false — but next tap resumes from offset.
                            android.widget.Toast.makeText(
                                ctx, R.string.translategemma_download_paused,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                            renderer?.refreshAllBackendStatuses()
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAdded) {
                    requireActivity().runOnUiThread {
                        dialog?.dismiss()
                        android.widget.Toast.makeText(
                            ctx,
                            getString(R.string.translategemma_download_failed,
                                e.message ?: e.javaClass.simpleName),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                        renderer?.refreshAllBackendStatuses()
                    }
                }
            } finally {
                // OverlayProgress lives on activity.window.decorView, not the
                // fragment view — a fragment-only lifecycle cancel (sheet
                // dismissed mid-download) would otherwise leave the scrim
                // stuck. dismiss() is idempotent so this is safe even after
                // the success/outcome branches above already dismissed.
                dialog?.dismiss()
            }
        }
    }


    /** OverlayAlert with three options when the user taps an enabled TG row.
     *  Scrim-tap and Cancel both revert the optimistic switch flip via
     *  [SettingsRenderer.refreshTranslategemmaSwitch]. */
    private fun showTranslateGemmaDisableDialog() {
        val ctx = context ?: return
        val activity = activity ?: return
        val oc = com.playtranslate.OverlayColors
        val sizeStr = com.playtranslate.translation.translategemma
            .TranslateGemmaModel.humanSize(ctx)
        OverlayAlert.Builder(ctx)
            .setTitle(getString(R.string.translategemma_disable_title))
            .setMessage(getString(R.string.translategemma_disable_message, sizeStr))
            .hideIcon()
            .addButton(getString(R.string.translategemma_disable_keep), oc.accent(ctx)) {
                // File kept; only the toggle flips. SP listener picks up the change.
                Prefs(ctx).translateGemmaEnabled = false
            }
            .addButton(getString(R.string.translategemma_disable_delete), oc.divider(ctx), oc.danger(ctx)) {
                Prefs(ctx).translateGemmaEnabled = false
                com.playtranslate.translation.translategemma
                    .TranslateGemmaModel.delete(ctx)
                // Drop the loaded model from native memory too. Without this,
                // the unlinked file's mmap'd pages remain valid and a subsequent
                // re-download would serve stale weights from the previous mmap
                // because LlamaTranslator.ensureLoaded matches on the path string
                // (which is unchanged after delete + re-download to the same
                // FILENAME). See Codex adversarial-review Finding #1.
                viewLifecycleOwner.lifecycleScope.launch {
                    com.playtranslate.translation.translategemma.LlamaTranslator
                        .getInstance(ctx).unloadModel()
                }
                renderer?.refreshAllBackendStatuses()
            }
            .addCancelButton { renderer?.refreshTranslategemmaSwitch() }
            .setOnDismiss { renderer?.refreshTranslategemmaSwitch() }
            .showInActivity(activity)
    }

    // ── Qwen flow ───────────────────────────────────────────────────────

    private var qwenDownloadJob: kotlinx.coroutines.Job? = null

    /** Show the modal download dialog for Qwen. Mirrors
     *  [showTranslateGemmaDownloadDialog] but with QwenModel + a 4 GB total-mem
     *  floor (Qwen 1.5B fits comfortably below TG's 6 GB requirement). */
    private fun showQwenDownloadDialog() {
        val ctx = context ?: return
        val backend = com.playtranslate.translation.TranslationBackendRegistry
            .byId("qwen") as? com.playtranslate.translation.llm.OnDeviceLlmBackend
            ?: return
        val downloader = com.playtranslate.translation.llm.OnDeviceLlmDownloader(
            context = ctx,
            modelHelper = com.playtranslate.translation.qwen.QwenModel,
            totalMemFloorBytes = backend.totalMemFloorBytes,
        )

        if (downloader.isCurrentNetworkMetered()) {
            val sizeStr = com.playtranslate.translation.qwen.QwenModel.humanSize(ctx)
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.qwen_metered_warning_title)
                .setMessage(getString(R.string.qwen_metered_warning_message, sizeStr))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    runQwenDownload(ctx, downloader)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> }
                .show()
            return
        }
        runQwenDownload(ctx, downloader)
    }

    private fun runQwenDownload(
        ctx: Context,
        downloader: com.playtranslate.translation.llm.OnDeviceLlmDownloader,
    ) {
        val activity = activity ?: return
        val sizeStr = com.playtranslate.translation.qwen.QwenModel.humanSize(ctx)

        var dialog: OverlayProgress? = null
        dialog = OverlayProgress.Builder(ctx)
            .setTitle(getString(R.string.qwen_display_name))
            .setMessage(getString(R.string.qwen_status_downloading, "0 B", sizeStr))
            .setProgress(0)
            .setOnCancel {
                qwenDownloadJob?.cancel()
                downloader.deletePartial()
                renderer?.refreshAllBackendStatuses()
            }
            .showInActivity(activity)

        qwenDownloadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val outcome = downloader.run { progress ->
                    requireActivity().runOnUiThread {
                        when (progress) {
                            is com.playtranslate.translation.llm
                                .OnDeviceLlmDownloader.Progress.Downloading -> {
                                val recv = com.playtranslate.translation.llm
                                    .humanSize(progress.received)
                                val total = com.playtranslate.translation.llm
                                    .humanSize(progress.total)
                                dialog?.setMessage(getString(
                                    R.string.qwen_status_downloading,
                                    recv, total,
                                ))
                                if (progress.total > 0) {
                                    dialog?.setProgress(
                                        ((progress.received * 100) / progress.total).toInt()
                                    )
                                }
                            }
                            is com.playtranslate.translation.llm
                                .OnDeviceLlmDownloader.Progress.Verifying -> {
                                dialog?.setMessage(getString(R.string.qwen_status_verifying))
                                dialog?.setProgress(100)
                            }
                        }
                    }
                }
                if (!isAdded) return@launch
                requireActivity().runOnUiThread {
                    dialog?.dismiss()
                    when (outcome) {
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Success -> {
                            Prefs(ctx).qwenEnabled = true
                        }
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Refused -> {
                            android.widget.Toast.makeText(
                                ctx, outcome.reason, android.widget.Toast.LENGTH_LONG
                            ).show()
                            renderer?.refreshAllBackendStatuses()
                        }
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Failed -> {
                            android.widget.Toast.makeText(
                                ctx,
                                getString(R.string.qwen_download_failed, outcome.reason),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                            renderer?.refreshAllBackendStatuses()
                        }
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Cancelled -> {
                            android.widget.Toast.makeText(
                                ctx, R.string.qwen_download_paused,
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                            renderer?.refreshAllBackendStatuses()
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAdded) {
                    requireActivity().runOnUiThread {
                        dialog?.dismiss()
                        android.widget.Toast.makeText(
                            ctx,
                            getString(R.string.qwen_download_failed,
                                e.message ?: e.javaClass.simpleName),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                        renderer?.refreshAllBackendStatuses()
                    }
                }
            } finally {
                // See translategemma flow above — OverlayProgress sits on
                // activity decor, so a fragment-only lifecycle cancel
                // bypasses the inner dismisses. Idempotent late dismiss
                // catches the !isAdded return and CancellationException
                // paths.
                dialog?.dismiss()
            }
        }
    }

    /** OverlayAlert with three options when the user taps an enabled Qwen row.
     *  Scrim-tap and Cancel both revert the optimistic switch flip via
     *  [SettingsRenderer.refreshQwenSwitch]. */
    private fun showQwenDisableDialog() {
        val ctx = context ?: return
        val activity = activity ?: return
        val oc = com.playtranslate.OverlayColors
        val sizeStr = com.playtranslate.translation.qwen.QwenModel.humanSize(ctx)
        OverlayAlert.Builder(ctx)
            .setTitle(getString(R.string.qwen_disable_title))
            .setMessage(getString(R.string.qwen_disable_message, sizeStr))
            .hideIcon()
            .addButton(getString(R.string.qwen_disable_keep), oc.accent(ctx)) {
                Prefs(ctx).qwenEnabled = false
            }
            .addButton(getString(R.string.qwen_disable_delete), oc.divider(ctx), oc.danger(ctx)) {
                Prefs(ctx).qwenEnabled = false
                com.playtranslate.translation.qwen.QwenModel.delete(ctx)
                // See translategemma_disable_delete branch above for why we
                // also unload the native model on file delete.
                viewLifecycleOwner.lifecycleScope.launch {
                    com.playtranslate.translation.translategemma.LlamaTranslator
                        .getInstance(ctx).unloadModel()
                }
                renderer?.refreshAllBackendStatuses()
            }
            .addCancelButton { renderer?.refreshQwenSwitch() }
            .setOnDismiss { renderer?.refreshQwenSwitch() }
            .showInActivity(activity)
    }

    // ── Companion ───────────────────────────────────────────────────────

    companion object {
        const val TAG = "SettingsBottomSheet"
        private const val ARG_HIDE_DISMISS = "hide_dismiss"

        // (TG and Qwen total-mem floors used to live here as TG_TOTAL_MEM_FLOOR_BYTES
        // and QWEN_TOTAL_MEM_FLOOR_BYTES, but they're now properties on the backend
        // class itself — see OnDeviceLlmBackend.totalMemFloorBytes — so the UI's
        // hardware-gate logic and the downloader's preflight read the same source.)

        fun newInstance(hideDismiss: Boolean = false) = SettingsBottomSheet().apply {
            if (hideDismiss) arguments = Bundle().apply { putBoolean(ARG_HIDE_DISMISS, true) }
        }
    }
}
