package com.playtranslate.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.playtranslate.Prefs
import com.playtranslate.R
import com.playtranslate.overlay.OverlayHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Pins [WorkspaceLookupPage]'s toggle + tab contract, with fake tab pages
 * (the real word/sentence pages run dictionary lookups): the first-ever
 * default is the word tab; [Prefs.lookupPreferredView] seeds the open
 * (SENTENCE opens on the sentence tab); a pill tap writes the pref and
 * shows the other tab, building it LAZILY on first selection (a tab never
 * shown is never built) and keeping both once built (flipping back
 * re-creates nothing); the shown tab alone answers nav actions, the
 * scroll viewport and back; the header carries the pill with the word as
 * its right label; a tab's title/header writes never reach the workspace
 * (the toggle owns the header); destroy destroys every built tab.
 */
@RunWith(RobolectricTestRunner::class)
class WorkspaceLookupPageTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private class FakePage(val name: String) : WorkspacePage {
        var created = 0
        var destroyed = 0
        var backHandled = false
        lateinit var host: WorkspaceHost
        val scroll by lazy { ScrollView(hostCtx) }
        val action by lazy { View(hostCtx).apply { isClickable = true } }
        private lateinit var hostCtx: Context

        override fun title(ctx: Context): CharSequence = name
        override fun onCreateView(ctx: Context, parent: ViewGroup, host: WorkspaceHost): View {
            created++
            this.host = host
            hostCtx = ctx
            return FrameLayout(ctx).apply {
                addView(scroll)
                addView(action)
            }
        }
        override fun navActions() = listOf(NavAction(action))
        override fun scrollView(): ViewGroup = scroll
        override fun onBack(): Boolean = backHandled
        override fun onDestroy() {
            destroyed++
        }
    }

    private class FakeHost(activity: Activity) : WorkspaceHost {
        override val ctx: Context = activity
        override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        override val displayId: Int = 0
        override val wm: WindowManager = activity.windowManager
        override val overlayHost: OverlayHost =
            OverlayHost(activity, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        override val modalLayer: FrameLayout = FrameLayout(activity)
        val pushed = mutableListOf<WorkspacePage>()
        val titles = mutableListOf<CharSequence>()
        var header: View? = null
        var headerWrites = 0
        var navInvalidations = 0

        override fun push(page: WorkspacePage) { pushed += page }
        override fun pop() = Unit
        override fun dismiss() = Unit
        override fun setTitle(title: CharSequence) { titles += title }
        override fun setHeaderView(view: View?) { header = view; headerWrites++ }
        override fun setImeMode(wantsIme: Boolean) = Unit
        override fun setParkedForActivity(parked: Boolean) = Unit
        override fun showProgress(title: String, onDismiss: (DismissReason) -> Unit): OverlayProgress =
            error("unused")
        override fun alert(): OverlayAlert.Builder = OverlayAlert.Builder(ctx)
        override fun invalidateNav(prefer: View?) { navInvalidations++ }
    }

    private lateinit var controller: ActivityController<Host>
    private lateinit var activity: Host
    private lateinit var host: FakeHost
    private lateinit var word: FakePage
    private lateinit var sentence: FakePage
    private lateinit var parent: FrameLayout

    private val args = LensDetailArgs(
        word = "猫", reading = "ねこ", sentence = "猫が食べる。", screenshotPath = null,
        audioAnchorMs = null,
        sentenceContext = SentenceContext("猫が食べる。", null, null),
    )

    @Before
    fun setUp() {
        clearPrefs()
        controller = Robolectric.buildActivity(Host::class.java).setup()
        activity = controller.get()
        host = FakeHost(activity)
        word = FakePage("word")
        sentence = FakePage("sentence")
        parent = FrameLayout(activity)
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        clearPrefs()
    }

    private fun open(): WorkspaceLookupPage {
        val page = WorkspaceLookupPage(args, wordPage = { word }, sentencePage = { sentence })
        parent.addView(page.onCreateView(activity, parent, host))
        return page
    }

    private fun pill(label: String): TextView {
        val header = host.header ?: error("no header view")
        return header.descendants().filterIsInstance<TextView>()
            .first { it.text?.toString() == label }
    }

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) {
            for (i in 0 until childCount) yieldAll(getChildAt(i).descendants())
        }
    }

    @Test
    fun `first-ever default is the word tab, built alone, under the pill`() {
        val page = open()
        assertEquals(LookupView.WORD, page.selected)
        assertEquals(1, word.created)
        assertEquals(0, sentence.created)
        assertNotNull(host.header)
        assertNotNull(pill("猫"))
        assertNotNull(pill(activity.getString(R.string.anki_mode_sentence)))
        assertEquals(listOf(NavAction(word.action)), page.navActions())
        assertSame(word.scroll, page.scrollView())
    }

    @Test
    fun `the persisted choice seeds the open`() {
        Prefs(activity).lookupPreferredView = LookupView.SENTENCE
        val page = open()
        assertEquals(LookupView.SENTENCE, page.selected)
        assertEquals(1, sentence.created)
        assertEquals(0, word.created)
        assertSame(sentence.scroll, page.scrollView())
    }

    @Test
    fun `a pill tap writes the pref, builds the other tab lazily, and keeps both`() {
        val page = open()
        pill(activity.getString(R.string.anki_mode_sentence)).performClick()
        assertEquals(LookupView.SENTENCE, page.selected)
        assertEquals(LookupView.SENTENCE, Prefs(activity).lookupPreferredView)
        assertEquals(1, sentence.created)
        assertEquals(View.GONE, parent.descendants().first { it === word.scroll.parent }.visibility)
        assertEquals(listOf(NavAction(sentence.action)), page.navActions())
        assertSame(sentence.scroll, page.scrollView())
        val invalidations = host.navInvalidations
        assertTrue(invalidations >= 2)

        pill("猫").performClick()
        assertEquals(LookupView.WORD, page.selected)
        assertEquals(LookupView.WORD, Prefs(activity).lookupPreferredView)
        assertEquals("flipping back re-creates nothing", 1, word.created)
        assertEquals(1, sentence.created)
        assertEquals(View.VISIBLE, (word.scroll.parent as View).visibility)
        assertEquals(View.GONE, (sentence.scroll.parent as View).visibility)
        assertSame(word.scroll, page.scrollView())
    }

    @Test
    fun `back and title writes route through the shown tab only`() {
        val page = open()
        word.backHandled = true
        assertTrue(page.onBack())
        pill(activity.getString(R.string.anki_mode_sentence)).performClick()
        assertTrue("the hidden tab's back is not consulted", !page.onBack())

        val headerWritesBefore = host.headerWrites
        sentence.host.setTitle("clobber")
        sentence.host.setHeaderView(View(activity))
        assertTrue(host.titles.isEmpty())
        assertEquals(headerWritesBefore, host.headerWrites)
        // Every other host call reaches the workspace unchanged.
        sentence.host.push(word)
        assertEquals(listOf<WorkspacePage>(word), host.pushed)
    }

    @Test
    fun `destroy destroys every built tab and nothing else`() {
        val page = open()
        page.onDestroy()
        assertEquals(1, word.destroyed)
        assertEquals(0, sentence.destroyed)
        assertNull(page.selected)

        // Fresh tab pages (a destroyed tab's view still belongs to the old
        // container), both shown this time.
        word = FakePage("word")
        sentence = FakePage("sentence")
        parent = FrameLayout(activity)
        val page2 = open()
        pill(activity.getString(R.string.anki_mode_sentence)).performClick()
        page2.onDestroy()
        assertEquals(1, word.destroyed)
        assertEquals(1, sentence.destroyed)
    }

    private fun clearPrefs() {
        activityContext().getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun activityContext(): Context =
        androidx.test.core.app.ApplicationProvider.getApplicationContext()
}
