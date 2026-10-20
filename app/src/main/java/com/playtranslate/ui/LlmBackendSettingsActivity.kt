package com.playtranslate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.playtranslate.R
import com.playtranslate.applyEdgeToEdge
import com.playtranslate.applyTheme
import com.playtranslate.net.CustomEndpointPolicy
import com.playtranslate.themeColor
import com.playtranslate.translation.KeyStatus
import com.playtranslate.translation.KeyValidator
import com.playtranslate.translation.OnlineBackendFactory
import com.playtranslate.translation.OnlineServiceInstance
import com.playtranslate.translation.OnlineServiceMutations
import com.playtranslate.translation.OnlineServiceStore
import com.playtranslate.translation.OpenAiPreset
import com.playtranslate.translation.ServiceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.core.view.isVisible
import androidx.core.net.toUri
import androidx.core.view.isGone

/**
 * Generic settings sub-screen for a GEMINI- or OPENAI-type service
 * instance. Two entry modes:
 *
 *  - EDIT ([editIntent]): the instance exists in [OnlineServiceStore];
 *    type/preset/URL prepopulate from its record.
 *  - CREATE ([createIntent]): launched from the add-service picker with
 *    a freshly generated instance id. **No store record exists until
 *    the first successful save** — backing out creates nothing, and the
 *    picker detects the id's appearance to dismiss itself. A picker row
 *    that stands for one preset (Index-Translate) passes it, pinned.
 *
 * OPENAI-type pages carry a Provider value cell (OpenAI / DeepSeek /
 * Custom — a dropdown of the presets the OpenAI row lists, sized to grow
 * as presets are added) and the base-URL field at the top of the main
 * card. Pinned presets fill and disable the URL field; Custom clears it
 * for free entry behind a "bring your own backend" hint. The preset names
 * the instance's cell on the services page. A preset with its own picker
 * row shows neither: it is what the instance is.
 *
 * A provider whose requests can choose thinking on or off (DeepSeek,
 * [OpenAiPreset.thinkingSwitch]) gets a Thinking switch row under the key
 * section, off by default, saved as the instance's thinking flag. The row
 * follows the Provider dropdown like the rest of the preset chrome.
 *
 * UX contract (unchanged from the legacy per-service screen):
 *  - The toolbar X discards in-progress edits. A recreation (rotation,
 *    theme change) keeps them: the typed key and URL through their own
 *    view state, the Provider pick and the Thinking flip through
 *    [onSaveInstanceState].
 *  - Save validates the typed key against the provider BEFORE
 *    persisting — against a shell backend built from the CURRENT page
 *    state, so a preset switched but not yet saved validates against
 *    the right endpoint. Every input is frozen until the probe answers,
 *    so what the screen shows is what gets saved ([setLoading]). On
 *    Ok/Unreachable the instance is persisted (enabled) and the screen
 *    finishes; on Invalid an OverlayAlert explains and nothing is written.
 *  - No key to send: a provider that takes none (its page shows a note
 *    instead of the key field) is saved enabled with no probe; a blank key
 *    where one is required clears it and disables the instance in EDIT,
 *    and creates nothing in CREATE.
 */
class LlmBackendSettingsActivity : AppCompatActivity() {

    private lateinit var config: LlmBackendConfig
    private lateinit var toolbar: MaterialToolbar
    private lateinit var etApiKey: EditText
    private lateinit var btnSave: MaterialButton
    private lateinit var progressSave: ProgressBar
    private lateinit var switchThinking: MaterialSwitch

    private lateinit var instanceId: String
    private lateinit var type: ServiceType
    private var preset = OpenAiPreset.OPENAI

    /** The instance's persisted custom URL (blank unless it was saved on
     *  the CUSTOM preset). Selecting Custom restores this rather than
     *  leaving a canonical provider URL in the field — a fresh Custom
     *  pick shows an empty field with the "bring your own backend" hint. */
    private var savedCustomUrl = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme(this)
        applyEdgeToEdge(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_llm_backend_settings)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(sys.left, sys.top, sys.right, maxOf(sys.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID)
            ?: error("LlmBackendSettingsActivity launched without EXTRA_INSTANCE_ID")
        val existing = OnlineServiceStore.byId(instanceId)
        type = existing?.type
            ?: ServiceType.valueOf(
                intent.getStringExtra(EXTRA_TYPE)
                    ?: error("CREATE launch without EXTRA_TYPE")
            )
        // The page's own unsaved pick first, on a recreation (a rotation
        // mid-edit must not drop it); then the record's preset in EDIT;
        // then, in CREATE, the picker row's pinned preset, read from the
        // intent so a recreation keeps it.
        preset = savedInstanceState?.getString(STATE_PRESET)?.let { OpenAiPreset.valueOf(it) }
            ?: existing?.preset
            ?: intent.getStringExtra(EXTRA_PRESET)?.let { OpenAiPreset.valueOf(it) }
            ?: OpenAiPreset.OPENAI
        savedCustomUrl = if (existing?.preset == OpenAiPreset.CUSTOM) existing.baseUrl else ""
        config = LlmBackendConfigs.forInstance(this, instanceId, type, preset)

        toolbar = findViewById(R.id.toolbar)
        toolbar.title = config.displayName
        toolbar.setNavigationOnClickListener { finish() }

        etApiKey = findViewById(R.id.etApiKey)
        etApiKey.hint = config.keyHint
        etApiKey.setText(config.getKey())
        etApiKey.setSelection(etApiKey.text.length)

        wireGetKeyLink(findViewById(R.id.rowGetKeyLink))
        wireProviderSection()
        applyKeyRequirement()
        wireThinkingRow(existing, savedInstanceState)

        btnSave = findViewById(R.id.btnSave)
        progressSave = findViewById(R.id.progressSave)
        btnSave.setOnClickListener { onSave() }
    }

    /** The page's unsaved state that no view saves for itself: the
     *  Provider pick and the Thinking flip. The key and URL fields save
     *  their own text by id; the switch row's layout turns that off
     *  (every copy shares one id), so the host keeps it. */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PRESET, preset.name)
        outState.putBoolean(STATE_THINKING, switchThinking.isChecked)
    }

    private fun wireGetKeyLink(row: View) {
        row.findViewById<TextView>(R.id.tvRowTitle).text =
            getString(R.string.llm_backend_get_key_title_fmt, config.displayName)
        val tvSub = row.findViewById<TextView>(R.id.tvRowSubtitle)
        tvSub.text = config.getKeyUrl
        tvSub.isVisible = true
        row.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, config.getKeyUrl.toUri()))
        }
        row.setOnLongClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("URL", config.getKeyUrl))
            Toast.makeText(this, getString(R.string.toast_link_copied), Toast.LENGTH_SHORT).show()
            true
        }
    }

    /** Provider preset + base URL rows at the top of the main card —
     *  OPENAI-type only; GONE for Gemini. */
    private fun wireProviderSection() {
        val section = findViewById<View>(R.id.sectionProviderUrl)
        if (!config.allowsBaseUrl) {
            section.isVisible = false
            return
        }
        val row = findViewById<View>(R.id.rowProvider)
        row.findViewById<TextView>(R.id.tvRowTitle).text =
            getString(R.string.llm_backend_provider_label)
        refreshProviderValue()
        row.setOnClickListener { showProviderDropdown(row) }
        applyPresetToUrlField()
    }

    /** Key field + get-a-key link for a provider that takes a key; its
     *  note in their place for one that doesn't. Both read the page
     *  config, so the two can never show together for a keyless provider. */
    private fun applyKeyRequirement() {
        findViewById<View>(R.id.sectionApiKey).isVisible = config.account.requiresKey
        findViewById<TextView>(R.id.tvProviderNote).apply {
            val note = config.noteRes
            isVisible = note != null
            if (note != null) setText(note)
        }
    }

    /** The Thinking switch row, set from the page's own unsaved flip on a
     *  recreation, else from the saved record (off for a new instance),
     *  and shown only where the page's provider offers the choice
     *  ([applyThinkingRow]). Nothing persists until Save. */
    private fun wireThinkingRow(existing: OnlineServiceInstance?, savedInstanceState: Bundle?) {
        val row = findViewById<View>(R.id.rowThinking)
        row.findViewById<TextView>(R.id.tvRowTitle).setText(R.string.llm_backend_thinking_title)
        row.findViewById<TextView>(R.id.tvRowSubtitle).apply {
            setText(R.string.llm_backend_thinking_subtitle)
            isVisible = true
        }
        switchThinking = row.findViewById(R.id.switchRowToggle)
        switchThinking.isChecked = savedInstanceState?.getBoolean(STATE_THINKING)
            ?: existing?.thinking ?: false
        row.setOnClickListener { switchThinking.toggle() }
        applyThinkingRow()
    }

    /** The Thinking row shows exactly where the page's provider lets a
     *  request choose; it reads the page config, so it moves with the
     *  Provider dropdown. */
    private fun applyThinkingRow() {
        findViewById<View>(R.id.sectionThinking).isVisible = config.thinkingSwitch
    }

    /** Anchored dropdown of the presets the OpenAI row lists — a menu
     *  rather than a segmented control so the list can grow (more
     *  providers are planned). */
    private fun showProviderDropdown(anchor: View) {
        val popup = PopupMenu(this, anchor, Gravity.END)
        providerMenuItems().forEachIndexed { idx, (itemId, p) ->
            popup.menu.add(0, itemId, idx, presetLabel(p))
        }
        popup.setOnMenuItemClickListener { item ->
            applyPreset(providerMenuPreset(item.itemId))
            true
        }
        popup.show()
    }

    private fun presetLabel(p: OpenAiPreset): String =
        OnlineBackendFactory.presetDisplayName(this, p)

    private fun refreshProviderValue() {
        findViewById<View>(R.id.rowProvider)
            .findViewById<TextView>(R.id.tvRowValue).text = presetLabel(preset)
    }

    /** Dropdown selection changed: rebind the preset-derived chrome
     *  (title, get-key link, provider value, URL field state). Nothing
     *  persists until Save. */
    private fun applyPreset(newPreset: OpenAiPreset) {
        preset = newPreset
        config = LlmBackendConfigs.forInstance(this, instanceId, type, preset)
        toolbar.title = config.displayName
        // The key placeholder is preset-derived too (only some providers
        // have a key prefix worth showing), so it moves with the dropdown.
        etApiKey.hint = config.keyHint
        wireGetKeyLink(findViewById(R.id.rowGetKeyLink))
        refreshProviderValue()
        applyPresetToUrlField()
        applyKeyRequirement()
        applyThinkingRow()
    }

    private fun applyPresetToUrlField() {
        val etBaseUrl = findViewById<EditText>(R.id.etBaseUrl)
        if (preset == OpenAiPreset.CUSTOM) {
            etBaseUrl.hint = getString(R.string.llm_backend_base_url_custom_hint)
            // The saved custom URL when this instance has one; otherwise
            // empty so the hint invites the user's own endpoint (never a
            // prefilled canonical provider URL).
            etBaseUrl.setText(savedCustomUrl)
            etBaseUrl.isEnabled = true
            etBaseUrl.setTextColor(themeColor(R.attr.ptText))
        } else {
            etBaseUrl.hint = null
            etBaseUrl.setText(config.presetBaseUrl)
            etBaseUrl.isEnabled = false
            etBaseUrl.setTextColor(themeColor(R.attr.ptTextMuted))
        }
        etBaseUrl.setSelection(etBaseUrl.text.length)
    }

    /** Toggle the Save button's loading state. While loading, the
     *  button text is blanked + click suppressed and the centered
     *  ProgressBar overlays it, and every input on the page is disabled:
     *  the key field, the Provider row, the Custom URL field and the
     *  Thinking row. The instance under validation was read off the page
     *  when Save was tapped and the probe then suspends, so an edit landing
     *  during it would show on screen and not be the thing saved, and a
     *  provider switched mid-probe would be saved with a key validated
     *  against the old one. The URL field's enabled state belongs to the
     *  preset rule ([applyPresetToUrlField]): it comes back only on Custom. */
    private fun setLoading(loading: Boolean) {
        if (loading) {
            btnSave.text = ""
            progressSave.isVisible = true
        } else {
            btnSave.text = getString(R.string.deepl_settings_save)
            progressSave.isGone = true
        }
        val editable = !loading
        btnSave.isEnabled = editable
        etApiKey.isEnabled = editable
        findViewById<View>(R.id.rowProvider).isEnabled = editable
        findViewById<View>(R.id.etBaseUrl).isEnabled = editable && preset == OpenAiPreset.CUSTOM
        findViewById<View>(R.id.rowThinking).isEnabled = editable
        switchThinking.isEnabled = editable
    }

    private fun onSave() {
        val key = etApiKey.text.toString().trim()

        // Custom base URL: format-validate up front but DON'T persist yet
        // — everything commits atomically after a passing validation.
        // Pinned presets resolve their canonical URL at translate time,
        // so there is nothing to check for them.
        if (config.allowsBaseUrl && preset == OpenAiPreset.CUSTOM) {
            val etBaseUrl = findViewById<EditText>(R.id.etBaseUrl)
            val err = validateBaseUrl(etBaseUrl.text.toString().trim())
            if (err != null) {
                etBaseUrl.error = err
                return
            }
        }

        val existing = OnlineServiceStore.byId(instanceId)

        // No key to send, so nothing to probe. A provider that takes none
        // is saved enabled, CREATE or EDIT. Where one is required, a blank
        // key in CREATE creates nothing (the picker stays open — no
        // instance appeared), and in EDIT clears the saved key + disables,
        // keeping any preset/URL edits (they passed their format check and
        // are independent settings).
        val requiresKey = config.account.requiresKey
        if (key.isBlank() || !requiresKey) {
            if (existing != null || !requiresKey) {
                OnlineServiceMutations.saveConfig(
                    this, pageInstance(existing, enabled = !requiresKey), key = "",
                )
            }
            finish()
            return
        }

        setLoading(true)
        // lifecycleScope so toolbar X (which calls finish()) cancels the
        // in-flight validation cleanly — nothing persists on cancel.
        lifecycleScope.launch {
            val pageInstance = pageInstance(existing, enabled = true)
            // Validate against a shell built from the page state (NOT the
            // registered backend): an unsaved preset switch must ping the
            // newly selected provider's endpoint, and in CREATE mode no
            // backend is registered yet. live = false is what makes the
            // first half of that true — a live shell reads its endpoint back
            // out of the saved record, which for a preset the user just
            // switched is the wrong provider entirely.
            val shell = OnlineBackendFactory.build(
                this@LlmBackendSettingsActivity,
                getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE),
                pageInstance,
                live = false,
            )
            val status = try {
                (shell as? KeyValidator)?.validateKey(key, customUrlOrNull())
                    ?: KeyStatus.Unreachable
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Defensive: validateKey shouldn't throw (Unreachable
                // captures network errors), but if it does, fall back
                // to Unreachable rather than blocking the user.
                KeyStatus.Unreachable
            } finally {
                shell.close()
            }
            when (status) {
                is KeyStatus.Invalid -> {
                    setLoading(false)
                    showInvalidKeyAlert(status.explanationRes)
                }
                else -> {
                    // Ok / Unreachable — persist and finish. Unreachable
                    // means we couldn't *prove* the key wrong (offline,
                    // 5xx, a custom endpoint without /models) so we let
                    // the user proceed; the next translate call surfaces
                    // any real issue.
                    OnlineServiceMutations.saveConfig(
                        this@LlmBackendSettingsActivity, pageInstance, key,
                    )
                    finish()
                }
            }
        }
    }

    /** The instance this page would persist right now. Model carries
     *  over from the saved record unless the preset changed — a
     *  DeepSeek model id is meaningless against OpenAI and vice versa,
     *  so a preset switch resets to the new preset's default. The
     *  Thinking switch is saved as it stands, shown or not: a preset
     *  without the switch ignores the flag, and the user's choice is
     *  waiting when they switch back. */
    private fun pageInstance(existing: OnlineServiceInstance?, enabled: Boolean): OnlineServiceInstance {
        val model = when {
            existing == null -> config.defaultModel
            type == ServiceType.OPENAI && existing.preset != preset -> config.defaultModel
            else -> existing.model
        }
        return OnlineServiceInstance(
            id = instanceId,
            type = type,
            enabled = enabled,
            model = model,
            preset = if (type == ServiceType.OPENAI) preset else OpenAiPreset.OPENAI,
            baseUrl = customUrlOrNull() ?: "",
            thinking = switchThinking.isChecked,
        )
    }

    /** The user-entered URL, only meaningful on the CUSTOM preset. */
    private fun customUrlOrNull(): String? =
        if (config.allowsBaseUrl && preset == OpenAiPreset.CUSTOM) {
            findViewById<EditText>(R.id.etBaseUrl).text.toString().trim()
        } else null

    /** [explanationRes] is the backend's condition-specific body, when the
     *  rejection has one ([KeyStatus.Invalid.explanationRes]); otherwise the
     *  generic "double-check it at the console" text. */
    private fun showInvalidKeyAlert(@StringRes explanationRes: Int?) {
        val message = if (explanationRes != null) {
            getString(explanationRes)
        } else {
            getString(
                R.string.llm_backend_invalid_key_alert_message_fmt,
                config.displayName,
                config.getKeyUrl,
            )
        }
        OverlayAlert.Builder(this)
            .setTitle(getString(R.string.llm_backend_invalid_key_alert_title))
            .setMessage(message)
            .addCancelButton(getString(R.string.llm_backend_invalid_key_alert_button))
            .show()
    }

    /**
     * Returns null when [raw] is an acceptable custom base URL, or an inline
     * error string. https is allowed to any host; http is allowed ONLY to a
     * loopback / private-LAN / link-local address (see [CustomEndpointPolicy]),
     * so a typo'd or pasted public http URL can't send the Bearer key in
     * cleartext over the internet — while a self-hosted LAN server still works.
     */
    private fun validateBaseUrl(raw: String): String? =
        if (CustomEndpointPolicy.isAcceptable(raw)) null
        else getString(R.string.llm_backend_base_url_invalid)

    companion object {
        const val EXTRA_INSTANCE_ID = "instance_id"
        const val EXTRA_TYPE = "service_type"
        const val EXTRA_PRESET = "openai_preset"
        private const val STATE_PRESET = "state_preset"
        private const val STATE_THINKING = "state_thinking"

        /** The provider dropdown's items as (menu item id, preset): the
         *  presets the OpenAI row lists, each keyed by its ordinal, so an
         *  item id resolves back to its own preset ([providerMenuPreset])
         *  however the list is filtered. A position in the filtered list
         *  would not: with Index-Translate filtered out, "Custom" sits at
         *  the position that is Index-Translate's ordinal. */
        internal fun providerMenuItems(): List<Pair<Int, OpenAiPreset>> =
            OpenAiPreset.entries.filter { it.listedUnderOpenAi }.map { it.ordinal to it }

        internal fun providerMenuPreset(itemId: Int): OpenAiPreset = OpenAiPreset.entries[itemId]

        /** Edit an existing instance — type/preset load from its record. */
        fun editIntent(context: Context, instanceId: String): Intent =
            Intent(context, LlmBackendSettingsActivity::class.java)
                .putExtra(EXTRA_INSTANCE_ID, instanceId)

        /** Create-mode launch from the add-service picker. [instanceId]
         *  is the picker-generated id whose appearance in the store
         *  signals "saved". [preset] pins the provider for a picker row
         *  that stands for one preset; null opens on OpenAI with the
         *  provider dropdown free. */
        fun createIntent(
            context: Context,
            instanceId: String,
            type: ServiceType,
            preset: OpenAiPreset? = null,
        ): Intent =
            Intent(context, LlmBackendSettingsActivity::class.java)
                .putExtra(EXTRA_INSTANCE_ID, instanceId)
                .putExtra(EXTRA_TYPE, type.name)
                .apply { preset?.let { putExtra(EXTRA_PRESET, it.name) } }
    }
}
