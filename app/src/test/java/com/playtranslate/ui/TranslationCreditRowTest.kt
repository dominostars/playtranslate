package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.model.TextSegments
import com.playtranslate.model.TranslationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The row under a translation that credits its service, and the gear in it
 * that opens the translation service picker, as [TranslationSectionBinder]
 * binds them on the real layout (fragment_translation_result.xml).
 *
 * The row shows whenever the translation has something to say under it: who
 * translated it (in italics), or the warning that stands in that line's
 * place. It is gone while the text is still being translated, and for a
 * translation with neither a service nor a warning (one that failed). The
 * gear, and the row's tap, need three things at once: a row that credits a
 * translation, a surface that offers the picker, and a picker to open; the
 * surface's offer going or coming back acts on the row already bound.
 *
 * While a service the user picked translates the result again, the
 * translation stays on show, dimmed, and the row says "Translating…" with
 * no gear; the next bind without a pick puts it all back.
 *
 * Before the row has been laid out, the fit's overhead for the Translation
 * card counts it as it will be: its label and the gap over it, and not the
 * tap area a negative margin cancels.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationCreditRowTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private val app: Context = ApplicationProvider.getApplicationContext()
    private lateinit var controller: ActivityController<Host>
    private lateinit var activity: Host
    private lateinit var root: View
    private lateinit var binder: TranslationSectionBinder
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var pickerOpens = 0

    @Before
    fun setUp() {
        clearPrefs()
        controller = Robolectric.buildActivity(Host::class.java).setup()
        activity = controller.get()
        root = LayoutInflater.from(activity).inflate(R.layout.fragment_translation_result, null)
        binder = TranslationSectionBinder(
            root, activity, Prefs(activity), scope, TtsAlertTarget.InActivity(activity), PopoverHost(root as FrameLayout),
        )
        binder.setupSectionButtons({})
        binder.onChooseTranslation = { pickerOpens++ }
    }

    @After
    fun tearDown() {
        binder.release()
        scope.cancel()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        clearPrefs()
    }

    private fun clearPrefs() {
        app.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private val row: View get() = root.findViewById(R.id.translationNoteRow)
    private val label: TextView get() = root.findViewById(R.id.tvTranslationNote)
    private val gear: View get() = root.findViewById(R.id.btnTranslationService)
    private val translation: TextView get() = root.findViewById(R.id.tvTranslation)

    private fun shown(v: View) = v.visibility == View.VISIBLE

    /** Whether [v] draws in italic, read through the typeface's style: under
     *  these tests' Robolectric, isItalic answers false for the italic
     *  typeface whose style says italic (as AskSectionBinderTest found). */
    private fun italic(v: TextView) = ((v.typeface?.style ?: Typeface.NORMAL) and Typeface.ITALIC) != 0

    /** A tap on the row, as a finger makes one: a view that is not
     *  clickable takes none (performClick would run its listener all the
     *  same). */
    private fun tapRow() {
        if (row.isClickable) row.performClick()
    }

    private fun result(translation: String = "Hello", by: String? = "Lingva", note: String? = null) =
        TranslationResult(
            originalText = "こんにちは", segments = TextSegments.ofText("こんにちは"), translatedText = translation,
            timestamp = "", note = note, backendDisplayName = by, langContext = Prefs(app).langContext(),
        )

    private fun credit(service: String) = activity.getString(R.string.translation_source_label, service)

    /** The gear shows and the row takes its tap, or neither. */
    private fun assertGear(offered: Boolean, why: String) {
        assertEquals("$why: gear", offered, shown(gear))
        assertEquals("$why: row tappable", offered, row.isClickable)
    }

    @Test
    fun `a credited translation has its row, in italics, with the gear, and a tap opens the picker`() {
        binder.bindTargetReady(result(by = "Lingva"))

        assertTrue(shown(row))
        assertEquals(credit("Lingva"), label.text.toString())
        assertTrue(italic(label))
        assertEquals(1f, translation.alpha, 0f)
        assertGear(true, "credited, offered, a picker to open")

        tapRow()
        assertEquals(1, pickerOpens)
    }

    @Test
    fun `a warning stands in the credit's place, upright, and keeps the gear`() {
        binder.bindTargetReady(result(by = "ML Kit", note = "⚠ Showing offline translation"))

        assertTrue(shown(row))
        assertEquals("⚠ Showing offline translation", label.text.toString())
        assertFalse(italic(label))
        assertGear(true, "a warned translation is still one a service can be picked for")

        tapRow()
        assertEquals(1, pickerOpens)
    }

    @Test
    fun `no row while the text is still being translated`() {
        binder.bindTargetReady(result(translation = "", by = "Lingva"))
        assertFalse("a blank translation is one in flight", shown(row))
        assertGear(false, "in flight")

        binder.bindTargetReady(result())
        assertTrue(shown(row))
        binder.setTargetTranslatingPlaceholder()
        assertFalse("the placeholder", shown(row))
        assertGear(false, "the placeholder")
    }

    @Test
    fun `no row, so no gear, for a translation with neither a service nor a warning`() {
        binder.bindTargetReady(result(translation = "—", by = null))

        assertFalse(shown(row))
        assertGear(false, "nothing credited")
        tapRow()
        assertEquals(0, pickerOpens)
    }

    @Test
    fun `a surface that does not offer the picker shows the credit without the gear, and the offer acts on the bound row`() {
        binder.translationPickerOffered = false
        binder.bindTargetReady(result(by = "Lingva"))

        assertTrue("the credit is still said", shown(row))
        assertEquals(credit("Lingva"), label.text.toString())
        assertGear(false, "not offered")
        tapRow()
        assertEquals("an inert row opens nothing", 0, pickerOpens)

        // Auto-translate stopped: the result it left can be picked for,
        // with no new bind.
        binder.translationPickerOffered = true
        assertGear(true, "offered again")
        tapRow()
        assertEquals(1, pickerOpens)

        binder.translationPickerOffered = false
        assertGear(false, "and taken back")
    }

    @Test
    fun `a surface with no picker to open has no gear`() {
        binder.onChooseTranslation = null
        binder.bindTargetReady(result(by = "Lingva"))

        assertTrue(shown(row))
        assertGear(false, "no picker")

        binder.onChooseTranslation = { pickerOpens++ }
        assertGear(true, "a picker given later")
    }

    @Test
    fun `while a picked service translates, the text stays on show dimmed under a Translating line with no gear`() {
        val on = result(translation = "Hello", by = "Lingva")
        binder.bindTargetReady(on)

        binder.bindTargetReady(on, picking = true)

        assertEquals("the translation is not taken away", "Hello", translation.text.toString())
        assertTrue("dimmed", translation.alpha < 1f)
        assertTrue(shown(row))
        assertEquals(activity.getString(R.string.status_translating), label.text.toString())
        assertFalse("the line is a status, not a credit", italic(label))
        assertGear(false, "a pick is running")
        tapRow()
        assertEquals(0, pickerOpens)

        // The run ended with nothing landed (it failed): as it was.
        binder.bindTargetReady(on, picking = false)
        assertEquals(1f, translation.alpha, 0f)
        assertEquals(credit("Lingva"), label.text.toString())
        assertTrue(italic(label))
        assertGear(true, "the run over")
    }

    @Test
    fun `what lands, or a placeholder, is drawn at full strength again`() {
        val on = result(translation = "Hello", by = "Lingva")
        binder.bindTargetReady(on, picking = true)

        binder.bindTargetReady(on.copy(translatedText = "Hi there", backendDisplayName = "Gemini"))
        assertEquals(1f, translation.alpha, 0f)
        assertEquals(credit("Gemini"), label.text.toString())
        assertGear(true, "landed")

        binder.bindTargetReady(on, picking = true)
        binder.setTargetTranslatingPlaceholder()
        assertEquals("another text took its place", 1f, translation.alpha, 0f)
    }

    @Test
    fun `the gear says what it opens and the row reads as its own label`() {
        assertEquals(
            activity.getString(R.string.cd_choose_translation_service),
            gear.contentDescription?.toString(),
        )
        assertNull("the row has no description of its own to hide the credit behind", row.contentDescription)
    }

    @Test
    fun `before layout the fit counts the row as its label and the gap over it, not its tap area`() {
        val withoutRow = binder.targetContentOverhead()
        binder.bindTargetReady(result(by = "Lingva"))
        assertEquals("not laid out yet", 0, row.height)

        val withRow = binder.targetContentOverhead()

        val tapArea = row.paddingBottom
        assertTrue("the row's tap area is cancelled by its margin", tapArea > 0)
        assertEquals(
            -tapArea,
            (row.layoutParams as android.view.ViewGroup.MarginLayoutParams).bottomMargin,
        )
        assertEquals(row.measuredHeight - tapArea, withRow - withoutRow)
        assertTrue("which is the label and the gap over it", withRow - withoutRow >= label.measuredHeight + row.paddingTop)
    }
}
