package com.playtranslate.ui

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.model.TextSegment
import com.playtranslate.model.TranslationResult
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.overlay.OwnWindows
import com.playtranslate.ui.ResultSectionsLayout.Arrangement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * The over-game capture sheet's results sections, on a real
 * [CaptureResultOverlay] shown with a result ([CaptureResultOverlay.showWithResult])
 * and let settle (its posted fits, its height animation and the layout they
 * cause).
 *
 * Pins:
 *  - Arrangement: the sections are one [ResultSectionsLayout], the scroll's
 *    only child, fixed PAIRED on a wide screen and STACKED on a narrow one
 *    for the sheet's whole life (every eye combination included).
 *  - The hidden rule ("the eye removes the card, never the header"): paired,
 *    a hidden section keeps its header as the 140dp cell at its own end and
 *    the other card spans the full width, and with both hidden both headers
 *    stay with no card; stacked, a hidden Translation's header stays in place
 *    at the top with the scroll at 0 (no "park" above the fold); an eye
 *    toggle never moves the scroll; the hidden section's restore control is
 *    its own header's eye, which the controller reaches and a tap on which
 *    brings the card back.
 *  - Height from the content's parts: hiding a section and showing it again
 *    returns the panel height and the drag ceiling exactly; a sheet shown
 *    with a section already hidden settles where one toggled to it does;
 *    hiding never raises the need, and stacked it lowers it; with both hidden
 *    the ceiling is the panel floor; the need is the same however tall the
 *    panel is; and at the floor the paired cards stretch to the body's
 *    bottom (the fill).
 *  - The swipe-away gate: a long downward drag of the body dismisses while
 *    the content is at its top, whether the texts fit the body or overflow
 *    it; scrolled down, the drag is the scroll's; an upward drag never
 *    starts a dismiss drag; a host that does not dismiss on gestures keeps
 *    the sheet.
 *
 * Every sheet here has its Ask and Words sections HIDDEN, which is a sheet
 * whose content is the two texts: a hidden section is a header row past the
 * fold and adds nothing to the panel's need. Those sections shown in the
 * sheet have their own class.
 *
 * The display is set to each sheet's own size before it is shown, as on a
 * device: Robolectric's default display is 320x470, and the bars it places
 * there would inset a larger sheet by hundreds of px. mdpi, so a dp is a px.
 * Native graphics, so the texts measure in a real font: a long text really
 * is taller, and a narrower card really wraps it into more lines.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CaptureSheetSectionsTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val host = OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    private val wm = OwnWindows.manager(ctx)!!
    private val prefs = Prefs(ctx)
    private var sheet: CaptureResultOverlay? = null
    private val dismissals = mutableListOf<CaptureResultOverlay.DismissReason>()

    private fun sp() = ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)

    @Before fun setUp() {
        sp().edit().clear().commit()
    }

    @After fun tearDown() {
        sheet?.dismiss()
        host.removeAll()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        sp().edit().clear().commit()
    }

    // ── harness ──────────────────────────────────────────────────────────

    private data class Screen(val w: Int, val h: Int)

    /** Two 640dp columns: the sheet pairs. */
    private val wide = Screen(1280, 720)

    /** Under two 300dp columns: the sheet stacks. */
    private val narrow = Screen(500, 900)

    /** A section's views: its card, the card's content holder (the text and
     *  its note row), its header and the header's eye. */
    private enum class Section(val cardId: Int, val contentId: Int, val headerId: Int, val eyeId: Int) {
        TRANSLATION(R.id.cardTranslation, R.id.translationContent, R.id.targetHeaderRow, R.id.btnToggleTranslation),
        ORIGINAL(R.id.cardOriginal, R.id.originalContent, R.id.sourceHeaderRow, R.id.btnToggleOriginal),
    }

    private val Section.other: Section
        get() = if (this == Section.TRANSLATION) Section.ORIGINAL else Section.TRANSLATION

    private val density get() = ctx.resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun constInt(name: String): Int =
        CaptureResultOverlay::class.java.getDeclaredField(name).apply { isAccessible = true }.getInt(null)

    private fun constFloat(name: String): Float =
        CaptureResultOverlay::class.java.getDeclaredField(name).apply { isAccessible = true }.getFloat(null)

    private fun <T> Any.field(name: String): T {
        @Suppress("UNCHECKED_CAST")
        return javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T
    }

    private val CaptureResultOverlay.sections: ResultSectionsLayout get() = field("sections")
    private val CaptureResultOverlay.scroll: NestedScrollView get() = field("scroll")
    private val CaptureResultOverlay.panel: View get() = field("panel")
    private val CaptureResultOverlay.body: View get() = field("body")
    private val CaptureResultOverlay.root: ViewGroup get() = field("root")
    private val CaptureResultOverlay.binder: TranslationSectionBinder get() = field("binder")
    private val CaptureResultOverlay.dismissed: Boolean get() = field("dismissed")

    /** The settled panel height. */
    private val CaptureResultOverlay.panelHeight: Int get() = field("panelHeightPx")

    /** What the content needs at max text size: the drag ceiling, and what
     *  the auto height grows to (up to its cap). */
    private val CaptureResultOverlay.need: Int get() = field("maxNeededHeightPx")

    private fun CaptureResultOverlay.v(id: Int): View = sections.findViewById(id)

    private fun CaptureResultOverlay.toggle(section: Section) = when (section) {
        Section.TRANSLATION -> binder.toggleTranslationHidden()
        Section.ORIGINAL -> binder.toggleOriginalHidden()
    }

    private fun hiddenPref(section: Section): Boolean = when (section) {
        Section.TRANSLATION -> prefs.hideTranslationSection
        Section.ORIGINAL -> prefs.hideOriginalSection
    }

    private fun setHiddenPref(section: Section, hidden: Boolean) = when (section) {
        Section.TRANSLATION -> prefs.hideTranslationSection = hidden
        Section.ORIGINAL -> prefs.hideOriginalSection = hidden
    }

    private fun floor(screen: Screen) = CaptureResultGeometry.minPanelHeight(screen.h)

    /** A pangram sentence repeated [sentences] times: about 62 characters a
     *  sentence, a line and a half of a 444px card at 24sp. */
    private fun text(sentences: Int) =
        "The quick brown fox jumps over the lazy dog, again and again. ".repeat(sentences).trim()

    private fun result(original: String, translation: String) = TranslationResult(
        originalText = original,
        segments = listOf(TextSegment(original)),
        translatedText = translation,
        timestamp = "",
        // The "Translated by" note row is part of the Translation card's
        // height, read live by the fit.
        backendDisplayName = "Test MT",
        langContext = prefs.langContext(),
    )

    /** Run the sheet's posted fits, its height animation and the layout
     *  they cause until nothing is left pending. */
    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

    /** A sheet on a display of [screen]'s size, shown with a result and
     *  settled, with the sections in [hidden] hidden by their prefs before
     *  show. [configure] runs before show. */
    private fun open(
        screen: Screen,
        original: String,
        translation: String,
        hidden: Set<Section> = emptySet(),
        configure: (CaptureResultOverlay) -> Unit = {},
    ): CaptureResultOverlay {
        RuntimeEnvironment.setQualifiers("w${screen.w}dp-h${screen.h}dp-mdpi")
        // Each sheet starts from the hide state it names: the eyes write the
        // prefs, and a sheet closed earlier in a test must not hand its
        // hidden sections on to this one.
        for (section in Section.values()) setHiddenPref(section, section in hidden)
        // The two texts are the content under test (class doc).
        prefs.hideWordsSection = true
        prefs.hideAskSection = true
        // "It opens how you left it": a sheet closed earlier in a test must
        // not seed this one's auto-size ceiling either.
        prefs.capturePanelPosture = CaptureResultGeometry.NO_POSTURE
        dismissals.clear()
        val o = CaptureResultOverlay(ctx, wm, Display.DEFAULT_DISPLAY, host)
        sheet = o
        o.onDismiss = { dismissals += it }
        configure(o)
        o.showWithResult(screen.w, screen.h, result(original, translation))
        settle()
        assertEquals("the sheet's window is the screen", screen.w to screen.h, o.root.width to o.root.height)
        return o
    }

    private fun close(o: CaptureResultOverlay) {
        o.dismiss()
        host.removeAll()
        settle()
    }

    /** Whether [section]'s card is taller than its content and its own
     *  frame: stretched by the fill (its minimum height), not by its text. */
    private fun stretched(o: CaptureResultOverlay, section: Section): Boolean {
        val card = o.v(section.cardId)
        return card.height > o.v(section.contentId).height + card.paddingTop + card.paddingBottom
    }

    /** The buffer the sheet keeps under its cards, down to the panel's
     *  bottom: what its height and the cards' fill count below them. */
    private fun cardBuffer(o: CaptureResultOverlay): Int = dp(
        constInt(
            if (o.sections.fixedArrangement == Arrangement.PAIRED) "SIDE_BY_SIDE_BOTTOM_BUFFER_DP"
            else "STACKED_BOTTOM_BUFFER_DP",
        ),
    )

    /** What is past the fold when the two texts fit the body exactly: the
     *  header rows of the sections hidden beneath them, and the Anki pill's
     *  room under those (the sections' bottom padding, less the buffer the
     *  fit keeps above the fold). That much a sheet of two texts always
     *  scrolls by, and no more. */
    private fun rowsPastTheFold(o: CaptureResultOverlay): Int =
        o.v(R.id.askHeaderRow).height + o.v(R.id.wordsHeaderRow).height +
            o.sections.paddingBottom - cardBuffer(o)

    /** How far the content can scroll: the sections past the viewport. */
    private fun scrollRange(o: CaptureResultOverlay): Int {
        val s = o.scroll
        return (o.sections.height - (s.height - s.paddingTop - s.paddingBottom)).coerceAtLeast(0)
    }

    private fun panelTop(o: CaptureResultOverlay): Float = o.panel.top + o.panel.translationY

    /** A finger on the sheet's root, as its window delivers one: DOWN at
     *  ([x], [fromY]), [steps] MOVEs out to [dy] past it, UP there.
     *  [onMove] runs after each MOVE. */
    private fun drag(
        o: CaptureResultOverlay,
        x: Float,
        fromY: Float,
        dy: Float,
        steps: Int = 6,
        onMove: () -> Unit = {},
    ) {
        val downTime = SystemClock.uptimeMillis()
        var t = downTime
        fun send(action: Int, y: Float) {
            val ev = MotionEvent.obtain(downTime, t, action, x, y, 0)
            o.root.dispatchTouchEvent(ev)
            ev.recycle()
        }
        send(MotionEvent.ACTION_DOWN, fromY)
        for (i in 1..steps) {
            t += 16
            send(MotionEvent.ACTION_MOVE, fromY + dy * i / steps)
            onMove()
        }
        t += 16
        send(MotionEvent.ACTION_UP, fromY + dy)
    }

    /** The middle of the sheet's body: below the grabber strip and the band
     *  under it that still grabs the sheet for a resize, so a drag there is
     *  the body's. */
    private fun bodyPoint(o: CaptureResultOverlay, screen: Screen): Pair<Float, Float> {
        val top = panelTop(o)
        val y = top + o.body.top + o.body.height / 2f
        val grabBottom = top + dp(constInt("HANDLE_HEIGHT_DP") + constInt("EXTRA_GRAB_INTO_SHEET_DP"))
        assertTrue("the body point ($y) is below the band that grabs the sheet ($grabBottom)", y > grabBottom)
        assertTrue("the body point ($y) is on the sheet", y < o.panel.bottom + o.panel.translationY)
        return screen.w / 4f to y
    }

    /** The y, in the panel's own coordinates, at which the panel last saw a
     *  DOWN: proof that a gesture reached the body rather than ending at
     *  the root (a resize grab, an outside tap). */
    private fun panelDownY(o: CaptureResultOverlay): Float = o.panel.field("downY")

    /** Past the distance that throws the sheet away, with room to spare. */
    private val dismissTravel: Float get() = constFloat("DISMISS_DISTANCE_DP") * density + dp(50)

    /** A long downward drag of the body. Returns the furthest the sheet
     *  followed the finger before the release. */
    private fun swipeDown(o: CaptureResultOverlay, screen: Screen): Float {
        val (x, y) = bodyPoint(o, screen)
        var followed = 0f
        drag(o, x, y, dismissTravel) { followed = maxOf(followed, o.panel.translationY) }
        return followed
    }

    // ── A. Arrangement and the hidden rule ──────────────────────────────

    @Test fun `the sections are one layout, the scroll's only child, paired wide and stacked narrow for the sheet's whole life`() {
        for ((screen, expected) in listOf(wide to Arrangement.PAIRED, narrow to Arrangement.STACKED)) {
            val o = open(screen, text(3), text(2))
            val sections = o.sections
            assertEquals("one child in the scroll at $screen", 1, o.scroll.childCount)
            assertSame("and it is the sections' layout at $screen", sections, o.scroll.getChildAt(0))
            for (section in Section.values()) {
                assertSame("$section's header is the layout's own child", sections, o.v(section.headerId).parent)
                assertSame("$section's card is the layout's own child", sections, o.v(section.cardId).parent)
            }
            assertEquals("decided at show on $screen", expected, sections.fixedArrangement)
            assertEquals("and laid out that way on $screen", expected, sections.arrangement)

            // Every eye combination, through the eyes: Translation hidden,
            // both hidden, Original hidden, both shown.
            val steps = listOf(Section.TRANSLATION, Section.ORIGINAL, Section.TRANSLATION, Section.ORIGINAL)
            for (section in steps) {
                o.toggle(section)
                settle()
                val state = "Translation hidden ${prefs.hideTranslationSection}, " +
                    "Original hidden ${prefs.hideOriginalSection}, on $screen"
                assertEquals("still decided $expected with $state", expected, sections.fixedArrangement)
                assertEquals("still laid out $expected with $state", expected, sections.arrangement)
            }
            close(o)
        }
    }

    /** Paired with exactly [hidden] hidden: its header is shown as the
     *  hidden cell at its own end of the row (Translation trailing, Original
     *  leading, in LTR), level with the other header; its card is gone; the
     *  other card spans the layout's full inner width. */
    private fun assertPairedHidden(o: CaptureResultOverlay, hidden: Section, how: String) {
        val s = o.sections
        assertEquals("paired ($how)", Arrangement.PAIRED, s.arrangement)
        val innerStart = s.paddingLeft
        val innerEnd = s.width - s.paddingRight
        val header = o.v(hidden.headerId)
        val otherCard = o.v(hidden.other.cardId)
        assertEquals("the hidden $hidden keeps its header ($how)", View.VISIBLE, header.visibility)
        assertEquals("as the hidden cell ($how)", dp(ResultSectionsGeometry.HIDDEN_HEADER_CELL_DP), header.width)
        if (hidden == Section.TRANSLATION) {
            assertEquals("at the trailing end ($how)", innerEnd, header.right)
        } else {
            assertEquals("at the leading end ($how)", innerStart, header.left)
        }
        assertEquals("level with the other header ($how)", o.v(hidden.other.headerId).top, header.top)
        assertEquals("its card is gone ($how)", View.GONE, o.v(hidden.cardId).visibility)
        assertEquals("the other card is shown ($how)", View.VISIBLE, otherCard.visibility)
        assertEquals("the ${hidden.other} card spans the full inner width ($how)",
            innerStart to innerEnd, otherCard.left to otherCard.right)
    }

    @Test fun `paired, a hidden section keeps its header as the 140dp cell at its own end and the other card spans the full width`() {
        for (first in Section.values()) {
            val o = open(wide, text(3), text(2), hidden = setOf(first))
            assertPairedHidden(o, first, "hidden by its pref before show")

            // The other one, through the eyes.
            o.toggle(first)
            o.toggle(first.other)
            settle()
            assertPairedHidden(o, first.other, "hidden by its eye")

            // Back to the first, through the eyes.
            o.toggle(first.other)
            o.toggle(first)
            settle()
            assertPairedHidden(o, first, "hidden by its eye")

            // Both hidden: both headers, no card.
            o.toggle(first.other)
            settle()
            for (section in Section.values()) {
                val header = o.v(section.headerId)
                assertEquals("both hidden, the $section header stays", View.VISIBLE, header.visibility)
                assertTrue("both hidden, the $section header has a size (${header.width}x${header.height})",
                    header.width > 0 && header.height > 0)
                assertEquals("both hidden, no $section card", View.GONE, o.v(section.cardId).visibility)
            }
            close(o)
        }
    }

    @Test fun `stacked, a hidden Translation keeps its header in place at the top, with the scroll at 0`() {
        // The text cannot shrink, so the content overflows the sheet: a park
        // of the header above the fold (the old behaviour) would have the
        // room to happen.
        prefs.resultsFontMinSp = Prefs.DEFAULT_RESULTS_FONT_MAX_SP
        val o = open(narrow, text(12), text(4), hidden = setOf(Section.TRANSLATION))
        val s = o.sections
        assertEquals(Arrangement.STACKED, s.arrangement)
        val targetHeader = o.v(R.id.targetHeaderRow)
        val sourceHeader = o.v(R.id.sourceHeaderRow)
        val sourceCard = o.v(R.id.cardOriginal)
        assertTrue("the content scrolls by more than the header (${scrollRange(o)} vs ${targetHeader.height})",
            scrollRange(o) > targetHeader.height)

        fun assertInPlace(at: String) {
            assertEquals("the scroll is at its top $at", 0, o.scroll.scrollY)
            assertEquals("the Translation header is shown $at", View.VISIBLE, targetHeader.visibility)
            assertTrue("the Translation header has a height $at", targetHeader.height > 0)
            assertEquals("the Translation header is the first row $at", s.paddingTop, targetHeader.top)
            assertEquals("its card is gone $at", View.GONE, o.v(R.id.cardTranslation).visibility)
            assertEquals("the Original header follows beneath $at", targetHeader.bottom, sourceHeader.top)
            assertEquals("the Original card follows beneath $at", sourceHeader.bottom, sourceCard.top)
        }
        assertInPlace("after the bind")

        // A live hide does the same (the old sheet parked on that too).
        o.binder.toggleTranslationHidden()
        settle()
        assertEquals("shown again", View.VISIBLE, o.v(R.id.cardTranslation).visibility)
        o.binder.toggleTranslationHidden()
        settle()
        assertInPlace("after a live hide")
    }

    @Test fun `an eye toggle never moves the scroll`() {
        // Fixed-size text, long in both sections: the content overflows in
        // every state, so the offset never needs clamping.
        prefs.resultsFontMinSp = Prefs.DEFAULT_RESULTS_FONT_MAX_SP
        val offset = dp(60)
        for ((screen, sentences) in listOf(narrow to 12, wide to 24)) {
            val o = open(screen, text(sentences), text(sentences))
            assertTrue("the content scrolls past the offset on $screen (${scrollRange(o)})", scrollRange(o) > offset)
            o.scroll.scrollTo(0, offset)
            settle()
            assertEquals("scrolled on $screen", offset, o.scroll.scrollY)
            for (section in Section.values()) {
                o.toggle(section)
                settle()
                assertEquals("$section hidden on $screen", View.GONE, o.v(section.cardId).visibility)
                assertTrue("the offset still fits the range with $section hidden on $screen (${scrollRange(o)})",
                    scrollRange(o) >= offset)
                assertEquals("hiding $section kept the scroll on $screen", offset, o.scroll.scrollY)
                o.toggle(section)
                settle()
                assertEquals("$section shown on $screen", View.VISIBLE, o.v(section.cardId).visibility)
                assertEquals("showing $section kept the scroll on $screen", offset, o.scroll.scrollY)
            }
            close(o)
        }
    }

    @Test fun `a hidden section's restore control is its own header's eye`() {
        for (screen in listOf(wide, narrow)) {
            val o = open(screen, text(3), text(2))
            val navHost: CaptureSheetNavHost = o.field("navHost")
            for (section in Section.values()) {
                o.toggle(section)
                settle()
                val card = o.v(section.cardId)
                assertEquals("$section hidden on $screen", View.GONE, card.visibility)
                val eye = o.v(section.eyeId)
                assertTrue("its eye is in its header on $screen", eye.parent === o.v(section.headerId))
                assertTrue("the binder offers $section's eye on $screen",
                    o.binder.navigableActions().any { it.view === eye })
                assertTrue("the controller reaches $section's eye on $screen",
                    navHost.navActions().any { it.view === eye })

                assertTrue("the eye takes the tap", eye.performClick())
                settle()
                assertEquals("the tap brought $section's card back on $screen", View.VISIBLE, card.visibility)
                assertFalse("and its pref on $screen", hiddenPref(section))
            }
            close(o)
        }
    }

    // ── B. Height comes from the content's parts ───────────────────────

    /** A screen and the two texts for the height tests. */
    private data class HeightCase(val screen: Screen, val original: String, val translation: String)

    /** Each case needs more than the panel floor and less than the
     *  auto-size cap with both sections shown, so the panel height moves on
     *  a hide, not only the ceiling. Paired cases first, then stacked. */
    private val heightCases: List<HeightCase>
        get() = listOf(
            // Either section hidden still needs more than the floor: the
            // surviving card is filled to exactly its content.
            HeightCase(wide, text(6), text(4)),
            HeightCase(narrow, text(2), text(2)),
            // A short Translation: with the Original hidden the panel drops
            // to its floor and the fill stretches the Translation card past
            // its content, a stretched height that must not feed back into
            // the need when the Original comes back.
            HeightCase(Screen(1280, 900), text(6), "Short."),
            HeightCase(Screen(500, 1100), text(2), "Short."),
        )

    @Test fun `hiding a section and showing it again returns the panel height and the drag ceiling exactly`() {
        var stretchedHides = 0
        for (case in heightCases) {
            for (section in Section.values()) {
                val o = open(case.screen, case.original, case.translation)
                val at = "$section on ${case.screen}"
                val height = o.panelHeight
                val need = o.need
                assertTrue("both shown, the need ($need) is above the floor ($at)", need > floor(case.screen))
                assertEquals("both shown, the panel rests at the need ($at)", need, height)

                o.toggle(section)
                settle()
                assertTrue("the hide lowered the need ($need to ${o.need}, $at)", o.need < need)
                assertTrue("the hide lowered the panel ($height to ${o.panelHeight}, $at)", o.panelHeight < height)
                if (stretched(o, section.other)) stretchedHides++

                o.toggle(section)
                settle()
                assertEquals("the panel height came back ($at)", height, o.panelHeight)
                assertEquals("the drag ceiling came back ($at)", need, o.need)
                close(o)
            }
        }
        assertTrue("some hide left the surviving card stretched past its content ($stretchedHides)",
            stretchedHides > 0)
    }

    @Test fun `a panel resting at the need shows both texts at their largest size, filling the body`() {
        // The need is the WHOLE content at the largest text size, the
        // buffer under the cards included: a need that left any part out
        // would rest the panel that much short, and the fit would shrink the
        // text to squeeze it in. The Anki pill's room is not in it: that
        // is scroll past the fold, with the hidden sections' header rows.
        val maxPx = prefs.resultsFontMaxSp * ctx.resources.displayMetrics.scaledDensity
        for (case in heightCases) {
            val o = open(case.screen, case.original, case.translation)
            val at = "${case.screen}"
            assertTrue("the need is above the floor ($at)", o.need > floor(case.screen))
            assertEquals("the panel rests at the need ($at)", o.need, o.panelHeight)
            assertEquals("the Original is at the largest size ($at)",
                maxPx, (o.v(R.id.tvOriginal) as android.widget.TextView).textSize, 0.01f)
            assertEquals("the Translation is at the largest size ($at)",
                maxPx, (o.v(R.id.tvTranslation) as android.widget.TextView).textSize, 0.01f)
            assertEquals("only the hidden sections' header rows are past the fold ($at)",
                rowsPastTheFold(o), scrollRange(o))
            close(o)
        }
    }

    @Test fun `a sheet shown with a section already hidden settles where a sheet toggled to it does`() {
        for (case in heightCases) {
            for (section in Section.values()) {
                val at = "$section on ${case.screen}"
                val toggled = open(case.screen, case.original, case.translation)
                val bothShown = toggled.need
                toggled.toggle(section)
                settle()
                val height = toggled.panelHeight
                val need = toggled.need
                assertNotEquals("the hidden state needs another height than both shown ($at)", bothShown, need)
                close(toggled)

                val preHidden = open(case.screen, case.original, case.translation, hidden = setOf(section))
                assertEquals("opened hidden ($at)", View.GONE, preHidden.v(section.cardId).visibility)
                assertEquals("the same panel height ($at)", height, preHidden.panelHeight)
                assertEquals("the same drag ceiling ($at)", need, preHidden.need)
                close(preHidden)
            }
        }
    }

    @Test fun `hiding a section never raises the need, and stacked it lowers it, the header staying`() {
        for (screen in listOf(wide, narrow)) {
            for (section in Section.values()) {
                val o = open(screen, text(6), text(6))
                val at = "$section on $screen"
                val shown = o.need
                assertTrue("both shown, the need ($shown) is above the floor ($at)", shown > floor(screen))
                o.toggle(section)
                settle()
                val hidden = o.need
                assertTrue("hiding did not raise the need ($shown to $hidden, $at)", hidden <= shown)
                if (screen == narrow) {
                    assertEquals(Arrangement.STACKED, o.sections.arrangement)
                    assertTrue("stacked, hiding lowered the need ($shown to $hidden, $at)", hidden < shown)
                    val header = o.v(section.headerId)
                    assertEquals("the card is gone ($at)", View.GONE, o.v(section.cardId).visibility)
                    assertEquals("the header stays ($at)", View.VISIBLE, header.visibility)
                    assertTrue("the header has a height ($at)", header.height > 0)
                }
                close(o)
            }
        }
    }

    @Test fun `with both sections hidden the drag ceiling is the panel floor`() {
        for (screen in listOf(wide, narrow)) {
            val o = open(screen, text(6), text(6))
            assertTrue("both shown, the need (${o.need}) is above the floor on $screen", o.need > floor(screen))
            o.binder.toggleTranslationHidden()
            o.binder.toggleOriginalHidden()
            settle()
            assertFalse(o.binder.isTranslationShown || o.binder.isOriginalShown)
            assertEquals("the drag ceiling is the floor on $screen", floor(screen), o.need)
            assertEquals("and the panel rests there on $screen", floor(screen), o.panelHeight)
            close(o)
        }
    }

    @Test fun `the need does not depend on how tall the panel is`() {
        for ((screen, texts) in listOf(wide to (text(10) to text(4)), narrow to (text(6) to text(6)))) {
            val o = open(screen, texts.first, texts.second)
            val need = o.need
            val rest = o.panelHeight
            assertEquals("the panel rests at the auto-size cap on $screen",
                CaptureResultGeometry.autoMaxHeight(screen.h), rest)
            assertTrue("the need ($need) is past the cap ($rest) on $screen", need > rest + dp(40))
            assertTrue("and under the drag's own limit on $screen",
                need < CaptureResultGeometry.maxPanelHeight(screen.h))
            val card = o.v(R.id.cardOriginal)
            val cardBefore = card.height

            // The grabber drag: up by half of what the content still needs.
            val grabY = panelTop(o) + dp(constInt("HANDLE_HEIGHT_DP")) / 2f
            drag(o, screen.w / 2f, grabY, -((need - rest) / 2f))
            settle()
            assertTrue("the drag grew the panel ($rest to ${o.panelHeight}) on $screen", o.panelHeight > rest)
            assertTrue("short of the need on $screen", o.panelHeight < need)
            assertTrue("and the fill stretched the card ($cardBefore to ${card.height}) on $screen",
                card.height > cardBefore)

            // The fit runs again on the taller panel, as a furigana re-render
            // runs it. The stored ceiling is poisoned first, so only a fresh
            // measurement can put the need back.
            CaptureResultOverlay::class.java.getDeclaredField("maxNeededHeightPx")
                .apply { isAccessible = true }.setInt(o, -1)
            o.binder.onSourceTextHeightChanged!!.invoke()
            assertEquals("the need, measured on the taller panel, on $screen", need, o.need)
            settle()
            assertEquals("and once that settles, on $screen", need, o.need)
            close(o)
        }
    }

    @Test fun `paired at the panel floor, the two cards stretch to the body's bottom`() {
        val tall = Screen(1280, 1600)
        val o = open(tall, "Short.", "Short too.")
        val s = o.sections
        assertEquals(Arrangement.PAIRED, s.arrangement)
        assertEquals("the panel rests at its floor", floor(tall), o.panelHeight)
        assertEquals("the text needs less than the floor", floor(tall), o.need)
        val sourceCard = o.v(R.id.cardOriginal)
        val targetCard = o.v(R.id.cardTranslation)
        val sourceContent = o.v(R.id.originalContent)
        assertTrue("the Original card is stretched past its content (${sourceCard.height} vs ${sourceContent.height})",
            sourceCard.height > sourceContent.height + o.binder.sourceCardInset())
        assertEquals("the cards end on one line", sourceCard.bottom, targetCard.bottom)
        // The buffer under the cards is the sheet's card buffer; the
        // sections hidden beneath begin right under the cards, their header
        // rows past the fold but for that buffer's worth, and the Anki
        // pill's room (the layout's bottom padding) after them.
        assertEquals("at the body's bottom less the buffer",
            o.scroll.height - cardBuffer(o), sourceCard.bottom)
    }

    // ── C. The swipe-away gate ──────────────────────────────────────────

    /** Text that cannot shrink and overflows the sheet: the content scrolls. */
    private fun openScrolling(configure: (CaptureResultOverlay) -> Unit = {}): CaptureResultOverlay {
        prefs.resultsFontMinSp = Prefs.DEFAULT_RESULTS_FONT_MAX_SP
        return open(wide, text(24), text(12), configure = configure)
    }

    private fun openFitting(configure: (CaptureResultOverlay) -> Unit = {}): CaptureResultOverlay {
        prefs.resultsFontMinSp = Prefs.DEFAULT_RESULTS_FONT_MIN_SP
        return open(wide, "Short.", "Short too.", configure = configure)
    }

    /** Let the exit slide run out. */
    private fun settleExit() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    @Test fun `texts that fit dismiss on a long downward drag of the body`() {
        val o = openFitting()
        assertEquals("the texts fit: only the hidden sections' header rows are past the fold",
            rowsPastTheFold(o), scrollRange(o))
        assertEquals("at the top", 0, o.scroll.scrollY)
        val followed = swipeDown(o, wide)
        assertTrue("the sheet followed the finger past the distance ($followed)",
            followed >= constFloat("DISMISS_DISTANCE_DP") * density)
        settleExit()
        assertEquals(listOf(CaptureResultOverlay.DismissReason.USER), dismissals)
    }

    @Test fun `texts that overflow, at the top, dismiss on a long downward drag of the body`() {
        val o = openScrolling()
        assertTrue("the texts overflow the body (${scrollRange(o)})", scrollRange(o) > rowsPastTheFold(o))
        assertEquals("from its top", 0, o.scroll.scrollY)
        val followed = swipeDown(o, wide)
        assertTrue("the sheet followed the finger past the distance ($followed)",
            followed >= constFloat("DISMISS_DISTANCE_DP") * density)
        settleExit()
        assertEquals(listOf(CaptureResultOverlay.DismissReason.USER), dismissals)
    }

    @Test fun `content scrolled down keeps a downward drag for the scroll and never dismisses`() {
        val o = openScrolling()
        val offset = dp(60)
        o.scroll.scrollTo(0, offset)
        settle()
        assertTrue("scrolled down (${o.scroll.scrollY})", o.scroll.scrollY > 0)
        val followed = swipeDown(o, wide)
        assertEquals("the sheet never moved", 0f, followed)
        settleExit()
        assertTrue("no dismissal ($dismissals)", dismissals.isEmpty())
        assertFalse(o.dismissed)
        assertTrue("the drag scrolled the content back up (${o.scroll.scrollY})", o.scroll.scrollY < offset)
    }

    @Test fun `an upward drag at the top never starts a dismiss drag, whether the texts fit or overflow`() {
        for (scrolls in listOf(false, true)) {
            val o = if (scrolls) openScrolling() else openFitting()
            assertEquals("the texts overflow: $scrolls", scrolls, scrollRange(o) > rowsPastTheFold(o))
            assertEquals(0, o.scroll.scrollY)
            val (x, y) = bodyPoint(o, wide)
            val localY = y - panelTop(o)
            val height = o.panelHeight
            var moved = 0f
            var started = false
            drag(o, x, y, -dismissTravel) {
                moved = maxOf(moved, kotlin.math.abs(o.panel.translationY))
                started = started || o.panel.field<Boolean>("dragging")
            }
            assertEquals("the body saw the gesture (scrollable: $scrolls)", localY, panelDownY(o), 0.5f)
            assertEquals("it was no resize (scrollable: $scrolls)", height, o.panelHeight)
            assertFalse("no dismiss drag began (scrollable: $scrolls)", started)
            assertEquals("the sheet never moved (scrollable: $scrolls)", 0f, moved)
            settleExit()
            assertTrue("no dismissal (scrollable: $scrolls, $dismissals)", dismissals.isEmpty())
            assertFalse(o.dismissed)
            if (scrolls) assertTrue("the drag was the scroll's (${o.scroll.scrollY})", o.scroll.scrollY > 0)
            close(o)
        }
    }

    @Test fun `a host that does not dismiss on gestures keeps the sheet after a long downward drag at the top`() {
        // The control: the same drag on the same content dismisses a sheet
        // that does.
        val control = openFitting()
        swipeDown(control, wide)
        settleExit()
        assertEquals("the control dismisses", listOf(CaptureResultOverlay.DismissReason.USER), dismissals)
        host.removeAll()
        settle()

        val o = openFitting { it.dismissOnGesture = false }
        assertEquals("at the top", 0, o.scroll.scrollY)
        val (x, y) = bodyPoint(o, wide)
        val localY = y - panelTop(o)
        drag(o, x, y, dismissTravel)
        assertEquals("the body saw the gesture", localY, panelDownY(o), 0.5f)
        settleExit()
        assertTrue("no dismissal ($dismissals)", dismissals.isEmpty())
        assertFalse(o.dismissed)
        assertEquals("the sheet settled back in place", 0f, o.panel.translationY)
    }
}
