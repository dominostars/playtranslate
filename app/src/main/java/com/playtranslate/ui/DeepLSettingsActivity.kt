package com.playtranslate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.applyTheme

/**
 * Sub-screen for entering / editing the DeepL API key.
 *
 * UX contract:
 *  - Prepopulates the field from [Prefs.deeplApiKey] on entry, so toggling
 *    DeepL off and on again returns the user to their saved key.
 *  - The X button in the toolbar discards in-progress edits — nothing is
 *    written.
 *  - The Save button persists whatever is in the field (including empty).
 *    Saving a non-empty value also flips [Prefs.deeplEnabled] on; saving
 *    empty flips it off. The pref change drives the Settings switch back
 *    in [SettingsBottomSheet] via its SharedPreferences listener — no
 *    Activity result contract needed.
 *
 * Mirrors [LanguageSetupActivity] for navigation and theming.
 */
class DeepLSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Match LanguageSetupActivity: theme must be applied before super
        // so the first inflation resolves ?attr/pt* against the user's
        // accent + mode rather than the manifest default.
        applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_deepl_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val prefs = Prefs(this)
        val etDeeplKey = findViewById<EditText>(R.id.etDeeplKey)
        etDeeplKey.setText(prefs.deeplApiKey)
        etDeeplKey.setSelection(etDeeplKey.text.length)

        wireGetKeyLink(findViewById(R.id.rowDeeplLink))

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            val key = etDeeplKey.text.toString().trim()
            prefs.deeplApiKey = key
            prefs.deeplEnabled = key.isNotBlank()
            finish()
        }
    }

    private fun wireGetKeyLink(row: View) {
        row.findViewById<TextView>(R.id.tvRowTitle).text =
            getString(R.string.deepl_settings_get_key_title)
        val tvSub = row.findViewById<TextView>(R.id.tvRowSubtitle)
        tvSub.text = getString(R.string.deepl_settings_get_key_subtitle)
        tvSub.visibility = View.VISIBLE
        val url = "https://www.deepl.com/en/pro#developer"
        row.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
        row.setOnLongClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("URL", url))
            Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
            true
        }
    }
}
