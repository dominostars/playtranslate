package com.playtranslate.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Bundle
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.AnkiManager
import com.playtranslate.R
import com.playtranslate.ask.AskSentence
import com.playtranslate.ask.Explanation
import com.playtranslate.ask.WordInSentence
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.Headword
import com.playtranslate.model.Sense
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.translation.FakeCompleterBackend
import com.playtranslate.translation.TranslationBackendRegistry
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowSystemClock
import java.lang.reflect.Proxy
import java.time.Duration

/**
 * A lens row's Anki button, through the actions the drag lens, the capture
 * sheet and the workspace's pages share ([SourceLensActions]): the review
 * it opens starts its note with the answer standing in that row, read at
 * the tap, before the lens is dismissed. Both of its ways in: the Activity
 * trampoline's extras, and the workspace's editor page. And a row's open
 * tap: the word page it presents is handed the row's answer inside its
 * sentence's context ([SentenceContext.explanations]), which is where the
 * page's card reads its note.
 */
@RunWith(RobolectricTestRunner::class)
class SourceLensAnkiNotesTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private val app: Context = ApplicationProvider.getApplicationContext()
    private lateinit var controller: ActivityController<Host>
    private lateinit var lens: MagnifierLens

    private val sentence = AskSentence("猫が魚を食べた", sourceLang = "ja", targetLang = "en")

    private fun entry(word: String, reading: String, meaning: String) = DictionaryEntry(
        slug = word, isCommon = true, tags = emptyList(), jlpt = emptyList(),
        headwords = listOf(Headword(word, reading)),
        senses = listOf(Sense(listOf(meaning), listOf("noun"), emptyList(), emptyList(), emptyList())),
    )

    private val catEntry = entry("猫", "ねこ", "cat")
    private val eatEntry = entry("食べる", "たべる", "to eat")

    private fun section(word: String, reading: String) = LensSection(
        WordDefinitionData(word, reading, emptyList(), freqScore = 0, isCommon = false),
        label = null, opens = true, anki = true,
        explain = WordInSentence(sentence, word, reading),
    )

    private fun context(word: String, e: DictionaryEntry) = LensActionContext(
        word, null, null, e, sentence.text, null, lookupForm = word, lookupReading = null,
    )

    private var reply = CompletableDeferred<String>()
    private val llm = FakeCompleterBackend(
        id = "lens-anki-test-llm", priority = 0, displayName = "Test LLM",
        answer = { _, _ -> reply.await() },
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun answer(text: String) {
        val r = reply
        reply = CompletableDeferred()
        r.complete(text)
        idle()
    }

    private fun rowId(word: String): String =
        lens.shownRowsForTest().filterIsInstance<DefRow.Word>().first { it.data.word == word }.id

    private fun tap(word: String, act: String) {
        lens.tapRowForTest(rowId(word), act)
        idle()
    }

    @Before
    fun setUp() {
        DefinitionShells.reset()
        TranslationBackendRegistry.addOnlineBackend(llm)
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = "com.ichi2.anki" })
        controller = Robolectric.buildActivity(Host::class.java).setup()
        val activity = controller.get()
        lens = MagnifierLens(activity, activity.windowManager, Display.DEFAULT_DISPLAY)
    }

    @After
    fun tearDown() {
        TranslationBackendRegistry.removeOnlineBackend(llm.id)
        lens.dismiss()
        controller.pause().stop().destroy()
        DefinitionShells.reset()
    }

    /** The lens up with the tapped unit 猫 and one secondary, 食べる (what
     *  its owner says of it is [eat]), its actions wired by [route]. */
    private fun show(route: WorkspaceRoute, eat: LensActionContext = context("食べる", eatEntry)) {
        SourceLensActions(
            app, Display.DEFAULT_DISPLAY,
            OverlayHost(app, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY), lens,
            route = route,
            currentSecondary = { i -> if (i == 0) eat else null },
        ) { context("猫", catEntry) }
        val dm = controller.get().resources.displayMetrics
        lens.show(dm.widthPixels / 2, dm.heightPixels * 3 / 4, dm.widthPixels, dm.heightPixels)
        lens.makeInteractive()
        lens.setSplitDefinitions(section("猫", "ねこ"), listOf(section("食べる", "たべる")))
        idle()
        // The launch lands on the display of a resumed activity of the
        // app, and this environment's activity has no display (reading it
        // throws): no activity of ours is in front here, as over a game
        // ([PtCloudPillGearTest] clears the same registry).
        com.playtranslate.PlayTranslateApplication::class.java.getDeclaredField("resumedActivity")
            .apply { isAccessible = true }.set(null, null)
    }

    // Mutations: read the answer after the lens is dismissed; read the
    // tapped unit's row for a secondary; leave the extras without it.
    @Test
    fun `the review a row's Anki button opens starts with that row's answer, by the trampoline's extras`() {
        show(WorkspaceRoute.None)
        tap("食べる", DefAction.EXPLAIN)
        answer("It is the plain past of to eat.")

        tap("食べる", DefAction.TRAILING)

        val review = shadowOf(app as Application).nextStartedActivity
        assertEquals(AnkiPermissionActivity::class.java.name, review?.component?.className)
        assertEquals("食べる", review?.getStringExtra(WordAnkiReviewActivity.EXTRA_WORD))
        assertEquals("It is the plain past of to eat.", review!!.extras!!.getAnkiNotes())
    }

    // Mutation: a row with no answer takes its neighbour's.
    @Test
    fun `a row with no answer opens its review with no note, whatever its neighbour holds`() {
        show(WorkspaceRoute.None)
        tap("食べる", DefAction.EXPLAIN)
        answer("It is the plain past of to eat.")

        tap("猫", DefAction.TRAILING)

        val review = shadowOf(app as Application).nextStartedActivity
        assertEquals("猫", review?.getStringExtra(WordAnkiReviewActivity.EXTRA_WORD))
        assertEquals("", review!!.extras!!.getAnkiNotes())
    }

    // Mutation: the held button's fall-back to the review loses the answer.
    @Test
    fun `a held Anki button that falls back to the review opens it with the row's answer`() {
        show(WorkspaceRoute.None)
        tap("猫", DefAction.EXPLAIN)
        answer("The one doing the eating.")

        // No AnkiDroid permission here: the headless send is not possible.
        tap("猫", DefAction.hold(DefAction.TRAILING))

        val review = shadowOf(app as Application).nextStartedActivity
        assertEquals(AnkiPermissionActivity::class.java.name, review?.component?.className)
        assertEquals("The one doing the eating.", review!!.extras!!.getAnkiNotes())
    }

    // Mutation: the workspace's editor page is built without the note.
    @Test
    fun `with the permission held the workspace's editor page is opened with the row's answer`() {
        shadowOf(app as Application).grantPermissions(AnkiManager.PERMISSION)
        val pushed = mutableListOf<WorkspacePage>()
        val host = Proxy.newProxyInstance(
            WorkspaceHost::class.java.classLoader, arrayOf(WorkspaceHost::class.java),
        ) { _, method, args ->
            if (method.name == "push") pushed += args[0] as WorkspacePage
            null
        } as WorkspaceHost
        show(WorkspaceRoute.PushInto(host))
        tap("猫", DefAction.EXPLAIN)
        answer("The one doing the eating.")

        tap("猫", DefAction.TRAILING)

        val editor = pushed.single() as AnkiEditorPage
        val args = AnkiEditorPage::class.java.getDeclaredField("args").apply { isAccessible = true }
            .get(editor) as Bundle
        assertEquals("猫", args.getString(WordAnkiReviewBinder.ARG_WORD))
        assertEquals("The one doing the eating.", args.getAnkiNotes())
        assertNull("the lens went with the tap", lens.rootViewForTest())
    }

    // ── A row's open tap ────────────────────────────────────────────────

    /** The lens holds an open tap back for 300ms after the one before it,
     *  counted from zero on a lens that has had none. */
    private fun pastDebounce() = ShadowSystemClock.advanceBy(Duration.ofMillis(400))

    // Mutations: the opener's snapshot leaves the row's answer out; puts
    // it under another word than the page's; reads the tapped unit's row
    // for a secondary.
    @Test
    fun `the page a row's open tap presents holds the row's answer in its sentence's context, under its own word`() {
        val pushed = mutableListOf<WorkspacePage>()
        val host = Proxy.newProxyInstance(
            WorkspaceHost::class.java.classLoader, arrayOf(WorkspaceHost::class.java),
        ) { _, method, args ->
            if (method.name == "push") pushed += args[0] as WorkspacePage
            null
        } as WorkspaceHost
        // Every part of the word's name differs from the others, so that
        // a key built from the wrong one is another key: found as 食べた,
        // resolved under たべる read たべ, shown as 食べる read たべる.
        show(
            WorkspaceRoute.PushInto(host),
            eat = LensActionContext(
                "食べる", reading = "たべる", surface = "食べた", entry = eatEntry,
                sentence = sentence.text, screenshotPath = null,
                lookupForm = "たべる", lookupReading = "たべ",
            ),
        )
        tap("食べる", DefAction.EXPLAIN)
        answer("It is the plain past of to eat.")

        pastDebounce()
        tap("食べる", DefAction.OPEN)

        val page = pushed.single() as WorkspaceWordDetailPage
        val context = page.sentenceContext()!!
        assertEquals("the sentence the answer is about", sentence.text, context.original)
        assertEquals(
            mapOf(CardTarget("食べた", "たべる", "たべ") to Explanation("It is the plain past of to eat.", "Test LLM")),
            context.explanations,
        )
    }

    // Mutation: a row with no answer opens its page with its neighbour's.
    @Test
    fun `a row with no answer opens its page with none in its sentence's context`() {
        val pushed = mutableListOf<WorkspacePage>()
        val host = Proxy.newProxyInstance(
            WorkspaceHost::class.java.classLoader, arrayOf(WorkspaceHost::class.java),
        ) { _, method, args ->
            if (method.name == "push") pushed += args[0] as WorkspacePage
            null
        } as WorkspaceHost
        show(WorkspaceRoute.PushInto(host))
        tap("食べる", DefAction.EXPLAIN)
        answer("It is the plain past of to eat.")

        pastDebounce()
        tap("猫", DefAction.OPEN)

        val page = pushed.single() as WorkspaceWordDetailPage
        assertEquals(emptyMap<CardTarget, Explanation>(), page.sentenceContext()!!.explanations)
    }

    // ── A link in a row's definitions to another word ───────────────────

    private fun WorkspaceWordDetailPage.named(field: String): Any? =
        WorkspaceWordDetailPage::class.java.getDeclaredField(field).apply { isAccessible = true }.get(this)

    // Mutations: the link opens the row's own word; the tapped unit's
    // context is read for a secondary's link; the row's answer or its
    // surface rides to the linked word's page; the row's sentence or its
    // capture moment does not.
    @Test
    fun `a link in a row's definitions opens the linked word's page in that row's sentence, with none of the row's answer`() {
        val pushed = mutableListOf<WorkspacePage>()
        val host = Proxy.newProxyInstance(
            WorkspaceHost::class.java.classLoader, arrayOf(WorkspaceHost::class.java),
        ) { _, method, args ->
            if (method.name == "push") pushed += args[0] as WorkspacePage
            null
        } as WorkspaceHost
        show(
            WorkspaceRoute.PushInto(host),
            eat = LensActionContext(
                "食べる", reading = "たべる", surface = "食べた", entry = eatEntry,
                sentence = sentence.text, screenshotPath = null, audioAnchorMs = 42L,
                lookupForm = "たべる", lookupReading = "たべ",
            ),
        )
        tap("食べる", DefAction.EXPLAIN)
        answer("It is the plain past of to eat.")

        pastDebounce()
        tap("食べる", DefAction.reference(0, "食う", "くう"))

        val page = pushed.single() as WorkspaceWordDetailPage
        assertEquals(
            listOf<Any?>("食う", "くう", null, "食う", "くう"),
            listOf("word", "reading", "surface", "lookupForm", "lookupReading").map { page.named(it) },
        )
        val context = page.sentenceContext()!!
        assertEquals("the row's own sentence", sentence.text, context.original)
        assertEquals(emptyMap<CardTarget, Explanation>(), context.explanations)
        assertEquals("the row's own capture moment", 42L, page.audioAnchorMs)
        assertNull("the lens went with the tap", lens.rootViewForTest())
    }

    // Mutation: a link in the tapped unit's row reads no context at all.
    @Test
    fun `a link in the tapped unit's row opens the linked word in the tapped unit's sentence`() {
        val pushed = mutableListOf<WorkspacePage>()
        val host = Proxy.newProxyInstance(
            WorkspaceHost::class.java.classLoader, arrayOf(WorkspaceHost::class.java),
        ) { _, method, args ->
            if (method.name == "push") pushed += args[0] as WorkspacePage
            null
        } as WorkspaceHost
        show(WorkspaceRoute.PushInto(host))

        pastDebounce()
        tap("猫", DefAction.reference(0, "子猫", null))

        val page = pushed.single() as WorkspaceWordDetailPage
        assertEquals(listOf<Any?>("子猫", null, "子猫", null), listOf("word", "reading", "lookupForm", "lookupReading").map { page.named(it) })
        assertEquals(sentence.text, page.sentenceContext()!!.original)
    }

    // Mutations: the secondary's index is dropped; a link is an open of
    // its own, outside the opens' debounce, in either order.
    @Test
    fun `the lens tells its owner of a link with the row it is in, and one gesture is one open`() {
        val links = mutableListOf<Triple<Int?, String, String?>>()
        var opens = 0
        lens.onReferenceTap = { secondary, term, reading -> links += Triple(secondary, term, reading) }
        lens.onOpenTap = { opens++ }
        val dm = controller.get().resources.displayMetrics
        lens.show(dm.widthPixels / 2, dm.heightPixels * 3 / 4, dm.widthPixels, dm.heightPixels)
        lens.makeInteractive()
        lens.setSplitDefinitions(section("猫", "ねこ"), listOf(section("食べる", "たべる")))
        idle()

        pastDebounce()
        tap("食べる", DefAction.reference(0, "食う", "くう"))
        // The same gesture reaching the row as well is not a second open.
        tap("猫", DefAction.OPEN)
        pastDebounce()
        tap("猫", DefAction.reference(1, "子猫", null))

        assertEquals(listOf<Triple<Int?, String, String?>>(Triple(0, "食う", "くう"), Triple(null, "子猫", null)), links)
        assertEquals(0, opens)

        // Nor is a link the gesture reaches after the row's own open.
        pastDebounce()
        tap("猫", DefAction.OPEN)
        tap("猫", DefAction.reference(1, "子猫", null))
        assertEquals(1, opens)
        assertEquals(2, links.size)
    }
}
