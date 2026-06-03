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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.playtranslate.Prefs
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.capturableDisplays
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.R
import com.playtranslate.applyAccentOverlay
import com.playtranslate.applyDialogEdgeToEdge
import com.playtranslate.applySystemBarAppearance
import com.playtranslate.fullScreenDialogTheme
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.playtranslate.overlayPermissionSettingsIntent
import com.playtranslate.themeColor
import com.playtranslate.language.SourceLanguageProfiles
import kotlinx.coroutines.launch
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.scale
import androidx.core.view.isGone

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
    var onSourceLangChanged: (() -> Unit)? = null
    var onScreenModeChanged: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null

    // ── Internal state ──────────────────────────────────────────────────
    private var renderer: SettingsRenderer? = null
    private var currentView: View? = null
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** Snapshot of LLM-backend configuration taken in [onPause]. Compared
     *  against the live config in [onResume] to detect changes made by
     *  [LlmBackendSettingsActivity] while the prefs listener was
     *  unregistered — those need to force-clear the translation cache so
     *  stale entries produced by the previous model / key / base URL
     *  aren't served by the same-id backend after the swap. */
    private var llmConfigSnapshotOnPause: String? = null

    private fun snapshotLlmConfig(prefs: Prefs): String = listOf(
        prefs.geminiApiKey, prefs.geminiModel,
        prefs.openaiApiKey, prefs.openaiModel,
        prefs.deepseekApiKey, prefs.deepseekModel,
    ).joinToString("|")

    /** The live MediaProjection session this sheet holds a teardown listener
     *  on while resumed (kept so onPause unregisters from the same one).
     *  MediaProjection "active" is held consent, not a pref the
     *  SharedPreferences listener observes — so the Turn On/Off buttons are
     *  refreshed from this teardown callback instead. */
    private var teardownController: com.playtranslate.capture.MediaProjectionController? = null
    private val onProjectionTeardown: () -> Unit = { renderer?.refreshOverlayIconState() }

    private val requestAnkiPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) renderer?.refreshAnkiConfigureCell()
    }

    /** Voice picker now returns the choice via setResult rather than
     *  writing the pref directly — Settings persists from its callback
     *  and refreshes the TTS row. The pref key is keyed by source lang;
     *  we snapshot the lang at launch time. */
    private var ttsVoicePickerLang: com.playtranslate.language.SourceLangId? = null
    private val ttsVoicePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val lang = ttsVoicePickerLang.also { ttsVoicePickerLang = null }
            ?: return@registerForActivityResult
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val picked = result.data?.getStringExtra(TtsVoiceActivity.EXTRA_PICKED_VOICE)
        Prefs(requireContext()).setTtsVoiceName(lang, picked)
        renderer?.refreshTtsSection()
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
            applyDialogEdgeToEdge(this, requireContext())
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        attachScrollImeInsetListener(view)
        currentView = view
        setupViews(view)
    }

    /** Bind the IME + nav-bar bottom-inset listener to the freshly-inflated
     *  [settingsScrollView]. Re-call after [reinflateContent] swaps the
     *  scroll view out — the listener is per-View, not per-window, so the
     *  replacement scroll view starts unattached and would otherwise leave
     *  etCaptureInterval (mid-scroll, ~75 blocks below the top) hidden
     *  behind the keyboard after an in-dialog theme toggle. */
    private fun attachScrollImeInsetListener(root: View) {
        val scroll = root.findViewById<View>(R.id.settingsScrollView) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = maxOf(ime.bottom, nav.bottom))
            WindowInsetsCompat.CONSUMED
        }
        // Platform doesn't replay the current WindowInsets to a listener
        // installed after the attach-time dispatch has already fired. If the
        // keyboard is up when reinflateContent runs (or even at first attach
        // in inline mode, where the parent is already laid out), the new
        // listener would otherwise sit silent until the next inset change.
        ViewCompat.requestApplyInsets(scroll)
    }

    override fun onDestroyView() {
        renderer?.destroyOverlayIconPreview()
        renderer = null
        currentView = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        // Observe MediaProjection teardown so the Turn On/Off buttons refresh
        // when capture is stopped from outside Settings (e.g. the floating-
        // icon menu's "Turn Off"). Released again in onPause.
        teardownController =
            com.playtranslate.CaptureService.instance?.mediaProjectionController?.also {
                it.addTeardownListener(onProjectionTeardown)
            }
        renderer?.startCaptureButtonShimmer()
        renderer?.refreshAnkiConfigureCell()
        renderer?.refreshOverlayIconState()
        // The toolbar hosts the Turn On/Off button on the MediaProjection
        // backend — re-check its visibility in case the accessibility grant
        // changed while we were away (same catch-up reason as the rows here).
        view?.let { refreshToolbarVisibility(it) }
        renderer?.refreshTtsSection()
        // Pick up backend toggle changes made while we were paused —
        // DeepLSettingsActivity / LlmBackendSettingsActivity flip the
        // *_enabled prefs while the prefs listener is unregistered, so
        // onResume is the catch-up point.
        renderer?.refreshDeeplBackendSwitch()
        renderer?.refreshGeminiBackendSwitch()
        renderer?.refreshOpenaiBackendSwitch()
        renderer?.refreshDeepseekBackendSwitch()
        renderer?.refreshGeminiModelValue()
        renderer?.refreshOpenaiModelValue()
        renderer?.refreshDeepseekModelValue()
        renderer?.refreshLingvaBackendSwitch()
        renderer?.refreshQwenMnnSwitch()
        renderer?.refreshGemmaE2bSwitch()
        renderer?.refreshHyMtSwitch()
        // LLM config changes (key / model / base URL) made while we were
        // paused don't necessarily flip *_enabled, but they DO change the
        // output any given input maps to — and reconcileBackendPreference
        // can't catch them (the preferred backend id is unchanged). Compare
        // the snapshot we stashed in onPause against the current config and
        // force-clear the translation cache when anything moved.
        context?.let { ctx ->
            val before = llmConfigSnapshotOnPause
            llmConfigSnapshotOnPause = null
            if (before != null && before != snapshotLlmConfig(Prefs(ctx))) {
                com.playtranslate.CaptureService.instance?.clearTranslationCache()
            }
        }
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
                "show_overlay_icon" -> renderer?.refreshOverlayIconState()
                Prefs.KEY_DEEPL_ENABLED -> {
                    renderer?.refreshDeeplBackendSwitch()
                    renderer?.refreshAllBackendStatuses()
                    com.playtranslate.CaptureService.instance?.reconcileBackendPreference()
                }
                Prefs.KEY_GEMINI_ENABLED -> {
                    renderer?.refreshGeminiBackendSwitch()
                    renderer?.refreshAllBackendStatuses()
                    com.playtranslate.CaptureService.instance?.reconcileBackendPreference()
                }
                Prefs.KEY_OPENAI_ENABLED -> {
                    renderer?.refreshOpenaiBackendSwitch()
                    renderer?.refreshAllBackendStatuses()
                    com.playtranslate.CaptureService.instance?.reconcileBackendPreference()
                }
                Prefs.KEY_DEEPSEEK_ENABLED -> {
                    renderer?.refreshDeepseekBackendSwitch()
                    renderer?.refreshAllBackendStatuses()
                    com.playtranslate.CaptureService.instance?.reconcileBackendPreference()
                }
                // LLM key / model changes don't flip the enabled toggle,
                // but they DO change the *output* the backend produces
                // for a given input — so cached entries from the previous
                // config would be served stale. reconcileBackendPreference
                // can't catch this (the preferred backend id is unchanged);
                // force-clear the whole cache instead. Refresh status too —
                // key presence drives the "API Key Required" vs "Today: …" line.
                Prefs.KEY_GEMINI_KEY, Prefs.KEY_OPENAI_KEY, Prefs.KEY_DEEPSEEK_KEY,
                Prefs.KEY_GEMINI_MODEL, Prefs.KEY_OPENAI_MODEL, Prefs.KEY_DEEPSEEK_MODEL -> {
                    renderer?.refreshAllBackendStatuses()
                    com.playtranslate.CaptureService.instance?.clearTranslationCache()
                    // The inline "Model" sub-cell under each LLM row also
                    // mirrors the model pref; refresh the matching one.
                    if (key == Prefs.KEY_GEMINI_MODEL) renderer?.refreshGeminiModelValue()
                    if (key == Prefs.KEY_OPENAI_MODEL) renderer?.refreshOpenaiModelValue()
                    if (key == Prefs.KEY_DEEPSEEK_MODEL) renderer?.refreshDeepseekModelValue()
                }
                Prefs.KEY_LINGVA_ENABLED -> {
                    renderer?.refreshLingvaBackendSwitch()
                    renderer?.refreshAllBackendStatuses()
                    com.playtranslate.CaptureService.instance?.reconcileBackendPreference()
                }
                Prefs.KEY_QWEN_MNN_ENABLED -> {
                    renderer?.refreshQwenMnnSwitch()
                    renderer?.refreshAllBackendStatuses()
                    com.playtranslate.CaptureService.instance?.reconcileBackendPreference()
                    maybeUnloadIdleEngines(ctx)
                }
                Prefs.KEY_QWEN35_MNN_2B_ENABLED -> {
                    renderer?.refreshQwen35Mnn2bSwitch()
                    renderer?.refreshAllBackendStatuses()
                    com.playtranslate.CaptureService.instance?.reconcileBackendPreference()
                    maybeUnloadIdleEngines(ctx)
                }
                Prefs.KEY_GEMMA_E2B_ENABLED -> {
                    renderer?.refreshGemmaE2bSwitch()
                    renderer?.refreshAllBackendStatuses()
                    com.playtranslate.CaptureService.instance?.reconcileBackendPreference()
                    maybeUnloadIdleEngines(ctx)
                }
                Prefs.KEY_HYMT_ENABLED -> {
                    renderer?.refreshHyMtSwitch()
                    renderer?.refreshAllBackendStatuses()
                    com.playtranslate.CaptureService.instance?.reconcileBackendPreference()
                    maybeUnloadIdleEngines(ctx)
                }
                Prefs.KEY_BERGAMOT_ENABLED -> {
                    renderer?.refreshBergamotSwitch()
                    renderer?.refreshAllBackendStatuses()
                    com.playtranslate.CaptureService.instance?.reconcileBackendPreference()
                }
            }
        }
        sp.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    /**
     * Drop the loaded MNN model when every on-device LLM backend toggle is
     * off, freeing the working set so the OS can reclaim that RAM. Since
     * `:mnn` serves every on-device LLM tier (Qwen 2.5 MNN, Qwen 3.5 2B,
     * Gemma E2B, Hunyuan-MT), we only unload when ALL of them
     * are disabled — otherwise we'd defeat the point of caching the
     * currently-active model mid-session.
     *
     * unloadModel is mutex-serialized inside the translator singleton so it
     * can't race an in-flight translation that started right before the
     * toggle changed.
     */
    private fun maybeUnloadIdleEngines(ctx: Context) {
        val prefs = Prefs(ctx)
        if (!prefs.qwenMnnEnabled && !prefs.gemmaE2bEnabled && !prefs.hyMtEnabled &&
            !prefs.qwen35Mnn2bEnabled) {
            viewLifecycleOwner.lifecycleScope.launch {
                com.playtranslate.translation.mnn.MnnTranslator
                    .getInstance(ctx).unloadModel()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        teardownController?.removeTeardownListener(onProjectionTeardown)
        teardownController = null
        renderer?.stopCaptureButtonShimmer()
        val ctx = context ?: return
        llmConfigSnapshotOnPause = snapshotLlmConfig(Prefs(ctx))
        val sp = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
        prefsListener?.let { sp.unregisterOnSharedPreferenceChangeListener(it) }
        prefsListener = null
    }

    // ── View setup ──────────────────────────────────────────────────────

    /** Show the toolbar in dialog mode (single-screen), and in inline mode
     *  (dual-screen) only when the accessibility service is off — the
     *  MediaProjection backend then needs the Turn On/Off control the toolbar
     *  hosts. With accessibility on, dual-screen capture is always running, so
     *  there is nothing to start. Re-checked on resume since the grant can
     *  change while the user is away in system settings.
     *
     *  Toggles the inner MaterialToolbar, not the AppBarLayout: a GONE
     *  AppBarLayout keeps its stale measured height, so the CoordinatorLayout
     *  leaves the scroll child offset by a phantom toolbar until the view is
     *  rebuilt. The AppBarLayout (wrap_content) collapses to 0 on its own once
     *  its only child is hidden. */
    private fun refreshToolbarVisibility(root: View) {
        val show = showsDialog ||
            !PlayTranslateAccessibilityService.isEnabled(requireContext())
        root.findViewById<View>(R.id.settingsToolbarInner).visibility =
            if (show) View.VISIBLE else View.GONE
    }

    private fun setupViews(view: View) {
        val hideDismiss = arguments?.getBoolean(ARG_HIDE_DISMISS, false) ?: false
        val isDialog = showsDialog
        val prefs = Prefs(requireContext())

        // Toolbar contents — mode-constant, configured once here. The title is
        // always the app name; the close button only makes sense in dialog
        // mode (inline mode is embedded in MainActivity, nothing to dismiss).
        view.findViewById<android.widget.TextView>(R.id.tvSettingsTitle)
            .text = getString(R.string.app_name)
        val closeBtn = view.findViewById<View>(R.id.btnCloseSettings)
        when {
            !isDialog -> closeBtn.isGone = true
            hideDismiss -> {
                closeBtn.isGone = true
                dialog?.setOnKeyListener { _, keyCode, event ->
                    if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                        event.action == android.view.KeyEvent.ACTION_UP) {
                        activity?.finish()
                        true
                    } else false
                }
            }
            else -> closeBtn.setOnClickListener { dismiss() }
        }
        refreshToolbarVisibility(view)

        // Scroll position restore after theme change vs. deep-link anchor.
        // Anchor wins when present — it's a fresh "land here" navigation, so
        // it should override a stale settingsScrollY left over from a prior
        // theme change (which would otherwise restore a position the user
        // never asked to return to in this flow).
        val settingsScrollView = view.findViewById<NestedScrollView>(R.id.settingsScrollView)
        val anchorName = arguments?.getString(ARG_ANCHOR)
        val savedScroll = prefs.settingsScrollY
        if (anchorName != null) {
            val anchor = runCatching { SettingsAnchor.valueOf(anchorName) }.getOrNull()
            // Clear the saved scroll position so a later theme change after
            // the anchor land doesn't pop the user back to a stale offset.
            if (savedScroll > 0) prefs.settingsScrollY = 0
            if (anchor != null) scrollToAnchor(anchor)
        } else if (savedScroll > 0) {
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
            isOnboarding = hideDismiss,
            callbacks = object : SettingsRenderer.Callbacks {
                override fun onClose() { this@SettingsBottomSheet.onClose?.invoke() ?: dismiss() }
                override fun openAppearanceSettings() {
                    startActivity(
                        android.content.Intent(requireContext(), AppearanceSettingsActivity::class.java)
                    )
                }
                override fun openHotkeysSettings() {
                    startActivity(
                        android.content.Intent(requireContext(), HotkeysSettingsActivity::class.java)
                    )
                }
                override fun openCaptureOverlaySettings() {
                    startActivity(
                        android.content.Intent(requireContext(), CaptureOverlaySettingsActivity::class.java)
                    )
                }
                override fun onSourceLangChanged() { this@SettingsBottomSheet.onSourceLangChanged?.invoke() }
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
                override fun openLlmBackendSettings(id: com.playtranslate.translation.BackendId) {
                    startActivity(
                        android.content.Intent(requireContext(), LlmBackendSettingsActivity::class.java)
                            .putExtra(LlmBackendSettingsActivity.EXTRA_BACKEND_ID, id)
                    )
                }
                override fun openLlmModelPicker(id: com.playtranslate.translation.BackendId) {
                    startActivity(LlmModelPickerActivity.newIntent(requireContext(), id))
                }
                override fun openTtsVoicePicker() {
                    val ctx = requireContext()
                    val lang = Prefs(ctx).sourceLangId
                    ttsVoicePickerLang = lang
                    ttsVoicePickerLauncher.launch(
                        TtsVoiceActivity.intent(ctx, lang, Prefs(ctx).ttsVoiceName(lang)),
                    )
                }
                override fun openTtsSetup() {
                    val act = activity ?: return
                    showTtsNoEngineDialog(TtsAlertTarget.InActivity(act)) { }
                }
                override fun startQwenMnnDownload() {
                    showQwenMnnDownloadDialog()
                }
                override fun enableInstalledQwenMnn() {
                    this@SettingsBottomSheet.enableInstalledQwenMnn()
                }
                override fun showQwenMnnDisableDialog() {
                    this@SettingsBottomSheet.showQwenMnnDisableDialog()
                }
                override fun startQwen35Mnn2bDownload() {
                    showOfflineLlmDownloadDialog(qwen35Mnn2bFlow)
                }
                override fun enableInstalledQwen35Mnn2b() {
                    enableInstalledOfflineLlm(qwen35Mnn2bFlow)
                }
                override fun showQwen35Mnn2bDisableDialog() {
                    showOfflineLlmDisableDialog(qwen35Mnn2bFlow)
                }
                override fun startGemmaE2bMnnDownload() {
                    showGemmaE2bMnnDownloadDialog()
                }
                override fun enableInstalledGemmaE2bMnn() {
                    this@SettingsBottomSheet.enableInstalledGemmaE2bMnn()
                }
                override fun showGemmaE2bMnnDisableDialog() {
                    this@SettingsBottomSheet.showGemmaE2bMnnDisableDialog()
                }
                override fun startHyMtDownload() {
                    showHyMtDownloadDialog()
                }
                override fun enableInstalledHyMt() {
                    this@SettingsBottomSheet.enableInstalledHyMt()
                }
                override fun showHyMtDisableDialog() {
                    this@SettingsBottomSheet.showHyMtDisableDialog()
                }
                override fun startBergamotDownload() {
                    this@SettingsBottomSheet.showBergamotDownloadDialog()
                }
                override fun enableInstalledBergamot() {
                    this@SettingsBottomSheet.enableInstalledBergamot()
                }
                override fun showBergamotDisableDialog() {
                    this@SettingsBottomSheet.showBergamotDisableDialog()
                }
                override fun onUpdateLanguagePacksTapped(
                    stalePacks: List<com.playtranslate.language.StalePack>
                ) {
                    this@SettingsBottomSheet.startPackUpgrade(stalePacks)
                }
                override fun showAccessibilityRequiredAlert(
                    requirement: AccessibilityRequirement
                ) {
                    this@SettingsBottomSheet.showAccessibilityRequiredAlert(requirement)
                }
                override fun requestMediaProjectionControls() {
                    this@SettingsBottomSheet.requestMediaProjectionControls()
                }
                override fun openAnkiSettings() {
                    startActivity(
                        android.content.Intent(requireContext(), AnkiSettingsActivity::class.java)
                    )
                }
            }
        )
        renderer = r

        // Bind all rows
        r.bind()
    }

    // ── Re-inflate (used for theme changes in dialog mode) ──────────────

    /**
     * Swaps the bottom-sheet's entire content view for a freshly-inflated
     * [R.layout.dialog_settings]. Used for theme changes (MainActivity)
     * and display hot-plug (this class's display listener).
     *
     * Skipped when:
     *  - The fragment isn't attached or currentView has been detached.
     *  - The parent is a FragmentContainerView. AndroidX's FCV refuses
     *    raw View children — only Fragment-managed views. Showing any
     *    child DialogFragment in this session (deck / card-type /
     *    field-mapping / source picker) replaces the sheet's content
     *    parent with an FCV, and that container persists for the rest
     *    of the session — even after the dialog dismisses. So a
     *    simple "is a child dialog currently shown" check isn't
     *    enough; we have to look at what the parent actually is.
     *
     * The fresh layout is picked up next time Settings opens (its
     * onViewCreated re-runs against a clean view tree).
     */
    fun reinflateContent() {
        if (!isAdded) return
        val old = currentView ?: return
        val parent = old.parent as? ViewGroup ?: return
        if (parent is androidx.fragment.app.FragmentContainerView) {
            android.util.Log.d(TAG,
                "reinflateContent skipped — parent is FragmentContainerView " +
                    "(a child DialogFragment was shown earlier this session)")
            return
        }
        // Recycle the outgoing renderer's preview bitmap — reinflate builds a
        // fresh renderer + preview without going through onDestroyView.
        renderer?.destroyOverlayIconPreview()
        val index = parent.indexOfChild(old)
        parent.removeView(old)
        val newView = layoutInflater
            .inflate(R.layout.dialog_settings, parent, false)
        parent.addView(newView, index)
        currentView = newView
        setupViews(newView)
        // The previous scroll view was removed; the freshly-inflated one
        // starts without an inset listener. Re-bind so etCaptureInterval
        // still gets keyboard + nav-bar bottom padding after a theme toggle.
        attachScrollImeInsetListener(newView)
        // After an in-place theme switch, refresh the system-bar icon tint
        // to match the new palette. Under edge-to-edge the bars are
        // transparent, so the only thing that flips is the appearance flag —
        // no statusBarColor/navigationBarColor writes (deprecated, no-op on
        // API 35+). windowBackground isn't touched because the content view
        // covers it anyway.
        dialog?.window?.let { applySystemBarAppearance(it, requireContext()) }
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

    // ── On-device LLM download / enable / disable flows ────────────────

    /**
     * Returns true and short-circuits if a download for the same backend is
     * already in flight. Two on-device backends use this guard (Qwen-MNN
     * and Gemma E2B) — without it, a frustrated user tapping the row
     * repeatedly (because the OverlayProgress dialog isn't showing fast
     * enough, or got dismissed by the back key, etc.) launches one new
     * download coroutine per tap, each opening the same `<name>.partial`
     * with `append=false` and truncating the others' progress to zero.
     * Worst case observed on the MNN Qwen flow: six concurrent downloads
     * in 4 s, app OOM-killed mid-stream, no successful install. See the
     * in-place comments at each call site.
     */
    private fun isDownloadInFlight(job: kotlinx.coroutines.Job?, label: String): Boolean {
        if (job?.isActive == true) {
            android.util.Log.d(TAG, "$label download already in flight; ignoring tap")
            return true
        }
        return false
    }

    /** Kick off [com.playtranslate.language.PackUpgradeOrchestrator] for the
     *  user's deferred-upgrade list (the "Update language packs" cell tap).
     *
     *  Uses **the Activity's lifecycleScope**, NOT this Fragment's view scope:
     *  the OverlayProgress dialog the orchestrator shows is attached to the
     *  Activity's decorView, so it survives a Settings dismiss. Tying the
     *  coroutine to the Fragment view would silently cancel the in-flight
     *  download while the user stares at a frozen progress bar.
     *
     *  On completion, refreshes just the Language section so the cell hides
     *  (since `staleInstalledPacks()` is now empty). Falls back gracefully
     *  if the renderer/activity is gone by the time the orchestrator returns
     *  (Settings dismissed mid-flight). */
    private fun startPackUpgrade(
        stalePacks: List<com.playtranslate.language.StalePack>
    ) {
        val activity = activity as? androidx.appcompat.app.AppCompatActivity ?: return
        com.playtranslate.language.PackUpgradeOrchestrator(activity, activity.lifecycleScope)
            .upgradeAll(stalePacks) {
                if (isAdded && view != null) {
                    runCatching { renderer?.refreshLanguageSection() }
                }
            }
    }

    /**
     * Run the pre-toggle availMem gate before [onProceed]. If
     * `MemoryInfo.availMem` is below [backend].`availMemFloorBytes`, show an
     * OverlayAlert (matching the disable-dialog visual style) with:
     *   - [Check memory again] — re-reads availMem and re-evaluates; if now
     *     OK, dismisses + invokes [onProceed]; otherwise the alert reappears.
     *   - [Delete model] (danger background) — only present when [allowDelete]
     *     is true; invokes [onDelete] then [onCancel] so the renderer can
     *     revert the optimistic switch flip.
     *   - [Cancel] — invokes [onCancel].
     *
     * If availMem is above the floor, [onProceed] is invoked synchronously.
     *
     * The gate value comes from [com.playtranslate.translation.llm.OnDeviceLlmBackend.availMemFloorBytes]
     * — the same threshold [com.playtranslate.translation.mnn.MnnTranslator.preflightMemory]
     * enforces at translate time, so the install-time gate and the run-time
     * gate agree on "low memory."
     */
    private fun checkAvailMemAndProceed(
        backend: com.playtranslate.translation.llm.OnDeviceLlmBackend,
        modelDisplayName: String,
        onProceed: () -> Unit,
        allowDelete: Boolean = false,
        onDelete: (() -> Unit)? = null,
        onCancel: () -> Unit = {},
    ) {
        val ctx = context ?: return
        val activity = activity ?: return
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        if (mi.availMem >= backend.availMemFloorBytes) {
            onProceed()
            return
        }

        val needStr = formatGb(backend.availMemFloorBytes)
        val freeStr = formatGb(mi.availMem)
        val builder = OverlayAlert.Builder(ctx)
            .setTitle(getString(R.string.llm_low_memory_title))
            .setMessage(getString(
                R.string.llm_low_memory_message,
                modelDisplayName, needStr, freeStr,
            ))
            .hideIcon()
            .addButton(getString(R.string.llm_low_memory_recheck), ctx.themeColor(R.attr.ptAccent)) {
                // Recurse: re-reads availMem and either proceeds (if memory
                // freed in the meantime) or shows the alert again. The prior
                // OverlayAlert has already dismissed by the time onClick fires
                // (see OverlayAlert.kt:232-235), so there's no overlap.
                checkAvailMemAndProceed(
                    backend, modelDisplayName, onProceed,
                    allowDelete, onDelete, onCancel,
                )
            }
        if (allowDelete && onDelete != null) {
            // Danger background (user explicitly chose "delete it instead").
            // White text for contrast against the saturated red — neither
            // pt_*_text_on_accent nor card maps cleanly here.
            builder.addButton(
                getString(R.string.llm_low_memory_delete),
                ctx.themeColor(R.attr.ptDanger),
                android.graphics.Color.WHITE,
            ) {
                onDelete()
                onCancel()
            }
        }
        builder.addCancelButton(getString(R.string.llm_low_memory_cancel)) { onCancel() }
        builder.show()
    }

    private fun formatGb(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        return if (gb == gb.toLong().toDouble()) "${gb.toLong()} GB"
               else "%.1f GB".format(gb)
    }


    /**
     * Smooth-scrolls the named section header into view. Safe to call on an
     * already-shown sheet (e.g. the dual-screen default sheet that's already
     * in the fragment manager when the cold-launch alert fires) — uses the
     * same `post` + height-poll pattern as the theme-change scroll restore
     * so the target's `top` coordinate is meaningful when we call scrollTo.
     *
     * Called from two paths:
     *  - onViewCreated, when a freshly-created sheet was constructed with
     *    `newInstance(anchor = …)` — the anchor is read from arguments.
     *  - [com.playtranslate.MainActivity.openSettingsAtAnchor], when the
     *    sheet is already shown and we want to navigate within it instead
     *    of duplicating the fragment.
     */
    fun scrollToAnchor(anchor: SettingsAnchor) {
        val root = view ?: return
        val sv = root.findViewById<NestedScrollView>(R.id.settingsScrollView) ?: return
        val target = root.findViewById<android.view.View>(anchor.headerId) ?: return
        fun tryScroll() {
            if (sv.height > 0 && target.height > 0) {
                // Subtract a small top margin so the header doesn't sit
                // flush with the scroll view's top edge.
                val dp = root.resources.displayMetrics.density
                val topMargin = (16 * dp).toInt()
                sv.smoothScrollTo(0, (target.top - topMargin).coerceAtLeast(0))
            } else {
                sv.postDelayed(::tryScroll, 16)
            }
        }
        sv.post { tryScroll() }
    }

    // ── Qwen (MNN) flow ──────────────────────────────────────────────────

    private var qwenMnnDownloadJob: kotlinx.coroutines.Job? = null

    /** Re-enable an already-extracted MNN-Qwen model. Mirrors
     *  [enableInstalledQwen] but routes through MnnTranslator for the unload-
     *  on-delete branch (so the right engine's native state is freed). */
    private fun enableInstalledQwenMnn() {
        val ctx = context ?: return
        val backend = com.playtranslate.translation.TranslationBackendRegistry
            .byId("qwen_mnn") as? com.playtranslate.translation.llm.OnDeviceLlmBackend
            ?: return
        checkAvailMemAndProceed(
            backend = backend,
            modelDisplayName = getString(R.string.qwen_mnn_display_name),
            onProceed = { Prefs(ctx).qwenMnnEnabled = true },
            allowDelete = true,
            onDelete = {
                com.playtranslate.translation.qwen.QwenMnnModel.delete(ctx)
                viewLifecycleOwner.lifecycleScope.launch {
                    com.playtranslate.translation.mnn.MnnTranslator
                        .getInstance(ctx).unloadModel()
                }
                renderer?.refreshAllBackendStatuses()
            },
            onCancel = { renderer?.refreshQwenMnnSwitch() },
        )
    }

    /** Show the modal download dialog for the MNN-Qwen zip. Mirrors
     *  [showQwenDownloadDialog]; the downloader picks ZipExtract automatically
     *  via [com.playtranslate.translation.qwen.QwenMnnModel.isDirectoryMode]. */
    private fun showQwenMnnDownloadDialog() {
        if (isDownloadInFlight(qwenMnnDownloadJob, "Qwen MNN")) return
        val ctx = context ?: return
        val backend = com.playtranslate.translation.TranslationBackendRegistry
            .byId("qwen_mnn") as? com.playtranslate.translation.llm.OnDeviceLlmBackend
            ?: return
        val downloader = com.playtranslate.translation.llm.OnDeviceLlmDownloader(
            context = ctx,
            modelHelper = com.playtranslate.translation.qwen.QwenMnnModel,
            totalMemFloorBytes = backend.totalMemFloorBytes,
        )
        checkAvailMemAndProceed(
            backend = backend,
            modelDisplayName = getString(R.string.qwen_mnn_display_name),
            onProceed = { showQwenMnnDownloadDialogPostGate(ctx, downloader) },
        )
    }

    private fun showQwenMnnDownloadDialogPostGate(
        ctx: Context,
        downloader: com.playtranslate.translation.llm.OnDeviceLlmDownloader,
    ) {
        if (downloader.isCurrentNetworkMetered()) {
            val sizeStr = com.playtranslate.translation.qwen.QwenMnnModel.humanSize(ctx)
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.qwen_mnn_metered_warning_title)
                .setMessage(getString(R.string.qwen_mnn_metered_warning_message, sizeStr))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    runQwenMnnDownload(ctx, downloader)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> }
                .show()
            return
        }
        runQwenMnnDownload(ctx, downloader)
    }

    private fun runQwenMnnDownload(
        ctx: Context,
        downloader: com.playtranslate.translation.llm.OnDeviceLlmDownloader,
    ) {
        val activity = activity ?: return
        val sizeStr = com.playtranslate.translation.qwen.QwenMnnModel.humanSize(ctx)

        val progressDialog = OverlayProgress.Builder(ctx)
            .setTitle(getString(R.string.qwen_mnn_display_name))
            .setMessage(getString(R.string.qwen_mnn_status_downloading, "0 B", sizeStr))
            .setProgress(0)
            .setOnDismiss { reason ->
                qwenMnnDownloadJob?.cancel()
                // USER explicitly bailed → wipe the .partial so a new
                // attempt starts fresh. LIFECYCLE_PAUSE keeps the .partial
                // so the next attempt resumes instead of restarting a
                // multi-GB download from zero.
                if (reason == com.playtranslate.ui.DismissReason.USER) {
                    downloader.deletePartial()
                }
                renderer?.refreshAllBackendStatuses()
            }
            .show()

        renderer?.setBackendDownloading("qwen_mnn", true)
        qwenMnnDownloadJob = viewLifecycleOwner.lifecycleScope.launch {
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
                                progressDialog.setMessage(getString(
                                    R.string.qwen_mnn_status_downloading,
                                    recv, total,
                                ))
                                if (progress.total > 0) {
                                    progressDialog.setProgress(
                                        ((progress.received * 100) / progress.total).toInt()
                                    )
                                }
                            }
                            is com.playtranslate.translation.llm
                                .OnDeviceLlmDownloader.Progress.Verifying -> {
                                progressDialog.setMessage(getString(R.string.qwen_mnn_status_verifying))
                                progressDialog.setProgress(100)
                            }
                            is com.playtranslate.translation.llm
                                .OnDeviceLlmDownloader.Progress.Extracting -> {
                                // The directory-mode commit path lives here.
                                // The MNN Qwen zip extracts to ~30 separate
                                // files (.mnn / .mnn.weight / tokenizer.txt /
                                // config.json / ...) over a few seconds on
                                // Thor; indeterminate spinner is fine.
                                progressDialog.setMessage(getString(R.string.model_download_extracting))
                                progressDialog.setProgress(100)
                            }
                        }
                    }
                }
                if (!isAdded) return@launch
                requireActivity().runOnUiThread {
                    progressDialog.dismiss()
                    when (outcome) {
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Success -> {
                            Prefs(ctx).qwenMnnEnabled = true
                            renderer?.refreshAllBackendStatuses()
                        }
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Refused -> {
                            android.widget.Toast.makeText(
                                ctx,
                                getString(R.string.qwen_mnn_download_failed, outcome.reason),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                            renderer?.refreshAllBackendStatuses()
                        }
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Failed -> {
                            android.widget.Toast.makeText(
                                ctx,
                                getString(R.string.qwen_mnn_download_failed, outcome.reason),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                            renderer?.refreshAllBackendStatuses()
                        }
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Cancelled -> {
                            // User-cancelled — partial may remain for resume,
                            // unless the Cancel button's onCancel deleted it.
                            renderer?.refreshAllBackendStatuses()
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Rethrow so the coroutine machinery records the cancellation
                // and the parent job tears down cleanly. Swallowing it leaves
                // the cancellation state inconsistent; mirroring TG/Qwen.
                throw e
            } catch (e: Exception) {
                if (isAdded) {
                    requireActivity().runOnUiThread {
                        progressDialog.dismiss()
                        android.widget.Toast.makeText(
                            ctx,
                            getString(R.string.qwen_mnn_download_failed,
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
                // stuck full-screen until process restart. dismiss() is
                // idempotent so this is safe even after the success/outcome
                // branches above already dismissed. Codex review
                // (2026-05-22, [P2]).
                progressDialog.dismiss()
                renderer?.setBackendDownloading("qwen_mnn", false)
                qwenMnnDownloadJob = null
            }
        }
    }

    /** Show the 3-button disable dialog for the Qwen-MNN tier. Delete branch
     *  deletes via [QwenMnnModel.delete] and unloads the loaded MNN model. */
    private fun showQwenMnnDisableDialog() {
        val ctx = context ?: return
        val sizeStr = com.playtranslate.translation.qwen.QwenMnnModel.humanSize(ctx)
        OverlayAlert.Builder(ctx)
            .setTitle(getString(R.string.qwen_mnn_disable_title))
            .setMessage(getString(R.string.qwen_mnn_disable_message, sizeStr))
            .hideIcon()
            .addButton(getString(R.string.qwen_mnn_disable_keep), ctx.themeColor(R.attr.ptAccent)) {
                Prefs(ctx).qwenMnnEnabled = false
            }
            .addButton(getString(R.string.qwen_mnn_disable_delete), ctx.themeColor(R.attr.ptDivider), ctx.themeColor(R.attr.ptDanger)) {
                Prefs(ctx).qwenMnnEnabled = false
                com.playtranslate.translation.qwen.QwenMnnModel.delete(ctx)
                viewLifecycleOwner.lifecycleScope.launch {
                    com.playtranslate.translation.mnn.MnnTranslator
                        .getInstance(ctx).unloadModel()
                }
                renderer?.refreshAllBackendStatuses()
            }
            .addCancelButton { renderer?.refreshQwenMnnSwitch() }
            .show()
    }

    // ── Generic offline-LLM flow (Qwen 3.5 2B / 4B) ──────────────────────
    // Faithful parameterization of the Qwen-MNN flow above, keyed by model +
    // strings, so additional MNN tiers don't each duplicate ~225 lines. The
    // older qwen/gemma/hymt flows predate this and are left as-is.

    private class OfflineLlmFlow(
        val backendId: String,
        val model: com.playtranslate.translation.llm.ModelHelper,
        val setEnabled: (Context, Boolean) -> Unit,
        val refreshSwitch: () -> Unit,
        @androidx.annotation.StringRes val displayName: Int,
        @androidx.annotation.StringRes val statusDownloading: Int,
        @androidx.annotation.StringRes val statusVerifying: Int,
        @androidx.annotation.StringRes val downloadFailed: Int,
        @androidx.annotation.StringRes val meteredTitle: Int,
        @androidx.annotation.StringRes val meteredMessage: Int,
        @androidx.annotation.StringRes val disableTitle: Int,
        @androidx.annotation.StringRes val disableMessage: Int,
        @androidx.annotation.StringRes val disableKeep: Int,
        @androidx.annotation.StringRes val disableDelete: Int,
    )

    private val offlineLlmDownloadJobs = mutableMapOf<String, kotlinx.coroutines.Job?>()

    private val qwen35Mnn2bFlow by lazy {
        OfflineLlmFlow(
            backendId = "qwen35_mnn_2b",
            model = com.playtranslate.translation.qwen.Qwen35Mnn2bModel,
            setEnabled = { c, v -> Prefs(c).qwen35Mnn2bEnabled = v },
            refreshSwitch = { renderer?.refreshQwen35Mnn2bSwitch() },
            displayName = R.string.qwen35_2b_mnn_display_name,
            statusDownloading = R.string.qwen35_2b_mnn_status_downloading,
            statusVerifying = R.string.qwen35_2b_mnn_status_verifying,
            downloadFailed = R.string.qwen35_2b_mnn_download_failed,
            meteredTitle = R.string.qwen35_2b_mnn_metered_warning_title,
            meteredMessage = R.string.qwen35_2b_mnn_metered_warning_message,
            disableTitle = R.string.qwen35_2b_mnn_disable_title,
            disableMessage = R.string.qwen35_2b_mnn_disable_message,
            disableKeep = R.string.qwen35_2b_mnn_disable_keep,
            disableDelete = R.string.qwen35_2b_mnn_disable_delete,
        )
    }

    private fun enableInstalledOfflineLlm(flow: OfflineLlmFlow) {
        val ctx = context ?: return
        val backend = com.playtranslate.translation.TranslationBackendRegistry
            .byId(flow.backendId) as? com.playtranslate.translation.llm.OnDeviceLlmBackend
            ?: return
        checkAvailMemAndProceed(
            backend = backend,
            modelDisplayName = getString(flow.displayName),
            onProceed = { flow.setEnabled(ctx, true) },
            allowDelete = true,
            onDelete = {
                flow.model.delete(ctx)
                viewLifecycleOwner.lifecycleScope.launch {
                    com.playtranslate.translation.mnn.MnnTranslator
                        .getInstance(ctx).unloadModel()
                }
                renderer?.refreshAllBackendStatuses()
            },
            onCancel = { flow.refreshSwitch() },
        )
    }

    private fun showOfflineLlmDownloadDialog(flow: OfflineLlmFlow) {
        if (isDownloadInFlight(offlineLlmDownloadJobs[flow.backendId], flow.backendId)) return
        val ctx = context ?: return
        val backend = com.playtranslate.translation.TranslationBackendRegistry
            .byId(flow.backendId) as? com.playtranslate.translation.llm.OnDeviceLlmBackend
            ?: return
        val downloader = com.playtranslate.translation.llm.OnDeviceLlmDownloader(
            context = ctx,
            modelHelper = flow.model,
            totalMemFloorBytes = backend.totalMemFloorBytes,
        )
        checkAvailMemAndProceed(
            backend = backend,
            modelDisplayName = getString(flow.displayName),
            onProceed = { showOfflineLlmDownloadDialogPostGate(ctx, downloader, flow) },
        )
    }

    private fun showOfflineLlmDownloadDialogPostGate(
        ctx: Context,
        downloader: com.playtranslate.translation.llm.OnDeviceLlmDownloader,
        flow: OfflineLlmFlow,
    ) {
        if (downloader.isCurrentNetworkMetered()) {
            val sizeStr = flow.model.humanSize(ctx)
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(flow.meteredTitle)
                .setMessage(getString(flow.meteredMessage, sizeStr))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    runOfflineLlmDownload(ctx, downloader, flow)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> }
                .show()
            return
        }
        runOfflineLlmDownload(ctx, downloader, flow)
    }

    private fun runOfflineLlmDownload(
        ctx: Context,
        downloader: com.playtranslate.translation.llm.OnDeviceLlmDownloader,
        flow: OfflineLlmFlow,
    ) {
        val sizeStr = flow.model.humanSize(ctx)
        val progressDialog = OverlayProgress.Builder(ctx)
            .setTitle(getString(flow.displayName))
            .setMessage(getString(flow.statusDownloading, "0 B", sizeStr))
            .setProgress(0)
            .setOnDismiss { reason ->
                offlineLlmDownloadJobs[flow.backendId]?.cancel()
                if (reason == com.playtranslate.ui.DismissReason.USER) {
                    downloader.deletePartial()
                }
                renderer?.refreshAllBackendStatuses()
            }
            .show()

        renderer?.setBackendDownloading(flow.backendId, true)
        offlineLlmDownloadJobs[flow.backendId] = viewLifecycleOwner.lifecycleScope.launch {
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
                                progressDialog.setMessage(getString(
                                    flow.statusDownloading, recv, total,
                                ))
                                if (progress.total > 0) {
                                    progressDialog.setProgress(
                                        ((progress.received * 100) / progress.total).toInt()
                                    )
                                }
                            }
                            is com.playtranslate.translation.llm
                                .OnDeviceLlmDownloader.Progress.Verifying -> {
                                progressDialog.setMessage(getString(flow.statusVerifying))
                                progressDialog.setProgress(100)
                            }
                            is com.playtranslate.translation.llm
                                .OnDeviceLlmDownloader.Progress.Extracting -> {
                                progressDialog.setMessage(getString(R.string.model_download_extracting))
                                progressDialog.setProgress(100)
                            }
                        }
                    }
                }
                if (!isAdded) return@launch
                requireActivity().runOnUiThread {
                    progressDialog.dismiss()
                    when (outcome) {
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Success -> {
                            flow.setEnabled(ctx, true)
                            renderer?.refreshAllBackendStatuses()
                        }
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Refused -> {
                            android.widget.Toast.makeText(
                                ctx,
                                getString(flow.downloadFailed, outcome.reason),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                            renderer?.refreshAllBackendStatuses()
                        }
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Failed -> {
                            android.widget.Toast.makeText(
                                ctx,
                                getString(flow.downloadFailed, outcome.reason),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                            renderer?.refreshAllBackendStatuses()
                        }
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Cancelled -> {
                            renderer?.refreshAllBackendStatuses()
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAdded) {
                    requireActivity().runOnUiThread {
                        progressDialog.dismiss()
                        android.widget.Toast.makeText(
                            ctx,
                            getString(flow.downloadFailed,
                                e.message ?: e.javaClass.simpleName),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                        renderer?.refreshAllBackendStatuses()
                    }
                }
            } finally {
                progressDialog.dismiss()
                renderer?.setBackendDownloading(flow.backendId, false)
                offlineLlmDownloadJobs[flow.backendId] = null
            }
        }
    }

    private fun showOfflineLlmDisableDialog(flow: OfflineLlmFlow) {
        val ctx = context ?: return
        val sizeStr = flow.model.humanSize(ctx)
        OverlayAlert.Builder(ctx)
            .setTitle(getString(flow.disableTitle))
            .setMessage(getString(flow.disableMessage, sizeStr))
            .hideIcon()
            .addButton(getString(flow.disableKeep), ctx.themeColor(R.attr.ptAccent)) {
                flow.setEnabled(ctx, false)
            }
            .addButton(getString(flow.disableDelete), ctx.themeColor(R.attr.ptDivider), ctx.themeColor(R.attr.ptDanger)) {
                flow.setEnabled(ctx, false)
                flow.model.delete(ctx)
                viewLifecycleOwner.lifecycleScope.launch {
                    com.playtranslate.translation.mnn.MnnTranslator
                        .getInstance(ctx).unloadModel()
                }
                renderer?.refreshAllBackendStatuses()
            }
            .addCancelButton { flow.refreshSwitch() }
            .show()
    }

    // ── Bergamot (Firefox Translations, fast offline NMT) flow ──────────
    // Lighter than the MNN tiers: per-pair, ~50 MB Mozilla models, no RAM
    // gate. The download is a plain loop over the pair's 1–2 required
    // directions (single hop, or two for an English pivot).

    private var bergamotDownloadJob: kotlinx.coroutines.Job? = null
    private val bergamotMemFloorBytes = 700_000_000L

    /** Re-enable Bergamot when the current pair is already downloaded. */
    private fun enableInstalledBergamot() {
        val ctx = context ?: return
        Prefs(ctx).bergamotEnabled = true   // SP listener refreshes the row
    }

    /** Download the current pair's required directions, then enable. */
    private fun showBergamotDownloadDialog() {
        if (isDownloadInFlight(bergamotDownloadJob, "Bergamot")) return
        val ctx = context ?: return
        val manager = com.playtranslate.translation.bergamot.BergamotModelManager(ctx)
        // Resolve the source through translationCode exactly like setup
        // (LanguageSetupActivity) and the runtime (CaptureService) do, so a
        // Traditional-Chinese (zh-Hant) source maps to the shared "zh" Bergamot
        // model instead of an unsupported zh_hant. Target is already canonical.
        val source = SourceLanguageProfiles[Prefs(ctx).sourceLangId].translationCode
        val target = Prefs(ctx).targetLang
        val dirs = manager.requiredDirections(source, target)
        if (dirs == null) { renderer?.refreshBergamotSwitch(); return }

        val progressDialog = OverlayProgress.Builder(ctx)
            .setTitle(getString(R.string.bergamot_row_title))
            .setMessage(getString(R.string.bergamot_status_downloading, "0 B", "…"))
            .setProgress(0)
            .setOnDismiss {
                bergamotDownloadJob?.cancel()
                renderer?.refreshAllBackendStatuses()
            }
            .show()

        renderer?.setBackendDownloading("bergamot", true)
        bergamotDownloadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                var outcome: com.playtranslate.translation.llm
                    .OnDeviceLlmDownloader.Outcome =
                    com.playtranslate.translation.llm.OnDeviceLlmDownloader.Outcome.Success
                for (dir in dirs) {
                    val helper = com.playtranslate.translation.bergamot.BergamotModel(dir)
                    if (helper.isInstalled(ctx)) continue
                    val downloader = com.playtranslate.translation.llm.OnDeviceLlmDownloader(
                        context = ctx,
                        modelHelper = helper,
                        totalMemFloorBytes = bergamotMemFloorBytes,
                    )
                    outcome = downloader.run { progress ->
                        if (progress is com.playtranslate.translation.llm
                                .OnDeviceLlmDownloader.Progress.Downloading) {
                            val recv = com.playtranslate.translation.llm.humanSize(progress.received)
                            val total = com.playtranslate.translation.llm.humanSize(progress.total)
                            requireActivity().runOnUiThread {
                                progressDialog.setMessage(getString(
                                    R.string.bergamot_status_downloading, recv, total))
                                if (progress.total > 0) {
                                    progressDialog.setProgress(
                                        ((progress.received * 100) / progress.total).toInt())
                                }
                            }
                        }
                    }
                    if (outcome !is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Success) break
                }
                if (!isAdded) return@launch
                requireActivity().runOnUiThread {
                    progressDialog.dismiss()
                    when (outcome) {
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Success ->
                            if (manager.isInstalled(source, target)) {
                                Prefs(ctx).bergamotEnabled = true
                            }
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Cancelled -> { /* user bailed */ }
                        else -> android.widget.Toast.makeText(
                            ctx, R.string.bergamot_download_failed,
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                    renderer?.refreshAllBackendStatuses()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAdded) requireActivity().runOnUiThread {
                    progressDialog.dismiss()
                    android.widget.Toast.makeText(
                        ctx, R.string.bergamot_download_failed,
                        android.widget.Toast.LENGTH_LONG).show()
                    renderer?.refreshAllBackendStatuses()
                }
            } finally {
                progressDialog.dismiss()
                renderer?.setBackendDownloading("bergamot", false)
                bergamotDownloadJob = null
            }
        }
    }

    /** 3-button disable dialog: keep the model + just turn off, or delete the
     *  current pair's models. Mirrors [showQwenMnnDisableDialog]. */
    private fun showBergamotDisableDialog() {
        val ctx = context ?: return
        OverlayAlert.Builder(ctx)
            .setTitle(getString(R.string.bergamot_disable_title))
            .setMessage(getString(R.string.bergamot_disable_message))
            .hideIcon()
            .addButton(getString(R.string.bergamot_disable_keep), ctx.themeColor(R.attr.ptAccent)) {
                Prefs(ctx).bergamotEnabled = false   // SP listener refreshes
            }
            .addButton(
                getString(R.string.bergamot_disable_delete),
                ctx.themeColor(R.attr.ptDivider),
                ctx.themeColor(R.attr.ptDanger),
            ) {
                Prefs(ctx).bergamotEnabled = false
                val manager = com.playtranslate.translation.bergamot.BergamotModelManager(ctx)
                val dirs = manager.requiredDirections(
                    SourceLanguageProfiles[Prefs(ctx).sourceLangId].translationCode,
                    Prefs(ctx).targetLang,
                )
                viewLifecycleOwner.lifecycleScope.launch {
                    dirs?.forEach { dir ->
                        com.playtranslate.translation.bergamot.BergamotModel(dir).delete(ctx)
                        com.playtranslate.translation.bergamot.BergamotTranslator
                            .getInstance(ctx).evictDirection(dir)
                    }
                    renderer?.refreshAllBackendStatuses()
                }
            }
            .addCancelButton { renderer?.refreshBergamotSwitch() }
            .show()
    }

    // ── Gemma E2B (MNN, manual-lookup tier) flow ────────────────────────

    private var gemmaE2bDownloadJob: kotlinx.coroutines.Job? = null

    /** Re-enable an already-extracted Gemma E2B model. Mirrors
     *  [enableInstalledQwenMnn] but targets [GemmaE2BMnnModel] +
     *  `prefs.gemmaE2bEnabled`. */
    private fun enableInstalledGemmaE2bMnn() {
        val ctx = context ?: return
        val backend = com.playtranslate.translation.TranslationBackendRegistry
            .byId("gemma_e2b_mnn") as? com.playtranslate.translation.llm.OnDeviceLlmBackend
            ?: return
        checkAvailMemAndProceed(
            backend = backend,
            modelDisplayName = getString(R.string.gemma_e2b_mnn_display_name),
            onProceed = { Prefs(ctx).gemmaE2bEnabled = true },
            allowDelete = true,
            onDelete = {
                com.playtranslate.translation.gemma.GemmaE2BMnnModel.delete(ctx)
                viewLifecycleOwner.lifecycleScope.launch {
                    com.playtranslate.translation.mnn.MnnTranslator
                        .getInstance(ctx).unloadModel()
                }
                renderer?.refreshAllBackendStatuses()
            },
            onCancel = { renderer?.refreshGemmaE2bSwitch() },
        )
    }

    /** Show the modal download dialog for the Gemma E2B zip. Mirrors
     *  [showQwenMnnDownloadDialog]; same ZipExtract commit strategy. */
    private fun showGemmaE2bMnnDownloadDialog() {
        if (isDownloadInFlight(gemmaE2bDownloadJob, "Gemma E2B")) return
        val ctx = context ?: return
        val backend = com.playtranslate.translation.TranslationBackendRegistry
            .byId("gemma_e2b_mnn") as? com.playtranslate.translation.llm.OnDeviceLlmBackend
            ?: return
        val downloader = com.playtranslate.translation.llm.OnDeviceLlmDownloader(
            context = ctx,
            modelHelper = com.playtranslate.translation.gemma.GemmaE2BMnnModel,
            totalMemFloorBytes = backend.totalMemFloorBytes,
        )
        checkAvailMemAndProceed(
            backend = backend,
            modelDisplayName = getString(R.string.gemma_e2b_mnn_display_name),
            onProceed = { showGemmaE2bMnnDownloadDialogPostGate(ctx, downloader) },
        )
    }

    private fun showGemmaE2bMnnDownloadDialogPostGate(
        ctx: Context,
        downloader: com.playtranslate.translation.llm.OnDeviceLlmDownloader,
    ) {
        if (downloader.isCurrentNetworkMetered()) {
            val sizeStr = com.playtranslate.translation.gemma.GemmaE2BMnnModel.humanSize(ctx)
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.gemma_e2b_mnn_metered_warning_title)
                .setMessage(getString(R.string.gemma_e2b_mnn_metered_warning_message, sizeStr))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    runGemmaE2bDownload(ctx, downloader)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> }
                .show()
            return
        }
        runGemmaE2bDownload(ctx, downloader)
    }

    private fun runGemmaE2bDownload(
        ctx: Context,
        downloader: com.playtranslate.translation.llm.OnDeviceLlmDownloader,
    ) {
        val sizeStr = com.playtranslate.translation.gemma.GemmaE2BMnnModel.humanSize(ctx)

        val progressDialog = OverlayProgress.Builder(ctx)
            .setTitle(getString(R.string.gemma_e2b_mnn_display_name))
            .setMessage(getString(R.string.gemma_e2b_mnn_status_downloading, "0 B", sizeStr))
            .setProgress(0)
            .setOnDismiss { reason ->
                gemmaE2bDownloadJob?.cancel()
                if (reason == com.playtranslate.ui.DismissReason.USER) {
                    downloader.deletePartial()
                }
                renderer?.refreshAllBackendStatuses()
            }
            .show()

        renderer?.setBackendDownloading("gemma_e2b_mnn", true)
        gemmaE2bDownloadJob = viewLifecycleOwner.lifecycleScope.launch {
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
                                progressDialog.setMessage(getString(
                                    R.string.gemma_e2b_mnn_status_downloading,
                                    recv, total,
                                ))
                                if (progress.total > 0) {
                                    progressDialog.setProgress(
                                        ((progress.received * 100) / progress.total).toInt()
                                    )
                                }
                            }
                            is com.playtranslate.translation.llm
                                .OnDeviceLlmDownloader.Progress.Verifying -> {
                                progressDialog.setMessage(getString(R.string.gemma_e2b_mnn_status_verifying))
                                progressDialog.setProgress(100)
                            }
                            is com.playtranslate.translation.llm
                                .OnDeviceLlmDownloader.Progress.Extracting -> {
                                progressDialog.setMessage(getString(R.string.model_download_extracting))
                                progressDialog.setProgress(100)
                            }
                        }
                    }
                }
                if (!isAdded) return@launch
                requireActivity().runOnUiThread {
                    progressDialog.dismiss()
                    when (outcome) {
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Success -> {
                            Prefs(ctx).gemmaE2bEnabled = true
                            renderer?.refreshAllBackendStatuses()
                        }
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Refused -> {
                            android.widget.Toast.makeText(
                                ctx,
                                getString(R.string.gemma_e2b_mnn_download_failed, outcome.reason),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                            renderer?.refreshAllBackendStatuses()
                        }
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Failed -> {
                            android.widget.Toast.makeText(
                                ctx,
                                getString(R.string.gemma_e2b_mnn_download_failed, outcome.reason),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                            renderer?.refreshAllBackendStatuses()
                        }
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Cancelled -> {
                            renderer?.refreshAllBackendStatuses()
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAdded) {
                    requireActivity().runOnUiThread {
                        progressDialog.dismiss()
                        android.widget.Toast.makeText(
                            ctx,
                            getString(R.string.gemma_e2b_mnn_download_failed,
                                e.message ?: e.javaClass.simpleName),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                        renderer?.refreshAllBackendStatuses()
                    }
                }
            } finally {
                progressDialog.dismiss()
                renderer?.setBackendDownloading("gemma_e2b_mnn", false)
                gemmaE2bDownloadJob = null
            }
        }
    }

    /** Show the 3-button disable dialog for the Gemma E2B tier. Delete branch
     *  deletes via [GemmaE2BMnnModel.delete] and unloads the loaded MNN model. */
    private fun showGemmaE2bMnnDisableDialog() {
        val ctx = context ?: return
        val sizeStr = com.playtranslate.translation.gemma.GemmaE2BMnnModel.humanSize(ctx)
        OverlayAlert.Builder(ctx)
            .setTitle(getString(R.string.gemma_e2b_mnn_disable_title))
            .setMessage(getString(R.string.gemma_e2b_mnn_disable_message, sizeStr))
            .hideIcon()
            .addButton(getString(R.string.gemma_e2b_mnn_disable_keep), ctx.themeColor(R.attr.ptAccent)) {
                Prefs(ctx).gemmaE2bEnabled = false
            }
            .addButton(getString(R.string.gemma_e2b_mnn_disable_delete), ctx.themeColor(R.attr.ptDivider), ctx.themeColor(R.attr.ptDanger)) {
                Prefs(ctx).gemmaE2bEnabled = false
                com.playtranslate.translation.gemma.GemmaE2BMnnModel.delete(ctx)
                viewLifecycleOwner.lifecycleScope.launch {
                    com.playtranslate.translation.mnn.MnnTranslator
                        .getInstance(ctx).unloadModel()
                }
                renderer?.refreshAllBackendStatuses()
            }
            .addCancelButton { renderer?.refreshGemmaE2bSwitch() }
            .show()
    }

    // ── Hunyuan-MT 1.5 (MNN, translation-specialist tier) flow ──────────
    //
    // Region-gated by SettingsRenderer.wireHyMtBackendRow (which hides the
    // row entirely in EU/UK/SK). Behind a one-time click-through legal
    // attestation dialog (showHyMtLegalAttestationDialog) before the first
    // download. Otherwise mirrors the Qwen-MNN flow above.

    private var hyMtDownloadJob: kotlinx.coroutines.Job? = null

    /** Re-enable an already-extracted Hunyuan-MT model. Skips the legal
     *  attestation dialog — once `hyMtLegalAccepted` is true (set when the
     *  user agreed before the first download), it stays true. */
    private fun enableInstalledHyMt() {
        val ctx = context ?: return
        val backend = com.playtranslate.translation.TranslationBackendRegistry
            .byId("hymt_mnn") as? com.playtranslate.translation.llm.OnDeviceLlmBackend
            ?: return
        checkAvailMemAndProceed(
            backend = backend,
            modelDisplayName = getString(R.string.hymt_display_name),
            onProceed = { Prefs(ctx).hyMtEnabled = true },
            allowDelete = true,
            onDelete = {
                com.playtranslate.translation.hymt.HyMtModel.delete(ctx)
                viewLifecycleOwner.lifecycleScope.launch {
                    com.playtranslate.translation.mnn.MnnTranslator
                        .getInstance(ctx).unloadModel()
                }
                renderer?.refreshAllBackendStatuses()
            },
            onCancel = { renderer?.refreshHyMtSwitch() },
        )
    }

    /** Show the modal download dialog for the Hunyuan-MT zip. Inserts the
     *  click-through legal attestation dialog
     *  ([showHyMtLegalAttestationDialog]) between the availMem and
     *  metered-network gates and the actual download. The legal dialog
     *  fires only on first download (when `hyMtLegalAccepted` is false);
     *  subsequent enables skip it. */
    private fun showHyMtDownloadDialog() {
        if (isDownloadInFlight(hyMtDownloadJob, "Hunyuan-MT")) return
        val ctx = context ?: return
        val backend = com.playtranslate.translation.TranslationBackendRegistry
            .byId("hymt_mnn") as? com.playtranslate.translation.llm.OnDeviceLlmBackend
            ?: return
        val downloader = com.playtranslate.translation.llm.OnDeviceLlmDownloader(
            context = ctx,
            modelHelper = com.playtranslate.translation.hymt.HyMtModel,
            totalMemFloorBytes = backend.totalMemFloorBytes,
        )
        checkAvailMemAndProceed(
            backend = backend,
            modelDisplayName = getString(R.string.hymt_display_name),
            onProceed = { showHyMtDownloadDialogPostAvailMem(ctx, downloader) },
        )
    }

    /** Stage 2: metered-network gate. Stage 1 (availMem) already passed. */
    private fun showHyMtDownloadDialogPostAvailMem(
        ctx: Context,
        downloader: com.playtranslate.translation.llm.OnDeviceLlmDownloader,
    ) {
        if (downloader.isCurrentNetworkMetered()) {
            val sizeStr = com.playtranslate.translation.hymt.HyMtModel.humanSize(ctx)
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.hymt_metered_warning_title)
                .setMessage(getString(R.string.hymt_metered_warning_message, sizeStr))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    showHyMtLegalAttestationDialog(ctx, downloader)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    renderer?.refreshHyMtSwitch()
                }
                .show()
            return
        }
        showHyMtLegalAttestationDialog(ctx, downloader)
    }

    /** Stage 3 (Hunyuan-MT specific): one-time click-through legal
     *  attestation. The Tencent HY Community License governs the model
     *  weights; §1(l) defines a "Territory" that excludes EU/UK/SK, §5(c)
     *  forbids use outside the Territory, and §5(b) forbids using outputs
     *  to improve other AI models. RegionPolicy already hid the row from
     *  users whose device signals indicate restricted regions; this dialog
     *  catches the default-open case (no signals available, e.g. wifi-only
     *  tablet) and gives every user an explicit point to read and accept
     *  the restrictions.
     *
     *  Skipped on subsequent enables — once the user has tapped "I Agree"
     *  once on this device (persisted via [Prefs.hyMtLegalAccepted]), the
     *  dialog never re-fires. Mirrors how Meta handles Llama ToS. */
    private fun showHyMtLegalAttestationDialog(
        ctx: Context,
        downloader: com.playtranslate.translation.llm.OnDeviceLlmDownloader,
    ) {
        if (Prefs(ctx).hyMtLegalAccepted) {
            // Already accepted on this device — straight to download.
            runHyMtDownload(ctx, downloader)
            return
        }
        OverlayAlert.Builder(ctx)
            .setTitle(getString(R.string.hymt_legal_title))
            .setMessage(getString(R.string.hymt_legal_message))
            .hideIcon()
            .addButton(getString(R.string.hymt_legal_agree), ctx.themeColor(R.attr.ptAccent)) {
                Prefs(ctx).hyMtLegalAccepted = true
                runHyMtDownload(ctx, downloader)
            }
            .addCancelButton(getString(R.string.hymt_legal_cancel)) {
                renderer?.refreshHyMtSwitch()
            }
            .show()
    }

    private fun runHyMtDownload(
        ctx: Context,
        downloader: com.playtranslate.translation.llm.OnDeviceLlmDownloader,
    ) {
        val sizeStr = com.playtranslate.translation.hymt.HyMtModel.humanSize(ctx)

        val progressDialog = OverlayProgress.Builder(ctx)
            .setTitle(getString(R.string.hymt_display_name))
            .setMessage(getString(R.string.hymt_status_downloading, "0 B", sizeStr))
            .setProgress(0)
            .setOnDismiss { reason ->
                hyMtDownloadJob?.cancel()
                if (reason == com.playtranslate.ui.DismissReason.USER) {
                    downloader.deletePartial()
                }
                renderer?.refreshAllBackendStatuses()
            }
            .show()

        renderer?.setBackendDownloading("hymt_mnn", true)
        hyMtDownloadJob = viewLifecycleOwner.lifecycleScope.launch {
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
                                progressDialog.setMessage(getString(
                                    R.string.hymt_status_downloading,
                                    recv, total,
                                ))
                                if (progress.total > 0) {
                                    progressDialog.setProgress(
                                        ((progress.received * 100) / progress.total).toInt()
                                    )
                                }
                            }
                            is com.playtranslate.translation.llm
                                .OnDeviceLlmDownloader.Progress.Verifying -> {
                                progressDialog.setMessage(getString(R.string.hymt_status_verifying))
                                progressDialog.setProgress(100)
                            }
                            is com.playtranslate.translation.llm
                                .OnDeviceLlmDownloader.Progress.Extracting -> {
                                progressDialog.setMessage(getString(R.string.model_download_extracting))
                                progressDialog.setProgress(100)
                            }
                        }
                    }
                }
                if (!isAdded) return@launch
                requireActivity().runOnUiThread {
                    progressDialog.dismiss()
                    when (outcome) {
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Success -> {
                            Prefs(ctx).hyMtEnabled = true
                            renderer?.refreshAllBackendStatuses()
                        }
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Refused -> {
                            android.widget.Toast.makeText(
                                ctx,
                                getString(R.string.hymt_download_failed, outcome.reason),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                            renderer?.refreshAllBackendStatuses()
                        }
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Failed -> {
                            android.widget.Toast.makeText(
                                ctx,
                                getString(R.string.hymt_download_failed, outcome.reason),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                            renderer?.refreshAllBackendStatuses()
                        }
                        is com.playtranslate.translation.llm
                            .OnDeviceLlmDownloader.Outcome.Cancelled -> {
                            renderer?.refreshAllBackendStatuses()
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAdded) {
                    requireActivity().runOnUiThread {
                        progressDialog.dismiss()
                        android.widget.Toast.makeText(
                            ctx,
                            getString(R.string.hymt_download_failed,
                                e.message ?: e.javaClass.simpleName),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                        renderer?.refreshAllBackendStatuses()
                    }
                }
            } finally {
                progressDialog.dismiss()
                renderer?.setBackendDownloading("hymt_mnn", false)
                hyMtDownloadJob = null
            }
        }
    }

    /** Show the 3-button disable dialog for the Hunyuan-MT tier. Mirrors
     *  [showQwenMnnDisableDialog]. Delete branch wipes the model + unloads
     *  the loaded MNN engine state. */
    private fun showHyMtDisableDialog() {
        val ctx = context ?: return
        val sizeStr = com.playtranslate.translation.hymt.HyMtModel.humanSize(ctx)
        OverlayAlert.Builder(ctx)
            .setTitle(getString(R.string.hymt_disable_title))
            .setMessage(getString(R.string.hymt_disable_message, sizeStr))
            .hideIcon()
            .addButton(getString(R.string.hymt_disable_keep), ctx.themeColor(R.attr.ptAccent)) {
                Prefs(ctx).hyMtEnabled = false
            }
            .addButton(getString(R.string.hymt_disable_delete), ctx.themeColor(R.attr.ptDivider), ctx.themeColor(R.attr.ptDanger)) {
                Prefs(ctx).hyMtEnabled = false
                com.playtranslate.translation.hymt.HyMtModel.delete(ctx)
                viewLifecycleOwner.lifecycleScope.launch {
                    com.playtranslate.translation.mnn.MnnTranslator
                        .getInstance(ctx).unloadModel()
                }
                renderer?.refreshAllBackendStatuses()
            }
            .addCancelButton { renderer?.refreshHyMtSwitch() }
            .show()
    }

    // ── Accessibility-required alert ─────────────────────────────────────

    /** OverlayAlert shown when the user attempts an accessibility-gated
     *  Settings action — switching capture displays or setting a hotkey —
     *  while the service is disabled. The accent button opens Accessibility
     *  Settings; the cancel button just dismisses. Icon-less, matching the
     *  TG / Qwen confirmation dialogs. */
    private fun showAccessibilityRequiredAlert(requirement: AccessibilityRequirement) {
        val ctx = context ?: return
        val message = when (requirement) {
            AccessibilityRequirement.MULTI_DISPLAY ->
                getString(R.string.a11y_required_displays_message)
            AccessibilityRequirement.HOTKEY ->
                getString(R.string.a11y_required_hotkey_message)
            AccessibilityRequirement.ENHANCED_AUTO_TRANSLATE ->
                getString(R.string.a11y_required_enhanced_message)
        }
        OverlayAlert.Builder(ctx)
            .hideIcon()
            .setTitle(getString(R.string.a11y_required_alert_title))
            .setMessage(message)
            .addButton(
                getString(R.string.btn_open_a11y_settings),
                ctx.themeColor(R.attr.ptAccent),
                ctx.themeColor(R.attr.ptAccentOn),
            ) {
                startActivity(android.content.Intent(
                    android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .addCancelButton(getString(android.R.string.cancel))
            .show()
    }

    // ── MediaProjection controls ─────────────────────────────────────────

    /** MediaProjection-backend "turn on game screen controls": prompt for the
     *  screen-record consent and, on grant, reconcile the floating icons.
     *  Runs on the Activity scope so the consent round-trip survives a
     *  Settings dismiss; the switch refresh is null-safe for that case. */
    private fun requestMediaProjectionControls() {
        val activity = activity as? androidx.appcompat.app.AppCompatActivity ?: return
        if (!android.provider.Settings.canDrawOverlays(activity)) {
            // The MediaProjection floating controls are TYPE_APPLICATION_OVERLAY
            // windows — without "Display over other apps" they silently fail to
            // add. Route the user to grant it; they re-tap Start afterwards.
            showOverlayPermissionAlert(activity)
            return
        }
        activity.lifecycleScope.launch {
            val controller =
                com.playtranslate.CaptureService.instance?.mediaProjectionController
            val granted = controller?.ensureConsent() ?: false
            if (granted) {
                // showOverlayIcon doesn't apply on the MediaProjection
                // backend — the icon shows whenever capture is active (see
                // OverlayUiController.reconcileFloatingIcons), so there's
                // nothing to flip here.
                com.playtranslate.capture.CaptureBackendResolver
                    .activeOverlayUi?.reconcileFloatingIcons()
                com.playtranslate.PlayTranslateTileService.TileSync.refresh(activity)
            }
            renderer?.refreshOverlayIconState()
        }
    }

    /** Routes the user to grant "Display over other apps" — required for the
     *  MediaProjection floating controls (TYPE_APPLICATION_OVERLAY windows). */
    private fun showOverlayPermissionAlert(activity: androidx.appcompat.app.AppCompatActivity) {
        OverlayAlert.Builder(activity)
            .hideIcon()
            .setTitle(getString(R.string.mp_overlay_permission_title))
            .setMessage(getString(R.string.mp_overlay_permission_message))
            .addButton(
                getString(R.string.mp_overlay_permission_button),
                activity.themeColor(R.attr.ptAccent),
                activity.themeColor(R.attr.ptAccentOn),
            ) {
                activity.startActivity(activity.overlayPermissionSettingsIntent())
            }
            .addCancelButton(getString(android.R.string.cancel))
            .show()
    }

    // ── Companion ───────────────────────────────────────────────────────

    companion object {
        const val TAG = "SettingsBottomSheet"
        private const val ARG_HIDE_DISMISS = "hide_dismiss"

        // (TG and Qwen total-mem floors used to live here as TG_TOTAL_MEM_FLOOR_BYTES
        // and QWEN_TOTAL_MEM_FLOOR_BYTES, but they're now properties on the backend
        // class itself — see OnDeviceLlmBackend.totalMemFloorBytes — so the UI's
        // hardware-gate logic and the downloader's preflight read the same source.)

        private const val ARG_ANCHOR = "anchor"

        fun newInstance(
            hideDismiss: Boolean = false,
            anchor: SettingsAnchor? = null,
        ) = SettingsBottomSheet().apply {
            val args = Bundle()
            if (hideDismiss) args.putBoolean(ARG_HIDE_DISMISS, true)
            if (anchor != null) args.putString(ARG_ANCHOR, anchor.name)
            if (!args.isEmpty) arguments = args
        }
    }
}

/**
 * Named scroll target for [SettingsBottomSheet.newInstance]. Add new entries
 * here when a deep-link needs to land the user at a specific section of the
 * Settings sheet; the sheet scrolls the corresponding header view into view
 * after layout. See [SettingsBottomSheet.maybeScrollToAnchor].
 */
enum class SettingsAnchor(val headerId: Int) {
    /** "Offline translations" section header. Used by the cold-launch
     *  "faster Qwen available" OverlayAlert in MainActivity. */
    OfflineTranslation(com.playtranslate.R.id.headerOfflineTranslations),
}
