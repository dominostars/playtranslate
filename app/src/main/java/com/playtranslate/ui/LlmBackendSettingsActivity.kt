package com.playtranslate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
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
import com.playtranslate.translation.KeyStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.core.view.isVisible
import androidx.core.net.toUri
import androidx.core.view.isGone

/**
 * Generic settings sub-screen for the OpenAI-, Gemini-, and DeepSeek-
 * style LLM backends.
 *
 * Routes through [LlmBackendConfig] so the activity itself stays
 * provider-agnostic — adding a new backend (Anthropic, etc.) is one
 * [LlmBackendConfigs.forId] branch plus the backend class.
 *
 * UX contract mirrors [DeepLSettingsActivity]:
 *  - Prepopulates the key field from prefs on entry.
 *  - The toolbar X discards in-progress edits to the key.
 *  - Save validates the typed key against the provider's auth-only
 *    endpoint BEFORE persisting. While validation is in flight the
 *    button is hidden behind an in-place spinner and the key field
 *    is disabled. On success (Ok or Unreachable) we persist the key
 *    + flip `enabled` and finish; on Invalid we restore the button,
 *    re-enable the field, and show an OverlayAlert explaining the
 *    rejection. Toolbar X mid-validation cancels the in-flight ping
 *    via lifecycleScope and dismisses the activity unchanged.
 *
 * Model selection deliberately lives outside this screen — the inline
 * "Model" sub-cell in the main Settings card appears only once the
 * backend is enabled (i.e. the user has saved a key), at which point
 * the picker has a real key to call /v1/models with.
 *
 * The ADVANCED section exposes a custom base URL for providers that opt
 * in via [LlmBackendConfig.allowsBaseUrl] (OpenAI only today); it's hidden
 * for the rest. The typed URL is format-checked up front but persisted
 * only together with the key on a successful (or unprovable) validation —
 * never before a failed ping — so the "X discards edits" contract holds
 * for the URL too.
 */
class LlmBackendSettingsActivity : AppCompatActivity() {

    private lateinit var config: LlmBackendConfig
    private lateinit var etApiKey: EditText
    private lateinit var btnSave: MaterialButton
    private lateinit var progressSave: ProgressBar

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

        val backendId = intent.getStringExtra(EXTRA_BACKEND_ID)
            ?: error("LlmBackendSettingsActivity launched without EXTRA_BACKEND_ID")
        config = LlmBackendConfigs.forId(this, backendId)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(config.titleStringRes)
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

    /** Shows the ADVANCED section (custom base URL) only for providers that
     *  opt in via [LlmBackendConfig.allowsBaseUrl]; GONE otherwise. Header
     *  text is set here because settings_group_header carries no
     *  android:text. The field prepopulates from the saved URL and hints
     *  the canonical default. */
    private fun wireAdvancedSection() {
        val section = findViewById<View>(R.id.sectionAdvanced)
        if (!config.allowsBaseUrl) {
            section.isVisible = false
            return
        }
        findViewById<View>(R.id.headerAdvanced)
            .findViewById<TextView>(R.id.tvGroupTitle).text =
            getString(R.string.llm_backend_advanced_header)
        findViewById<EditText>(R.id.etBaseUrl).apply {
            setText(config.getBaseUrl())
            hint = config.defaultBaseUrl
        }
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

        // Custom base URL (OpenAI only): format-validate up front but DON'T
        // persist yet. The URL is committed atomically with the key on a
        // successful (or unprovable) validation below, so a failed ping or a
        // toolbar-X leaves the prior config untouched.
        val typedUrl: String? = if (config.allowsBaseUrl) {
            val etBaseUrl = findViewById<EditText>(R.id.etBaseUrl)
            val raw = etBaseUrl.text.toString().trim()
            val err = validateBaseUrl(raw)
            if (err != null) {
                etBaseUrl.error = err
                return
            }
            raw
        } else {
            null
        }

        // Blank key short-circuit: clear the saved key + disable the
        // backend. The URL passed its format check, so persist it (it's an
        // independent setting); nothing to validate.
        if (key.isBlank()) {
            if (typedUrl != null) config.setBaseUrl(typedUrl)
            config.setKey("")
            config.setEnabled(false)
            finish()
            return
        }

        setLoading(true)
        // lifecycleScope so toolbar X (which calls finish()) cancels
        // the in-flight validation cleanly — the activity's destroy
        // tears down the scope and the coroutine never reaches its
        // post-validation save+finish. No prefs are persisted on cancel.
        lifecycleScope.launch {
            val status = try {
                // Validate the typed key against the typed URL (overrideBaseUrl)
                // so nothing has to be persisted to run the ping.
                config.validateKey(key, typedUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Defensive: validateKey shouldn't throw (Unreachable
                // captures network errors), but if it does, fall back
                // to Unreachable rather than blocking the user.
                KeyStatus.Unreachable
            }
            when (status) {
                is KeyStatus.Invalid -> {
                    setLoading(false)
                    showInvalidKeyAlert()
                }
                else -> {
                    // Ok / Unreachable — persist URL + key together and
                    // finish. Unreachable means we couldn't *prove* the key
                    // wrong (offline, 5xx, a custom endpoint without /models)
                    // so we let the user proceed; the next translate call
                    // surfaces any real issue.
                    if (typedUrl != null) config.setBaseUrl(typedUrl)
                    config.setKey(key)
                    config.setEnabled(true)
                    finish()
                }
            }
        }
    }

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
        const val EXTRA_BACKEND_ID = "backend_id"
    }
}
