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
import com.playtranslate.AnkiManager
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
import com.playtranslate.language.SourceLangId
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
import com.playtranslate.tts.TtsEngine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.view.isVisible
import androidx.core.net.toUri
import androidx.core.view.isGone

/**
 * Wires the Online + Offline translation backend rows (extracted verbatim from
 * the old monolithic SettingsRenderer). Field names mirror the renderer's so
 * the moved methods need no internal rewiring; the host
 * [TranslationServicesActivity] supplies a [Callbacks] that runs the offline-
 * model install flows + opens the LLM/DeepL sub-screens, and drives refreshes
 * from its own lifecycle.
 */
class TranslationServicesBinder(
    private val root: View,
    private val prefs: Prefs,
    private val ctx: Context,
    private val lifecycleScope: CoroutineScope,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun startQwenMnnDownload()
        fun enableInstalledQwenMnn()
        fun showQwenMnnDisableDialog()
        fun startQwen35Mnn2bDownload()
        fun enableInstalledQwen35Mnn2b()
        fun showQwen35Mnn2bDisableDialog()
        fun startGemmaE2bMnnDownload()
        fun enableInstalledGemmaE2bMnn()
        fun showGemmaE2bMnnDisableDialog()
        fun startHyMtDownload()
        fun enableInstalledHyMt()
        fun showHyMtDisableDialog()
        fun startBergamotDownload()
        fun enableInstalledBergamot()
        fun showBergamotDisableDialog()
        fun openDeepLSettings()
        fun openLlmBackendSettings(id: BackendId)
        fun openLlmModelPicker(id: BackendId)
    }

    /** Wire all backend rows + kick off the initial status render. */
    fun bind() {
        setupTranslationServiceSection()
        refreshAllBackendStatuses()
    }

    private val rowBackendGemini: View = root.findViewById(R.id.rowBackendGemini)
    private val sectionBackendGeminiModel: View = root.findViewById(R.id.sectionBackendGeminiModel)
    private val rowBackendGeminiModel: View = root.findViewById(R.id.rowBackendGeminiModel)
    private val rowBackendOpenai: View = root.findViewById(R.id.rowBackendOpenai)
    private val sectionBackendOpenaiModel: View = root.findViewById(R.id.sectionBackendOpenaiModel)
    private val rowBackendOpenaiModel: View = root.findViewById(R.id.rowBackendOpenaiModel)
    private val rowBackendDeepseek: View = root.findViewById(R.id.rowBackendDeepseek)
    private val sectionBackendDeepseekModel: View = root.findViewById(R.id.sectionBackendDeepseekModel)
    private val rowBackendDeepseekModel: View = root.findViewById(R.id.rowBackendDeepseekModel)
    private val rowBackendDeepl: View = root.findViewById(R.id.rowBackendDeepl)
    private val rowBackendLingva: View = root.findViewById(R.id.rowBackendLingva)
    private val rowBackendGemmaE2bMnn: View = root.findViewById(R.id.rowBackendGemmaE2bMnn)
    private val dividerBackendQwenMnn: View = root.findViewById(R.id.dividerBackendQwenMnn)
    private val rowBackendQwenMnn: View = root.findViewById(R.id.rowBackendQwenMnn)
    private val dividerBackendQwen35Mnn2b: View = root.findViewById(R.id.dividerBackendQwen35Mnn2b)
    private val rowBackendQwen35Mnn2b: View = root.findViewById(R.id.rowBackendQwen35Mnn2b)
    private val dividerBackendHyMt: View = root.findViewById(R.id.dividerBackendHyMt)
    private val rowBackendHyMt: View = root.findViewById(R.id.rowBackendHyMt)
    private val dividerBackendBergamot: View = root.findViewById(R.id.dividerBackendBergamot)
    private val rowBackendBergamot: View = root.findViewById(R.id.rowBackendBergamot)
    private val rowBackendMlkit: View = root.findViewById(R.id.rowBackendMlkit)

    /** Per-backend in-flight `refreshStatus` job, keyed by [BackendId]. Used
     *  to single-flight: a new render that triggers a refresh cancels any
     *  prior refresh for the same backend so a slow request can't overwrite
     *  the result of a faster, more recent one. */
    private val backendRefreshJobs: MutableMap<BackendId, Job> = mutableMapOf()

    private fun setupTranslationServiceSection() {
        // ML Kit row: bundled, always-on fallback. No switch, not tappable.
        // The C7 offline layout's `switchOfflineToggle` stays GONE (set by
        // renderOfflineBackendRow during refreshAllBackendStatuses); the
        // stat grid + downloaded-check icon are bound there too.
        wireMlKitBackendRow()

        wireBackendSwitchRow(
            row = rowBackendLingva,
            title = ctx.getString(R.string.lingva_display_name),
            initial = prefs.lingvaEnabled,
            onChanged = { checked -> prefs.lingvaEnabled = checked },
        )

        wireGeminiBackendRow()
        wireGeminiModelRow()
        wireOpenAiBackendRow()
        wireOpenAiModelRow()
        wireDeepseekBackendRow()
        wireDeepseekModelRow()
        wireDeeplBackendRow()
        wireGemmaE2bMnnBackendRow()
        wireQwenMnnBackendRow()
        wireQwen35Mnn2bBackendRow()
        wireHyMtBackendRow()
        wireBergamotBackendRow()

        // Compose line 1 for each ONLINE backend from its metadata
        // (requiresInternet + quality), styled with mixed-color spans.
        // Offline backends use the C7 stat-grid layout instead — their
        // line-1 TextView doesn't exist; renderOfflineBackendRow binds
        // the quality/speed stars directly.
        for (backend in TranslationBackendRegistry.orderedBackends()) {
            val row = backendRowById(backend.id) ?: continue
            if (isOfflineRowBackend(backend)) continue
            setBackendLine1(row, backend)
        }

        // Render every backend's status line, kicking off async refreshes
        // for ones in Loading state. For offline backends this fully binds
        // the C7 row (title, stat grid, icon, switch, warning sub-row).
        refreshAllBackendStatuses()
    }

    /** Compose the row's line-1 subtitle from the backend's metadata —
     *  `(quality) · (speed)` for offline backends — with each part tinted
     *  by its own [Tone] via a [ForegroundColorSpan]. The TextView's base
     *  color (set by the `Text.PT.RowSubtitle` style) handles parts we
     *  don't span. The online/offline distinction itself is carried by
     *  the section header (Online vs Offline cards), and online rows skip
     *  this line entirely — the status line (line 2) carries everything
     *  the user needs for online backends (key state, usage, quota). */
    private fun setBackendLine1(row: View, backend: TranslationBackend) {
        val tv = row.findViewById<TextView>(R.id.tvRowSubtitle)
        if (backend.requiresInternet) {
            tv.isGone = true
            tv.contentDescription = null
            return
        }
        val builder = SpannableStringBuilder()

        // Quality: <stars>
        appendLabelAndStars(
            builder = builder,
            label = ctx.getString(R.string.a11y_quality_label_colon),
            rating = backend.qualityStars,
            tone = qualityTone(backend.qualityStars),
        )

        // · Speed: <stars> (when the backend supplies a speed rating)
        backend.speedStars?.let { speed ->
            builder.append(" · ")
            appendLabelAndStars(
                builder = builder,
                label = ctx.getString(R.string.a11y_speed_label),
                rating = speed,
                tone = speedTone(speed),
            )
        }

        tv.text = builder
        // Accessibility — the visible text is mostly ImageSpans over
        // single space characters, so TalkBack would only announce
        // "Quality: Speed:" without the actual ratings. The
        // contentDescription overrides that with a parallel readable
        // form like "Quality 4 out of 5 stars, Speed 2 out of 5 stars".
        tv.contentDescription = buildString {
            append(ctx.getString(R.string.a11y_quality_label))
            append(' ')
            append(formatStars(backend.qualityStars))
            append(' ')
            append(ctx.getString(R.string.a11y_out_of_5_stars))
            backend.speedStars?.let { speed ->
                append(", ")
                append(ctx.getString(R.string.a11y_speed_label_comma))
                append(' ')
                append(formatStars(speed))
                append(' ')
                append(ctx.getString(R.string.a11y_out_of_5_stars))
            }
        }
        tv.isVisible = true
    }

    /** Half-step star formatter for accessibility text:
     *  4.0 → "4", 3.5 → "3.5", 0.0 → "0". Rounds to the nearest 0.5
     *  to match how the visible stars are drawn. */
    private fun formatStars(rating: StarRating): String {
        val halfSteps = (rating * 2f).toInt().coerceIn(0, 10)
        val whole = halfSteps / 2
        val hasHalf = (halfSteps % 2) != 0
        return if (hasHalf) "$whole.5" else whole.toString()
    }

    /** Quality is read as alarming only when truly unusable. Matches
     *  the previous enum mapping (`Bad → Danger`, everything else
     *  default-tinted). */
    private fun qualityTone(stars: StarRating): Tone? = when {
        stars <= 1.0f -> Tone.Danger
        else -> null
    }

    /** Speed tones preserve the prior enum buckets:
     *  VerySlow (≈0.5 stars) → Danger, Slow (≈2 stars) → Warning,
     *  Okay/Fast (≥3 stars) → default. */
    private fun speedTone(stars: StarRating): Tone? = when {
        stars <= 0.5f -> Tone.Danger
        stars <= 2.0f -> Tone.Warning
        else -> null
    }

    /** Append "$label$stars" to [builder], tinted by [tone]. The label
     *  text gets the same color as the stars so the whole segment reads
     *  as one tinted unit. */
    private fun appendLabelAndStars(
        builder: SpannableStringBuilder,
        label: String,
        rating: StarRating,
        tone: Tone?,
    ) {
        val color: Int? = tone?.let { ctx.themeColor(toneAttr(it)) }
        val segStart = builder.length
        builder.append(label)
        builder.append(' ')
        appendStars(builder, rating, color)
        if (color != null) {
            // Cover the whole "Quality: ★★★☆☆" run so the label text
            // matches the (tinted) stars. ImageSpan handles its own
            // tint, the ForegroundColorSpan covers the text portion.
            builder.setSpan(
                ForegroundColorSpan(color),
                segStart,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    /** Append five star ImageSpans (filled / half / outlined) for
     *  [rating] clamped to 0-5. [color] tints all five drawables when
     *  non-null; otherwise the vector's default fillColor (white) is
     *  recolored to the row's muted subtitle attr so it doesn't appear
     *  jarringly white on dark themes. */
    private fun appendStars(
        builder: SpannableStringBuilder,
        rating: StarRating,
        color: Int?,
    ) {
        val halfStars = (rating * 2f).toInt().coerceIn(0, 10)
        val starSizePx = (14 * ctx.resources.displayMetrics.density).toInt()
        val tintColor = color ?: ctx.themeColor(R.attr.ptTextMuted)
        for (i in 0 until 5) {
            val starStart = i * 2
            val resId = when {
                halfStars >= starStart + 2 -> R.drawable.ic_star_filled
                halfStars >= starStart + 1 -> R.drawable.ic_star_half
                else -> R.drawable.ic_star_outline
            }
            val drawable = androidx.appcompat.content.res.AppCompatResources
                .getDrawable(ctx, resId)?.mutate() ?: continue
            androidx.core.graphics.drawable.DrawableCompat.setTint(drawable, tintColor)
            drawable.setBounds(0, 0, starSizePx, starSizePx)
            val pos = builder.length
            builder.append(" ")
            builder.setSpan(
                android.text.style.ImageSpan(drawable, android.text.style.ImageSpan.ALIGN_BASELINE),
                pos,
                pos + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun appendMaybeColored(
        builder: SpannableStringBuilder,
        text: String,
        tone: Tone?,
    ) {
        val start = builder.length
        builder.append(text)
        if (tone != null) {
            val color = ctx.themeColor(toneAttr(tone))
            builder.setSpan(
                ForegroundColorSpan(color),
                start,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    /** Refresh the DeepL switch from the current pref value. Called from
     *  [SettingsBottomSheet]'s pref-change observer after
     *  [DeepLSettingsActivity] returns. */
    fun refreshDeeplBackendSwitch() {
        rowBackendDeepl.findViewById<MaterialSwitch>(R.id.switchRowToggle)?.let {
            it.isChecked = prefs.deeplEnabled
        }
    }

    /** Refresh the Gemini switch from the current pref value AND the
     *  visibility of the inline "Model" sub-cell (only shown when the
     *  backend is enabled). Driven by the SP listener after
     *  [LlmBackendSettingsActivity] persists a save. */
    fun refreshGeminiBackendSwitch() {
        rowBackendGemini.findViewById<MaterialSwitch>(R.id.switchRowToggle)?.let {
            it.isChecked = prefs.geminiEnabled
        }
        sectionBackendGeminiModel.visibility =
            if (prefs.geminiEnabled) View.VISIBLE else View.GONE
    }

    /** Refresh the OpenAI switch from the current pref value AND the
     *  visibility of the inline "Model" sub-cell. Mirrors
     *  [refreshGeminiBackendSwitch]. */
    fun refreshOpenaiBackendSwitch() {
        rowBackendOpenai.findViewById<MaterialSwitch>(R.id.switchRowToggle)?.let {
            it.isChecked = prefs.openaiEnabled
        }
        sectionBackendOpenaiModel.visibility =
            if (prefs.openaiEnabled) View.VISIBLE else View.GONE
    }

    /** Refresh the DeepSeek switch + model sub-cell visibility. Mirrors
     *  [refreshOpenaiBackendSwitch]. */
    fun refreshDeepseekBackendSwitch() {
        rowBackendDeepseek.findViewById<MaterialSwitch>(R.id.switchRowToggle)?.let {
            it.isChecked = prefs.deepseekEnabled
        }
        sectionBackendDeepseekModel.visibility =
            if (prefs.deepseekEnabled) View.VISIBLE else View.GONE
    }

    /** Re-read the Gemini model name into the inline sub-cell's title
     *  (the row's title is the model name itself; the value column is
     *  intentionally blank). Driven by the SP listener on
     *  [Prefs.KEY_GEMINI_MODEL] so a save from [LlmModelPickerActivity]
     *  propagates here on resume. */
    fun refreshGeminiModelValue() {
        rowBackendGeminiModel.findViewById<TextView>(R.id.tvRowTitle).text = prefs.geminiModel
    }

    /** Mirrors [refreshGeminiModelValue] for OpenAI. */
    fun refreshOpenaiModelValue() {
        rowBackendOpenaiModel.findViewById<TextView>(R.id.tvRowTitle).text = prefs.openaiModel
    }

    /** Mirrors [refreshGeminiModelValue] for DeepSeek. */
    fun refreshDeepseekModelValue() {
        rowBackendDeepseekModel.findViewById<TextView>(R.id.tvRowTitle).text = prefs.deepseekModel
    }

    /** Refresh the Lingva switch from the current pref value. */
    fun refreshLingvaBackendSwitch() {
        rowBackendLingva.findViewById<MaterialSwitch>(R.id.switchRowToggle)?.let {
            it.isChecked = prefs.lingvaEnabled
        }
    }

    /** Refresh the MNN-Qwen row's switch + status icon from current pref +
     *  install state + busy state. Driven by the SP-listener observer
     *  after the Cancel branch of the disable dialog (which needs to
     *  revert the optimistic toggle) and after a successful download
     *  (which flips the pref to true). Delegates to the shared offline
     *  binder so the icon stays in sync with the switch. */
    fun refreshQwenMnnSwitch() {
        val backend = TranslationBackendRegistry.byId("qwen_mnn") ?: return
        updateOfflineStatusIconAndSwitch(rowBackendQwenMnn, backend)
    }

    /** Refresh the Qwen 3.5 2B row. Mirrors [refreshQwenMnnSwitch]. */
    fun refreshQwen35Mnn2bSwitch() {
        val backend = TranslationBackendRegistry.byId("qwen35_mnn_2b") ?: return
        updateOfflineStatusIconAndSwitch(rowBackendQwen35Mnn2b, backend)
    }

    fun refreshBergamotSwitch() {
        val backend = TranslationBackendRegistry.byId("bergamot") ?: return
        updateOfflineStatusIconAndSwitch(rowBackendBergamot, backend)
    }

    /** Refresh the Gemma E2B row. Mirrors [refreshQwenMnnSwitch]. */
    fun refreshGemmaE2bSwitch() {
        val backend = TranslationBackendRegistry.byId("gemma_e2b_mnn") ?: return
        updateOfflineStatusIconAndSwitch(rowBackendGemmaE2bMnn, backend)
    }

    /** Refresh the Hunyuan-MT row. Mirrors [refreshQwenMnnSwitch]. */
    fun refreshHyMtSwitch() {
        val backend = TranslationBackendRegistry.byId("hymt_mnn") ?: return
        updateOfflineStatusIconAndSwitch(rowBackendHyMt, backend)
    }

    /** Re-render every backend row's secondary subtitle line and kick off
     *  an async [TranslationBackend.refreshStatus] for each. Called on
     *  initial bind, on Settings resume (after [DeepLSettingsActivity]
     *  returns), and on relevant pref changes.
     *
     *  We render the cached status synchronously first (so the row shows
     *  the last known value immediately) and then trigger a background
     *  refresh that updates the row when fresh data arrives. Backends
     *  without async state (Lingva, ML Kit) inherit the default no-op
     *  [refreshStatus] that returns the same status without I/O — so
     *  always-launching is essentially free for them. */
    fun refreshAllBackendStatuses() {
        for (backend in TranslationBackendRegistry.orderedBackends()) {
            val row = backendRowById(backend.id) ?: continue
            if (isOfflineRowBackend(backend)) {
                // C7 layout — single entry point owns title/grid/icon/switch/warning.
                renderOfflineBackendRow(row, backend)
            } else {
                renderBackendStatusLine(row, backend.status)
                renderBackendCooldownLine(row, backend)
            }
            backendRefreshJobs[backend.id]?.cancel()
            backendRefreshJobs[backend.id] = lifecycleScope.launch {
                val fresh = backend.refreshStatus()
                if (isOfflineRowBackend(backend)) {
                    renderOfflineBackendRow(row, backend)
                } else {
                    renderBackendStatusLine(row, fresh)
                    renderBackendCooldownLine(row, backend)
                }
            }
        }
    }

    private fun backendRowById(id: BackendId): View? = when (id) {
        "gemini"          -> rowBackendGemini
        "openai"          -> rowBackendOpenai
        "deepseek"        -> rowBackendDeepseek
        "deepl"           -> rowBackendDeepl
        "lingva"          -> rowBackendLingva
        "gemma_e2b_mnn"   -> rowBackendGemmaE2bMnn
        "qwen_mnn"        -> rowBackendQwenMnn
        "qwen35_mnn_2b"   -> rowBackendQwen35Mnn2b
        "hymt_mnn"        -> rowBackendHyMt
        "bergamot"        -> rowBackendBergamot
        "mlkit"           -> rowBackendMlkit
        else              -> null
    }

    /** Apply a [BackendStatus] to a row's secondary subtitle TextView,
     *  styling by tone and italic flag. The Loading state has its own
     *  generic text since backends don't supply transient text. */
    private fun renderBackendStatusLine(row: View, status: BackendStatus) {
        val tv = row.findViewById<TextView>(R.id.tvRowSubtitle2) ?: return
        when (status) {
            is BackendStatus.Hidden -> tv.isGone = true
            is BackendStatus.Loading -> {
                tv.text = ctx.getString(R.string.tr_service_status_loading)
                applyTone(tv, Tone.Neutral)
                applyItalic(tv, true)
                tv.isVisible = true
            }
            is BackendStatus.Info -> {
                tv.text = status.text
                applyTone(tv, status.tone)
                applyItalic(tv, status.italic)
                tv.isVisible = true
            }
            is BackendStatus.Quota -> {
                tv.text = formatQuota(status)
                // Danger tone when the user has hit (or exceeded) their
                // limit for the period — translations will start failing
                // through to the next backend.
                val exhausted = status.used >= status.limit
                applyTone(tv, if (exhausted) Tone.Danger else Tone.Neutral)
                applyItalic(tv, false)
                tv.isVisible = true
            }
        }
    }

    /**
     * Render (or hide) the cooldown line below the existing status line.
     * Drives both the per-row text content and the warning-tinted row
     * background — when [backend] implements [Cooldownable] and reports
     * a future `retryAt`, we surface a third subtitle line with a
     * relative-or-absolute time hint and tint the row to mirror the
     * existing "Update language packs" warning recipe.
     *
     * Called from [refreshAllBackendStatuses] both synchronously and
     * after the async `refreshStatus()` completes, so the row picks up
     * cooldown expiry (`unavailableUntil` returning null after the time
     * passes) the next time Settings opens or refreshes.
     */
    private fun renderBackendCooldownLine(row: View, backend: TranslationBackend) {
        val tv = row.findViewById<TextView>(R.id.tvRowSubtitle3) ?: return
        val cooldownable = backend as? Cooldownable
        val until = cooldownable?.unavailableUntil()
        if (until == null) {
            tv.isGone = true
            clearRowWarningTint(row)
            return
        }
        val description = cooldownable.unavailableDescription()
            ?: ctx.getString(R.string.backend_status_unavailable_default)
        tv.text = formatCooldownLine(description, until)
        applyTone(tv, Tone.Warning)
        applyItalic(tv, false)
        tv.isVisible = true
        applyRowWarningTint(row)
    }

    /** "Rate limited · Retry at 3:42 PM" for short cooldowns;
     *  "Monthly quota used · Retry on Jun 1" for ones more than ~24h
     *  out. Time uses the user's locale TimeFormat; date uses a fixed
     *  "MMM d" so the line stays readable. */
    private fun formatCooldownLine(description: String, retryAt: Long): String {
        val now = System.currentTimeMillis()
        val withinDay = retryAt - now < 24L * 60 * 60 * 1000
        val formatted = if (withinDay) {
            android.text.format.DateFormat.getTimeFormat(ctx).format(Date(retryAt))
        } else {
            SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(retryAt))
        }
        val word = if (withinDay) ctx.getString(R.string.backend_cooldown_retry_at)
                   else ctx.getString(R.string.backend_cooldown_retry_on)
        return ctx.getString(R.string.backend_cooldown_status_fmt, description, word, formatted)
    }

    private fun applyRowWarningTint(row: View) {
        val baseCard = ctx.themeColor(R.attr.ptCard)
        val warning = ctx.themeColor(R.attr.ptWarning)
        val density = ctx.resources.displayMetrics.density
        // GradientDrawable here (rather than the MaterialCardView recipe
        // used by applyUpdatePacksWarningTint) because the row is a
        // LinearLayout inside an already-rounded card — applying card
        // properties would target the wrong View. Foreground stays as
        // selectableItemBackground (XML default) so the ripple still
        // works over the tinted fill.
        row.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(blendColors(warning, baseCard, 0.20f))
            setStroke((1 * density).toInt(), warning)
        }
    }

    private fun clearRowWarningTint(row: View) {
        row.background = null
    }

    private fun applyTone(tv: TextView, tone: Tone) {
        tv.setTextColor(ctx.themeColor(toneAttr(tone)))
    }

    private fun toneAttr(tone: Tone): Int = when (tone) {
        Tone.Neutral -> R.attr.ptTextHint
        Tone.Warning -> R.attr.ptWarning
        Tone.Danger  -> R.attr.ptDanger
        Tone.Accent  -> R.attr.ptAccent
    }

    private fun applyItalic(tv: TextView, italic: Boolean) {
        // Pass null for the family so only the style flag changes;
        // otherwise re-styling a previously-italicised typeface can
        // leave residual italic-ness on platforms where the styled
        // typeface gets cached. This guarantees a clean toggle.
        tv.setTypeface(null, if (italic) Typeface.ITALIC else Typeface.NORMAL)
    }

    private fun formatQuota(q: BackendStatus.Quota): String {
        val used  = String.format(Locale.getDefault(), "%,d", q.used)
        val limit = String.format(Locale.getDefault(), "%,d", q.limit)
        val base  = ctx.getString(R.string.tr_service_status_quota_fmt, used, limit)
        return q.resetEpochMs?.let { ms ->
            val date = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ms))
            ctx.getString(R.string.tr_service_status_quota_with_reset_fmt, base, date)
        } ?: base
    }

    /** Wire the DeepL row's title + tap behavior. The line-1 subtitle is
     *  composed by [setBackendLine1]; line 2 is rendered by
     *  [renderBackendStatusLine] via [refreshAllBackendStatuses]. The
     *  switch in `settings_row_backend.xml` is non-clickable; the whole
     *  row is the tap target.
     *
     *    - off → tap: open [DeepLSettingsActivity]. The activity writes
     *      `deeplEnabled=true` on Save and the pref-change listener flips
     *      the switch + retriggers status refresh.
     *    - on  → tap: directly disable (preserving the saved DeepL key
     *      so a later re-enable can prepopulate it). */
    /** ML Kit row — bundled, always-on fallback. The C7 offline layout
     *  shows the title + stat grid + downloaded-check icon via
     *  [renderOfflineBackendRow]; the switch stays GONE (user-confirmed
     *  deviation from the C7 design) and the row is not tappable. */
    private fun wireMlKitBackendRow() {
        rowBackendMlkit.isClickable = false
        rowBackendMlkit.isFocusable = false
        rowBackendMlkit.setOnClickListener(null)
    }

    /** Attach the click handler for an offline on-device-LLM row. All three
     *  callers (Qwen, Gemma E2B, Hunyuan-MT) share the same three-state
     *  branch (enabled → disable dialog · installed-disabled → enable
     *  directly · not-installed → start download); each callback is supplied
     *  by the caller. Visual binding (title, stat grid, status icon, switch
     *  state, warning sub-row, hardware-incompat replacement) lives in
     *  [renderOfflineBackendRow] and runs via [refreshAllBackendStatuses];
     *  this method only owns the row's `onClickListener`. On
     *  hardware-incompatible devices the row is left inert (the renderer
     *  hides the switch and shows the incompat reason in place of the
     *  stat grid).
     *
     *  HyMt's region gate and Cancel-revert behavior are unchanged — the
     *  caller handles the region gate before invoking this, and the
     *  Cancel branch of [onDisable] is still responsible for calling
     *  [refreshQwenMnnSwitch] / [refreshGemmaE2bSwitch] / [refreshHyMtSwitch]
     *  to revert the optimistic switch flip. */
    private fun wireOfflineLlmRow(
        row: View,
        backendId: BackendId,
        isEnabled: () -> Boolean,
        onDisable: () -> Unit,
        onEnableInstalled: () -> Unit,
        onDownload: () -> Unit,
    ) {
        val backend = TranslationBackendRegistry.byId(backendId) as? OnDeviceLlmBackend
        // Only early-return when we have a definite hardware-incompat
        // signal. If the registry can't find the backend (unexpected — but
        // historically the wiring didn't depend on it), still attach the
        // tap handler; the install check inside the click handler falls
        // back to "not installed" → download path.
        if (backend != null && !backend.meetsHardwareRequirements()) {
            row.setOnClickListener(null)
            row.isClickable = false
            return
        }
        row.setOnClickListener {
            val switch = row.findViewById<MaterialSwitch>(R.id.switchOfflineToggle)
            val installed = backend?.isInstalled() == true
            if (isEnabled()) {
                switch.isChecked = false
                onDisable()
            } else if (installed) {
                switch.isChecked = true
                onEnableInstalled()
            } else {
                onDownload()
            }
        }
    }

    private fun wireGemmaE2bMnnBackendRow() = wireOfflineLlmRow(
        row = rowBackendGemmaE2bMnn,
        backendId = "gemma_e2b_mnn",
        isEnabled = { prefs.gemmaE2bEnabled },
        onDisable = callbacks::showGemmaE2bMnnDisableDialog,
        onEnableInstalled = callbacks::enableInstalledGemmaE2bMnn,
        onDownload = callbacks::startGemmaE2bMnnDownload,
    )

    /** Hunyuan-MT 1.5 has an extra **region gate** before the standard
     *  wiring: if [com.playtranslate.region.RegionPolicy.isHunyuanRestricted]
     *  reports true (any device-region signal indicates EU/UK/SK per the
     *  Tencent HY Community License Territory definition), the row + its
     *  preceding divider are hidden entirely so the user never sees the
     *  catalog row, never gets the legal-attestation dialog, and never
     *  downloads. The legal-attestation dialog inside the download flow
     *  is the second-line gate for cases where region signals don't catch
     *  it (default-open). */
    private fun wireHyMtBackendRow() {
        if (com.playtranslate.region.RegionPolicy.isHunyuanRestricted(ctx)) {
            rowBackendHyMt.isGone = true
            dividerBackendHyMt.isGone = true
            return
        }
        wireOfflineLlmRow(
            row = rowBackendHyMt,
            backendId = "hymt_mnn",
            isEnabled = { prefs.hyMtEnabled },
            onDisable = callbacks::showHyMtDisableDialog,
            // Legal-attestation dialog does NOT re-fire when enabling an
            // already-downloaded model — once hyMtLegalAccepted is true
            // it stays true, mirroring how Meta handles the Llama ToS.
            onEnableInstalled = callbacks::enableInstalledHyMt,
            onDownload = callbacks::startHyMtDownload,
        )
    }

    private fun wireQwenMnnBackendRow() = wireOfflineLlmRow(
        row = rowBackendQwenMnn,
        backendId = "qwen_mnn",
        isEnabled = { prefs.qwenMnnEnabled },
        onDisable = callbacks::showQwenMnnDisableDialog,
        onEnableInstalled = callbacks::enableInstalledQwenMnn,
        onDownload = callbacks::startQwenMnnDownload,
    )

    private fun wireQwen35Mnn2bBackendRow() = wireOfflineLlmRow(
        row = rowBackendQwen35Mnn2b,
        backendId = "qwen35_mnn_2b",
        isEnabled = { prefs.qwen35Mnn2bEnabled },
        onDisable = callbacks::showQwen35Mnn2bDisableDialog,
        onEnableInstalled = callbacks::enableInstalledQwen35Mnn2b,
        onDownload = callbacks::startQwen35Mnn2bDownload,
    )

    /** Bergamot's row can't reuse [wireOfflineLlmRow]: install state is
     *  **per-pair** (the model for the current source→target), not the global
     *  [OnDeviceLlmBackend.isInstalled]. Hidden outright on 32-bit (the .so is
     *  arm64-only), mirroring HyMt's region gate. Otherwise the same three-state
     *  tap branch — enabled+installed → disable dialog · installed → enable ·
     *  else → download — but keyed off [offlineInstalled] for the current pair. */
    private fun wireBergamotBackendRow() {
        val bergamot = TranslationBackendRegistry.byId("bergamot") as? BergamotBackend
        if (bergamot == null || !bergamot.supportsRequiredAbi()) {
            rowBackendBergamot.isGone = true
            dividerBackendBergamot.isGone = true
            return
        }
        rowBackendBergamot.setOnClickListener {
            val switch = rowBackendBergamot.findViewById<MaterialSwitch>(R.id.switchOfflineToggle)
            val installed = offlineInstalled(bergamot)
            when {
                prefs.bergamotEnabled && installed -> {
                    switch.isChecked = false
                    callbacks.showBergamotDisableDialog()
                }
                installed -> {
                    switch.isChecked = true
                    callbacks.enableInstalledBergamot()
                }
                else -> callbacks.startBergamotDownload()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Offline backend row (settings_row_backend_offline) — C7 redesign.
    // Header row with title + status icon + switch, then a 4-column stat
    // grid (Quality | Speed | RAM | Disk). Used by MlKit + every
    // OnDeviceLlmBackend; online backends keep settings_row_backend.xml.
    // ─────────────────────────────────────────────────────────────────────

    /** Backend IDs whose download is in flight; the row swaps its status
     *  icon to an indeterminate ProgressBar while present in this set.
     *  Mutated by [setBackendDownloading], called from [SettingsBottomSheet]
     *  at download job start/end. */
    private val offlineDownloadingIds = mutableSetOf<BackendId>()

    /** True iff [backend] uses the C7 `settings_row_backend_offline` layout.
     *  Today: any [OnDeviceLlmBackend] subclass plus [MlKitBackend]. */
    private fun isOfflineRowBackend(backend: TranslationBackend): Boolean =
        backend is OnDeviceLlmBackend || backend is MlKitBackend || backend is BergamotBackend

    /** Round the half-step [StarRating] (0.0–5.0) into the [1, 5] integer
     *  bucket used for the a11y label mapping (Bad / Okay / Good / Better).
     *  Half-up rounding: 1.0→1, 2.5→3, 3.5→4, 5.0→5. Clamped to [1, 5] so
     *  we never surface "0 stars" in the spoken description (the 4 existing
     *  offline backends never emit ratings below 1.0; the clamp is
     *  defense-in-depth for a future low-rated backend). The visible stars
     *  use [toHalfSteps5] for half-step resolution; this is only the label. */
    private fun StarRating.toIntStars5(): Int =
        (this + 0.5f).toInt().coerceIn(1, 5)

    /** Convert the [StarRating] to an integer count of half-steps in [0, 10]
     *  for visible rendering. 1.0→2, 2.5→5, 3.5→7, 5.0→10. Each star slot
     *  consumes 2 half-steps (one for the left half, one for the right);
     *  [bindStarCell] maps the count to filled/half/outline drawables. */
    private fun StarRating.toHalfSteps5(): Int =
        (this * 2f).toInt().coerceIn(0, 10)

    /** Map the 1–5 star count to the existing four-label scale for the
     *  composed row contentDescription. The label set has four buckets
     *  (Bad / Okay / Good / Better), so 4 and 5 stars both surface as
     *  "Better quality" — TalkBack still reads the full numeric form via
     *  the visible stars; this is just the prose adjective. */
    @StringRes private fun qualityLabelRes(stars5: Int): Int = when (stars5) {
        1 -> R.string.tr_service_quality_bad
        2 -> R.string.tr_service_quality_okay
        3 -> R.string.tr_service_quality_good
        else -> R.string.tr_service_quality_better
    }

    @StringRes private fun speedLabelRes(stars5: Int): Int = when (stars5) {
        1 -> R.string.tr_service_speed_very_slow
        2 -> R.string.tr_service_speed_slow
        3 -> R.string.tr_service_speed_okay
        else -> R.string.tr_service_speed_fast
    }

    /** Read the per-backend `*Enabled` pref. Null for ML Kit (no pref —
     *  it's the always-on fallback). */
    private fun enabledPrefFor(backendId: BackendId): Boolean? = when (backendId) {
        "qwen_mnn"       -> prefs.qwenMnnEnabled
        "qwen35_mnn_2b"  -> prefs.qwen35Mnn2bEnabled
        "gemma_e2b_mnn"  -> prefs.gemmaE2bEnabled
        "hymt_mnn"       -> prefs.hyMtEnabled
        "bergamot"       -> prefs.bergamotEnabled
        else             -> null
    }

    /** Install state for an offline row. ML Kit is bundled (always installed);
     *  Bergamot is **per-pair** (the model for the current source+target must be
     *  present); the on-device LLM tiers are global. Centralized here so the
     *  three render call sites stay agnostic to the per-pair distinction. */
    private fun offlineInstalled(backend: TranslationBackend): Boolean = when (backend) {
        is MlKitBackend       -> true
        is BergamotBackend    -> backend.manager.isInstalled(
            SourceLanguageProfiles[prefs.sourceLangId].translationCode, prefs.targetLang)
        is OnDeviceLlmBackend -> backend.isInstalled()
        else                  -> false
    }

    /** Whether Mozilla's Bergamot model set has a path (direct or English-pivot)
     *  for the current source→target. `supportsPair == false` means no model
     *  exists for this pair, so [renderOfflineBackendRow] shows the row inert
     *  with an unsupported-pair message instead of a download affordance that
     *  silently no-ops. Source is resolved through translationCode — matching
     *  setup/runtime — so e.g. Traditional Chinese (zh-Hant) → "zh". */
    private fun bergamotPairSupported(backend: BergamotBackend): Boolean =
        backend.manager.supportsPair(
            SourceLanguageProfiles[prefs.sourceLangId].translationCode, prefs.targetLang)

    /** The id'd divider that precedes an offline row (so the deprecation gate
     *  can hide it together with the row). Null for rows with no id'd preceding
     *  divider (Gemma — first row in the card; ML Kit — id-less divider). */
    private fun dividerForOfflineRow(backendId: BackendId): View? = when (backendId) {
        "qwen_mnn"       -> dividerBackendQwenMnn
        "qwen35_mnn_2b"  -> dividerBackendQwen35Mnn2b
        "hymt_mnn"       -> dividerBackendHyMt
        "bergamot"       -> dividerBackendBergamot
        else             -> null
    }

    /** Full visual bind for an offline backend row. Idempotent — called on
     *  initial bind, on Settings resume, and after
     *  [TranslationBackend.refreshStatus] returns. Owns the entire layout:
     *  title, stat grid (or hardware-incompat replacement), status icon
     *  (or busy ProgressBar), switch state, warning sub-row, and the
     *  composed row [View.setContentDescription]. */
    private fun renderOfflineBackendRow(row: View, backend: TranslationBackend) {
        val onDeviceLlm = backend as? OnDeviceLlmBackend

        // Deprecation gate (generic — driven by CatalogEntry.deprecated via
        // OnDeviceLlmBackend.isDeprecated). A deprecated model's row is shown
        // only while the model is fully installed, so nobody can start a fresh
        // download of a retired model. Re-evaluated on every refresh (this
        // method runs from refreshAllBackendStatuses), so deleting the model
        // while Settings is open hides the row and its preceding divider.
        val deprecated = onDeviceLlm?.isDeprecated() == true
        if (deprecated) {
            val installed = offlineInstalled(backend)
            row.isGone = !installed
            dividerForOfflineRow(backend.id)?.isGone = !installed
            if (!installed) return
        }

        val rowTitle =
            if (backend is BergamotBackend) ctx.getString(R.string.bergamot_row_title)
            else backend.displayName
        val title = row.findViewById<TextView>(R.id.tvOfflineTitle)
        val header = row.findViewById<View>(R.id.offlineHeaderRow)
        title.text = rowTitle
        // Warning-colored "⚠ DEPRECATED ⚠" badge on the title line (deprecated
        // + installed rows only; the not-installed case returned above).
        row.findViewById<TextView>(R.id.tvOfflineDeprecatedBadge).isVisible = deprecated

        val isMlKit = backend is MlKitBackend

        val grid = row.findViewById<View>(R.id.cardStatGrid)
        val incompat = row.findViewById<TextView>(R.id.tvOfflineHardwareIncompat)
        val iconWrap = row.findViewById<View>(R.id.ivStatusIconWrap)
        val switch = row.findViewById<MaterialSwitch>(R.id.switchOfflineToggle)
        val warning = row.findViewById<TextView>(R.id.tvOfflineWarningLine)

        // "Visible but inert" branch — the row stays so the user sees what's
        // unavailable and why, but the stat grid + status icon + switch are
        // replaced by a single reason line. Two triggers:
        //   • on-device LLM whose device fails the hardware floor (arch / RAM)
        //   • Bergamot when Mozilla ships no model for the current source→target
        //     pair — this is per-pair, so it's re-evaluated on every refresh and
        //     the row's interactivity is toggled here (not in the one-time
        //     wiring), so switching to a supported pair re-enables the row.
        val disabledReason: String? = when {
            onDeviceLlm != null && !onDeviceLlm.meetsHardwareRequirements() ->
                onDeviceLlm.hardwareIncompatibilityReason()
            backend is BergamotBackend && !bergamotPairSupported(backend) ->
                ctx.getString(R.string.bergamot_pair_unsupported)
            else -> null
        }
        if (backend is BergamotBackend) row.isClickable = disabledReason == null
        if (disabledReason != null) {
            // Compact, recessed "disabled" presentation: collapse the header's
            // 48dp touch-target floor so the title pairs tightly with the reason
            // line, add symmetric vertical padding (the collapse otherwise
            // leaves the title flush against the row's top edge), and drop the
            // title to ptTextHint — the same recessed tone the online rows'
            // neutral status line uses — so the whole cell reads as a single
            // inactive group. (The reason line is ptTextHint via the layout.)
            header.minimumHeight = 0
            val vPad = row.paddingBottom   // 10dp from the layout
            row.setPadding(row.paddingLeft, vPad, row.paddingRight, vPad)
            title.setTextColor(ctx.themeColor(R.attr.ptTextHint))
            grid.isGone = true
            incompat.text = disabledReason
            incompat.isVisible = true
            iconWrap.isGone = true
            switch.isGone = true
            warning.isGone = true
            row.contentDescription = "$rowTitle. $disabledReason"
            return
        }

        // Enabled presentation — restore the header floor, the layout's top
        // padding (0dp; the header's height supplies the top inset), and the
        // primary title color. The row View is recycled across refreshes and
        // backends, so an earlier disabled pass may have collapsed/padded/muted
        // them.
        header.minimumHeight =
            ctx.resources.getDimensionPixelSize(R.dimen.offline_row_header_min_height)
        row.setPadding(row.paddingLeft, 0, row.paddingRight, row.paddingBottom)
        title.setTextColor(ctx.themeColor(R.attr.ptText))
        grid.isVisible = true
        incompat.isGone = true
        iconWrap.isVisible = true

        val qualityStars5 = backend.qualityStars.toIntStars5()
        bindStarCell(row.findViewById(R.id.cellQuality),
            R.string.offline_backend_quality_label,
            backend.qualityStars.toHalfSteps5())
        val speedStars5 = backend.speedStars?.toIntStars5()
        bindStarCell(row.findViewById(R.id.cellSpeed),
            R.string.offline_backend_speed_label,
            backend.speedStars?.toHalfSteps5() ?: 0)

        val ramText = when {
            backend is BergamotBackend ->
                ctx.getString(R.string.offline_backend_bergamot_ram)
            else -> onDeviceLlm?.availMemFloorBytes?.toGbDisplay()
                ?: ctx.getString(R.string.offline_backend_mlkit_ram)
        }
        val diskText = when {
            backend is BergamotBackend ->
                ctx.getString(R.string.offline_backend_bergamot_disk)
            else -> onDeviceLlm?.humanSize()
                ?: ctx.getString(R.string.offline_backend_mlkit_disk)
        }
        bindMonoCell(row.findViewById(R.id.cellRam),
            R.string.offline_backend_ram_label, ramText)
        bindMonoCell(row.findViewById(R.id.cellDisk),
            R.string.offline_backend_disk_label, diskText)

        updateOfflineStatusIconAndSwitch(row, backend)
        // ML Kit gets no switch — user-confirmed deviation from C7. GONE
        // (not INVISIBLE) so the status icon slides to the right edge
        // instead of leaving a switch-shaped gap to its right. The header
        // row's minHeight (48dp = MaterialSwitch's touch-target height)
        // keeps every offline row the same height regardless of whether a
        // switch is drawn.
        if (isMlKit) switch.isGone = true

        bindOfflineWarningLine(row, backend)
        row.contentDescription = composeOfflineRowA11y(
            backend, qualityStars5, speedStars5, ramText, diskText)
    }

    /** Render [halfSteps] (0–10) across the 5 star slots. Each slot consumes
     *  2 half-steps: if the rating reaches the slot's upper edge, render the
     *  filled drawable in ptText; if it lands on the lower edge, render the
     *  half drawable (its own two-tone colors take over — tint cleared);
     *  otherwise render the outline in ptTextDim. */
    private fun bindStarCell(cell: View, @StringRes labelRes: Int, halfSteps: Int) {
        cell.findViewById<TextView>(R.id.tvStatLabel).setText(labelRes)
        val filledTint = ColorStateList.valueOf(ctx.themeColor(R.attr.ptText))
        val emptyTint = ColorStateList.valueOf(ctx.themeColor(R.attr.ptTextDim))
        val starIds = intArrayOf(R.id.star1, R.id.star2, R.id.star3, R.id.star4, R.id.star5)
        for ((idx, id) in starIds.withIndex()) {
            val iv = cell.findViewById<ImageView>(id)
            val slotStart = idx * 2
            when {
                halfSteps >= slotStart + 2 -> {
                    iv.setImageResource(R.drawable.ic_offline_star_filled)
                    ImageViewCompat.setImageTintList(iv, filledTint)
                }
                halfSteps >= slotStart + 1 -> {
                    iv.setImageResource(R.drawable.ic_offline_star_half)
                    // Half-star drawable owns its own colors (ptText for the
                    // filled side, ptTextDim for the outline). Clear the tint
                    // so neither side is recolored to a single value.
                    ImageViewCompat.setImageTintList(iv, null)
                }
                else -> {
                    iv.setImageResource(R.drawable.ic_offline_star_outline)
                    ImageViewCompat.setImageTintList(iv, emptyTint)
                }
            }
        }
    }

    private fun bindMonoCell(cell: View, @StringRes labelRes: Int, value: String) {
        cell.findViewById<TextView>(R.id.tvStatLabel).setText(labelRes)
        cell.findViewById<TextView>(R.id.tvStatValue).text = value
    }

    /** Status icon (downloaded-check / cloud-down / busy spinner) plus the
     *  switch checked state, derived from install state + busy state +
     *  pref. Called by [renderOfflineBackendRow] for the full bind and by
     *  [setBackendDownloading] / refresh*Switch for targeted updates. */
    private fun updateOfflineStatusIconAndSwitch(row: View, backend: TranslationBackend) {
        val isMlKit = backend is MlKitBackend
        val installed = offlineInstalled(backend)
        val downloading = backend.id in offlineDownloadingIds

        val icon = row.findViewById<ImageView>(R.id.ivStatusIcon)
        val progress = row.findViewById<ProgressBar>(R.id.pbStatusDownloading)
        if (downloading) {
            icon.isGone = true
            progress.isVisible = true
        } else {
            progress.isGone = true
            icon.setImageResource(
                if (installed) R.drawable.ic_status_downloaded
                else R.drawable.ic_status_cloud_down
            )
            // Downloaded badge: the drawable owns its own colors (accent
            // disc + card-colored check), so clear the tint that the
            // layout applies for the cloud-down case. Cloud-down stays
            // muted via setImageTintList.
            ImageViewCompat.setImageTintList(
                icon,
                if (installed) null
                else ColorStateList.valueOf(ctx.themeColor(R.attr.ptTextMuted))
            )
            icon.contentDescription = ctx.getString(
                if (installed) R.string.offline_backend_downloaded_cd
                else R.string.offline_backend_not_downloaded_cd
            )
            icon.isVisible = true
        }

        if (!isMlKit) {
            val switch = row.findViewById<MaterialSwitch>(R.id.switchOfflineToggle)
            val enabledPref = enabledPrefFor(backend.id) ?: false
            switch.isChecked = installed && enabledPref
            switch.isVisible = true
        }
    }

    private fun bindOfflineWarningLine(row: View, backend: TranslationBackend) {
        val tv = row.findViewById<TextView>(R.id.tvOfflineWarningLine)
        val status = backend.status
        if (status is BackendStatus.Info && status.tone == Tone.Warning) {
            tv.text = status.text
            tv.isVisible = true
        } else {
            tv.isGone = true
        }
    }

    private fun composeOfflineRowA11y(
        backend: TranslationBackend,
        qualityStars5: Int,
        speedStars5: Int?,
        ramText: String,
        diskText: String,
    ): String {
        val isMlKit = backend is MlKitBackend
        val installed = offlineInstalled(backend)
        val downloadedLabel = ctx.getString(
            if (installed) R.string.offline_backend_downloaded_cd
            else R.string.offline_backend_not_downloaded_cd
        )
        // ML Kit has no enabled pref — treat as always-enabled fallback.
        val enabledPref = enabledPrefFor(backend.id) ?: isMlKit
        val enabledLabel = ctx.getString(
            if (enabledPref && installed) R.string.offline_backend_enabled_cd
            else R.string.offline_backend_disabled_cd
        )
        val quality = ctx.getString(qualityLabelRes(qualityStars5))
        val speed = speedStars5?.let { ctx.getString(speedLabelRes(it)) }
        return if (speed != null) {
            ctx.getString(R.string.offline_backend_row_a11y_fmt,
                backend.displayName, quality, speed, ramText, diskText,
                downloadedLabel, enabledLabel)
        } else {
            ctx.getString(R.string.offline_backend_row_a11y_no_speed_fmt,
                backend.displayName, quality, ramText, diskText,
                downloadedLabel, enabledLabel)
        }
    }

    /** Called by [SettingsBottomSheet] at download job start/end. While the
     *  ID is in [offlineDownloadingIds], the row's status icon renders as
     *  an indeterminate spinner; otherwise it falls back to the
     *  downloaded-check / cloud-down vector based on install state. */
    fun setBackendDownloading(backendId: BackendId, downloading: Boolean) {
        val changed = if (downloading) offlineDownloadingIds.add(backendId)
                      else offlineDownloadingIds.remove(backendId)
        if (!changed) return
        val row = backendRowById(backendId) ?: return
        val backend = TranslationBackendRegistry.byId(backendId) ?: return
        updateOfflineStatusIconAndSwitch(row, backend)
    }

    private fun wireDeeplBackendRow() {
        rowBackendDeepl.findViewById<TextView>(R.id.tvRowTitle).text = ctx.getString(R.string.deepl_settings_title)

        val switch = rowBackendDeepl.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        switch.isChecked = prefs.deeplEnabled

        rowBackendDeepl.setOnClickListener {
            if (prefs.deeplEnabled) {
                prefs.deeplEnabled = false
                switch.isChecked = false
            } else {
                callbacks.openDeepLSettings()
            }
        }
    }

    private fun wireGeminiBackendRow() {
        rowBackendGemini.findViewById<TextView>(R.id.tvRowTitle).text =
            ctx.getString(R.string.gemini_display_name)

        val switch = rowBackendGemini.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        switch.isChecked = prefs.geminiEnabled

        // Match the DeepL UX: tap when on → disable (preserves saved key);
        // tap when off → open the sub-screen so the user can enter / verify
        // the key. The save path in the activity flips the enabled pref.
        rowBackendGemini.setOnClickListener {
            if (prefs.geminiEnabled) {
                prefs.geminiEnabled = false
                switch.isChecked = false
            } else {
                callbacks.openLlmBackendSettings("gemini")
            }
        }
    }

    private fun wireOpenAiBackendRow() {
        rowBackendOpenai.findViewById<TextView>(R.id.tvRowTitle).text =
            ctx.getString(R.string.openai_display_name)

        val switch = rowBackendOpenai.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        switch.isChecked = prefs.openaiEnabled

        rowBackendOpenai.setOnClickListener {
            if (prefs.openaiEnabled) {
                prefs.openaiEnabled = false
                switch.isChecked = false
            } else {
                callbacks.openLlmBackendSettings("openai")
            }
        }
    }

    private fun wireGeminiModelRow() {
        // The row's title IS the model name (e.g. "gemini-2.5-flash"); the
        // value column is left blank — only the chevron carries "tap to
        // change" affordance. Title is rendered in the regular sans-serif
        // weight (not the medium baked into Text.PT.RowTitle) and the
        // row is compacted vertically so the sub-cell reads as a
        // secondary annotation rather than a peer to the backend row.
        applyModelRowChrome(rowBackendGeminiModel, prefs.geminiModel)
        rowBackendGeminiModel.setOnClickListener {
            callbacks.openLlmModelPicker("gemini")
        }
        sectionBackendGeminiModel.visibility =
            if (prefs.geminiEnabled) View.VISIBLE else View.GONE
    }

    private fun wireOpenAiModelRow() {
        applyModelRowChrome(rowBackendOpenaiModel, prefs.openaiModel)
        rowBackendOpenaiModel.setOnClickListener {
            callbacks.openLlmModelPicker("openai")
        }
        sectionBackendOpenaiModel.visibility =
            if (prefs.openaiEnabled) View.VISIBLE else View.GONE
    }

    private fun wireDeepseekBackendRow() {
        rowBackendDeepseek.findViewById<TextView>(R.id.tvRowTitle).text =
            ctx.getString(R.string.deepseek_display_name)

        val switch = rowBackendDeepseek.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        switch.isChecked = prefs.deepseekEnabled

        rowBackendDeepseek.setOnClickListener {
            if (prefs.deepseekEnabled) {
                prefs.deepseekEnabled = false
                switch.isChecked = false
            } else {
                callbacks.openLlmBackendSettings("deepseek")
            }
        }
    }

    private fun wireDeepseekModelRow() {
        applyModelRowChrome(rowBackendDeepseekModel, prefs.deepseekModel)
        rowBackendDeepseekModel.setOnClickListener {
            callbacks.openLlmModelPicker("deepseek")
        }
        sectionBackendDeepseekModel.visibility =
            if (prefs.deepseekEnabled) View.VISIBLE else View.GONE
    }

    /** Apply the compact, muted styling to an inline "Model" sub-cell.
     *  Shared between Gemini/OpenAI (and any future LLM row) so the visual
     *  treatment stays in one place. Title is rendered in the regular
     *  sans-serif weight tinted with [R.attr.ptTextMuted] — the same color
     *  the hotkey row uses for its "Not set" placeholder — so the cell
     *  reads as a secondary annotation rather than a peer to the backend
     *  row above it. */
    private fun applyModelRowChrome(row: View, modelName: String) {
        val title = row.findViewById<TextView>(R.id.tvRowTitle)
        title.text = modelName
        title.typeface = android.graphics.Typeface.SANS_SERIF
        title.setTextColor(ctx.themeColor(R.attr.ptTextMuted))
        row.findViewById<TextView>(R.id.tvRowValue).text = ""
        val density = ctx.resources.displayMetrics.density
        val hPad = ctx.resources.getDimensionPixelSize(R.dimen.pt_row_h_padding)
        val vPad = (6 * density).toInt()
        row.setPaddingRelative(hPad, vPad, hPad, vPad)
        row.minimumHeight = (48 * density).toInt()
    }

    private fun wireBackendSwitchRow(
        row: View,
        title: String,
        initial: Boolean,
        onChanged: (Boolean) -> Unit,
    ) {
        row.findViewById<TextView>(R.id.tvRowTitle).text = title

        val switch = row.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        switch.isChecked = initial
        // Single source of truth: row click toggles the switch and
        // immediately writes the pref. The switch itself stays
        // non-clickable per the layout, so we don't need a separate
        // OnCheckedChangeListener — that path can't fire for user input.
        row.setOnClickListener {
            val next = !switch.isChecked
            switch.isChecked = next
            onChanged(next)
        }
    }

    /** Variant of [wireBackendSwitchRow] for rows without an interactive
     *  toggle (the ML Kit row). Caller is responsible for hiding the
     *  switch view; this method only wires the title and removes any
     *  click handler. The line-1 subtitle is composed by [setBackendLine1]. */
    private fun wireBackendStaticRow(row: View, title: String) {
        row.findViewById<TextView>(R.id.tvRowTitle).text = title
        row.isClickable = false
        row.isFocusable = false
        row.setOnClickListener(null)
    }

}
