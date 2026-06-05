package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import com.playtranslate.capturableDisplays
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.capture.CaptureLifecycle
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.BuildConfig
import com.playtranslate.CaptureService
import com.playtranslate.OcrManager
import com.playtranslate.OverlayMode
import com.playtranslate.PlayTranslateAccessibilityService
import com.playtranslate.PlayTranslateTileService
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.diagnostics.LogExporter
import com.playtranslate.language.HintTextKind
import com.playtranslate.language.SourceLanguageProfiles
import com.playtranslate.blendColors
import com.playtranslate.themeColor
import com.playtranslate.translation.BackendId
import com.playtranslate.translation.BackendStatus
import com.playtranslate.translation.Cooldownable
import com.playtranslate.translation.BergamotBackend
import com.playtranslate.translation.MlKitBackend
import com.playtranslate.translation.StarRating
import com.playtranslate.translation.Tone
import com.playtranslate.translation.TranslationBackend
import com.playtranslate.translation.TranslationBackendRegistry
import com.playtranslate.translation.llm.OnDeviceLlmBackend
import com.playtranslate.translation.llm.OnDeviceLlmDownloader
import com.playtranslate.translation.llm.humanSize
import com.playtranslate.translation.llm.toGbDisplay
import com.playtranslate.language.LanguagePackStore
import com.playtranslate.language.OcrBackend
import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.ocr.registry.OcrPackModelHelper
import com.playtranslate.ocr.registry.ocrLabel
import com.playtranslate.ocr.registry.selectionToken
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import androidx.core.view.isVisible
import androidx.core.net.toUri
import androidx.core.view.isGone

/** Which accessibility-gated Settings action raised the "accessibility
 *  required" alert — selects the alert's explanatory copy. */
enum class AccessibilityRequirement { MULTI_DISPLAY, HOTKEY, ENHANCED_AUTO_TRANSLATE }

/** What a tap on the TTS hub cell resolves to. `null` (the
 *  [RootSettingsViewModel.TtsCell.Loading] case) means non-interactive: the
 *  picker-vs-setup choice isn't known until engine availability resolves, so a
 *  tap in the initial bind window must not open the (empty) voice picker on a
 *  device with no usable engine. */
internal enum class TtsTap { OPEN_PICKER, OPEN_SETUP }

internal fun ttsTapFor(state: RootSettingsViewModel.TtsCell): TtsTap? = when (state) {
    RootSettingsViewModel.TtsCell.Loading -> null
    is RootSettingsViewModel.TtsCell.Available -> TtsTap.OPEN_PICKER
    RootSettingsViewModel.TtsCell.NoEngine -> TtsTap.OPEN_SETUP
}

/** An [ImageSpan] drawn [dyPx] higher than ALIGN_BOTTOM would place it, so an
 *  inline icon optically centers with the surrounding text rather than sitting
 *  on the baseline. */
private class OffsetImageSpan(drawable: Drawable, private val dyPx: Int) :
    ImageSpan(drawable, ALIGN_BOTTOM) {
    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        canvas.save()
        canvas.translate(0f, -dyPx.toFloat())
        super.draw(canvas, text, start, end, x, top, y, bottom, paint)
        canvas.restore()
    }
}

/**
 * Wires every settings row in dialog_settings.xml to prefs / callbacks.
 *
 * Extracted from SettingsBottomSheet so the fragment only handles lifecycle
 * while this class owns all the view ↔ pref binding.
 */
class SettingsRenderer(
    private val root: View,
    private val prefs: Prefs,
    private val ctx: Context,
    private val lifecycleScope: CoroutineScope,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        fun onClose()
        /** Tap on the Appearance CONFIGURE cell — open the theme/accent
         *  sub-page ([AppearanceSettingsActivity]). */
        fun openAppearanceSettings()
        /** Tap on the Hotkeys CONFIGURE cell — open [HotkeysSettingsActivity]. */
        fun openHotkeysSettings()
        /** Tap on the Capture & overlay CONFIGURE cell — open [CaptureOverlaySettingsActivity]. */
        fun openCaptureOverlaySettings()
        /** Tap on the Translation services CONFIGURE cell — open [TranslationServicesActivity]. */
        fun openTranslationServicesSettings()
        fun onSourceLangChanged()
        fun onScreenModeChanged()
        fun requestAnkiPermission()
        /** Tap on the Anki CONFIGURE cell when AnkiDroid is installed + granted
         *  — open [AnkiSettingsActivity]. */
        fun openAnkiSettings()
        fun openLanguageSetup(mode: String)
        /** Tap on the TTS "Voice" cell — open the per-language voice picker. */
        fun openTtsVoicePicker()
        /** Tap on the TTS no-engine cell — show the "No Text-to-Speech" alert. */
        fun openTtsSetup()

        /** Show an OverlayAlert explaining that [requirement] needs the
         *  accessibility service enabled. The accent button opens
         *  Accessibility Settings; the cancel button just dismisses. */
        fun showAccessibilityRequiredAlert(requirement: AccessibilityRequirement)

        /** MediaProjection backend: prompt for the screen-record consent and,
         *  on grant, bring up the floating game-screen controls. Refreshes the
         *  overlay-icon switch when the flow settles so it mirrors the result. */
        fun requestMediaProjectionControls()

        /** Tap on the "Update language packs" row in the Language section.
         *  Implementer instantiates [com.playtranslate.language.PackUpgradeOrchestrator]
         *  and calls `upgradeAll(stalePacks)`. On completion, calls
         *  [SettingsRenderer.refreshLanguageSection] so the cell hides
         *  (since `staleInstalledPacks()` is now empty). Use the **Activity's**
         *  lifecycleScope for the orchestrator coroutine — NOT the Fragment's
         *  view lifecycle scope, which would cancel the in-flight download
         *  while the OverlayProgress dialog (attached to the Activity's
         *  decorView) keeps spinning. */
        fun onUpdateLanguagePacksTapped(
            stalePacks: List<com.playtranslate.language.StalePack>
        )

        /** Tap on the "Download offline models" row. Implementer runs
         *  [com.playtranslate.language.primeActivePair] for the active pair on the
         *  Activity's lifecycleScope behind an OverlayProgress, then calls
         *  [SettingsRenderer.refreshLanguageSection] so the cell hides once ready. */
        fun onDownloadOfflineModelsTapped()
    }

    // ── View references for refresh ─────────────────────────────────────

    private val rowSourceLang: View = root.findViewById(R.id.rowSourceLang)
    private val rowTargetLang: View = root.findViewById(R.id.rowTargetLang)
    private val cardUpdateLanguagePacks: MaterialCardView = root.findViewById(R.id.cardUpdateLanguagePacks)
    private val rowUpdateLanguagePacks: View = root.findViewById(R.id.rowUpdateLanguagePacks)
    private val cardOfflineModels: MaterialCardView = root.findViewById(R.id.cardOfflineModels)
    private val rowOfflineModels: View = root.findViewById(R.id.rowOfflineModels)

    /** In-flight offline-translation readiness check (Concept B). Cancelled +
     *  restarted on each setupLanguageSection so a refresh can't race a stale
     *  result onto the card. */
    private var offlineReadinessJob: Job? = null

    private val rowOverlayIcon: View = root.findViewById(R.id.rowOverlayIcon)
    private val overlayIconPreviewSlot: FrameLayout = rowOverlayIcon.findViewById(R.id.overlayIconPreviewSlot)
    private var overlayIconPreview: FloatingOverlayIcon? = null
    // "On the floating icon" cell — text + icon tints are driven by the
    // capture lifecycle. Active: title = ptTextMuted (GroupHeader default),
    // gesture text + icons = ptText. Disabled: everything shifts down to
    // ptTextHint so the whole cell reads as a single recessed group.
    private val tvOverlayIconTitle: TextView = rowOverlayIcon.findViewById(R.id.tvRowTitle)
    private val tvGestureDrag: TextView = rowOverlayIcon.findViewById(R.id.tvGestureDrag)
    private val tvGestureHold: TextView = rowOverlayIcon.findViewById(R.id.tvGestureHold)
    private val tvGestureTap: TextView = rowOverlayIcon.findViewById(R.id.tvGestureTap)
    private val iconGestureDrag: ImageView = rowOverlayIcon.findViewById(R.id.iconGestureDrag)
    private val iconGestureHold: ImageView = rowOverlayIcon.findViewById(R.id.iconGestureHold)
    private val iconGestureTap: ImageView = rowOverlayIcon.findViewById(R.id.iconGestureTap)

    private val btnCaptureLifecycle: ShimmerButton = root.findViewById(R.id.btnCaptureLifecycle)

    // ── Power status cell (top row of the unified top-section card) ──────
    // The cell itself is the tap target; the inner switch is non-clickable
    // and follows the row tap. All per-state styling — icon block bg + stroke,
    // glyph tint, dot + halo, state-label text + colour, title + subtitle
    // copy, switch checked-ness — is applied in stylePowerCard(). The
    // divider sits between the power cell and the Game-screen-controls row
    // and is hidden in lock-step with the cell (a11y dual-screen has no
    // activate control and the cell is GONE).
    private val powerCard: ShimmerLinearLayout = root.findViewById(R.id.powerCard)
    private val powerIconBlock: FrameLayout = root.findViewById(R.id.powerIconBlock)
    private val powerIconGlyph: ImageView = root.findViewById(R.id.powerIconGlyph)
    private val powerStateHalo: View = root.findViewById(R.id.powerStateHalo)
    private val powerStateDot: View = root.findViewById(R.id.powerStateDot)
    private val powerStateLabel: TextView = root.findViewById(R.id.powerStateLabel)
    private val powerTitle: TextView = root.findViewById(R.id.powerTitle)
    private val powerSubtitle: TextView = root.findViewById(R.id.powerSubtitle)
    private val powerSwitch: MaterialSwitch = root.findViewById(R.id.powerSwitch)

    // Game Screen Controls — the floating-icon visibility toggle that takes
    // the power cell's slot on the accessibility backend in dual-screen.
    // Exactly one of {powerCard, rowGameScreenControls} is visible at a time.
    private val rowGameScreenControls: View = root.findViewById(R.id.rowGameScreenControls)
    private val switchGameScreenControls: MaterialSwitch =
        root.findViewById(R.id.switchGameScreenControls)

    private val rowDiscord: View = root.findViewById(R.id.rowDiscord)
    private val rowDonate: View = root.findViewById(R.id.rowDonate)
    private val settingsScrollView: androidx.core.widget.NestedScrollView = root.findViewById(R.id.settingsScrollView)

    private val llDebugSection: LinearLayout = root.findViewById(R.id.llDebugSection)

    private var deckEntries: List<Map.Entry<Long, String>> = emptyList()

    // ── Public entry point ───────────────────────────────────────────────

    fun bind() {
        setupGroupHeaders()
        setupCaptureLifecycleButton()
        setupGameScreenControlsRow()
        setupLanguageSection()
        setupOnScreenControls()
        setupConfigureSection()
        setupSupportSection()
        setupDebugSection()
        setupFooter()
    }

    // ── Group headers ────────────────────────────────────────────────────

    private fun setupGroupHeaders() {
        // LANGUAGE has no header — the two-cell button row sits at the top
        // of the settings sheet (above the power section, no headerLanguage
        // in dialog_settings.xml).
        // ON-SCREEN CONTROLS has no header — its card sits directly under
        // the power card as part of the top section (no headerOnScreen in
        // dialog_settings.xml).
        setGroupHeader(R.id.headerSupport, ctx.getString(R.string.settings_header_support))
        setGroupHeader(R.id.headerDebug, ctx.getString(R.string.settings_header_debug))
    }

    private fun setGroupHeader(id: Int, title: String, badge: String? = null) {
        val header = root.findViewById<View>(id) ?: return
        header.findViewById<TextView>(R.id.tvGroupTitle)?.text = title
        val badgeView = header.findViewById<TextView>(R.id.tvGroupBadge)
        if (badge != null) {
            badgeView?.text = badge
            badgeView?.visibility = View.VISIBLE
        } else {
            badgeView?.visibility = View.GONE
        }
    }

    // ── Language section ──────────────────────────────────────────────────

    private fun setupLanguageSection() {
        // Source/target names are VM-owned and applied in render(); this wires
        // only the static click targets + the stale-pack update card (the card
        // is live disk state, so it stays renderer-owned, refreshed on resume).
        rowSourceLang.setOnClickListener {
            callbacks.openLanguageSetup(LanguageSetupActivity.MODE_SOURCE)
        }
        rowTargetLang.setOnClickListener {
            callbacks.openLanguageSetup(LanguageSetupActivity.MODE_TARGET)
        }

        // "Update language packs" cell — its OWN MaterialCardView so the
        // warning tint (background fill + stroke) doesn't bleed onto the
        // Source/Target rows. Visible iff there are stale packs on disk
        // (additive or force, mixed labeling). Subtitle is the same
        // multi-line list the launch-time OverlayAlert uses.
        val activity = root.context as? android.app.Activity
        val stalePacks = if (activity != null) {
            com.playtranslate.language.LanguagePackStore.staleInstalledPacks(activity)
        } else {
            emptyList()
        }
        if (stalePacks.isEmpty() || activity == null) {
            cardUpdateLanguagePacks.isGone = true
        } else {
            cardUpdateLanguagePacks.isVisible = true
            rowUpdateLanguagePacks.findViewById<TextView>(R.id.tvRowTitle).text =
                root.context.getString(R.string.lang_section_update_packs_title)
            rowUpdateLanguagePacks.findViewById<TextView>(R.id.tvRowSubtitle).text =
                com.playtranslate.language.PackUpgradeOrchestrator
                    .describeForAlert(activity, stalePacks)
            rowUpdateLanguagePacks.setOnClickListener {
                callbacks.onUpdateLanguagePacksTapped(stalePacks)
            }
            applyWarningTint(cardUpdateLanguagePacks)
        }

        // ── Download offline models (Concept B) ──────────────────────────────
        // The readiness check is a suspend GMS round-trip (unlike the synchronous
        // stale-pack scan), so the card starts hidden and reveals only if the
        // active pair lacks offline translation. Cancel any prior in-flight check
        // so a refresh can't race an earlier result onto the card.
        offlineReadinessJob?.cancel()
        cardOfflineModels.isGone = true
        if (activity != null) {
            val sourceId = prefs.sourceLangId
            val targetLang = prefs.targetLang
            offlineReadinessJob = lifecycleScope.launch {
                val ready = withContext(Dispatchers.IO) {
                    com.playtranslate.translation.OfflineModelReclaimer
                        .isOfflineTranslationReady(sourceId, targetLang)
                }
                if (!ready) {
                    val targetName = java.util.Locale.forLanguageTag(targetLang)
                        .getDisplayLanguage(java.util.Locale.getDefault())
                        .replaceFirstChar { it.uppercase(java.util.Locale.getDefault()) }
                    cardOfflineModels.isVisible = true
                    rowOfflineModels.findViewById<TextView>(R.id.tvRowTitle).text =
                        ctx.getString(R.string.lang_section_offline_models_title)
                    rowOfflineModels.findViewById<TextView>(R.id.tvRowSubtitle).text =
                        ctx.getString(
                            R.string.lang_section_offline_models_subtitle,
                            sourceId.displayName(),
                            targetName,
                        )
                    rowOfflineModels.setOnClickListener {
                        callbacks.onDownloadOfflineModelsTapped()
                    }
                    applyWarningTint(cardOfflineModels)
                }
            }
        }
    }

    /** Tint a Language-section warning card (Update packs / Download offline
     *  models) with the attention color — blend `blendColors(warning, baseCard,
     *  0.20f)` with a full-strength stroke. One recipe so both recoverable-state
     *  warnings carry the same visual weight. */
    private fun applyWarningTint(card: MaterialCardView) {
        val baseCard = ctx.themeColor(R.attr.ptCard)
        val warning = ctx.themeColor(R.attr.ptWarning)
        card.setCardBackgroundColor(blendColors(warning, baseCard, 0.20f))
        card.strokeColor = warning
    }

    /** Public shim for the Settings cell callback to refresh the Language
     *  section after the upgrade orchestrator completes. The cell hides when
     *  staleInstalledPacks() is now empty. */
    fun refreshLanguageSection() {
        setupLanguageSection()
    }

    // ── On-screen controls ───────────────────────────────────────────────

    private fun setupOnScreenControls() {
        // "On the floating icon" — informational cell. No toggle, no click
        // handler. The floating icon's visibility is driven by the capture
        // lifecycle (and the Quick Settings tile via Prefs.showOverlayIcon)
        // — this row just teaches the user about the icon and its gestures.
        // Gesture text + icon tints come from refreshCaptureLifecycleButton().
        tvOverlayIconTitle.setText(R.string.settings_show_overlay_icon)
        overlayIconPreviewSlot.isVisible = true
        buildOverlayIconPreview()
    }


    /** Pulls the gesture string [stringRes] (which carries an inline `<b>`
     *  on the leading verb word), copies it into a SpannableStringBuilder,
     *  and overlays a [ForegroundColorSpan] of [color] across the same
     *  range as the inline BOLD — so the verb word gets the accent (or
     *  the disabled hint) colour without needing to hardcode verb-word
     *  lengths in code or duplicate the string.
     *
     *  Returns a fresh SpannableStringBuilder each call — cheap (one
     *  StyleSpan lookup, one ForegroundColorSpan applied) and avoids the
     *  resource cache reusing the same Spanned instance across refreshes
     *  with stale colours layered on. */
    private fun withVerbColored(stringRes: Int, color: Int): SpannableStringBuilder {
        val text = ctx.getText(stringRes)
        val sb = SpannableStringBuilder(text)
        val boldSpan = sb.getSpans(0, sb.length, StyleSpan::class.java)
            .firstOrNull { it.style == Typeface.BOLD }
        if (boldSpan != null) {
            val start = sb.getSpanStart(boldSpan)
            val end = sb.getSpanEnd(boldSpan)
            sb.setSpan(
                ForegroundColorSpan(color),
                start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return sb
    }


    // ── Turn On / Turn Off PlayTranslate ───────────────────────────────────────

    private fun setupCaptureLifecycleButton() {
        val onClick = View.OnClickListener {
            when {
                CaptureLifecycle.isActive(ctx) -> {
                    CaptureLifecycle.deactivate(ctx)
                    refreshCaptureLifecycleButton()
                }
                !CaptureBackendResolver.active().requiresAccessibilityService ->
                    // MediaProjection — the consent flow is Activity-scoped;
                    // the callback refreshes the buttons once it settles.
                    callbacks.requestMediaProjectionControls()
                CaptureLifecycle.activateAccessibility(ctx) -> {
                    refreshCaptureLifecycleButton()
                }
                else -> showOverlayIconA11yAlert()
            }
        }
        btnCaptureLifecycle.setOnClickListener(onClick)
        powerCard.setOnClickListener(onClick)

        // The nav-bar ShimmerButton fades in as the user scrolls past the
        // power card. The card itself does not fade — it scrolls out of view
        // naturally and the toolbar takes over as the sticky reminder.
        settingsScrollView.setOnScrollChangeListener(
            androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
                updateCaptureButtonCrossfade(scrollY)
            }
        )
        refreshCaptureLifecycleButton()
    }

    /** Wire the Game Screen Controls row — the floating-icon visibility
     *  toggle that occupies the power cell's slot on the accessibility
     *  backend in dual-screen, where capture itself is always on. Mirrors
     *  the QS tile's accessibility on/off branch so the Settings toggle and
     *  the tile stay in sync. [refreshCaptureLifecycleButton] owns the row's
     *  visibility and the switch's checked state. */
    private fun setupGameScreenControlsRow() {
        rowGameScreenControls.setOnClickListener {
            if (prefs.showOverlayIcon) {
                // Off — the canonical "icon goes away" path (writes the pref,
                // stops live mode, hides the icon, refreshes the tile), shared
                // with the QS tile and CaptureLifecycle.deactivate.
                PlayTranslateAccessibilityService.disable(
                    ctx, "settings_game_screen_controls_off"
                )
            } else {
                // On — bring the floating icon back.
                prefs.showOverlayIcon = true
                CaptureBackendResolver.activeOverlayUi?.reconcileFloatingIcons()
                PlayTranslateTileService.TileSync.refresh(ctx)
            }
            refreshCaptureLifecycleButton()
        }
    }

    /** Show / hide + style the top-slot capture controls against the current
     *  [CaptureLifecycle] state:
     *   - Power cell + nav-bar button — shown wherever there is a capture
     *     lifecycle to start / stop.
     *   - Game Screen Controls row — shown instead on the accessibility
     *     backend in dual-screen, where "active" is always true and the
     *     floating icon is the only thing left to toggle.
     *
     *  Also recolours the adjacent "On the floating icon" footer: while the
     *  icon is hidden its title + gesture text + gesture icons all drop to
     *  ptTextHint (the next-darker theme step below ptTextMuted) so the whole
     *  cell reads as a single recessed group, and the docked-icon preview
     *  drops to 50% alpha as a stronger "this is currently dark" cue.
     */
    fun refreshCaptureLifecycleButton() {
        // Exactly one of {power cell, Game Screen Controls row} occupies the
        // top slot. The power cell shows wherever there is a capture lifecycle
        // to start / stop; the Game Screen Controls row takes over on the
        // accessibility backend in dual-screen, where capture is always on.
        val showPowerCell = CaptureLifecycle.hasActivateControl(ctx)
        val powerVisibility = if (showPowerCell) View.VISIBLE else View.GONE
        btnCaptureLifecycle.visibility = powerVisibility
        powerCard.visibility = powerVisibility
        rowGameScreenControls.visibility =
            if (showPowerCell) View.GONE else View.VISIBLE
        switchGameScreenControls.isChecked = prefs.showOverlayIcon
        val active = CaptureLifecycle.isActive(ctx)
        // The floating-icon footer is lit when the icon is actually on the
        // game screen, dim otherwise. That tracks `active` everywhere except
        // a11y dual-screen, where capture is always active but the Game
        // Screen Controls toggle gates the icon — there it tracks
        // showOverlayIcon instead.
        val iconLit = if (showPowerCell) active else prefs.showOverlayIcon
        val titleColor = ctx.themeColor(
            if (iconLit) R.attr.ptTextMuted else R.attr.ptTextHint
        )
        // Gesture line has two colour zones: the rest of the line (baseColor)
        // and the leading bold verb (verbColor). Lit accents the verb +
        // matching icon; dim flattens both into the same muted hint colour as
        // the rest of the line.
        val baseColor = ctx.themeColor(
            if (iconLit) R.attr.ptText else R.attr.ptTextHint
        )
        val verbColor = ctx.themeColor(
            if (iconLit) R.attr.ptAccent else R.attr.ptTextHint
        )
        val iconTint = ColorStateList.valueOf(verbColor)
        tvOverlayIconTitle.setTextColor(titleColor)
        tvGestureDrag.text = withVerbColored(R.string.overlay_icon_gesture_drag, verbColor)
        tvGestureHold.text = withVerbColored(R.string.overlay_icon_gesture_hold, verbColor)
        tvGestureTap.text  = withVerbColored(R.string.overlay_icon_gesture_tap,  verbColor)
        tvGestureDrag.setTextColor(baseColor)
        tvGestureHold.setTextColor(baseColor)
        tvGestureTap.setTextColor(baseColor)
        iconGestureDrag.imageTintList = iconTint
        iconGestureHold.imageTintList = iconTint
        iconGestureTap.imageTintList = iconTint
        overlayIconPreviewSlot.alpha = if (iconLit) 1f else 0.5f
        if (!showPowerCell) return
        styleCaptureButton(btnCaptureLifecycle, active)
        stylePowerCard(active)
        // A refresh can land while the list is already scrolled — re-apply
        // the toolbar fade-in for the current offset.
        updateCaptureButtonCrossfade(settingsScrollView.scrollY)
    }

    /** Filled-accent ("Turn On") / outlined ("Turn Off") styling for the
     *  nav-bar ShimmerButton. The in-content power card uses a different
     *  shape entirely — see [stylePowerCard]. */
    private fun styleCaptureButton(btn: MaterialButton, active: Boolean) {
        btn.setText(
            if (active) R.string.capture_lifecycle_stop
            else R.string.capture_lifecycle_start
        )
        if (active) {
            // Outlined — the system is running.
            btn.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            btn.setTextColor(ctx.themeColor(R.attr.ptText))
            btn.iconTint = ColorStateList.valueOf(ctx.themeColor(R.attr.ptText))
            btn.strokeColor = ColorStateList.valueOf(ctx.themeColor(R.attr.ptTextMuted))
            btn.strokeWidth = (1 * ctx.resources.displayMetrics.density).toInt()
        } else {
            // Filled accent — the prominent call to action.
            btn.backgroundTintList = ColorStateList.valueOf(ctx.themeColor(R.attr.ptAccent))
            btn.setTextColor(ctx.themeColor(R.attr.ptAccentOn))
            btn.iconTint = ColorStateList.valueOf(ctx.themeColor(R.attr.ptAccentOn))
            btn.strokeWidth = 0
        }
    }

    /** Render the power status card for [active]:
     *  - Icon block: ptElevated fill / ptOutline stroke / muted glyph (Off);
     *    ptAccentTint fill / ptAccent stroke / accent glyph (On).
     *  - State label: ALL-CAPS "OFF"/"ON" in ptTextMuted/ptAccent.
     *  - LED dot: ptTextHint (Off) or ptAccent with a ptAccentTint halo (On).
     *  - Subtitle: per-state copy from strings.xml.
     *  - Switch: checked = active, driven by state (NOT optimistically — the
     *    refresh chain runs after the lifecycle call settles).
     */
    private fun stylePowerCard(active: Boolean) {
        val density = ctx.resources.displayMetrics.density

        // Icon block: rounded 12dp rect with theme-tinted fill + stroke.
        powerIconBlock.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(ctx.themeColor(if (active) R.attr.ptAccentTint else R.attr.ptElevated))
            setStroke(
                (1 * density).toInt(),
                ctx.themeColor(if (active) R.attr.ptAccent else R.attr.ptOutline),
            )
        }
        powerIconGlyph.imageTintList = ColorStateList.valueOf(
            ctx.themeColor(if (active) R.attr.ptAccent else R.attr.ptTextMuted)
        )

        // State label + LED dot + halo.
        powerStateLabel.setText(
            if (active) R.string.capture_lifecycle_state_on
            else R.string.capture_lifecycle_state_off
        )
        powerStateLabel.setTextColor(
            ctx.themeColor(if (active) R.attr.ptAccent else R.attr.ptTextMuted)
        )
        powerStateDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ctx.themeColor(if (active) R.attr.ptAccent else R.attr.ptTextHint))
        }
        if (active) {
            powerStateHalo.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ctx.themeColor(R.attr.ptAccentTint))
            }
            powerStateHalo.isVisible = true
        } else {
            powerStateHalo.visibility = View.INVISIBLE
        }

        // Title + supporting text + switch state. The title swaps with state
        // to honour the "permission, not action" framing — Off is the CTA
        // ("Allow screen capture"), On is the calm permission state
        // ("Screen capture permitted"). Subtitle mirrors: Off describes
        // what's required; On describes the resulting capability.
        powerTitle.setText(
            if (active) R.string.capture_lifecycle_on_title
            else R.string.capture_lifecycle_off_title
        )
        powerSubtitle.setText(
            if (active) R.string.capture_lifecycle_on_subtitle
            else R.string.capture_lifecycle_off_subtitle
        )
        powerSwitch.isChecked = active
    }

    /** Fade the nav-bar ShimmerButton in as the user scrolls past the power
     *  card. The card itself is never faded — it just scrolls naturally with
     *  the content. The startOffset delays the fade-in until the user has
     *  scrolled past the language picker and most of the power card. */
    private fun updateCaptureButtonCrossfade(scrollY: Int) {
        val density = ctx.resources.displayMetrics.density
        val startOffset = 100f * density
        val distance = 72f * density
        val t = ((scrollY - startOffset) / distance).coerceIn(0f, 1f)
        btnCaptureLifecycle.alpha = t
    }

    // ── Turn On/Off attention shimmer ─────────────────────────────────────
    // A brief light sweep every few seconds draws the eye to the controls
    // ONLY while capture is off. Once the user has enabled it, the accent
    // tint and LED-on state already announce themselves on both surfaces
    // (card + nav-bar button) and a sweep over them would be noise.

    private val shimmerHandler = Handler(Looper.getMainLooper())
    private val shimmerRunnable = object : Runnable {
        override fun run() {
            if (CaptureLifecycle.hasActivateControl(ctx) &&
                !CaptureLifecycle.isActive(ctx)
            ) {
                btnCaptureLifecycle.shimmer()
                powerCard.shimmer()
            }
            shimmerHandler.postDelayed(this, 5_000L)
        }
    }

    /** Begin the periodic attention shimmer on the Turn On / Turn Off control. */
    fun startCaptureButtonShimmer() {
        shimmerHandler.removeCallbacks(shimmerRunnable)
        shimmerHandler.postDelayed(shimmerRunnable, 5_000L)
    }

    /** Stop the periodic shimmer — call when the settings screen pauses. */
    fun stopCaptureButtonShimmer() {
        shimmerHandler.removeCallbacks(shimmerRunnable)
    }

    /** The accessibility-required dialog for the floating icon — shared by the
     *  dual-screen toggle and the accessibility-backend Start path. */
    private fun showOverlayIconA11yAlert() {
        AlertDialog.Builder(ctx)
            .setTitle(R.string.overlay_icon_a11y_required_title)
            .setMessage(R.string.overlay_icon_a11y_required_message)
            .setPositiveButton(R.string.btn_open_a11y_settings) { _, _ ->
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Build (or rebuild) the floating-icon preview shown in the "On the
     *  floating icon" cell. The icon view draws a full notional 56dp circle
     *  pushed off the screen edge — the 40dp-wide slot clips it to the
     *  visible quarter. Non-interactive — never hosted as a window. */
    private fun buildOverlayIconPreview() {
        destroyOverlayIconPreview()
        overlayIconPreviewSlot.removeAllViews()
        val preview = FloatingOverlayIcon(ctx).apply {
            isClickable = false
            isFocusable = false
        }
        overlayIconPreviewSlot.addView(
            preview,
            FrameLayout.LayoutParams(preview.viewSizePx, preview.viewSizePx),
        )
        overlayIconPreview = preview
    }

    /** Recycle the preview's bitmap. Call before the renderer is discarded. */
    fun destroyOverlayIconPreview() {
        overlayIconPreview?.destroy()
        overlayIconPreview = null
    }

    // ── Configure (drill-down to settings sub-pages) ─────────────────────

    /** Wire the CONFIGURE cells, each of which opens a settings sub-page. */
    private fun setupConfigureSection() {
        setGroupHeader(R.id.headerConfigure, ctx.getString(R.string.settings_header_configure))
        // The cells carry VM-driven status digests, so they're bound from
        // render(); this only sets the static group header.
    }

    /** Bind the six CONFIGURE hub cells (icon chip + title + status digest +
     *  trailing) from the VM state. Re-run on each emission — the digests are
     *  the dynamic part; icons / titles / clicks are constant. */
    private fun bindConfigureCells(state: RootSettingsViewModel.UiState) {
        bindHubCell(
            root.findViewById(R.id.rowConfigCaptureOverlay),
            HubCell(
                iconRes = R.drawable.ic_capture,
                title = ctx.getString(R.string.settings_cell_capture_overlay),
                summary = state.captureSummary,
                onClick = { callbacks.openCaptureOverlaySettings() },
            ),
        )
        bindHubCell(
            root.findViewById(R.id.rowConfigTranslationServices),
            HubCell(
                iconRes = R.drawable.ic_translate,
                title = ctx.getString(R.string.settings_cell_translation_services),
                summary = translationSummary(state),
                onClick = { callbacks.openTranslationServicesSettings() },
            ),
        )
        bindHubCell(
            root.findViewById(R.id.rowConfigHotkeys),
            HubCell(
                iconRes = R.drawable.ic_game_controller,
                title = ctx.getString(R.string.settings_cell_hotkeys),
                summary = state.hotkeysSummary,
                onClick = { callbacks.openHotkeysSettings() },
            ),
        )
        bindAnkiCell(state.anki)
        bindTtsCell(state.tts)
        bindHubCell(
            root.findViewById(R.id.rowConfigAppearance),
            HubCell(
                iconRes = R.drawable.ic_palette,
                title = ctx.getString(R.string.settings_cell_appearance),
                summary = state.appearanceSummary,
                isLast = true,
                onClick = { callbacks.openAppearanceSettings() },
            ),
        )
    }

    /** Anki cell: get-app (external) / grant (lock) / deck·card-type (chevron). */
    private fun bindAnkiCell(state: RootSettingsViewModel.AnkiCell) {
        val cell = when (state) {
            RootSettingsViewModel.AnkiCell.GetApp -> HubCell(
                iconRes = R.drawable.ic_card_stack,
                title = ctx.getString(R.string.settings_cell_anki),
                summary = ctx.getString(R.string.settings_anki_get_app_summary),
                trailing = Trailing.EXTERNAL,
                onClick = {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, ctx.getString(R.string.anki_play_store_url).toUri()),
                    )
                },
            )
            RootSettingsViewModel.AnkiCell.GrantAccess -> HubCell(
                iconRes = R.drawable.ic_card_stack,
                title = ctx.getString(R.string.settings_cell_anki),
                summary = ctx.getString(R.string.settings_anki_grant_summary),
                trailing = Trailing.LOCK,
                onClick = { callbacks.requestAnkiPermission() },
            )
            is RootSettingsViewModel.AnkiCell.Navigate -> HubCell(
                iconRes = R.drawable.ic_card_stack,
                title = ctx.getString(R.string.settings_cell_anki),
                summary = ctx.getString(R.string.settings_anki_digest, state.deckName, state.cardName),
                onClick = { callbacks.openAnkiSettings() },
            )
        }
        bindHubCell(root.findViewById(R.id.rowConfigAnki), cell)
    }

    /** TTS cell (no sub-page): engine available → "engine · voice" + voice
     *  picker; no engine → CTA + the "No Text-to-Speech" alert. */
    private fun bindTtsCell(state: RootSettingsViewModel.TtsCell) {
        val summary: CharSequence? = when (state) {
            RootSettingsViewModel.TtsCell.Loading -> null
            is RootSettingsViewModel.TtsCell.Available ->
                state.engineLabel?.let { "$it · ${state.voiceLabel}" } ?: state.voiceLabel
            RootSettingsViewModel.TtsCell.NoEngine -> ctx.getString(R.string.tts_no_engine_row_subtitle)
        }
        // Loading → ttsTapFor is null → non-interactive (see TtsTap): a tap
        // before availability resolves must not open the dead picker.
        val onClick: (() -> Unit)? = when (ttsTapFor(state)) {
            TtsTap.OPEN_PICKER -> ({ callbacks.openTtsVoicePicker() })
            TtsTap.OPEN_SETUP -> ({ callbacks.openTtsSetup() })
            null -> null
        }
        bindHubCell(
            root.findViewById(R.id.rowConfigTts),
            HubCell(
                iconRes = R.drawable.ic_text_to_speech,
                title = ctx.getString(R.string.settings_cell_tts),
                summary = summary,
                onClick = onClick,
            ),
        )
    }

    /** Translation digest: cloud + online half · cloud-off + offline half, the
     *  two glyphs inlined as muted ImageSpans ahead of each name. */
    private fun translationSummary(state: RootSettingsViewModel.UiState): CharSequence {
        val sb = SpannableStringBuilder()
        appendSummaryIcon(sb, R.drawable.ic_cloud)
        sb.append(" ").append(state.translationOnline).append(" · ")
        appendSummaryIcon(sb, R.drawable.ic_cloud_off)
        sb.append(" ").append(state.translationOffline)
        return sb
    }

    private fun appendSummaryIcon(sb: SpannableStringBuilder, @DrawableRes iconRes: Int) {
        val px = (14 * ctx.resources.displayMetrics.density).toInt()
        val drawable = ContextCompat.getDrawable(ctx, iconRes)?.mutate() ?: return
        drawable.setTint(ctx.themeColor(R.attr.ptTextMuted))
        drawable.setBounds(0, 0, px, px)
        val start = sb.length
        sb.append(" ")
        // Lift 1.5dp above ALIGN_BOTTOM to optically center; at 2dp the icons
        // read slightly high, so 1.5dp drops them ~0.5dp.
        val dy = (1.5f * ctx.resources.displayMetrics.density).toInt()
        sb.setSpan(OffsetImageSpan(drawable, dy), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    // ── Support ──────────────────────────────────────────────────────────

    private fun setupSupportSection() {
        val discordUrl = "https://go.playtranslate.com/discord"
        bindHubCell(
            rowDiscord,
            HubCell(
                iconRes = R.drawable.ic_discord,
                title = ctx.getString(R.string.settings_support_discord_title),
                summary = ctx.getString(R.string.settings_support_discord_subtitle),
                trailing = Trailing.EXTERNAL,
                onClick = { openUrl(discordUrl) },
                onLongClick = { copyUrl(discordUrl) },
            ),
        )
        bindHubCell(
            root.findViewById(R.id.rowExportLogs),
            HubCell(
                iconRes = R.drawable.ic_export_notes,
                title = ctx.getString(R.string.settings_debug_export_logs_title),
                summary = ctx.getString(R.string.settings_debug_export_logs_subtitle),
                // Same external-link affordance as the link rows — export leaves
                // the app via the system share sheet.
                trailing = Trailing.EXTERNAL,
                onClick = { exportLogs() },
            ),
        )
        val donateUrl = "https://go.playtranslate.com/donate"
        bindHubCell(
            rowDonate,
            HubCell(
                iconRes = R.drawable.ic_volunteer_activism,
                iconTint = R.attr.ptWarning,
                title = ctx.getString(R.string.settings_support_donate_title),
                summary = ctx.getString(
                    R.string.settings_support_donate_subtitle, ctx.getString(R.string.app_name),
                ),
                trailing = Trailing.EXTERNAL,
                isLast = true,
                onClick = { openUrl(donateUrl) },
                onLongClick = { copyUrl(donateUrl) },
            ),
        )
    }

    private fun openUrl(url: String) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }

    /** Long-press affordance on the external-link rows: copy the URL + toast. */
    private fun copyUrl(url: String) {
        val clipboard =
            ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL", url))
        Toast.makeText(ctx, ctx.getString(R.string.toast_link_copied), Toast.LENGTH_SHORT).show()
    }

    private fun exportLogs() {
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                runCatching {
                    val logFile = LogExporter.exportLogcat(ctx)
                    listOf(logFile) + LogExporter.getCrashFiles(ctx)
                }
            }
            files.fold(
                onSuccess = {
                    if (ctx is android.app.Activity) {
                        LogExporter.shareFiles(
                            ctx, it, ctx.getString(R.string.settings_debug_export_logs_subject),
                        )
                    }
                },
                onFailure = {
                    Toast.makeText(
                        ctx,
                        ctx.getString(R.string.settings_debug_export_logs_failed, it.javaClass.simpleName),
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

    // ── Hub cell binder ────────────────────────────────────────────────────

    enum class Trailing { CHEVRON, EXTERNAL, LOCK, NONE }

    /** A Settings hub cell: icon chip + title + status summary + trailing
     *  affordance. See design_handoff_settings_cell. */
    data class HubCell(
        @DrawableRes val iconRes: Int,
        @AttrRes val iconTint: Int = R.attr.ptAccent,
        val title: String,
        val summary: CharSequence?,
        val trailing: Trailing = Trailing.CHEVRON,
        val isLast: Boolean = false,
        /** null → the cell is non-interactive (no ripple, no action). */
        val onClick: (() -> Unit)?,
        /** Long-press action — e.g. copy the URL on external-link rows. */
        val onLongClick: (() -> Unit)? = null,
    )

    private fun bindHubCell(row: View, cell: HubCell) {
        val icon = row.findViewById<ImageView>(R.id.hubRowIcon)
        icon.setImageResource(cell.iconRes)
        icon.imageTintList = ColorStateList.valueOf(ctx.themeColor(cell.iconTint))
        row.findViewById<TextView>(R.id.hubRowTitle).text = cell.title
        val summaryView = row.findViewById<TextView>(R.id.hubRowSummary)
        if (cell.summary.isNullOrEmpty()) {
            summaryView.isGone = true
        } else {
            summaryView.text = cell.summary
            summaryView.isVisible = true
        }
        val trailing = row.findViewById<ImageView>(R.id.hubRowTrailing)
        val trailingRes = when (cell.trailing) {
            Trailing.CHEVRON -> R.drawable.ic_chevron_right
            Trailing.EXTERNAL -> R.drawable.ic_open_in_new
            Trailing.LOCK -> R.drawable.ic_lock
            Trailing.NONE -> null
        }
        if (trailingRes == null) {
            trailing.isGone = true
        } else {
            trailing.setImageResource(trailingRes)
            trailing.isVisible = true
        }
        row.findViewById<View>(R.id.hubRowDivider).isVisible = !cell.isLast
        val click = cell.onClick
        if (click != null) {
            row.isClickable = true
            row.setOnClickListener { click() }
        } else {
            row.setOnClickListener(null)
            row.isClickable = false
        }
        val longClick = cell.onLongClick
        if (longClick != null) {
            row.setOnLongClickListener { longClick(); true }
        } else {
            row.setOnLongClickListener(null)
        }
    }

    // ── Debug section ────────────────────────────────────────────────────

    private fun setupDebugSection() {
        if (!BuildConfig.DEBUG) return
        llDebugSection.isVisible = true

        // Force single screen
        val rowForceSingleScreen = root.findViewById<View>(R.id.rowForceSingleScreen)
        val switchForceSingle = rowForceSingleScreen.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        rowForceSingleScreen.findViewById<TextView>(R.id.tvRowTitle).text = ctx.getString(R.string.settings_debug_force_single_screen)
        switchForceSingle.isChecked = prefs.debugForceSingleScreen
        switchForceSingle.setOnCheckedChangeListener { _, checked ->
            prefs.debugForceSingleScreen = checked
            callbacks.onScreenModeChanged()
        }
        rowForceSingleScreen.setOnClickListener { switchForceSingle.toggle() }

        // Show OCR boxes
        val rowShowOcrBoxes = root.findViewById<View>(R.id.rowShowOcrBoxes)
        val switchOcrBoxes = rowShowOcrBoxes.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        rowShowOcrBoxes.findViewById<TextView>(R.id.tvRowTitle).text = ctx.getString(R.string.settings_debug_show_ocr_boxes)
        switchOcrBoxes.isChecked = prefs.debugShowOcrBoxes
        switchOcrBoxes.setOnCheckedChangeListener { _, checked ->
            prefs.debugShowOcrBoxes = checked
            val a11y = PlayTranslateAccessibilityService.instance
            if (checked) a11y?.startDebugOcrLoop()
            else a11y?.stopDebugOcrLoop()
        }
        rowShowOcrBoxes.setOnClickListener { switchOcrBoxes.toggle() }

        // Detection log
        val rowDetectionLog = root.findViewById<View>(R.id.rowDetectionLog)
        val switchDetLog = rowDetectionLog.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        rowDetectionLog.findViewById<TextView>(R.id.tvRowTitle).text = ctx.getString(R.string.settings_debug_show_detection_log)
        switchDetLog.isChecked = prefs.debugShowDetectionLog
        switchDetLog.setOnCheckedChangeListener { _, checked ->
            prefs.debugShowDetectionLog = checked
        }
        rowDetectionLog.setOnClickListener { switchDetLog.toggle() }

        // Live-mode debug logging
        val rowLiveModeDebug = root.findViewById<View>(R.id.rowLiveModeDebug)
        val switchLiveModeDebug = rowLiveModeDebug.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        rowLiveModeDebug.findViewById<TextView>(R.id.tvRowTitle).text = ctx.getString(R.string.settings_debug_log_pinhole)
        switchLiveModeDebug.isChecked = prefs.debugLiveMode
        switchLiveModeDebug.setOnCheckedChangeListener { _, checked ->
            prefs.debugLiveMode = checked
        }
        rowLiveModeDebug.setOnClickListener { switchLiveModeDebug.toggle() }

        // Save OCR captures as seeds (for golden-set curation)
        val rowSaveOcrSeed = root.findViewById<View>(R.id.rowSaveOcrSeed)
        val switchSaveOcrSeed = rowSaveOcrSeed.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        rowSaveOcrSeed.findViewById<TextView>(R.id.tvRowTitle).text = ctx.getString(R.string.settings_debug_save_ocr_seed)
        switchSaveOcrSeed.isChecked = prefs.debugSaveOcrSeed
        switchSaveOcrSeed.setOnCheckedChangeListener { _, checked ->
            prefs.debugSaveOcrSeed = checked
        }
        rowSaveOcrSeed.setOnClickListener { switchSaveOcrSeed.toggle() }

        // Log OCR grouping decisions (per-pair MERGE/SPLIT + numeric reason)
        val rowLogGrouping = root.findViewById<View>(R.id.rowLogGrouping)
        val switchLogGrouping = rowLogGrouping.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        rowLogGrouping.findViewById<TextView>(R.id.tvRowTitle).text = ctx.getString(R.string.settings_debug_log_grouping)
        switchLogGrouping.isChecked = prefs.debugLogGrouping
        switchLogGrouping.setOnCheckedChangeListener { _, checked ->
            prefs.debugLogGrouping = checked
            OcrManager.instance.debugLogGroupingEnabled = checked
        }
        rowLogGrouping.setOnClickListener { switchLogGrouping.toggle() }

        // OCR engine selection moved to the production Settings "OCR" section
        // (per source language; Phase 3). The debug engine picker + PaddleOCR A/B
        // rows are retired — hide their layout rows so they don't render empty.
        root.findViewById<View>(R.id.rowUsePaddleOcr).isVisible = false
        root.findViewById<View>(R.id.rowPaddleServerRec).isVisible = false
        root.findViewById<View>(R.id.rowPaddleDumpCrops).isVisible = false

        // Force crash
        val rowForceCrash = root.findViewById<View>(R.id.rowForceCrash)
        rowForceCrash.findViewById<TextView>(R.id.tvRowTitle).text = ctx.getString(R.string.settings_debug_force_crash_title)
        val btnCrash = rowForceCrash.findViewById<MaterialButton>(R.id.btnRowAction)
        btnCrash.text = ctx.getString(R.string.settings_debug_force_crash_button)
        val crashClick = View.OnClickListener {
            throw RuntimeException("Forced crash from Settings -> Debug -> Force crash")
        }
        btnCrash.setOnClickListener(crashClick)
        rowForceCrash.setOnClickListener(crashClick)
    }

    // ── Footer ───────────────────────────────────────────────────────────

    private fun setupFooter() {
        val tvFooter = root.findViewById<TextView>(R.id.tvFooterVersion) ?: return
        val appName = ctx.getString(R.string.app_name)
        tvFooter.text = ctx.getString(R.string.settings_footer_version, appName, BuildConfig.VERSION_NAME)
    }

    // ── Refresh methods (called externally) ──────────────────────────────

    /** Render the VM-owned root state: language names + every CONFIGURE cell
     *  (icon chip + title + status digest). Called by the host on each
     *  [RootSettingsViewModel] emission. The power card, stale-pack card,
     *  Support cells (static), debug + footer are NOT here. */
    fun render(state: RootSettingsViewModel.UiState) {
        rowSourceLang.findViewById<TextView>(R.id.tvSourceLangValue).text = state.sourceName
        rowTargetLang.findViewById<TextView>(R.id.tvTargetLangValue).text = state.targetName
        bindConfigureCells(state)
    }

    /** Settings's response to [Prefs.showOverlayIcon] changing externally
     *  (Quick Settings tile, accessibility service disabling it). The pref
     *  feeds [CaptureLifecycle.isActive] on the accessibility backend, so a
     *  full refresh of the lifecycle surfaces (power cell + nav-bar button +
     *  on-screen-controls dim) is what we need. */
    fun refreshOverlayIconState() {
        refreshCaptureLifecycleButton()
    }


}
