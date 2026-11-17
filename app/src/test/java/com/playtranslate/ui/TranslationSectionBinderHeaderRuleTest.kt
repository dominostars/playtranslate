package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Space
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.themeColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * The results headers' rule for a hidden section, as [TranslationSectionBinder]
 * applies it to the real headers (fragment_translation_result.xml, built the
 * way [TranslationResultContent] builds the binder, laid out by its
 * [ResultSectionsLayout]).
 *
 * A hidden section's header is its label, receded to the hint colour, and
 * its eye; nothing else. The two whole-result actions, Text size and Show on
 * screen, each have a view in both headers and are offered by the host
 * (a size callback; show-on-screen availability). Pins, for each action on
 * its own: not offered, neither view shows in any hide combination;
 * offered, only the Translation header's view shows while Translation is
 * shown, only the Original header's while Translation is hidden and
 * Original is shown, and neither with both hidden. Either Text size view
 * runs the one size callback anchored on itself, either pill runs the one
 * show-on-screen handler, the ON look paints whichever pill is showing,
 * and the eyes move an offered action between the headers and back. A
 * shown label is drawn muted.
 *
 * The row is laid out wide enough that nothing folds behind ⋯ (each layout
 * asserts it), so a view that is not shown was never offered there, rather
 * than folded.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationSectionBinderHeaderRuleTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    /** One of the two whole-result actions: its view in each header. */
    private enum class ResultAction(val inTranslation: Int, val inOriginal: Int) {
        TEXT_SIZE(R.id.btnFontSize, R.id.btnFontSizeOriginal),
        SHOW_ON_SCREEN(R.id.btnShowOnScreen, R.id.btnShowOnScreenOriginal),
    }

    /** Where an action's view shows right now. */
    private enum class Home { NEITHER, TRANSLATION, ORIGINAL, BOTH }

    private val app: Context = ApplicationProvider.getApplicationContext()
    private lateinit var controller: ActivityController<Host>
    private lateinit var activity: Host
    private lateinit var prefs: Prefs
    private lateinit var root: View
    private lateinit var sections: ResultSectionsLayout
    private lateinit var binder: TranslationSectionBinder
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Every anchor the one Text size callback was given, in order. */
    private val sizeAnchors = mutableListOf<View>()
    private val sizeCallback: (View) -> Unit = { anchor -> sizeAnchors += anchor }
    private var showOnScreenRuns = 0

    /** (Original hidden, Translation hidden), every combination of the two eyes. */
    private val combinations = listOf(false to false, false to true, true to false, true to true)

    /** A paired row and a stacked one, both wide enough that nothing folds. */
    private val widths = listOf(1200, 580)

    @Before
    fun setUp() {
        clearPrefs()
        controller = Robolectric.buildActivity(Host::class.java).setup()
        activity = controller.get()
        prefs = Prefs(activity)
        root = LayoutInflater.from(activity).inflate(R.layout.fragment_translation_result, null)
        sections = root.findViewById(R.id.resultsScrollContent)
        binder = TranslationSectionBinder(
            root, activity, prefs, scope, TtsAlertTarget.InActivity(activity), PopoverHost(root as FrameLayout),
        )
        binder.setupSectionButtons({})
        binder.setShowOnScreenAction { showOnScreenRuns++ }
    }

    @After
    fun tearDown() {
        binder.release()
        scope.cancel()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        clearPrefs()
    }

    private fun view(id: Int): View = root.findViewById(id)
    private fun shown(id: Int): Boolean = view(id).visibility == View.VISIBLE

    private fun homeOf(action: ResultAction): Home {
        val inTranslation = shown(action.inTranslation)
        val inOriginal = shown(action.inOriginal)
        return when {
            inTranslation && inOriginal -> Home.BOTH
            inTranslation -> Home.TRANSLATION
            inOriginal -> Home.ORIGINAL
            else -> Home.NEITHER
        }
    }

    /** The host offers [action] or takes it back, the way each host does. */
    private fun offer(action: ResultAction, offered: Boolean) {
        when (action) {
            ResultAction.TEXT_SIZE -> binder.onChooseFontSize = if (offered) sizeCallback else null
            ResultAction.SHOW_ON_SCREEN -> binder.setShowOnScreenAvailable(offered)
        }
    }

    /** One layout pass at [width]. A header decides which of its available
     *  actions show inline while it measures, so every read follows one. */
    private fun layOut(width: Int = widths.first()) {
        sections.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        sections.layout(0, 0, width, sections.measuredHeight)
        assertEquals("nothing folds behind the Original's more at $width", View.GONE,
            view(R.id.btnMoreOriginal).visibility)
        assertEquals("nothing folds behind the Translation's more at $width", View.GONE,
            view(R.id.btnMoreTranslation).visibility)
    }

    /** Set both eyes' prefs and apply them the way the binder's own toggles
     *  do, then lay the headers out. */
    private fun setHidden(original: Boolean, translation: Boolean, width: Int = widths.first()) {
        prefs.hideOriginalSection = original
        prefs.hideTranslationSection = translation
        binder.applyOriginalVisibility()
        binder.applyTranslationVisibility()
        layOut(width)
    }

    /** What a header shows: its children that are not GONE, its weighted
     *  spacer aside. */
    private fun visibleItems(headerId: Int): Set<View> {
        val header = view(headerId) as ViewGroup
        return (0 until header.childCount).map { header.getChildAt(it) }
            .filter { it.visibility == View.VISIBLE && it !is Space }
            .toSet()
    }

    /** Each set of offered actions a host can be in. */
    private val offerSets = listOf(
        setOf(ResultAction.TEXT_SIZE),
        setOf(ResultAction.SHOW_ON_SCREEN),
        setOf(ResultAction.TEXT_SIZE, ResultAction.SHOW_ON_SCREEN),
    )

    private fun offerOnly(actions: Set<ResultAction>) {
        for (a in ResultAction.values()) offer(a, a in actions)
    }

    // ── The truth table ─────────────────────────────────────────────────

    @Test
    fun `an action the host has not offered shows in neither header, in every hide combination`() {
        for (offered in listOf(emptySet(), setOf(ResultAction.TEXT_SIZE), setOf(ResultAction.SHOW_ON_SCREEN))) {
            offerOnly(offered)
            for (width in widths) for ((original, translation) in combinations) {
                setHidden(original, translation, width)
                for (action in ResultAction.values().filter { it !in offered }) {
                    assertEquals(
                        "$action not offered (offered: $offered), Original hidden $original, " +
                            "Translation hidden $translation, at $width",
                        Home.NEITHER, homeOf(action),
                    )
                }
            }
        }
    }

    @Test
    fun `an offered action shows only in the Translation header while Translation is shown, whatever the Original's state`() {
        for (offered in offerSets) {
            offerOnly(offered)
            for (width in widths) for (original in listOf(false, true)) {
                setHidden(original, translation = false, width = width)
                for (action in ResultAction.values()) {
                    assertEquals(
                        "$action (offered: $offered), Original hidden $original, at $width",
                        if (action in offered) Home.TRANSLATION else Home.NEITHER, homeOf(action),
                    )
                }
            }
        }
    }

    @Test
    fun `an offered action shows only in the Original header while Translation is hidden and Original is shown`() {
        for (offered in offerSets) {
            offerOnly(offered)
            for (width in widths) {
                setHidden(original = false, translation = true, width = width)
                for (action in ResultAction.values()) {
                    assertEquals(
                        "$action (offered: $offered), at $width",
                        if (action in offered) Home.ORIGINAL else Home.NEITHER, homeOf(action),
                    )
                }
            }
        }
        // The same with the offer made after the hide, and withdrawn: the
        // host's offer and the eyes decide it in either order.
        offerOnly(emptySet())
        setHidden(original = false, translation = true)
        for (action in ResultAction.values()) {
            offer(action, true)
            layOut()
            assertEquals("$action offered after the hide", Home.ORIGINAL, homeOf(action))
            offer(action, false)
            layOut()
            assertEquals("$action withdrawn", Home.NEITHER, homeOf(action))
        }
    }

    @Test
    fun `with both sections hidden neither header shows either action, offered or not`() {
        for (offered in offerSets) {
            offerOnly(offered)
            for (width in widths) {
                setHidden(original = true, translation = true, width = width)
                for (action in ResultAction.values()) {
                    assertEquals("$action (offered: $offered), at $width", Home.NEITHER, homeOf(action))
                }
            }
        }
    }

    // ── One handler per action ──────────────────────────────────────────

    @Test
    fun `either Text size view runs the one size callback, anchored on that view`() {
        offer(ResultAction.TEXT_SIZE, true)
        setHidden(original = false, translation = false)
        view(R.id.btnFontSize).performClick()
        assertEquals(listOf(view(R.id.btnFontSize)), sizeAnchors)

        setHidden(original = false, translation = true)
        view(R.id.btnFontSizeOriginal).performClick()
        assertEquals(listOf(view(R.id.btnFontSize), view(R.id.btnFontSizeOriginal)), sizeAnchors)
        assertEquals("Text size never runs show on screen", 0, showOnScreenRuns)
    }

    @Test
    fun `either Show on screen pill runs the one handler`() {
        offer(ResultAction.SHOW_ON_SCREEN, true)
        setHidden(original = false, translation = false)
        view(R.id.btnShowOnScreen).performClick()
        assertEquals(1, showOnScreenRuns)

        setHidden(original = false, translation = true)
        view(R.id.btnShowOnScreenOriginal).performClick()
        assertEquals(2, showOnScreenRuns)
        assertTrue("show on screen never runs the size callback", sizeAnchors.isEmpty())
    }

    @Test
    fun `the ON look paints whichever Show on screen pill is showing`() {
        val on = activity.themeColor(R.attr.ptAccentOn)
        val off = activity.themeColor(R.attr.ptTextMuted)
        val accent = activity.themeColor(R.attr.ptAccent)
        assertNotEquals("the two looks differ in this theme", on, off)
        val inTranslation = view(R.id.btnShowOnScreen) as TextView
        val inOriginal = view(R.id.btnShowOnScreenOriginal) as TextView
        offer(ResultAction.SHOW_ON_SCREEN, true)

        setHidden(original = false, translation = false)
        assertEquals("off until toggled", off, inTranslation.currentTextColor)
        binder.setShowOnScreenToggled(true)
        assertEquals(on, inTranslation.currentTextColor)
        assertEquals(accent, inTranslation.backgroundTintList?.defaultColor)

        // The pill that shows once Translation is hidden is ON too.
        setHidden(original = false, translation = true)
        assertTrue(shown(R.id.btnShowOnScreenOriginal))
        assertEquals(on, inOriginal.currentTextColor)
        assertEquals(accent, inOriginal.backgroundTintList?.defaultColor)

        binder.setShowOnScreenToggled(false)
        assertEquals(off, inOriginal.currentTextColor)
        assertNull(inOriginal.backgroundTintList)

        setHidden(original = false, translation = false)
        assertEquals("the Translation's pill comes back off", off, inTranslation.currentTextColor)
    }

    @Test
    fun `the eyes move an offered action between the headers and back`() {
        offerOnly(ResultAction.values().toSet())
        setHidden(original = false, translation = false)
        for (action in ResultAction.values()) assertEquals(Home.TRANSLATION, homeOf(action))

        view(R.id.btnToggleTranslation).performClick()
        layOut()
        assertTrue(prefs.hideTranslationSection)
        for (action in ResultAction.values()) assertEquals("Translation hidden: $action", Home.ORIGINAL, homeOf(action))

        view(R.id.btnToggleTranslation).performClick()
        layOut()
        assertFalse(prefs.hideTranslationSection)
        for (action in ResultAction.values()) assertEquals("Translation shown: $action", Home.TRANSLATION, homeOf(action))

        // From the Original header, the Original's eye takes them away with
        // its card and gives them back.
        view(R.id.btnToggleTranslation).performClick()
        layOut()
        view(R.id.btnToggleOriginal).performClick()
        layOut()
        assertTrue(prefs.hideOriginalSection)
        for (action in ResultAction.values()) assertEquals("both hidden: $action", Home.NEITHER, homeOf(action))
        view(R.id.btnToggleOriginal).performClick()
        layOut()
        for (action in ResultAction.values()) assertEquals("Original shown again: $action", Home.ORIGINAL, homeOf(action))
    }

    // ── A hidden section's header ───────────────────────────────────────

    @Test
    fun `a hidden Original's header is its label and its eye, nothing else`() {
        offerOnly(ResultAction.values().toSet())
        val sourceActions = listOf(
            R.id.btnToggleFurigana, R.id.btnSpeakOriginal, R.id.btnEditOriginal,
        )
        // While Translation is hidden the shown Original header carries
        // everything, the whole-result actions included...
        setHidden(original = false, translation = true)
        for (id in sourceActions + R.id.btnFontSizeOriginal + R.id.btnShowOnScreenOriginal) {
            assertTrue("shown: ${view(id).resources.getResourceEntryName(id)}", shown(id))
        }
        // ...and hidden, it keeps its label and its eye alone.
        for (translation in listOf(false, true)) {
            setHidden(original = true, translation = translation)
            assertEquals(
                "Translation hidden $translation",
                setOf(view(R.id.labelOriginal), view(R.id.btnToggleOriginal)),
                visibleItems(R.id.sourceHeaderRow),
            )
            for (id in sourceActions) {
                assertEquals(view(id).resources.getResourceEntryName(id), View.GONE, view(id).visibility)
            }
        }
    }

    @Test
    fun `a hidden Translation's header is its label and its eye, nothing else`() {
        offerOnly(ResultAction.values().toSet())
        setHidden(original = false, translation = false)
        for (id in listOf(R.id.btnFontSize, R.id.btnShowOnScreen)) {
            assertTrue("shown: ${view(id).resources.getResourceEntryName(id)}", shown(id))
        }
        for (original in listOf(false, true)) {
            setHidden(original = original, translation = true)
            assertEquals(
                "Original hidden $original",
                setOf(view(R.id.labelTranslation), view(R.id.btnToggleTranslation)),
                visibleItems(R.id.targetHeaderRow),
            )
        }
        // Both hidden: each header is its label and its eye.
        assertEquals(
            setOf(view(R.id.labelOriginal), view(R.id.btnToggleOriginal)),
            visibleItems(R.id.sourceHeaderRow),
        )
    }

    @Test
    fun `a hidden section's label recedes to the hint colour, and a shown one's is muted`() {
        val muted = activity.themeColor(R.attr.ptTextMuted)
        val hint = activity.themeColor(R.attr.ptTextHint)
        assertNotEquals("the two colours differ in this theme", muted, hint)
        val labelOriginal = view(R.id.labelOriginal) as TextView
        val labelTranslation = view(R.id.labelTranslation) as TextView
        // Through every combination and back, so a label is checked coming
        // back from hidden as well as going.
        for ((original, translation) in listOf(
            false to false, true to false, true to true, false to true, false to false,
        )) {
            setHidden(original, translation)
            val at = "Original hidden $original, Translation hidden $translation"
            assertEquals("the Original label, $at", if (original) hint else muted, labelOriginal.currentTextColor)
            assertEquals("the Translation label, $at", if (translation) hint else muted, labelTranslation.currentTextColor)
        }
    }

    private fun clearPrefs() {
        app.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
}
