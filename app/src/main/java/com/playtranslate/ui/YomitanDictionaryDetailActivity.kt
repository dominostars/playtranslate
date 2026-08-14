package com.playtranslate.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.playtranslate.PlayTranslateApplication
import com.playtranslate.R
import com.playtranslate.language.SourceLangId
import com.playtranslate.themeColor
import com.playtranslate.translation.llm.humanSize
import com.playtranslate.yomitan.YomitanAutoUpdateOrchestrator
import com.playtranslate.yomitan.YomitanDictionary
import com.playtranslate.yomitan.YomitanDictionaryStore
import com.playtranslate.yomitan.YomitanUpdater
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Detail view of an installed Yomitan dictionary: an editable Configure section
 * (alias + accent color) above the read-only index.json metadata. Opened from
 * [YomitanSettingsActivity] by tapping a dictionary row. Settings sub-page
 * chrome with a back arrow in the toolbar's navigation slot.
 */
class YomitanDictionaryDetailActivity : SettingsSubPageActivity() {

    override val layoutResId: Int = R.layout.activity_yomitan_dictionary_detail

    private var dictId: String? = null

    /** Guards onPause from persisting an empty alias before the current value
     *  has loaded into the field (which would clobber an existing alias). */
    private var aliasLoaded = false

    /** Current per-dictionary accent override (ARGB), or null for the default
     *  (subtitle text color). Drives the swatch selection ring. */
    private var accentColor: Int? = null

    /** Chains auto-update toggle writes (on appScope so they survive this
     *  activity finishing) so rapid taps persist in tap order, not whichever
     *  IO write happens to win the store mutex. */
    private var autoUpdateWriteJob: Job? = null

    /** Current source-language override ([SourceLangId.code]), or null for
     *  "Any" (the dictionary stays a match-everything wildcard). Drives the
     *  Source Language row's value and the picker's checkmark. */
    private var sourceLangOverride: String? = null

    /** Returns the standalone picker's choice. Registered at construction (the
     *  ActivityResult contract requires it before onStart); the row value
     *  updates in place, so no resume-time reload is needed. */
    private val sourceLangPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null ||
            !data.hasExtra(LanguageSetupActivity.EXTRA_PICKED_CODE)
        ) {
            return@registerForActivityResult
        }
        // "" = the Any row; anything else is a SourceLangId.code.
        val picked = data.getStringExtra(LanguageSetupActivity.EXTRA_PICKED_CODE)
            ?.takeUnless { it.isEmpty() }
        if (picked == sourceLangOverride) return@registerForActivityResult
        sourceLangOverride = picked
        applySourceLangValue()
        val id = dictId ?: return@registerForActivityResult
        (application as PlayTranslateApplication).appScope.launch {
            YomitanDictionaryStore.setSourceLanguageOverride(applicationContext, id, picked)
        }
    }

    override fun onContentCreated(savedInstanceState: Bundle?) {
        val id = intent.getStringExtra(EXTRA_ID)
        if (id == null) {
            finish()
            return
        }
        dictId = id

        // Back arrow lives in the toolbar's navigation slot; the base
        // SettingsSubPageActivity already wires it to finish().
        findViewById<MaterialToolbar>(R.id.toolbar).title =
            intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.yomitan_metadata_title)

        findViewById<View>(R.id.configureHeader)
            .findViewById<TextView>(R.id.tvGroupTitle)
            .setText(R.string.settings_header_configure)
        findViewById<View>(R.id.metadataHeader)
            .findViewById<TextView>(R.id.tvGroupTitle)
            .setText(R.string.yomitan_metadata_header)

        loadConfig(id)
        render(id)
    }

    /** Persist the alias on the way out, via the application scope so the write
     *  survives this activity finishing. [YomitanDictionaryStore.setAlias]
     *  trims, blank-clears, and no-ops when unchanged. (The accent is persisted
     *  on each swatch tap, not here.) */
    override fun onPause() {
        super.onPause()
        flushAlias()
    }

    /** Persists the alias field's current text; returns the write's job (null
     *  when there is nothing to flush). [onPause] ignores it — fire-and-forget
     *  on appScope so the write survives the activity finishing. The manual
     *  update flow MUST await it: an update's swap carries whatever the
     *  registry holds at commit time, and a still-queued alias write would
     *  land after the swap against the removed old id and silently no-op,
     *  losing the edit. */
    private fun flushAlias(): Job? {
        val id = dictId ?: return null
        if (!aliasLoaded) return null
        val alias = findViewById<EditText>(R.id.etYomitanAlias).text?.toString()
        return (application as PlayTranslateApplication).appScope.launch {
            YomitanDictionaryStore.setAlias(applicationContext, id, alias)
        }
    }

    private fun loadConfig(id: String) {
        val aliasField = findViewById<EditText>(R.id.etYomitanAlias)
        lifecycleScope.launch {
            val dict = YomitanDictionaryStore.load(this@YomitanDictionaryDetailActivity)
                .dictionaries.firstOrNull { it.id == id }
            aliasField.setText(dict?.alias.orEmpty())
            aliasLoaded = true
            accentColor = dict?.accentColor
            buildAccentPicker()
            bindAutoUpdateToggle(dict)
            bindSourceLanguageRow(dict)
            bindCheckUpdatesRow(dict)
        }
    }

    /** Wires the auto-update switch. Visible ONLY for a dictionary that declares
     *  update capability (isUpdatable + an indexUrl to check); hidden otherwise,
     *  along with its divider. Default ON; the row tap toggles and persists via
     *  [YomitanDictionaryStore.setAutoUpdate]. */
    private fun bindAutoUpdateToggle(dict: YomitanDictionary?) {
        val row = findViewById<View>(R.id.rowYomitanAutoUpdate)
        val divider = findViewById<View>(R.id.autoUpdateDivider)
        val updatable = dict != null && dict.isUpdatable && dict.indexUrl != null
        row.isVisible = updatable
        divider.isVisible = updatable
        if (!updatable) return
        row.findViewById<TextView>(R.id.tvRowTitle).setText(R.string.yomitan_auto_update_label)
        row.findViewById<TextView>(R.id.tvRowSubtitle).apply {
            setText(R.string.yomitan_auto_update_subtitle)
            isVisible = true
        }
        val toggle = row.findViewById<MaterialSwitch>(R.id.switchRowToggle)
        toggle.isChecked = dict.autoUpdate
        val id = dict.id
        // The row is the tap target (the switch is non-clickable by layout
        // contract); capture the new value at tap time.
        row.setOnClickListener {
            val enabled = !toggle.isChecked
            toggle.isChecked = enabled
            val previous = autoUpdateWriteJob
            autoUpdateWriteJob = (application as PlayTranslateApplication).appScope.launch {
                previous?.join()
                YomitanDictionaryStore.setAutoUpdate(applicationContext, id, enabled)
            }
        }
    }

    /** Wires the Source Language value row. Visible ONLY for a dictionary whose
     *  index.json declares no sourceLanguage — undeclared is a match-everything
     *  wildcard, and this row narrows it to one source language ("Any" keeps
     *  the wildcard). A dictionary with a declared language needs no row: the
     *  declaration already scopes it (and always wins over the override). Taps
     *  open [LanguageSetupActivity]'s standalone pick mode; the result persists
     *  via [YomitanDictionaryStore.setSourceLanguageOverride]. */
    private fun bindSourceLanguageRow(dict: YomitanDictionary?) {
        val row = findViewById<View>(R.id.rowYomitanSourceLang)
        val divider = findViewById<View>(R.id.sourceLangDivider)
        val overridable = dict != null && dict.sourceLanguage == null
        row.isVisible = overridable
        divider.isVisible = overridable
        if (!overridable) return
        row.findViewById<TextView>(R.id.tvRowTitle)
            .setText(R.string.yomitan_source_language_label)
        sourceLangOverride = dict.sourceLanguageOverride
        applySourceLangValue()
        row.setOnClickListener {
            sourceLangPicker.launch(
                LanguageSetupActivity.pickSourceIntent(
                    this,
                    currentCode = sourceLangOverride,
                    title = getString(R.string.yomitan_source_language_label),
                ),
            )
        }
    }

    /** Renders [sourceLangOverride] into the row value: muted "Any" for the
     *  wildcard, accent-colored language name otherwise (the Anki deck-row
     *  idiom). A stored code the enum no longer knows renders raw rather than
     *  masquerading as Any — the filter would still apply it. */
    private fun applySourceLangValue() {
        val value = findViewById<View>(R.id.rowYomitanSourceLang)
            .findViewById<TextView>(R.id.tvRowValue)
        val display = SourceLangId.fromCode(sourceLangOverride)?.displayName()
            ?: sourceLangOverride
        if (display == null) {
            value.setText(R.string.lang_pick_any)
            value.setTextColor(themeColor(R.attr.ptTextMuted))
        } else {
            value.text = display
            value.setTextColor(themeColor(R.attr.ptAccent))
        }
    }

    // ── Manual update check ─────────────────────────────────────────────

    /** The in-flight manual update flow; the progress popups' Cancel hooks it,
     *  and the re-entrancy guard in [startManualUpdate] keys on it. */
    private var manualUpdateJob: Job? = null

    /** Wires the Check for Updates action row. Visible under the same condition
     *  as the auto-update toggle (declared update capability). Runs the shared
     *  per-deck update mechanism, but user-visibly: checking popup, then either
     *  an up-to-date/failed alert or an update prompt → download progress →
     *  outcome alert (the app-update UX). A deck whose rows were dropped by a
     *  schema bump gets a re-download prompt even at the same revision — the
     *  manual analog of the auto-heal pass. */
    private fun bindCheckUpdatesRow(dict: YomitanDictionary?) {
        val row = findViewById<View>(R.id.rowYomitanCheckUpdates)
        val divider = findViewById<View>(R.id.checkUpdatesDivider)
        val updatable = dict != null && dict.isUpdatable && dict.indexUrl != null
        row.isVisible = updatable
        divider.isVisible = updatable
        if (!updatable) return
        row.findViewById<TextView>(R.id.tvRowTitle)
            .setText(R.string.yomitan_check_updates_label)
        row.setOnClickListener { startManualUpdate() }
    }

    private fun startManualUpdate() {
        if (manualUpdateJob?.isActive == true) return
        val id = dictId ?: return
        // Flush a pending alias edit, then AWAIT it inside the flow before
        // touching the registry: the update's swap carries whatever the
        // registry holds at commit time, so a still-queued write would land
        // after the swap against the removed old id and the edit would be
        // lost. Awaiting also means the re-read entry (and the prompt's
        // dictionary name) already show the fresh alias.
        val aliasFlush = flushAlias()
        // LAZY + claim BEFORE start: this flow and the launch-time background
        // scan share the orchestrator's single-flight slot, so they can never
        // race the same deck's download+apply. One job spans the whole flow
        // (check → prompt → download → apply), so the claim does too.
        val job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            aliasFlush?.join()
            runManualUpdate(id)
        }
        if (!YomitanAutoUpdateOrchestrator.tryClaimSlot(job)) {
            job.cancel()
            showOutcomeAlert(
                getString(R.string.yomitan_update_busy_title),
                getString(R.string.yomitan_update_scan_active_message),
            )
            return
        }
        manualUpdateJob = job
        job.start()
    }

    private suspend fun runManualUpdate(id: String) {
        // Re-read the live entry: the page's loaded copy may be stale, and a
        // scan that ran before this claim may have already replaced the deck
        // (id gone). The check-failed alert is the honest answer then.
        val dict = YomitanDictionaryStore.load(this)
            .dictionaries.firstOrNull { it.id == id }
        if (dict == null) {
            showOutcomeAlert(
                getString(R.string.yomitan_update_check_failed_title),
                getString(R.string.yomitan_update_check_failed_message),
            )
            return
        }
        val name = dict.alias ?: dict.title

        val checkProgress = OverlayProgress.Builder(this)
            .setTitle(getString(R.string.yomitan_update_checking_title))
            .setMessage(name)
            .setOnDismiss { manualUpdateJob?.cancel() } // USER cancel/back only
            .show()
        checkProgress.setIndeterminate(true)
        val check = try {
            YomitanUpdater.checkOne(applicationContext, dict)
        } finally {
            checkProgress.dismiss() // idempotent; before any alert so scrims don't stack
        }
        when (check) {
            YomitanUpdater.ManualCheck.UpToDate -> showOutcomeAlert(
                getString(R.string.yomitan_update_none_title),
                getString(R.string.yomitan_update_none_message, name),
            )
            YomitanUpdater.ManualCheck.Failed -> showOutcomeAlert(
                getString(R.string.yomitan_update_check_failed_title),
                getString(R.string.yomitan_update_check_failed_message),
            )
            is YomitanUpdater.ManualCheck.UpdateAvailable -> {
                val confirmed = promptConfirm(
                    getString(R.string.yomitan_update_available_title),
                    getString(
                        R.string.yomitan_update_available_message,
                        name,
                        check.remote.revision?.trim().orEmpty(),
                    ),
                    getString(R.string.yomitan_update_confirm),
                )
                if (confirmed) runDownloadAndApply(dict, check.remote, name)
            }
            is YomitanUpdater.ManualCheck.RepairAvailable -> {
                val confirmed = promptConfirm(
                    getString(R.string.yomitan_update_repair_title),
                    getString(R.string.yomitan_update_repair_message, name),
                    getString(R.string.yomitan_update_redownload_confirm),
                )
                if (confirmed) runDownloadAndApply(dict, check.remote, name)
            }
        }
    }

    private suspend fun runDownloadAndApply(
        dict: YomitanDictionary,
        remote: YomitanUpdater.RemoteIndex,
        name: String,
    ) {
        val progress = OverlayProgress.Builder(this)
            .setTitle(getString(R.string.yomitan_downloading_title))
            .setMessage(name)
            .setOnDismiss { manualUpdateJob?.cancel() } // USER cancel/back only
            .show()
        // Guard against a late determinate download update clobbering the
        // apply phase's indeterminate switch — same race as the recommended
        // download (see YomitanSettingsActivity.startRecommendedDownload).
        var applying = false
        val result = try {
            YomitanUpdater.downloadAndApply(
                applicationContext,
                dict,
                remote,
                isBusy = YomitanAutoUpdateOrchestrator::isAppBusy,
                // The user's explicit tap outranks the auto-update opt-out —
                // without this, the row dead-ends on exactly the decks it is
                // most useful for (auto-update OFF, updated by hand).
                userInitiated = true,
                onProgress = { p ->
                    runOnUiThread {
                        if (!applying) {
                            progress.showYomitanDownloadProgress(
                                this, p.bytesReceived, p.totalBytes,
                            )
                        }
                    }
                },
                onApplying = {
                    // Ingest of a large deck takes a while — don't sit on a
                    // full determinate bar. Cancel is hidden because the
                    // apply is committing (prove-then-swap) and cancelling
                    // mid-commit buys nothing over letting it finish.
                    runOnUiThread {
                        applying = true
                        progress.setIndeterminate(true)
                        progress.setMessage(getString(R.string.yomitan_importing_message))
                        progress.hideCancel()
                    }
                },
            )
        } finally {
            progress.dismiss()
        }
        when (result) {
            is YomitanUpdater.ManualApply.Updated -> {
                // THE ID SWAP: the update replaced the content-derived id, so
                // this page's handle is now stale — every later write (alias
                // flush on pause, accent, source language, another check)
                // would silently no-op against the gone entry. Re-point and
                // reload; loadConfig re-binds every row on the new entry.
                dictId = result.dictionary.id
                loadConfig(result.dictionary.id)
                render(result.dictionary.id)
                // The toolbar title arrived as an intent extra and is stale
                // now too — a date-stamped deck changes TITLE every release.
                // The success alert names the NEW identity for the same reason.
                val newName = result.dictionary.alias ?: result.dictionary.title
                findViewById<MaterialToolbar>(R.id.toolbar).title = newName
                showOutcomeAlert(
                    getString(R.string.yomitan_update_done_title),
                    getString(R.string.yomitan_update_done_message, newName),
                )
            }
            YomitanUpdater.ManualApply.Deferred -> showOutcomeAlert(
                getString(R.string.yomitan_update_busy_title),
                getString(R.string.yomitan_update_busy_message),
            )
            is YomitanUpdater.ManualApply.NoSpace -> showOutcomeAlert(
                getString(R.string.yomitan_no_space_title),
                getString(
                    R.string.yomitan_no_space_message,
                    humanSize(this, result.requiredBytes),
                    humanSize(this, result.availableBytes),
                ),
            )
            // Skipped means the download was FINE but the commit refused it
            // (endpoint served a different dictionary, or the deck vanished
            // mid-update) — telling the user to check their connection would
            // be a lie, and hid the identity-guard bug on first field use.
            is YomitanUpdater.ManualApply.Skipped -> showOutcomeAlert(
                getString(R.string.yomitan_update_skipped_title),
                getString(R.string.yomitan_update_skipped_message),
            )
            YomitanUpdater.ManualApply.Failed -> showOutcomeAlert(
                getString(R.string.yomitan_download_error_title),
                getString(R.string.yomitan_download_error_message),
            )
        }
    }

    /** Suspends on an [OverlayAlert] with one confirm button + Cancel; true
     *  only on the confirm tap. Cancel tap, scrim tap, back-press, and host
     *  pause all resume false (the alert's cancel handler covers every
     *  non-confirm dismissal); the flag makes the resume exactly-once. */
    private suspend fun promptConfirm(
        title: String,
        message: String,
        confirmLabel: String,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val resumed = AtomicBoolean(false)
        fun finish(value: Boolean) {
            if (resumed.compareAndSet(false, true) && cont.isActive) cont.resume(value)
        }
        val alert = OverlayAlert.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .addButton(
                confirmLabel,
                themeColor(R.attr.ptAccent),
                themeColor(R.attr.ptAccentOn),
            ) { finish(true) }
            .addCancelButton(getString(R.string.btn_cancel)) { finish(false) }
            .show()
        cont.invokeOnCancellation { runOnUiThread { alert.dismiss() } }
    }

    private fun showOutcomeAlert(title: String, message: String) {
        OverlayAlert.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .addButton(
                getString(R.string.btn_ok),
                themeColor(R.attr.ptAccent),
                themeColor(R.attr.ptAccentOn),
            ) { }
            .show()
    }

    // ── Accent picker ───────────────────────────────────────────────────

    /** Swatch row mirroring the Appearance accent picker, with a leading
     *  "default" swatch (the subtitle text color) that clears the override. */
    private fun buildAccentPicker() {
        val container = findViewById<WrappingLinearLayout>(R.id.yomitanAccentPicker)
        container.removeAllViews()
        val dp = resources.displayMetrics.density
        container.horizontalSpacingPx = (8 * dp).toInt()
        container.verticalSpacingPx = (12 * dp).toInt()

        // Leading swatch: the subtitle text color = "default", no override.
        addSwatch(
            container,
            color = themeColor(R.attr.ptTextMuted),
            selected = accentColor == null,
            contentDescription = getString(R.string.yomitan_accent_default_cd),
        ) { selectAccent(null) }

        AccentColor.values().forEach { accent ->
            val color = ContextCompat.getColor(this, accent.color)
            addSwatch(
                container,
                color = color,
                selected = accentColor == color,
                contentDescription = getString(accent.displayName),
            ) { selectAccent(color) }
        }
    }

    private fun addSwatch(
        container: WrappingLinearLayout,
        color: Int,
        selected: Boolean,
        contentDescription: String,
        onClick: () -> Unit,
    ) {
        val dp = resources.displayMetrics.density
        val swatchSize = (48 * dp).toInt()
        val ringStroke = (2 * dp).toInt()
        val innerInset = (8 * dp).toInt()

        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            if (selected) setStroke(ringStroke, color)
        }
        val inner = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        val layered = LayerDrawable(arrayOf(ring, inner)).apply {
            setLayerInset(1, innerInset, innerInset, innerInset, innerInset)
        }
        val swatch = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(swatchSize, swatchSize)
            background = layered
            isClickable = true
            isFocusable = true
            this.contentDescription = contentDescription
            foreground = TypedValue().let { tv ->
                theme.resolveAttribute(
                    android.R.attr.selectableItemBackgroundBorderless, tv, true,
                )
                ContextCompat.getDrawable(this@YomitanDictionaryDetailActivity, tv.resourceId)
            }
            setOnClickListener { onClick() }
        }
        container.addView(swatch)
    }

    /** Apply + persist a swatch tap. null = the default (subtitle text color). */
    private fun selectAccent(color: Int?) {
        if (accentColor == color) return
        accentColor = color
        buildAccentPicker() // redraw the selection ring
        val id = dictId ?: return
        (application as PlayTranslateApplication).appScope.launch {
            YomitanDictionaryStore.setAccentColor(applicationContext, id, color)
        }
    }

    // ── Metadata ────────────────────────────────────────────────────────

    private fun render(id: String) {
        val container = findViewById<LinearLayout>(R.id.metadataRows)
        val inflater = LayoutInflater.from(this)
        lifecycleScope.launch {
            val fields = YomitanDictionaryStore.readIndexJson(this@YomitanDictionaryDetailActivity, id)
            container.removeAllViews()
            if (fields.isNullOrEmpty()) {
                val row = inflater.inflate(R.layout.item_yomitan_metadata_row, container, false)
                row.findViewById<TextView>(R.id.tvMetaLabel).isVisible = false
                row.findViewById<TextView>(R.id.tvMetaValue)
                    .setText(R.string.yomitan_metadata_unavailable)
                row.findViewById<View>(R.id.metaRowDivider).isVisible = false
                container.addView(row)
                return@launch
            }
            fields.forEachIndexed { index, (key, value) ->
                val row = inflater.inflate(R.layout.item_yomitan_metadata_row, container, false)
                row.findViewById<TextView>(R.id.tvMetaLabel).text = key
                row.findViewById<TextView>(R.id.tvMetaValue).text = value
                row.findViewById<View>(R.id.metaRowDivider).isVisible = index < fields.size - 1
                container.addView(row)
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "yomitan_dict_id"
        private const val EXTRA_TITLE = "yomitan_dict_title"

        fun intent(context: Context, id: String, title: String): Intent =
            Intent(context, YomitanDictionaryDetailActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_TITLE, title)
    }
}
