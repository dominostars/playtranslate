package com.playtranslate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
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
 *    picker detects the id's appearance to dismiss itself.
 *
 * OPENAI-type pages carry a provider-preset pill (OpenAI / DeepSeek /
 * Custom) at the top of the ADVANCED section. Pinned presets fill and
 * disable the base-URL field; Custom enables free entry. The preset
 * names the instance's cell on the services page.
 *
 * UX contract (unchanged from the legacy per-service screen):
 *  - The toolbar X discards in-progress edits.
 *  - Save validates the typed key against the provider BEFORE
 *    persisting — against a shell backend built from the CURRENT page
 *    state, so a preset switched but not yet saved validates against
 *    the right endpoint. On Ok/Unreachable the instance is persisted
 *    (enabled) and the screen finishes; on Invalid an OverlayAlert
 *    explains and nothing is written.
 *  - Blank key: EDIT clears the key + disables the instance; CREATE
 *    just closes.
 */
class LlmBackendSettingsActivity : AppCompatActivity() {

    private lateinit var config: LlmBackendConfig
    private lateinit var toolbar: MaterialToolbar
    private lateinit var etApiKey: EditText
    private lateinit var btnSave: MaterialButton
    private lateinit var progressSave: ProgressBar

    private lateinit var instanceId: String
    private lateinit var type: ServiceType
    private var preset = OpenAiPreset.OPENAI

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
        preset = existing?.preset ?: OpenAiPreset.OPENAI
        config = LlmBackendConfigs.forInstance(this, instanceId, type, preset)

        toolbar = findViewById(R.id.toolbar)
        toolbar.title = config.displayName
        toolbar.setNavigationOnClickListener { finish() }

        etApiKey = findViewById(R.id.etApiKey)
        etApiKey.hint = config.keyHint
        etApiKey.setText(config.getKey())
        etApiKey.setSelection(etApiKey.text.length)

        wireGetKeyLink(findViewById(R.id.rowGetKeyLink))
        wireAdvancedSection()

        btnSave = findViewById(R.id.btnSave)
        progressSave = findViewById(R.id.progressSave)
        btnSave.setOnClickListener { onSave() }
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

    /** ADVANCED (provider preset + base URL) is OPENAI-type only; GONE
     *  for Gemini. Header text is set here because settings_group_header
     *  carries no android:text. */
    private fun wireAdvancedSection() {
        val section = findViewById<View>(R.id.sectionAdvanced)
        if (!config.allowsBaseUrl) {
            section.isVisible = false
            return
        }
        findViewById<View>(R.id.headerAdvanced)
            .findViewById<TextView>(R.id.tvGroupTitle).text =
            getString(R.string.llm_backend_advanced_header)
        buildPillToggle(
            container = findViewById<FrameLayout>(R.id.presetToggleContainer),
            options = listOf(
                getString(R.string.openai_display_name) to OpenAiPreset.OPENAI,
                getString(R.string.deepseek_display_name) to OpenAiPreset.DEEPSEEK,
                getString(R.string.llm_backend_preset_custom) to OpenAiPreset.CUSTOM,
            ),
            selected = preset,
        ) { applyPreset(it) }
        applyPresetToUrlField()
    }

    /** Pill selection changed: rebind the preset-derived chrome (title,
     *  get-key link, URL field state). Nothing persists until Save. */
    private fun applyPreset(newPreset: OpenAiPreset) {
        preset = newPreset
        config = LlmBackendConfigs.forInstance(this, instanceId, type, preset)
        toolbar.title = config.displayName
        wireGetKeyLink(findViewById(R.id.rowGetKeyLink))
        applyPresetToUrlField()
    }

    private fun applyPresetToUrlField() {
        val etBaseUrl = findViewById<EditText>(R.id.etBaseUrl)
        etBaseUrl.hint = config.presetBaseUrl
        if (preset == OpenAiPreset.CUSTOM) {
            // Entering Custom restores the stored custom URL when one was
            // saved; otherwise the current (canonical) text stays as the
            // editing starting point.
            val stored = config.getBaseUrl()
            if (stored.isNotBlank()) etBaseUrl.setText(stored)
            etBaseUrl.isEnabled = true
            etBaseUrl.setTextColor(themeColor(R.attr.ptText))
        } else {
            etBaseUrl.setText(config.presetBaseUrl)
            etBaseUrl.isEnabled = false
            etBaseUrl.setTextColor(themeColor(R.attr.ptTextMuted))
        }
        etBaseUrl.setSelection(etBaseUrl.text.length)
    }

    /** Toggle the Save button's loading state. While loading, the
     *  button text is blanked + click suppressed and the centered
     *  ProgressBar overlays it. The key field is disabled to prevent
     *  edits racing with the in-flight validation request. */
    private fun setLoading(loading: Boolean) {
        if (loading) {
            btnSave.text = ""
            btnSave.isEnabled = false
            progressSave.isVisible = true
            etApiKey.isEnabled = false
        } else {
            btnSave.text = getString(R.string.deepl_settings_save)
            btnSave.isEnabled = true
            progressSave.isGone = true
            etApiKey.isEnabled = true
        }
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

        // Blank key short-circuit. CREATE: nothing exists, create nothing
        // (the picker stays open — no instance appeared). EDIT: clear the
        // saved key + disable, keeping any preset/URL edits (they passed
        // their format check and are independent settings).
        if (key.isBlank()) {
            if (existing != null) {
                OnlineServiceMutations.saveConfig(this, pageInstance(existing, enabled = false), key = "")
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
            // backend is registered yet.
            val shell = OnlineBackendFactory.build(
                this@LlmBackendSettingsActivity,
                getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE),
                pageInstance,
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
                    showInvalidKeyAlert()
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
     *  so a preset switch resets to the new preset's default. */
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
        )
    }

    /** The user-entered URL, only meaningful on the CUSTOM preset. */
    private fun customUrlOrNull(): String? =
        if (config.allowsBaseUrl && preset == OpenAiPreset.CUSTOM) {
            findViewById<EditText>(R.id.etBaseUrl).text.toString().trim()
        } else null

    private fun showInvalidKeyAlert() {
        OverlayAlert.Builder(this)
            .hideIcon()
            .setTitle(getString(R.string.llm_backend_invalid_key_alert_title))
            .setMessage(
                getString(
                    R.string.llm_backend_invalid_key_alert_message_fmt,
                    config.displayName,
                    config.getKeyUrl,
                )
            )
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

        /** Edit an existing instance — type/preset load from its record. */
        fun editIntent(context: Context, instanceId: String): Intent =
            Intent(context, LlmBackendSettingsActivity::class.java)
                .putExtra(EXTRA_INSTANCE_ID, instanceId)

        /** Create-mode launch from the add-service picker. [instanceId]
         *  is the picker-generated id whose appearance in the store
         *  signals "saved". */
        fun createIntent(context: Context, instanceId: String, type: ServiceType): Intent =
            Intent(context, LlmBackendSettingsActivity::class.java)
                .putExtra(EXTRA_INSTANCE_ID, instanceId)
                .putExtra(EXTRA_TYPE, type.name)
    }
}
