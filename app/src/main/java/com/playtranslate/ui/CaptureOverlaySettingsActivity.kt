package com.playtranslate.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.CaptureService
import com.playtranslate.OverlayMode
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.capturableDisplays
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.compositeOver
import com.playtranslate.language.HintTextKind
import com.playtranslate.language.LanguagePackStore
import com.playtranslate.language.OcrBackend
import com.playtranslate.language.SourceLangId
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.ocr.registry.OcrPackModelHelper
import com.playtranslate.ocr.registry.ocrLabel
import com.playtranslate.ocr.registry.selectionToken
import com.playtranslate.themeColor
import com.playtranslate.translation.llm.OnDeviceLlmDownloader
import com.playtranslate.translation.llm.humanSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Capture & overlay sub-page: OCR engine choice, the multi-display capture
 * picker, and the auto-translate controls (enhanced auto-translate, overlay
 * mode, hide-overlays, capture interval).
 *
 * Unlike the toggle-only Appearance / Hotkeys pages, this screen renders mostly
 * *live system state* — connected displays + window thumbnails, OCR pack
 * install status, accessibility-service state — refreshed on resume and on
 * display hot-plug, so it's hosted imperatively here rather than projected
 * through a StateFlow ViewModel. Prefs remain the source of truth: a display or
 * overlay-mode change writes the pref, and MainActivity reconfigures the
 * running capture on its next onResume (display) or via its existing
 * updateRegionButton (overlay mode).
 */
class CaptureOverlaySettingsActivity : SettingsSubPageActivity() {

    override val layoutResId = R.layout.activity_capture_overlay_settings

    private val prefs by lazy { Prefs(this) }

    // ── Auto-translate refs ───────────────────────────────────────────────
    private lateinit var rowEnhancedAutoTranslate: View
    private lateinit var tvEnhancedAutoTranslateSubtitle: TextView
    private lateinit var switchEnhancedAutoTranslate: MaterialSwitch
    private lateinit var checkEnhancedAutoTranslate: ImageView
    private lateinit var overlayModeSection: View
    private lateinit var overlayModeToggleContainer: FrameLayout
    private lateinit var rowHideOverlays: View
    private lateinit var switchHideOverlays: MaterialSwitch

    // ── Capture-display refs + state ──────────────────────────────────────
    private lateinit var captureDisplaySection: View
    private lateinit var llDisplayOptions: LinearLayout
    private var displayList: List<Display> = emptyList()
    private val displayThumbnails = HashMap<Int, Bitmap?>()
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var lastDisplayIds: Set<Int> = emptySet()

    override fun onContentCreated(savedInstanceState: Bundle?) {
        rowEnhancedAutoTranslate = findViewById(R.id.rowEnhancedAutoTranslate)
        tvEnhancedAutoTranslateSubtitle =
            rowEnhancedAutoTranslate.findViewById(R.id.tvEnhancedAutoTranslateSubtitle)
        switchEnhancedAutoTranslate =
            rowEnhancedAutoTranslate.findViewById(R.id.switchEnhancedAutoTranslate)
        checkEnhancedAutoTranslate =
            rowEnhancedAutoTranslate.findViewById(R.id.checkEnhancedAutoTranslate)
        overlayModeSection = findViewById(R.id.overlayModeSection)
        overlayModeToggleContainer = findViewById(R.id.overlayModeToggleContainer)
        rowHideOverlays = findViewById(R.id.rowHideOverlays)
        switchHideOverlays = rowHideOverlays.findViewById(R.id.switchRowToggle)
        captureDisplaySection = findViewById(R.id.captureDisplaySection)
        llDisplayOptions = findViewById(R.id.llDisplayOptions)

        setGroupHeader(R.id.headerAutoTranslate, R.string.settings_header_auto_translate)
        setGroupHeader(R.id.headerCaptureDisplay, R.string.settings_header_capture_display)

        setupAutoTranslateSection()
        setupCaptureDisplaySection()
        setupOcrSection()
        setupDisplays()
    }

    override fun onResume() {
        super.onResume()
        // Accessibility can be granted/revoked while we're away in system
        // Settings: the enhanced-auto-translate row and the display picker's
        // lock both key off it.
        refreshEnhancedAutoTranslateRow()
        refreshDisplayRows()
        // The source language can change from another screen (the language
        // picker); rebuild the OCR cells so they track the current language.
        setupOcrSection()
    }

    override fun onDestroy() {
        displayListener?.let { displayManager?.unregisterDisplayListener(it) }
        displayListener = null
        displayThumbnails.values.forEach { it?.recycle() }
        displayThumbnails.clear()
        super.onDestroy()
    }

    private fun setGroupHeader(id: Int, titleRes: Int) {
        findViewById<View>(id)?.findViewById<TextView>(R.id.tvGroupTitle)?.text = getString(titleRes)
    }

    // ── Auto-translate ─────────────────────────────────────────────────────

    private fun setupEnhancedAutoTranslateRow() {
        rowEnhancedAutoTranslate.setOnClickListener {
            // Click only fires while a11y is off (refresh sets isClickable=false
            // otherwise). The alert routes to system Accessibility Settings;
            // onResume's refresh picks up the grant.
            showAccessibilityRequiredAlert(AccessibilityRequirement.ENHANCED_AUTO_TRANSLATE)
        }
        refreshEnhancedAutoTranslateRow()
    }

    private fun refreshEnhancedAutoTranslateRow() {
        val enabled = PlayTranslateAccessibilityService.isEnabled(this)
        if (enabled) {
            tvEnhancedAutoTranslateSubtitle.setText(R.string.enhanced_auto_translate_subtitle_on)
            switchEnhancedAutoTranslate.isGone = true
            checkEnhancedAutoTranslate.isVisible = true
            rowEnhancedAutoTranslate.isClickable = false
            rowEnhancedAutoTranslate.isFocusable = false
        } else {
            tvEnhancedAutoTranslateSubtitle.setText(R.string.enhanced_auto_translate_subtitle_off)
            switchEnhancedAutoTranslate.isVisible = true
            switchEnhancedAutoTranslate.isChecked = false
            checkEnhancedAutoTranslate.isGone = true
            rowEnhancedAutoTranslate.isClickable = true
            rowEnhancedAutoTranslate.isFocusable = true
        }
    }

    private fun setupAutoTranslateSection() {
        setupEnhancedAutoTranslateRow()

        val hintKind = SourceLanguageProfiles[prefs.sourceLangId].hintTextKind
        val hasHintText = hintKind != HintTextKind.NONE

        // -- Overlay mode toggle (Translation / Furigana-Pinyin) --
        if (hasHintText) {
            overlayModeSection.isVisible = true
            val hintLabel = when (hintKind) {
                HintTextKind.PINYIN -> getString(R.string.overlay_mode_option_pinyin)
                else -> getString(R.string.overlay_mode_option_furigana)
            }
            buildPillToggle(
                container = overlayModeToggleContainer,
                options = listOf(
                    getString(R.string.overlay_mode_option_translation) to OverlayMode.TRANSLATION,
                    hintLabel to OverlayMode.FURIGANA,
                ),
                selected = prefs.overlayMode,
                onSelect = { mode ->
                    prefs.overlayMode = mode
                    if (CaptureService.instance?.isLive == true) {
                        CaptureService.instance?.stopLive()
                    }
                },
            )
            findViewById<View>(R.id.dividerOverlayMode)?.visibility = View.VISIBLE
        } else {
            overlayModeSection.isGone = true
            findViewById<View>(R.id.dividerOverlayMode)?.visibility = View.GONE
            if (prefs.overlayMode == OverlayMode.FURIGANA) {
                prefs.overlayMode = OverlayMode.TRANSLATION
            }
        }

        // -- Hide game screen overlays toggle (multi-screen only) --
        val isSingle = Prefs.isSingleScreen(this)
        if (!isSingle) {
            rowHideOverlays.isVisible = true
            rowHideOverlays.findViewById<TextView>(R.id.tvRowTitle).text =
                getString(R.string.settings_hide_overlays_during_auto_mode)
            val subtitleHide = rowHideOverlays.findViewById<TextView>(R.id.tvRowSubtitle)
            if (prefs.captureDisplayIds.size > 1) {
                subtitleHide.text =
                    getString(R.string.settings_hide_overlays_ignored_multi_display)
                subtitleHide.isVisible = true
                subtitleHide.setTextColor(themeColor(R.attr.ptTextHint))
            } else {
                subtitleHide.isGone = true
            }
            switchHideOverlays.isChecked = prefs.hideGameOverlays
            switchHideOverlays.setOnCheckedChangeListener { _, checked ->
                prefs.hideGameOverlays = checked
                if (CaptureService.instance?.isLive == true) {
                    CaptureService.instance?.stopLive()
                }
            }
            rowHideOverlays.setOnClickListener { switchHideOverlays.toggle() }
        }

        setupCaptureInterval()
    }

    private fun setupCaptureInterval() {
        val minSec = Prefs.MIN_CAPTURE_INTERVAL_SEC
        val minLabel = if (minSec == minSec.toLong().toFloat()) "${minSec.toLong()}"
        else "%.1f".format(minSec)

        findViewById<TextView>(R.id.tvCaptureIntervalHint)?.text =
            getString(R.string.settings_capture_interval_hint, minLabel)

        val etCaptureInterval = findViewById<EditText>(R.id.etCaptureInterval)
        etCaptureInterval.setText(prefs.captureIntervalSec.let {
            if (it == it.toLong().toFloat()) it.toLong().toString() else "%.1f".format(it)
        })
        etCaptureInterval.inputType =
            android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        etCaptureInterval.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.toFloatOrNull() ?: return
                prefs.captureIntervalSec = v
            }
        })
        etCaptureInterval.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                etCaptureInterval.clearFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(etCaptureInterval.windowToken, 0)
                true
            } else false
        }
        findViewById<View>(R.id.rowCaptureInterval)?.setOnClickListener {
            etCaptureInterval.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(etCaptureInterval, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // ── Capture display ────────────────────────────────────────────────────

    private fun setupCaptureDisplaySection() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayList = dm.capturableDisplays()
        captureDisplaySection.visibility =
            if (displayList.size <= 1) View.GONE else View.VISIBLE
        buildDisplayRows()
    }

    private fun refreshDisplayRows() {
        buildDisplayRows()
    }

    private fun buildDisplayRows() {
        llDisplayOptions.removeAllViews()
        if (displayList.isEmpty()) {
            llDisplayOptions.addView(TextView(this).apply {
                text = getString(R.string.settings_no_displays_found)
                setTextColor(themeColor(R.attr.ptTextHint))
                textSize = 13f
                val pad = (16 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            })
            return
        }
        val a11yEnabled = PlayTranslateAccessibilityService.isEnabled(this)
        displayList.forEachIndexed { idx, display ->
            if (idx > 0) {
                llDisplayOptions.addView(
                    LayoutInflater.from(this)
                        .inflate(R.layout.settings_row_divider, llDisplayOptions, false),
                )
            }
            val isFirst = idx == 0
            val isLast = idx == displayList.size - 1
            llDisplayOptions.addView(buildDisplayRow(display, isFirst, isLast, a11yEnabled))
        }
    }

    private fun buildDisplayRow(
        display: Display,
        isFirst: Boolean,
        isLast: Boolean,
        a11yEnabled: Boolean,
    ): View {
        val dp = resources.displayMetrics.density
        val isSelected =
            if (a11yEnabled) display.displayId in prefs.captureDisplayIds else isFirst
        val rowHPad = resources.getDimensionPixelSize(R.dimen.pt_row_h_padding)
        val bufferPx = (10 * dp).toInt()
        val thumbH = (46 * dp).toInt()
        val thumbW = (thumbH * 1.6f).toInt()

        val accent = themeColor(R.attr.ptAccent)
        val cardColor = themeColor(R.attr.ptCard)
        val accent10 = ColorUtils.setAlphaComponent(accent, 26)
        val selectedBg = compositeOver(accent10, cardColor)
        val cardRadius = resources.getDimension(R.dimen.pt_radius)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(bufferPx, bufferPx, rowHPad, bufferPx)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            isClickable = true
            isFocusable = true
            if (isSelected) {
                val tl = if (isFirst) cardRadius else 0f
                val br = if (isLast) cardRadius else 0f
                background = GradientDrawable().apply {
                    setColor(selectedBg)
                    cornerRadii = floatArrayOf(tl, tl, tl, tl, br, br, br, br)
                }
            }
            foreground = android.util.TypedValue().let { tv ->
                theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                ContextCompat.getDrawable(this@CaptureOverlaySettingsActivity, tv.resourceId)
            }
        }

        val iv = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(thumbW, thumbH).also {
                it.marginEnd = (12 * dp).toInt()
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(themeColor(R.attr.ptDivider))
            displayThumbnails[display.displayId]?.let { setImageBitmap(it) }
        }

        val tv = TextView(this).apply {
            text = getString(R.string.capture_display_row_label, display.displayId, display.name)
            setTextColor(themeColor(if (isSelected) R.attr.ptText else R.attr.ptTextMuted))
            setTextAppearance(R.style.Text_PT_RowTitle)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        row.addView(iv)
        row.addView(tv)
        if (isSelected) {
            val checkSize = (20 * dp).toInt()
            row.addView(ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(checkSize, checkSize).also {
                    it.marginStart = (8 * dp).toInt()
                }
                setImageResource(R.drawable.ic_check)
                imageTintList = ColorStateList.valueOf(accent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
        row.setOnClickListener {
            if (!a11yEnabled) {
                if (!isFirst) {
                    showAccessibilityRequiredAlert(AccessibilityRequirement.MULTI_DISPLAY)
                }
                return@setOnClickListener
            }
            val current = prefs.captureDisplayIds
            val targetId = display.displayId
            val next = if (targetId in current) {
                if (current.size <= 1) return@setOnClickListener
                current - targetId
            } else {
                current.toMutableSet().also { it += targetId }
            }
            // Persist only — MainActivity reconfigures the running capture on its
            // next onResume (it diffs captureDisplayIds). Rebuild the rows so the
            // selection reflects immediately.
            prefs.captureDisplayIds = next
            buildDisplayRows()
        }
        return row
    }

    /**
     * Capture a thumbnail per display: the active backend's clean frame for
     * displays it can capture, else a PixelCopy of this Activity's own window
     * for the local display. Registers a hot-plug listener that rebuilds the
     * picker when the capturable-display set changes.
     */
    private fun setupDisplays() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager = dm
        val displays = displayList
        lastDisplayIds = displays.mapTo(mutableSetOf()) { it.displayId }

        displayListener?.let { dm.unregisterDisplayListener(it) }
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = reinflateIfDisplaysChanged(dm)
            override fun onDisplayRemoved(displayId: Int) = reinflateIfDisplaysChanged(dm)
            override fun onDisplayChanged(displayId: Int) = reinflateIfDisplaysChanged(dm)
        }
        dm.registerDisplayListener(displayListener, null)

        val myDisplayId = display?.displayId ?: Display.DEFAULT_DISPLAY
        val backend = CaptureBackendResolver.active()
        val mgr = backend.captureSource?.takeIf { backend.canCaptureWithoutPrompting }
        displays.forEach { display ->
            if (mgr != null && backend.canCapture(display.displayId)) {
                lifecycleScope.launch {
                    val bitmap = mgr.requestClean(display.displayId)
                    if (bitmap != null) {
                        displayThumbnails[display.displayId] = scaleThumbnail(bitmap)
                        if (!isFinishing) refreshDisplayRows()
                    } else if (display.displayId == myDisplayId) {
                        captureActivityWindow { thumb ->
                            displayThumbnails[display.displayId] = thumb
                            if (!isFinishing) refreshDisplayRows()
                        }
                    }
                }
            } else if (display.displayId == myDisplayId) {
                captureActivityWindow { thumb ->
                    displayThumbnails[display.displayId] = thumb
                    if (!isFinishing) refreshDisplayRows()
                }
            }
        }
    }

    private fun reinflateIfDisplaysChanged(dm: DisplayManager) {
        val newIds = dm.capturableDisplays().mapTo(mutableSetOf()) { it.displayId }
        if (newIds != lastDisplayIds && !isFinishing) {
            lastDisplayIds = newIds
            // Re-query displays + rebuild rows, then re-capture thumbnails for
            // the new set.
            setupCaptureDisplaySection()
            setupDisplays()
        }
    }

    private fun scaleThumbnail(bitmap: Bitmap): Bitmap {
        val targetW = 192
        val scale = targetW.toFloat() / bitmap.width
        val scaled = bitmap.scale(targetW, (bitmap.height * scale).toInt(), true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun captureActivityWindow(onReady: (Bitmap?) -> Unit) {
        val decorView = window.decorView
        val w = decorView.width.takeIf { it > 0 } ?: run { onReady(null); return }
        val h = decorView.height.takeIf { it > 0 } ?: run { onReady(null); return }
        val bmp = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(window, bmp, { result ->
                if (result == PixelCopy.SUCCESS) onReady(scaleThumbnail(bmp))
                else { bmp.recycle(); onReady(null) }
            }, Handler(Looper.getMainLooper()))
        } catch (e: IllegalArgumentException) {
            bmp.recycle()
            onReady(null)
        }
    }

    // ── OCR ──────────────────────────────────────────────────────────────

    /** One cell per OCR engine available for the CURRENT source language. The
     *  selected engine gets the same accent + check treatment as the display
     *  picker above; a downloaded, unselected engine gets a trash. Hidden when
     *  the language has no real choice beyond the ML Kit floor. */
    private fun setupOcrSection() {
        val container = findViewById<LinearLayout>(R.id.containerOcrLanguages) ?: return
        val card = findViewById<View>(R.id.cardOcr)
        val header = findViewById<View>(R.id.headerOcr)
        container.removeAllViews()

        val id = prefs.sourceLangId
        val backends = OcrModelManager.availableBackends(this, id)
        if (backends.size <= 1) {
            header?.visibility = View.GONE
            card?.visibility = View.GONE
            return
        }
        header?.visibility = View.VISIBLE
        card?.visibility = View.VISIBLE
        setGroupHeader(R.id.headerOcr, R.string.settings_header_ocr)

        val selectedToken = OcrModelManager.selectedBackend(this, id).selectionToken
        backends.forEachIndexed { i, backend ->
            if (i > 0) {
                container.addView(
                    LayoutInflater.from(this).inflate(R.layout.settings_row_divider, container, false),
                )
            }
            container.addView(
                buildOcrCell(
                    container = container,
                    id = id,
                    backend = backend,
                    isSelected = backend.selectionToken == selectedToken,
                    isFirst = i == 0,
                    isLast = i == backends.lastIndex,
                ),
            )
        }
    }

    private fun buildOcrCell(
        container: ViewGroup,
        id: SourceLangId,
        backend: OcrBackend,
        isSelected: Boolean,
        isFirst: Boolean,
        isLast: Boolean,
    ): View {
        val view = LayoutInflater.from(this)
            .inflate(R.layout.language_list_row, container, false)
        view.findViewById<TextView>(R.id.tvRowTitle).text = backend.ocrLabel

        // "Downloaded" (strict) gates the trash + re-tap; ML Kit (no packs) is
        // never "downloaded" but is always "available" for the status icon.
        val downloaded = backend.packKeys.isNotEmpty() &&
            backend.packKeys.all { OcrPackModelHelper(it).isInstalled(this) }
        val available = downloaded || backend.packKeys.isEmpty()

        // Subtitle: the model's package size (or "Built-in"), led by the same
        // download-state icon the offline translation cells use — an accent
        // check when available, a muted download-cloud when not.
        val subtitle = view.findViewById<TextView>(R.id.tvRowEndonym)
        subtitle.visibility = View.VISIBLE
        subtitle.text = ocrSizeSubtitle(backend)
        val dp = resources.displayMetrics.density
        val statusIcon = ContextCompat.getDrawable(
            this,
            if (available) R.drawable.ic_status_downloaded else R.drawable.ic_status_cloud_down,
        )?.apply { setBounds(0, 0, (20 * dp).toInt(), (20 * dp).toInt()) }
        subtitle.setCompoundDrawablesRelative(statusIcon, null, null, null)
        subtitle.compoundDrawablePadding = (6 * dp).toInt()
        // The check is self-colored (accent disc + card tick) → no tint; the
        // cloud is tinted muted, exactly like the offline cells.
        TextViewCompat.setCompoundDrawableTintList(
            subtitle,
            if (available) null else ColorStateList.valueOf(themeColor(R.attr.ptTextMuted)),
        )

        // Trailing slot mirrors the language picker: an accent check when
        // selected (a non-interactive status mark), a trash when downloaded +
        // unselected, nothing otherwise (the XML default is GONE + trash icon).
        val trailing = view.findViewById<FrameLayout>(R.id.btnDelete)
        val trailingIcon = view.findViewById<ImageView>(R.id.ivDeleteIcon)
        when {
            isSelected -> {
                trailing.visibility = View.VISIBLE
                trailingIcon.setImageResource(R.drawable.ic_check)
                trailingIcon.imageTintList = ColorStateList.valueOf(themeColor(R.attr.ptAccent))
                trailing.isClickable = false
                trailing.isFocusable = false
                trailing.foreground = null
                trailing.contentDescription = null
            }
            downloaded -> {
                trailing.visibility = View.VISIBLE
                trailingIcon.setImageResource(R.drawable.ic_delete)
                trailingIcon.imageTintList = ColorStateList.valueOf(themeColor(R.attr.ptTextMuted))
                trailing.contentDescription =
                    getString(R.string.settings_ocr_delete_cd, backend.ocrLabel)
                trailing.setOnClickListener { confirmAndDeleteOcr(id, backend) }
            }
            // else: trailing stays GONE.
        }

        // Selected-row accent: 10% accent composited over the card, matching the
        // display picker on this page. Corners round only on the card's ends.
        if (isSelected) {
            val accent10 = ColorUtils.setAlphaComponent(themeColor(R.attr.ptAccent), 26)
            val selectedBg = compositeOver(accent10, themeColor(R.attr.ptCard))
            val cardRadius = resources.getDimension(R.dimen.pt_radius)
            val tl = if (isFirst) cardRadius else 0f
            val br = if (isLast) cardRadius else 0f
            view.background = GradientDrawable().apply {
                setColor(selectedBg)
                cornerRadii = floatArrayOf(tl, tl, tl, tl, br, br, br, br)
            }
        }

        // Tap the row body to select this engine (downloading first if needed);
        // re-tapping the already-installed current engine is a no-op.
        view.setOnClickListener {
            if (!(isSelected && downloaded)) selectOcr(id, backend)
        }
        return view
    }

    /** Subtitle = the model's own package size; ML Kit (no packs) → "Built-in".
     *  Always the model's real size — not an "incremental" cost — so a pack
     *  shared with another language still shows its true download size instead
     *  of reading as free. */
    private fun ocrSizeSubtitle(backend: OcrBackend): String =
        if (backend.packKeys.isEmpty()) getString(R.string.settings_ocr_note_builtin)
        else humanSize(backend.packKeys.sumOf { OcrPackModelHelper(it).expectedSize(this) })

    /** Select [backend] for [id]: persist immediately if its pack is already
     *  present, else download with a progress overlay and persist only on
     *  success (the previous picker's flow). Rebuilds the section so the check +
     *  accent move to the new selection. */
    private fun selectOcr(id: SourceLangId, backend: OcrBackend) {
        val needsDownload = backend.packKeys.any { !OcrPackModelHelper(it).isInstalled(this) }
        if (!needsDownload) {
            prefs.setOcrBackendToken(id, backend.selectionToken)
            setupOcrSection()
            return
        }
        var job: Job? = null
        val overlay = OverlayProgress.Builder(this)
            .setTitle(getString(R.string.settings_ocr_downloading_title, backend.ocrLabel))
            .setMessage(getString(R.string.settings_ocr_downloading_msg))
            .setProgress(0)
            .setOnDismiss { reason -> if (reason == DismissReason.USER) job?.cancel() }
            .show()
        val main = Handler(Looper.getMainLooper())
        job = lifecycleScope.launch {
            val installed = OcrModelManager.installBackend(this@CaptureOverlaySettingsActivity, backend) { _, p ->
                main.post {
                    when (p) {
                        is OnDeviceLlmDownloader.Progress.Downloading ->
                            if (p.total > 0) {
                                overlay.setProgress(((p.received * 100L) / p.total).toInt())
                                overlay.setMessage(
                                    getString(
                                        R.string.install_downloading_with_bytes,
                                        humanSize(p.received), humanSize(p.total),
                                    ),
                                )
                            } else {
                                overlay.setIndeterminate(true)
                            }
                        OnDeviceLlmDownloader.Progress.Verifying -> {
                            overlay.setIndeterminate(true)
                            overlay.setMessage(getString(R.string.settings_ocr_verifying))
                        }
                        is OnDeviceLlmDownloader.Progress.Extracting -> {
                            overlay.setIndeterminate(true)
                            overlay.setMessage(getString(R.string.settings_ocr_installing))
                        }
                    }
                }
            }
            if (installed) {
                prefs.setOcrBackendToken(id, backend.selectionToken)
            }
            overlay.dismiss()
            setupOcrSection()
            if (!installed) {
                Toast.makeText(this@CaptureOverlaySettingsActivity, R.string.settings_ocr_download_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Trash tap for a downloaded, unselected engine. Always confirms via
     *  OverlayAlert; when another downloaded language's selected engine shares
     *  this model, the message also names them (they keep their choice —
     *  switching to one re-downloads the model through the normal source-setup
     *  path). */
    private fun confirmAndDeleteOcr(id: SourceLangId, backend: OcrBackend) {
        val dependents = LanguagePackStore.installedCodes(this)
            .filter { it != id }
            .filter { other ->
                OcrModelManager.selectedBackend(this, other).packKeys.any { it in backend.packKeys }
            }
            .sortedBy { it.displayName() }
        val message = if (dependents.isEmpty()) {
            getString(R.string.settings_ocr_delete_msg, backend.ocrLabel)
        } else {
            val names = dependents.joinToString("\n") { it.displayName() }
            getString(R.string.settings_ocr_delete_shared_msg, backend.ocrLabel, names)
        }
        OverlayAlert.Builder(this)
            .hideIcon()
            .setTitle(getString(R.string.settings_ocr_delete_title, backend.ocrLabel))
            .setMessage(message)
            .addButton(
                getString(R.string.settings_ocr_delete_confirm),
                themeColor(R.attr.ptDanger),
                themeColor(R.attr.ptAccentOn),
            ) { deleteOcr(backend) }
            .addCancelButton()
            .show()
    }

    private fun deleteOcr(backend: OcrBackend) {
        backend.packKeys.forEach { OcrModelManager.deleteOcrPack(this, it) }
        setupOcrSection()
    }
}
