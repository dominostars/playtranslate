package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Looper
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.ActivityResult
import androidx.core.view.children
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.playtranslate.R
import com.playtranslate.cloud.TermsAgreement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import kotlin.math.roundToInt

/**
 * The PlayTranslate Cloud terms page, a reader with two buttons: the text it
 * is launched with renders with its spans (the `#` heading large and bold, a
 * `##` heading medium and bold, the leads bold, no mark left), the shipped
 * text (the fixture `ptcloud/terms_en.md`) included; Agree finishes
 * RESULT_OK with the launch's version and locale, which [agreement] reads
 * as the agreement; Cancel and the toolbar's back finish RESULT_CANCELED,
 * which it reads as none; and Agree has the cloud page's call-to-action
 * shape, 56dp and bold.
 */
@RunWith(RobolectricTestRunner::class)
class PtCloudTermsActivityTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private var controller: ActivityController<PtCloudTermsActivity>? = null

    @After fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
    }

    private fun open(markdown: String = SAMPLE, version: String = VERSION, locale: String = LOCALE): PtCloudTermsActivity {
        val c = Robolectric.buildActivity(
            PtCloudTermsActivity::class.java,
            PtCloudTermsActivity.intent(ctx, version, locale, markdown),
        ).setup()
        controller = c
        shadowOf(Looper.getMainLooper()).idle()
        return c.get()
    }

    private fun PtCloudTermsActivity.text(): Spanned = findViewById<TextView>(R.id.tvCloudTermsText).text as Spanned
    private fun PtCloudTermsActivity.agreeButton() = findViewById<MaterialButton>(R.id.btnCloudTermsAgree)
    private fun PtCloudTermsActivity.cancelButton() = findViewById<MaterialButton>(R.id.btnCloudTermsCancel)

    /** What the page answered, read as the cloud page reads it. */
    private fun PtCloudTermsActivity.answer() =
        PtCloudTermsActivity.agreement(ActivityResult(shadowOf(this).resultCode, shadowOf(this).resultIntent))

    /** The text under each size span with its proportion, in order. */
    private fun Spanned.sized(): List<Pair<String, Float>> = getSpans(0, length, RelativeSizeSpan::class.java)
        .sortedBy { getSpanStart(it) }
        .map { subSequence(getSpanStart(it), getSpanEnd(it)).toString() to it.sizeChange }

    /** The text under each bold span, in order. */
    private fun Spanned.bold(): List<String> = getSpans(0, length, StyleSpan::class.java)
        .filter { it.style == Typeface.BOLD }
        .sortedBy { getSpanStart(it) }
        .map { subSequence(getSpanStart(it), getSpanEnd(it)).toString() }

    @Test fun `the text renders with its spans, the shipped text included`() {
        val page = open()
        val text = page.text()
        assertEquals(
            "PlayTranslate Cloud: Terms and Privacy\n\nVersion: 2026-10-30\n\nThe service\n\n" +
                "Your account. One account per person, 13 or older.",
            text.toString(),
        )
        assertEquals(listOf("PlayTranslate Cloud: Terms and Privacy" to 1.3f, "The service" to 1.15f), text.sized())
        assertEquals(listOf("PlayTranslate Cloud: Terms and Privacy", "The service", "Your account."), text.bold())
        assertEquals("the toolbar's title", "Terms and Privacy", page.findViewById<MaterialToolbar>(R.id.toolbar).title.toString())
        assertEquals("Agree", page.agreeButton().text.toString())
        assertEquals("Cancel", page.cancelButton().text.toString())
        controller!!.pause().stop().destroy()

        val markdown = javaClass.getResourceAsStream("/ptcloud/terms_en.md")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val shipped = open(markdown).text()
        assertEquals(
            listOf("PlayTranslate Cloud: Terms and Privacy" to 1.3f, "The service" to 1.15f, "Privacy" to 1.15f),
            shipped.sized(),
        )
        assertEquals("three headings and fourteen leads", 3 + 14, shipped.bold().size)
        assertFalse('*' in shipped.toString())
        assertFalse('#' in shipped.toString())
    }

    @Test fun `Agree finishes RESULT_OK with the launch's version and locale`() {
        val page = open(version = "2026-11-02", locale = "pt-br")

        page.agreeButton().performClick()

        assertTrue(page.isFinishing)
        assertEquals(Activity.RESULT_OK, shadowOf(page).resultCode)
        assertEquals("2026-11-02", shadowOf(page).resultIntent.getStringExtra(PtCloudTermsActivity.EXTRA_VERSION))
        assertEquals("pt-br", shadowOf(page).resultIntent.getStringExtra(PtCloudTermsActivity.EXTRA_LOCALE))
        assertFalse("the text is not sent back", shadowOf(page).resultIntent.hasExtra(PtCloudTermsActivity.EXTRA_TEXT))
        assertEquals(TermsAgreement("2026-11-02", "pt-br"), page.answer())
    }

    @Test fun `Cancel and the toolbar's back finish RESULT_CANCELED, which is no agreement`() {
        val page = open()
        page.cancelButton().performClick()
        assertTrue(page.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(page).resultCode)
        assertNull(shadowOf(page).resultIntent)
        assertNull(page.answer())
        controller!!.pause().stop().destroy()

        val again = open()
        val toolbar = again.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.children.filterIsInstance<ImageButton>().single().performClick()
        assertTrue(again.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(again).resultCode)
        assertNull(again.answer())

        // An answer missing either extra is no agreement either.
        val data = Intent().putExtra(PtCloudTermsActivity.EXTRA_VERSION, VERSION)
        assertNull(PtCloudTermsActivity.agreement(ActivityResult(Activity.RESULT_OK, data)))
        assertNull(PtCloudTermsActivity.agreement(ActivityResult(Activity.RESULT_OK, null)))
    }

    /** Agree is the cloud page's call to action in its shape: 56dp tall,
     *  bold; Cancel is the quieter text button under it. */
    @Test fun `the Agree button is 56dp and bold like the cloud page's buttons`() {
        val page = open()
        val agree = page.agreeButton()
        assertEquals((56 * page.resources.displayMetrics.density).roundToInt(), agree.layoutParams.height)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, agree.layoutParams.width)
        // Robolectric answers Typeface.isBold false for every face, a bold one
        // too; the style it does answer carries the same bit.
        assertEquals(Typeface.BOLD, agree.typeface.style and Typeface.BOLD)
        val cancel = page.cancelButton()
        assertTrue(cancel.isShown)
        assertTrue("under Agree", (cancel.parent as ViewGroup).indexOfChild(cancel) > (agree.parent as ViewGroup).indexOfChild(agree))
        assertTrue("both outside the scroll", agree.parent === cancel.parent && agree.parent !is NestedScrollView)
    }

    private companion object {
        const val VERSION = "2026-10-30"
        const val LOCALE = "en"
        const val SAMPLE = "# PlayTranslate Cloud: Terms and Privacy\n\nVersion: 2026-10-30\n\n## The service\n\n" +
            "**Your account.** One account per person,\n13 or older.\n"
    }
}
