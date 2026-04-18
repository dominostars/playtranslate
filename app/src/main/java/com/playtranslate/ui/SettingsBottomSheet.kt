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
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.playtranslate.AnkiManager
import com.playtranslate.Prefs
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.R
import com.playtranslate.fullScreenDialogTheme
import com.playtranslate.themeColor
import kotlinx.coroutines.MainScope
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
    private var lastDisplayCount = 0

    private val requestAnkiPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) renderer?.refreshAnkiSection()
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun getTheme(): Int = fullScreenDialogTheme(requireContext())

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

        val ctx = context ?: return
        val sp = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "show_overlay_icon" -> renderer?.refreshOverlayIconSwitch()
                "compact_overlay_icon" -> renderer?.refreshCompactIconSwitch()
                "auto_translation_mode" -> renderer?.refreshAutoModeToggle()
            }
        }
        sp.registerOnSharedPreferenceChangeListener(prefsListener)
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

        // Toolbar (dialog mode only)
        if (isDialog) {
            view.findViewById<View>(R.id.settingsToolbar).visibility = View.VISIBLE
            view.findViewById<View>(R.id.settingsToolbarDivider).visibility = View.VISIBLE
            if (!Prefs.hasMultipleDisplays(requireContext())) {
                view.findViewById<android.widget.TextView>(R.id.tvSettingsTitle)
                    .text = getString(R.string.app_name)
            }
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
        val settingsScrollView = view.findViewById<ScrollView>(R.id.settingsScrollView)
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
                override fun showHotkeyDialog(
                    title: String?, onSet: (List<Int>) -> Unit, onCancel: () -> Unit
                ) {
                    val dialog = HotkeySetupDialog.newInstance(title)
                    dialog.onHotkeySet = onSet
                    dialog.onCancelled = onCancel
                    dialog.show(childFragmentManager, "hotkey_setup")
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
        val displays = displayManager.displays.toList()
        lastDisplayCount = displays.size

        r.displayList = displays
        r.selectedDisplayIdx = displays.indexOfFirst { it.displayId == prefs.captureDisplayId }
            .takeIf { it >= 0 } ?: 0

        // Register display listener for hot-plug
        displayListener?.let { displayManager.unregisterDisplayListener(it) }
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) { reinflateIfDisplayCountChanged(displayManager) }
            override fun onDisplayRemoved(displayId: Int) { reinflateIfDisplayCountChanged(displayManager) }
            override fun onDisplayChanged(displayId: Int) {}
        }
        displayManager.registerDisplayListener(displayListener, null)

        // Capture thumbnails asynchronously
        val myDisplayId = requireActivity().display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
        displays.forEach { display ->
            val mgr = PlayTranslateAccessibilityService.instance?.screenshotManager
            if (mgr != null) {
                MainScope().launch {
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

    private fun reinflateIfDisplayCountChanged(dm: DisplayManager) {
        val newCount = dm.displays.size
        if (newCount != lastDisplayCount && isAdded) {
            lastDisplayCount = newCount
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

    // ── Companion ───────────────────────────────────────────────────────

    companion object {
        const val TAG = "SettingsBottomSheet"
        private const val ARG_HIDE_DISMISS = "hide_dismiss"

        fun newInstance(hideDismiss: Boolean = false) = SettingsBottomSheet().apply {
            if (hideDismiss) arguments = Bundle().apply { putBoolean(ARG_HIDE_DISMISS, true) }
        }
    }
}
