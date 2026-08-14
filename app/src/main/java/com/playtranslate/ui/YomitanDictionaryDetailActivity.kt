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
import com.playtranslate.yomitan.YomitanDictionary
import com.playtranslate.yomitan.YomitanDictionaryStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
     *  "None" (the dictionary stays a match-everything wildcard). Drives the
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
        // "" = the None row; anything else is a SourceLangId.code.
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
        val id = dictId ?: return
        if (!aliasLoaded) return
        val alias = findViewById<EditText>(R.id.etYomitanAlias).text?.toString()
        (application as PlayTranslateApplication).appScope.launch {
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
     *  wildcard, and this row narrows it to one source language ("None" keeps
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

    /** Renders [sourceLangOverride] into the row value: muted "None" for the
     *  wildcard, accent-colored language name otherwise (the Anki deck-row
     *  idiom). A stored code the enum no longer knows renders raw rather than
     *  masquerading as None — the filter would still apply it. */
    private fun applySourceLangValue() {
        val value = findViewById<View>(R.id.rowYomitanSourceLang)
            .findViewById<TextView>(R.id.tvRowValue)
        val display = SourceLangId.fromCode(sourceLangOverride)?.displayName()
            ?: sourceLangOverride
        if (display == null) {
            value.setText(R.string.lang_pick_none)
            value.setTextColor(themeColor(R.attr.ptTextMuted))
        } else {
            value.text = display
            value.setTextColor(themeColor(R.attr.ptAccent))
        }
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
