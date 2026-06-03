package com.playtranslate.ui

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
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
    private val rootVm: RootSettingsViewModel by viewModels()

    /** The live MediaProjection session this sheet holds a teardown listener
     *  on while resumed (kept so onPause unregisters from the same one).
     *  MediaProjection "active" is held consent, not a pref our observers
     *  see — so the Turn On/Off buttons are refreshed from this teardown
     *  callback instead. */
    private var teardownController: com.playtranslate.capture.MediaProjectionController? = null
    private val onProjectionTeardown: () -> Unit = { renderer?.refreshOverlayIconState() }

    private val requestAnkiPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) rootVm.refresh()
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
        rootVm.refresh()
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

        // Render the VM-owned root state (language names + the Anki/TTS cells)
        // and react to overlay-icon flips (QS tile) — both view-lifecycle-
        // scoped. The power card + stale-pack card are NOT here: they project
        // live system state and stay imperative (resume-driven) in the renderer.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                rootVm.state.collect { renderer?.render(it) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Prefs(requireContext()).observe(Prefs.KEY_SHOW_OVERLAY_ICON).collect {
                    renderer?.refreshOverlayIconState()
                }
            }
        }
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
        // Re-poll the system-state cells (Anki permission, TTS engine) that can
        // change while Settings is backgrounded — the VM has no pref to observe
        // for these, so resume is the catch-up point.
        rootVm.refresh()
        renderer?.refreshOverlayIconState()
        // The toolbar hosts the Turn On/Off button on the MediaProjection
        // backend — re-check its visibility in case the accessibility grant
        // changed while we were away (same catch-up reason as the rows here).
        view?.let { refreshToolbarVisibility(it) }
    }

    override fun onPause() {
        super.onPause()
        teardownController?.removeTeardownListener(onProjectionTeardown)
        teardownController = null
        renderer?.stopCaptureButtonShimmer()
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
        val nonDismissible = arguments?.getBoolean(ARG_NON_DISMISSIBLE, false) ?: false
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
            nonDismissible -> {
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

        // Restore the scroll position saved before a theme-change recreate.
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
                override fun openTranslationServicesSettings() {
                    startActivity(
                        android.content.Intent(requireContext(), TranslationServicesActivity::class.java)
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
                onSourceLangChanged?.invoke()
            }
            override fun onTargetSelectionDone(targetCode: String) {
                onSourceLangChanged?.invoke()
            }
        }
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
        private const val ARG_NON_DISMISSIBLE = "non_dismissible"

        /** True if [fragment] is the non-dismissible single-screen "home"
         *  instance (no close button, back exits the app). The single owner of
         *  the [ARG_NON_DISMISSIBLE] key, so callers don't re-spell it. */
        fun isNonDismissible(fragment: SettingsBottomSheet?): Boolean =
            fragment?.arguments?.getBoolean(ARG_NON_DISMISSIBLE, false) == true

        // (TG and Qwen total-mem floors used to live here as TG_TOTAL_MEM_FLOOR_BYTES
        // and QWEN_TOTAL_MEM_FLOOR_BYTES, but they're now properties on the backend
        // class itself — see OnDeviceLlmBackend.totalMemFloorBytes — so the UI's
        // hardware-gate logic and the downloader's preflight read the same source.)

        fun newInstance(
            nonDismissible: Boolean = false,
        ) = SettingsBottomSheet().apply {
            val args = Bundle()
            if (nonDismissible) args.putBoolean(ARG_NON_DISMISSIBLE, true)
            if (!args.isEmpty) arguments = args
        }
    }
}
