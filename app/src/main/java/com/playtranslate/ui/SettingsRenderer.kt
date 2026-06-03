package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import com.playtranslate.capturableDisplays
import com.playtranslate.capture.CaptureBackendResolver
import com.playtranslate.capture.CaptureLifecycle
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
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
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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
import com.playtranslate.compositeOver
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
    /** True when hosted by the onboarding flow (hideDismiss). The CONFIGURE
     *  drill-down is suppressed in that mode. */
    private val isOnboarding: Boolean = false,
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
    }

    // ── View references for refresh ─────────────────────────────────────

    private val rowSourceLang: View = root.findViewById(R.id.rowSourceLang)
    private val rowTargetLang: View = root.findViewById(R.id.rowTargetLang)
    private val cardUpdateLanguagePacks: MaterialCardView = root.findViewById(R.id.cardUpdateLanguagePacks)
    private val rowUpdateLanguagePacks: View = root.findViewById(R.id.rowUpdateLanguagePacks)

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
            applyUpdatePacksWarningTint()
        }
    }

    // ── Text-to-Speech section ────────────────────────────────────────────

    /** Wire the state-dependent TTS CONFIGURE cell. Unlike the other cells it
     *  has no sub-page — it behaves like the old TTS row: engine available →
     *  a cell showing the selected voice that opens the per-language voice
     *  picker; no engine → a cell whose subtitle explains the gap and whose
     *  tap shows the "No Text-to-Speech" alert. The voice / explanation share
     *  the subtitle slot. Refreshed on resume so a voice picked in the detail
     *  screen — or a newly-installed engine — shows without leaving root. */
    private fun renderTtsCell(state: RootSettingsViewModel.TtsCell) {
        if (isOnboarding) return
        val row = root.findViewById<View>(R.id.rowConfigTts) ?: return
        val title = row.findViewById<TextView>(R.id.tvRowTitle)
        val subtitle = row.findViewById<TextView>(R.id.tvRowSubtitle)
        title.text = ctx.getString(R.string.settings_cell_tts)
        when (state) {
            // Engine check in flight — leave the subtitle hidden (no flash).
            RootSettingsViewModel.TtsCell.Loading -> subtitle.isGone = true
            is RootSettingsViewModel.TtsCell.Available -> {
                subtitle.text = state.voiceLabel
                subtitle.isVisible = true
                row.setOnClickListener { callbacks.openTtsVoicePicker() }
            }
            RootSettingsViewModel.TtsCell.NoEngine -> {
                subtitle.text = ctx.getString(R.string.tts_no_engine_row_subtitle)
                subtitle.isVisible = true
                row.setOnClickListener { callbacks.openTtsSetup() }
            }
        }
    }

    /** Tint the Update language packs card with the warning attention color
     *  — blend recipe `blendColors(attention, baseCard, 0.20f)` with a full-
     *  strength stroke. Conveys "this pack is degraded; tap to fix" with a
     *  consistent visual weight for recoverable-state warnings. */
    private fun applyUpdatePacksWarningTint() {
        val baseCard = ctx.themeColor(R.attr.ptCard)
        val warning = ctx.themeColor(R.attr.ptWarning)
        cardUpdateLanguagePacks.setCardBackgroundColor(blendColors(warning, baseCard, 0.20f))
        cardUpdateLanguagePacks.strokeColor = warning
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

    /** Wire the CONFIGURE cells, each of which opens a settings sub-page.
     *  Suppressed entirely during onboarding ([isOnboarding]): the focused
     *  setup flow shouldn't branch into the sub-pages, and an Appearance
     *  change there would recreate MainActivity out from under the onboarding
     *  dialog. Cells are added here as each page's migration lands. */
    private fun setupConfigureSection() {
        val section = root.findViewById<View>(R.id.configureSection)
        if (isOnboarding) {
            section.isGone = true
            return
        }
        setGroupHeader(R.id.headerConfigure, ctx.getString(R.string.settings_header_configure))
        wireConfigureCell(R.id.rowConfigCaptureOverlay, R.string.settings_cell_capture_overlay) {
            callbacks.openCaptureOverlaySettings()
        }
        wireConfigureCell(R.id.rowConfigTranslationServices, R.string.settings_cell_translation_services) {
            callbacks.openTranslationServicesSettings()
        }
        wireConfigureCell(R.id.rowConfigHotkeys, R.string.settings_cell_hotkeys) {
            callbacks.openHotkeysSettings()
        }
        // The Anki + TTS cells are state-dependent — rendered from the VM via
        // render(), not wired here. The static nav cells above are.
        wireConfigureCell(R.id.rowConfigAppearance, R.string.settings_cell_appearance) {
            callbacks.openAppearanceSettings()
        }
    }

    /** Wire the state-dependent Anki CONFIGURE cell: AnkiDroid not installed →
     *  Play Store link; installed without permission → request the grant;
     *  installed + granted → navigate to [AnkiSettingsActivity]. Refreshed on
     *  resume + after the permission result so it flips state without leaving
     *  the root screen. */
    private fun renderAnkiCell(state: RootSettingsViewModel.AnkiCell) {
        if (isOnboarding) return
        val row = root.findViewById<View>(R.id.rowConfigAnki) ?: return
        val title = row.findViewById<TextView>(R.id.tvRowTitle)
        val subtitle = row.findViewById<TextView>(R.id.tvRowSubtitle)
        when (state) {
            RootSettingsViewModel.AnkiCell.GetApp -> {
                title.text = ctx.getString(R.string.anki_settings_get_ankidroid_title)
                subtitle.text = ctx.getString(R.string.anki_section_description, ctx.getString(R.string.app_name))
                subtitle.isVisible = true
                row.setOnClickListener {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, ctx.getString(R.string.anki_play_store_url).toUri()))
                }
            }
            RootSettingsViewModel.AnkiCell.GrantAccess -> {
                title.text = ctx.getString(R.string.settings_cell_anki)
                subtitle.text = ctx.getString(R.string.anki_settings_grant_access_subtitle, ctx.getString(R.string.app_name))
                subtitle.isVisible = true
                row.setOnClickListener { callbacks.requestAnkiPermission() }
            }
            RootSettingsViewModel.AnkiCell.Navigate -> {
                title.text = ctx.getString(R.string.settings_cell_anki)
                subtitle.isGone = true
                row.setOnClickListener { callbacks.openAnkiSettings() }
            }
        }
    }

    /** A CONFIGURE row rendered as a title + chevron nav cell (empty value). */
    private fun wireConfigureCell(rowId: Int, @StringRes titleRes: Int, onTap: () -> Unit) {
        val row = root.findViewById<View>(rowId)
        row.findViewById<TextView>(R.id.tvRowTitle).text = ctx.getString(titleRes)
        row.findViewById<TextView>(R.id.tvRowValue).text = ""
        row.setOnClickListener { onTap() }
    }

    // ── Support ──────────────────────────────────────────────────────────

    private fun setupSupportSection() {
        wireLinkRow(rowDiscord, ctx.getString(R.string.settings_support_discord_title),
            ctx.getString(R.string.settings_support_discord_subtitle),
            "https://go.playtranslate.com/discord")

        // Export logs row
        val rowExportLogs = root.findViewById<View>(R.id.rowExportLogs)
        rowExportLogs.findViewById<TextView>(R.id.tvRowTitle).text = ctx.getString(R.string.settings_debug_export_logs_title)
        val tvExportSub = rowExportLogs.findViewById<TextView>(R.id.tvRowSubtitle)
        tvExportSub.text = ctx.getString(R.string.settings_debug_export_logs_subtitle)
        tvExportSub.isVisible = true
        rowExportLogs.setOnClickListener {
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
                            LogExporter.shareFiles(ctx, it,
                                ctx.getString(R.string.settings_debug_export_logs_subject))
                        }
                    },
                    onFailure = {
                        Toast.makeText(ctx,
                            ctx.getString(R.string.settings_debug_export_logs_failed,
                                it.javaClass.simpleName),
                            Toast.LENGTH_LONG).show()
                    }
                )
            }
        }

        wireLinkRow(rowDonate, ctx.getString(R.string.settings_support_donate_title),
            ctx.getString(R.string.settings_support_donate_subtitle,
                ctx.getString(R.string.app_name)),
            "https://go.playtranslate.com/donate")
        applyDonateRowTint()
    }

    /** Tints the donate row with a soft 10% accent blend so the support CTA
     *  reads as a highlighted call-to-action without being loud. Bottom
     *  corners track the parent card's radius (donate is the last row in
     *  its card); top corners stay square so the row abuts the divider
     *  above it cleanly. The row's selectableItemBackground ripple gets
     *  moved to foreground so the custom background doesn't kill it. */
    private fun applyDonateRowTint() {
        val parentCard = rowDonate.parent?.parent as? MaterialCardView
        val radiusPx = parentCard?.radius
            ?: ctx.resources.getDimension(R.dimen.pt_radius)
        val accent = ctx.themeColor(R.attr.ptAccent)
        val card = ctx.themeColor(R.attr.ptCard)
        val effectiveDivider = compositeOver(ctx.themeColor(R.attr.ptDivider), card)
        val fill = blendColors(accent, card, 0.10f)
        val stroke = blendColors(accent, effectiveDivider, 0.10f)
        val dp = ctx.resources.displayMetrics.density
        rowDonate.background = GradientDrawable().apply {
            setColor(fill)
            setStroke((1 * dp).toInt(), stroke)
            cornerRadii = floatArrayOf(
                0f, 0f,
                0f, 0f,
                radiusPx, radiusPx,
                radiusPx, radiusPx,
            )
        }
        val ripple = ctx.obtainStyledAttributes(
            intArrayOf(android.R.attr.selectableItemBackground)
        ).run {
            val d = getDrawable(0)
            recycle()
            d
        }
        rowDonate.foreground = ripple
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

    /** Render the VM-owned root state: language names + the Anki/TTS cells.
     *  Called by the host on every [RootSettingsViewModel] emission. The power
     *  card, stale-pack card, support, debug + footer are NOT here — they're
     *  live-system-state or static and stay imperative (see the class header). */
    fun render(state: RootSettingsViewModel.UiState) {
        rowSourceLang.findViewById<TextView>(R.id.tvSourceLangValue).text = state.sourceName
        rowTargetLang.findViewById<TextView>(R.id.tvTargetLangValue).text = state.targetName
        renderAnkiCell(state.anki)
        renderTtsCell(state.tts)
    }

    /** Settings's response to [Prefs.showOverlayIcon] changing externally
     *  (Quick Settings tile, accessibility service disabling it). The pref
     *  feeds [CaptureLifecycle.isActive] on the accessibility backend, so a
     *  full refresh of the lifecycle surfaces (power cell + nav-bar button +
     *  on-screen-controls dim) is what we need. */
    fun refreshOverlayIconState() {
        refreshCaptureLifecycleButton()
    }


    // ── Private helpers ──────────────────────────────────────────────────

    /** Add a 1dp inset divider to a container (for dynamically-built row lists). */
    private fun addInsetDivider(container: LinearLayout) {
        val divider = LayoutInflater.from(ctx)
            .inflate(R.layout.settings_row_divider, container, false)
        container.addView(divider)
    }

    /** Wire an existing link row view (from <include>) with title, subtitle, and URL. */
    private fun wireLinkRow(row: View, title: String, subtitle: String, url: String) {
        row.findViewById<TextView>(R.id.tvRowTitle).text = title
        val tvSub = row.findViewById<TextView>(R.id.tvRowSubtitle)
        if (subtitle.isNotEmpty()) {
            tvSub.text = subtitle
            tvSub.isVisible = true
        }
        row.setOnClickListener {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }
        row.setOnLongClickListener {
            val clipboard =
                ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL", url))
            Toast.makeText(ctx, ctx.getString(R.string.toast_link_copied), Toast.LENGTH_SHORT).show()
            true
        }
    }

}
