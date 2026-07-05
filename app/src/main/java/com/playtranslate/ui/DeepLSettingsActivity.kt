package com.playtranslate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.playtranslate.R
import com.playtranslate.applyEdgeToEdge
import com.playtranslate.applyTheme
import com.playtranslate.translation.OnlineServiceInstance
import com.playtranslate.translation.OnlineServiceMutations
import com.playtranslate.translation.OnlineServiceStore
import com.playtranslate.translation.ServiceType
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.net.toUri

/**
 * Sub-screen for entering / editing a DeepL instance's API key.
 *
 * UX contract:
 *  - Prepopulates the field from the instance's key slot on entry.
 *  - The X button in the toolbar discards in-progress edits — nothing is
 *    written.
 *  - Save with a non-empty key persists the instance enabled (creating
 *    the store record on the first save — CREATE mode launches from the
 *    add-service picker with a fresh id and no record). Save with an
 *    empty key clears the key + disables an existing instance, or
 *    creates nothing in CREATE mode.
 *
 * Mirrors [LlmBackendSettingsActivity] minus the validation ping (DeepL
 * has no cheap auth-only endpoint wired) and the ADVANCED section.
 */
class DeepLSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Match LanguageSetupActivity: theme must be applied before super
        // so the first inflation resolves ?attr/pt* against the user's
        // accent + mode rather than the manifest default.
        applyTheme(this)
        applyEdgeToEdge(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_deepl_settings)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(sys.left, sys.top, sys.right, maxOf(sys.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID)
            ?: error("DeepLSettingsActivity launched without EXTRA_INSTANCE_ID")

        val etDeeplKey = findViewById<EditText>(R.id.etDeeplKey)
        etDeeplKey.setText(OnlineServiceStore.readKey(instanceId))
        etDeeplKey.setSelection(etDeeplKey.text.length)

        wireGetKeyLink(findViewById(R.id.rowDeeplLink))

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            val key = etDeeplKey.text.toString().trim()
            val existing = OnlineServiceStore.byId(instanceId)
            if (existing == null && key.isBlank()) {
                // CREATE mode with nothing typed: create nothing; the
                // picker stays open because the id never appeared.
                finish()
                return@setOnClickListener
            }
            val instance = existing?.copy(enabled = key.isNotBlank())
                ?: OnlineServiceInstance(
                    id = instanceId,
                    type = ServiceType.DEEPL,
                    enabled = true,
                )
            OnlineServiceMutations.saveConfig(this, instance, key)
            finish()
        }
    }

    private fun wireGetKeyLink(row: View) {
        row.findViewById<TextView>(R.id.tvRowTitle).text =
            getString(R.string.deepl_settings_get_key_title)
        val tvSub = row.findViewById<TextView>(R.id.tvRowSubtitle)
        tvSub.text = getString(R.string.deepl_settings_get_key_subtitle)
        tvSub.isVisible = true
        val url = "https://www.deepl.com/en/pro#developer"
        row.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }
        row.setOnLongClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("URL", url))
            Toast.makeText(this, getString(R.string.toast_link_copied), Toast.LENGTH_SHORT).show()
            true
        }
    }

    companion object {
        const val EXTRA_INSTANCE_ID = "instance_id"

        fun newIntent(context: Context, instanceId: String): Intent =
            Intent(context, DeepLSettingsActivity::class.java)
                .putExtra(EXTRA_INSTANCE_ID, instanceId)
    }
}
