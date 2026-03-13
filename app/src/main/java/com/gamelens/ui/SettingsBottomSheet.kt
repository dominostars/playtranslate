package com.gamelens.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import android.text.Editable
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.Gravity
import android.view.LayoutInflater
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.gamelens.AnkiManager
import com.gamelens.Prefs
import com.gamelens.R
import com.gamelens.fullScreenDialogTheme
import com.gamelens.themeColor
import android.content.Intent
import android.provider.Settings
import com.gamelens.PlayTranslateAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsBottomSheet : DialogFragment() {

    /** Called when the selected capture display changes. */
    var onDisplayChanged: (() -> Unit)? = null
    /** Called when the hide-live-mode switch changes. */
    var onHideLiveModeChanged: (() -> Unit)? = null

    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    /** Called when the hide-translation switch changes. */
    var onHideTranslationChanged: (() -> Unit)? = null
    /** Called when the debug force-single-screen toggle changes. */
    var onScreenModeChanged: (() -> Unit)? = null

    private var deckEntries: List<Map.Entry<Long, String>> = emptyList()
    private var displayList: List<android.view.Display> = emptyList()
    private var selectedDisplayIdx = 0
    private val displayThumbnails = HashMap<Int, Bitmap?>()

    private lateinit var llDisplayOptions: LinearLayout
    private lateinit var spinnerAnkiDeck: android.widget.Spinner
    private lateinit var settingsScrollView: android.widget.ScrollView

    private val requestAnkiPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) loadAnkiDecks()
    }

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

        val hideDismiss = arguments?.getBoolean(ARG_HIDE_DISMISS, false) ?: false
        if (Prefs.isSingleScreen(requireContext())) {
            view.findViewById<TextView>(R.id.tvSettingsTitle).text = getString(R.string.app_name)
        }
        val closeBtn = view.findViewById<View>(R.id.btnCloseSettings)
        if (hideDismiss) {
            closeBtn.visibility = View.GONE
            // In single-screen mode, back should exit the app, not dismiss the sheet
            dialog?.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                    activity?.finish()
                    true
                } else false
            }
        } else {
            closeBtn.setOnClickListener { dismiss() }
        }

        val prefs           = Prefs(requireContext())
        llDisplayOptions    = view.findViewById(R.id.llDisplayOptions)
        settingsScrollView  = view.findViewById(R.id.settingsScrollView)
        val etDeeplKey      = view.findViewById<EditText>(R.id.etDeeplKey)
        spinnerAnkiDeck     = view.findViewById(R.id.spinnerAnkiDeck)
        val switchHideLiveMode = view.findViewById<Switch>(R.id.switchHideLiveMode)
        val llThemePicker   = view.findViewById<LinearLayout>(R.id.llThemePicker)

        // Restore scroll position after theme recreate.
        // Uses onPreDrawListener so the position is set before the first frame is drawn,
        // avoiding a visible jump from 0 to the saved position.
        val savedScroll = prefs.settingsScrollY
        if (savedScroll > 0) {
            prefs.settingsScrollY = 0
            settingsScrollView.viewTreeObserver.addOnPreDrawListener(
                object : android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        settingsScrollView.viewTreeObserver.removeOnPreDrawListener(this)
                        settingsScrollView.scrollTo(0, savedScroll)
                        return true
                    }
                }
            )
        }

        // ── Overlay icon toggle ──────────────────────────────────────────
        val switchOverlayIcon = view.findViewById<Switch>(R.id.switchOverlayIcon)
        val tvOverlayIconTitle = view.findViewById<TextView>(R.id.tvOverlayIconTitle)
        val tvOverlayIconHint = view.findViewById<TextView>(R.id.tvOverlayIconHint)
        val isSingle = Prefs.isSingleScreen(requireContext())
        tvOverlayIconHint.setText(
            if (isSingle) R.string.settings_overlay_icon_hint_single
            else R.string.settings_overlay_icon_hint_dual
        )
        // Make subtext same color as title (both modes)
        tvOverlayIconHint.setTextColor(requireContext().themeColor(R.attr.colorTextPrimary))
        // Double text sizes in single-screen mode
        if (isSingle) {
            tvOverlayIconTitle.textSize = 21f
            tvOverlayIconHint.textSize = 15f
            val rowOverlayIcon = view.findViewById<LinearLayout>(R.id.rowOverlayIcon)
            val dp = resources.displayMetrics.density
            val pad = (18 * dp).toInt()
            rowOverlayIcon.setPadding(pad, pad, pad, pad)
        }
        switchOverlayIcon.isChecked = prefs.showOverlayIcon && PlayTranslateAccessibilityService.isEnabled
        switchOverlayIcon.setOnCheckedChangeListener { _, checked ->
            if (checked && !PlayTranslateAccessibilityService.isEnabled) {
                switchOverlayIcon.isChecked = false
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.overlay_icon_a11y_required_title)
                    .setMessage(R.string.overlay_icon_a11y_required_message)
                    .setPositiveButton(R.string.btn_open_a11y_settings) { _, _ ->
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return@setOnCheckedChangeListener
            }
            prefs.showOverlayIcon = checked
            PlayTranslateAccessibilityService.instance?.ensureFloatingIcon()
        }
        view.findViewById<View>(R.id.rowOverlayIcon).setOnClickListener {
            switchOverlayIcon.toggle()
        }

        // ── DeepL key (auto-save on text change) ─────────────────────────
        etDeeplKey.setText(prefs.deeplApiKey)
        etDeeplKey.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                prefs.deeplApiKey = s?.toString()?.trim() ?: ""
            }
        })

        view.findViewById<TextView>(R.id.tvDeeplHint).apply {
            Linkify.addLinks(this, Linkify.WEB_URLS)
            movementMethod = LinkMovementMethod.getInstance()
        }

        // ── Display selector ─────────────────────────────────────────────

        val displayManager = requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayList = displayManager.displays.toList()
        selectedDisplayIdx = displayList.indexOfFirst { it.displayId == prefs.captureDisplayId }
            .takeIf { it >= 0 } ?: 0

        buildDisplayRows(prefs)

        val myDisplayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requireActivity().display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
        } else {
            @Suppress("DEPRECATION")
            requireActivity().windowManager.defaultDisplay.displayId
        }

        displayList.forEach { display ->
            val a11y = com.gamelens.PlayTranslateAccessibilityService.instance
            if (a11y != null) {
                a11y.captureDisplay(display.displayId) { bitmap ->
                    if (bitmap != null) {
                        displayThumbnails[display.displayId] = scaleThumbnail(bitmap)
                        view?.post { if (isAdded) buildDisplayRows(prefs) }
                    } else if (display.displayId == myDisplayId) {
                        captureActivityWindow { thumb ->
                            displayThumbnails[display.displayId] = thumb
                            if (isAdded) buildDisplayRows(prefs)
                        }
                    }
                }
            } else if (display.displayId == myDisplayId) {
                captureActivityWindow { thumb ->
                    displayThumbnails[display.displayId] = thumb
                    if (isAdded) buildDisplayRows(prefs)
                }
            }
        }

        // ── AnkiDroid Play Store link ─────────────────────────────────────
        view.findViewById<TextView>(R.id.tvAnkiPlayStoreLink).setOnClickListener {
            val uri = android.net.Uri.parse(getString(R.string.anki_play_store_url))
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
        }

        // ── Anki section ─────────────────────────────────────────────────
        refreshAnkiSection()

        // ── Capture method section (hidden when accessibility is enabled) ─
        view.findViewById<View>(R.id.btnCaptureMethodOpenA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        refreshCaptureMethodSection()

        // ── Capture interval (auto-save on text change) ───────────────────
        val etCaptureInterval = view.findViewById<EditText>(R.id.etCaptureInterval)
        etCaptureInterval.setText(prefs.captureIntervalSec.toString())
        etCaptureInterval.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.toIntOrNull() ?: return
                prefs.captureIntervalSec = v
            }
        })

        // ── Hide live mode (auto-save on toggle) ──────────────────────────
        switchHideLiveMode.isChecked = prefs.hideLiveMode
        switchHideLiveMode.setOnCheckedChangeListener { _, checked ->
            prefs.hideLiveMode = checked
            onHideLiveModeChanged?.invoke()
        }
        view.findViewById<View>(R.id.rowHideLiveMode).setOnClickListener {
            switchHideLiveMode.toggle()
        }

        // ── Hide translation (auto-save on toggle) ────────────────────────
        val switchHideTranslation = view.findViewById<Switch>(R.id.switchHideTranslation)
        switchHideTranslation.isChecked = prefs.hideTranslation
        switchHideTranslation.setOnCheckedChangeListener { _, checked ->
            prefs.hideTranslation = checked
            onHideTranslationChanged?.invoke()
        }
        view.findViewById<View>(R.id.rowHideTranslation).setOnClickListener {
            switchHideTranslation.toggle()
        }

        // ── Show transliteration (auto-save on toggle) ──────────────────
        val switchShowTransliteration = view.findViewById<Switch>(R.id.switchShowTransliteration)
        switchShowTransliteration.isChecked = prefs.showTransliteration
        switchShowTransliteration.setOnCheckedChangeListener { _, checked ->
            prefs.showTransliteration = checked
        }
        view.findViewById<View>(R.id.rowShowTransliteration).setOnClickListener {
            switchShowTransliteration.toggle()
        }

        // ── Theme picker ──────────────────────────────────────────────────
        buildThemePicker(llThemePicker, prefs)

        // ── Debug section (debug builds only) ────────────────────────────
        val llDebugSection = view.findViewById<LinearLayout>(R.id.llDebugSection)
        if (com.gamelens.BuildConfig.DEBUG) {
            llDebugSection.visibility = View.VISIBLE
            val switchForceSingleScreen = view.findViewById<Switch>(R.id.switchForceSingleScreen)
            switchForceSingleScreen.isChecked = prefs.debugForceSingleScreen
            switchForceSingleScreen.setOnCheckedChangeListener { _, checked ->
                prefs.debugForceSingleScreen = checked
                onScreenModeChanged?.invoke()
            }
            view.findViewById<View>(R.id.rowForceSingleScreen).setOnClickListener {
                switchForceSingleScreen.toggle()
            }
            val switchShowOcrBoxes = view.findViewById<Switch>(R.id.switchShowOcrBoxes)
            switchShowOcrBoxes.isChecked = prefs.debugShowOcrBoxes
            switchShowOcrBoxes.setOnCheckedChangeListener { _, checked ->
                prefs.debugShowOcrBoxes = checked
                val a11y = PlayTranslateAccessibilityService.instance
                if (checked) {
                    a11y?.startDebugOcrLoop()
                } else {
                    a11y?.stopDebugOcrLoop()
                }
            }
            view.findViewById<View>(R.id.rowShowOcrBoxes).setOnClickListener {
                switchShowOcrBoxes.toggle()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAnkiSection()
        refreshCaptureMethodSection()
        refreshOverlayIconSwitch()

        val ctx = context ?: return
        val sp = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "show_overlay_icon") refreshOverlayIconSwitch()
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

    private fun refreshOverlayIconSwitch() {
        val v = view ?: return
        val ctx = context ?: return
        val prefs = Prefs(ctx)
        val sw = v.findViewById<Switch>(R.id.switchOverlayIcon) ?: return
        sw.isChecked = prefs.showOverlayIcon && PlayTranslateAccessibilityService.isEnabled
    }

    // ── Capture method section ────────────────────────────────────────────

    private fun refreshCaptureMethodSection() {
        val v = view ?: return
        val llCaptureMethodSection = v.findViewById<LinearLayout>(R.id.llCaptureMethodSection)
        llCaptureMethodSection.visibility =
            if (PlayTranslateAccessibilityService.isEnabled) View.GONE else View.VISIBLE
    }

    // ── Anki section ──────────────────────────────────────────────────────

    private fun refreshAnkiSection() {
        val v = view ?: return
        val ankiManager    = AnkiManager(requireContext())
        val tvDescription  = v.findViewById<TextView>(R.id.tvAnkiDescription)
        val tvPlayStore    = v.findViewById<TextView>(R.id.tvAnkiPlayStoreLink)
        val tvAnkiStatus   = v.findViewById<TextView>(R.id.tvAnkiStatus)
        val btnGrantAnki   = v.findViewById<Button>(R.id.btnGrantAnkiPermission)

        val installed = ankiManager.isAnkiDroidInstalled()
        // Show description + Play Store link only when AnkiDroid is not installed
        tvDescription.visibility = if (installed) View.GONE else View.VISIBLE
        tvPlayStore.visibility   = if (installed) View.GONE else View.VISIBLE

        when {
            !installed -> {
                tvAnkiStatus.visibility = View.GONE
                btnGrantAnki.visibility = View.GONE
                spinnerAnkiDeck.visibility = View.GONE
            }
            !ankiManager.hasPermission() -> {
                tvAnkiStatus.text = getString(R.string.anki_permission_needed)
                tvAnkiStatus.visibility = View.VISIBLE
                spinnerAnkiDeck.visibility = View.GONE
                btnGrantAnki.visibility = View.VISIBLE
                btnGrantAnki.setOnClickListener {
                    requestAnkiPermission.launch(AnkiManager.PERMISSION)
                }
            }
            else -> {
                btnGrantAnki.visibility = View.GONE
                if (spinnerAnkiDeck.visibility != View.VISIBLE) loadAnkiDecks()
            }
        }
    }

    // ── Thumbnail helpers ─────────────────────────────────────────────────

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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) { onReady(null); return }
        val activity = activity ?: run { onReady(null); return }
        val decorView = activity.window.decorView
        val w = decorView.width.takeIf  { it > 0 } ?: run { onReady(null); return }
        val h = decorView.height.takeIf { it > 0 } ?: run { onReady(null); return }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        PixelCopy.request(activity.window, bmp, { result ->
            if (result == PixelCopy.SUCCESS) onReady(scaleThumbnail(bmp))
            else { bmp.recycle(); onReady(null) }
        }, Handler(Looper.getMainLooper()))
    }

    // ── Display rows ──────────────────────────────────────────────────────

    private fun buildDisplayRows(prefs: Prefs) {
        if (!isAdded) return
        val ctx = requireContext()
        llDisplayOptions.removeAllViews()
        displayList.forEachIndexed { idx, display ->
            llDisplayOptions.addView(buildDisplayRow(ctx, idx, display, prefs))
        }
        if (displayList.isEmpty()) {
            llDisplayOptions.addView(TextView(ctx).apply {
                text = getString(R.string.settings_no_displays_found)
                setTextColor(ctx.themeColor(R.attr.colorTextHint))
                textSize = 13f
                setPadding(0, 8, 0, 8)
            })
        }
    }

    private fun buildDisplayRow(
        ctx: Context, idx: Int, display: android.view.Display, prefs: Prefs
    ): View {
        val dp = ctx.resources.displayMetrics.density
        val isSelected = idx == selectedDisplayIdx
        val thumbW = (80 * dp).toInt()
        val thumbH = (50 * dp).toInt()

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((4 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            setBackgroundColor(ctx.themeColor(if (isSelected) R.attr.colorBgCard else R.attr.colorBgSurface))
        }

        val rb = RadioButton(ctx).apply {
            isChecked = isSelected
            isClickable = false
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = (4 * dp).toInt() }
        }

        val iv = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(thumbW, thumbH).also {
                it.marginEnd = (10 * dp).toInt()
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(ctx.themeColor(R.attr.colorBgDivider))
            val thumb = displayThumbnails[display.displayId]
            if (thumb != null) setImageBitmap(thumb)
        }

        val tv = TextView(ctx).apply {
            text = "Display ${display.displayId}  —  ${display.name}"
            textSize = 13f
            setTextColor(ctx.themeColor(R.attr.colorTextPrimary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        row.addView(rb)
        row.addView(iv)
        row.addView(tv)
        row.setOnClickListener {
            selectedDisplayIdx = idx
            val newDisplayId = displayList.getOrNull(idx)?.displayId ?: prefs.captureDisplayId
            if (newDisplayId != prefs.captureDisplayId) {
                prefs.captureDisplayId = newDisplayId
                onDisplayChanged?.invoke()
            }
            buildDisplayRows(prefs)
        }
        return row
    }

    // ── Anki decks ────────────────────────────────────────────────────────

    private fun loadAnkiDecks() {
        val view = view ?: return
        val tvAnkiStatus = view.findViewById<TextView>(R.id.tvAnkiStatus)
        val btnGrantAnki = view.findViewById<Button>(R.id.btnGrantAnkiPermission)
        val prefs = Prefs(requireContext())

        tvAnkiStatus.text       = getString(R.string.anki_loading_decks)
        tvAnkiStatus.visibility = View.VISIBLE
        spinnerAnkiDeck.visibility = View.GONE
        btnGrantAnki.visibility    = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val decks = withContext(Dispatchers.IO) { AnkiManager(requireContext()).getDecks() }

            if (decks.isEmpty()) {
                tvAnkiStatus.text = getString(R.string.anki_no_deck_selected)
                return@launch
            }

            deckEntries = decks.entries.toList()
            spinnerAnkiDeck.adapter = android.widget.ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                deckEntries.map { it.value }
            )

            val savedIdx = deckEntries.indexOfFirst { it.key == prefs.ankiDeckId }.takeIf { it >= 0 } ?: 0
            spinnerAnkiDeck.setSelection(savedIdx)

            // Auto-save on deck selection
            spinnerAnkiDeck.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val entry = deckEntries.getOrNull(pos) ?: return
                    prefs.ankiDeckId   = entry.key
                    prefs.ankiDeckName = entry.value
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

            tvAnkiStatus.visibility    = View.GONE
            spinnerAnkiDeck.visibility = View.VISIBLE
        }
    }

    // ── Theme picker ──────────────────────────────────────────────────────

    private data class ThemeOption(
        val label: String,
        val index: Int,
        val bgColor: Int,       // device / window background colour
        val accentColor: Int    // accent colour shown as inner dot
    )

    private fun buildThemePicker(container: LinearLayout, prefs: Prefs) {
        val themes = listOf(
            ThemeOption("Black",   0, Color.parseColor("#0D0D0D"), Color.parseColor("#00BCD4")),
            ThemeOption("White",   1, Color.parseColor("#F0F0F0"), Color.parseColor("#1565C0")),
            ThemeOption("Rainbow", 2, Color.parseColor("#D8D4D1"), Color.parseColor("#546E7A")),
            ThemeOption("Purple",  3, Color.parseColor("#2E2238"), Color.parseColor("#CE93D8")),
        )

        val ctx = requireContext()
        val dp  = ctx.resources.displayMetrics.density
        val selectionColor = ctx.themeColor(R.attr.colorAccentPrimary)

        container.removeAllViews()

        themes.forEach { theme ->
            val isSelected = prefs.themeIndex == theme.index

            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                isClickable = true
                isFocusable = true
                background = android.util.TypedValue().let { tv ->
                    ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                    ctx.getDrawable(tv.resourceId)
                }
            }

            // Outer circle: device background colour with accent ring when selected
            val outerCircle = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.bgColor)
                val strokeColor = if (isSelected) selectionColor else Color.argb(80, 128, 128, 128)
                setStroke((if (isSelected) 3 else 1).toInt() * dp.toInt(), strokeColor)
            }
            // Inner accent dot
            val innerDot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.accentColor)
            }

            val circleSize = (52 * dp).toInt()
            val dotSize    = (16 * dp).toInt()

            val frame = android.widget.FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(circleSize, circleSize).also { lp ->
                    lp.bottomMargin = (6 * dp).toInt()
                    lp.gravity = Gravity.CENTER_HORIZONTAL
                }
            }
            val outerView = View(ctx).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
                background = outerCircle
            }
            val innerView = View(ctx).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(dotSize, dotSize).also { lp ->
                    lp.gravity = Gravity.CENTER
                }
                background = innerDot
            }
            frame.addView(outerView)
            frame.addView(innerView)
            col.addView(frame)

            val label = TextView(ctx).apply {
                text = theme.label
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(ctx.themeColor(R.attr.colorTextPrimary))
            }
            col.addView(label)

            col.setOnClickListener {
                if (prefs.themeIndex != theme.index) {
                    prefs.settingsScrollY = settingsScrollView.scrollY
                    prefs.suppressNextTransition = true
                    prefs.themeIndex = theme.index
                    activity?.recreate()
                }
            }

            container.addView(col)
        }
    }

    companion object {
        const val TAG = "SettingsBottomSheet"
        private const val ARG_HIDE_DISMISS = "hide_dismiss"

        fun newInstance(hideDismiss: Boolean = false) = SettingsBottomSheet().apply {
            if (hideDismiss) arguments = android.os.Bundle().apply { putBoolean(ARG_HIDE_DISMISS, true) }
        }
    }
}
