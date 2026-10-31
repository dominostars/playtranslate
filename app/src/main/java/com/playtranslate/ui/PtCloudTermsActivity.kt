package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.ActivityResult
import com.playtranslate.R
import com.playtranslate.cloud.TermsAgreement

/**
 * PlayTranslate Cloud's terms (debug builds only, like the cloud page that
 * opens it): the service's terms and privacy text, one text as the server
 * serves it (`GET /v1/terms`, "Terms" in docs/api.md in the
 * playtranslate-cloud repository), with Agree and Cancel under it. A reader
 * with two buttons: the cloud page ([PtCloudSettingsActivity]) fetches the
 * text, launches this page for a result with the text, its version and its
 * locale ([intent]), and acts on the answer itself ([agreement]): it ticks
 * the sign-in form's agreement box, or sends the acceptance with the stored
 * key. This page
 * calls nothing, stores nothing and asks the service nothing.
 *
 *  - The text is the server's Markdown subset rendered by [TermsMarkdown]
 *    (the headings bold and larger, the paragraph leads bold), in one card
 *    that scrolls; Agree and Cancel sit under the scroll, always in reach.
 *  - Agree finishes with [Activity.RESULT_OK] and the version and the locale
 *    the page was given, echoed ([EXTRA_VERSION], [EXTRA_LOCALE]): what an
 *    acceptance names, so the member accepts the text they were shown.
 *  - Cancel, the toolbar's back and the system's back finish with
 *    [Activity.RESULT_CANCELED], which is [setResult]'s default: nothing is
 *    agreed, and the cloud page stays as it was.
 *
 * Rotation does not recreate it; a theme or locale change does, and the text
 * comes back from the launch intent. The text is never logged here.
 */
class PtCloudTermsActivity : SettingsSubPageActivity() {

    override val layoutResId = R.layout.activity_pt_cloud_terms

    override fun onContentCreated(savedInstanceState: Bundle?) {
        val version = intent.getStringExtra(EXTRA_VERSION)
        val locale = intent.getStringExtra(EXTRA_LOCALE)
        findViewById<TextView>(R.id.tvCloudTermsText).text =
            TermsMarkdown.render(intent.getStringExtra(EXTRA_TEXT).orEmpty())
        findViewById<View>(R.id.btnCloudTermsAgree).setOnClickListener {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_VERSION, version).putExtra(EXTRA_LOCALE, locale))
            finish()
        }
        findViewById<View>(R.id.btnCloudTermsCancel).setOnClickListener { finish() }
    }

    companion object {
        /** The terms' version (the launch's and the result's): the date an
         *  acceptance names ([com.playtranslate.cloud.TermsResult.Text.version]). */
        const val EXTRA_VERSION = "extra_terms_version"

        /** The locale the server served the text in (the launch's and the
         *  result's), which an acceptance echoes. */
        const val EXTRA_LOCALE = "extra_terms_locale"

        /** The terms' Markdown (the launch's only), 4 KB today: far under
         *  the Binder's limit for an intent. */
        const val EXTRA_TEXT = "extra_terms_text"

        /** The launch of the terms page for [markdown], whose [version] and
         *  [locale] Agree answers with. */
        fun intent(context: Context, version: String, locale: String, markdown: String): Intent =
            Intent(context, PtCloudTermsActivity::class.java)
                .putExtra(EXTRA_VERSION, version)
                .putExtra(EXTRA_LOCALE, locale)
                .putExtra(EXTRA_TEXT, markdown)

        /** The one reading of the page's answer: the agreement of an Agree
         *  ([Activity.RESULT_OK] with the version and the locale), null for
         *  anything else (Cancel, back, an answer missing either). */
        fun agreement(result: ActivityResult): TermsAgreement? {
            if (result.resultCode != Activity.RESULT_OK) return null
            val data = result.data ?: return null
            val version = data.getStringExtra(EXTRA_VERSION) ?: return null
            val locale = data.getStringExtra(EXTRA_LOCALE) ?: return null
            return TermsAgreement(version, locale)
        }
    }
}
