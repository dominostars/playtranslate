package com.playtranslate.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Bundle
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.AnkiManager
import com.playtranslate.R
import com.playtranslate.model.DictionaryEntry
import com.playtranslate.model.Headword
import com.playtranslate.model.Sense
import com.playtranslate.overlay.OverlayHost
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
import java.lang.reflect.Proxy

/**
 * A lens row for a word no dictionary lists, through the actions the drag
 * lens, the capture sheet and the workspace's pages share
 * ([SourceLensActions]): its Anki button opens the review, tapped or held
 * where the headless send cannot go, with the reading the lens showed, no
 * part of speech and no definition, and the word named as an
 * [CardTarget.unlisted] target for its sentence card. Both of the review's
 * ways in: the Activity trampoline's extras, and the workspace's editor
 * page. A row with an entry names no target. Where these buttons did
 * nothing for a row with no entry, as on the workspace's Sentence page.
 */
@RunWith(RobolectricTestRunner::class)
class SourceLensUnlistedAnkiTest {

    class Host : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_PlayTranslate)
            super.onCreate(savedInstanceState)
        }
    }

    private val app: Context = ApplicationProvider.getApplicationContext()
    private lateinit var controller: ActivityController<Host>
    private lateinit var lens: MagnifierLens

    private val sentenceText = "ググった猫"

    private val catEntry = DictionaryEntry(
        slug = "猫", isCommon = true, tags = emptyList(), jlpt = emptyList(),
        headwords = listOf(Headword("猫", "ねこ")),
        senses = listOf(Sense(listOf("cat"), listOf("noun"), emptyList(), emptyList(), emptyList())),
    )

    /** The tapped unit: ググる, found as ググった and read ぐぐる, which no
     *  dictionary lists. */
    private val googled = LensActionContext(
        "ググる", reading = "ぐぐる", surface = "ググった", entry = null,
        sentence = sentenceText, screenshotPath = null,
        lookupForm = "ググる", lookupReading = "ぐぐる",
    )

    /** The secondary: 猫, with its entry. */
    private val cat = LensActionContext(
        "猫", reading = "ねこ", surface = "猫", entry = catEntry,
        sentence = sentenceText, screenshotPath = null,
        lookupForm = "猫", lookupReading = "ねこ",
    )

    private val unlistedTarget = CardTarget("ググった", "ググる", "ぐぐる", unlisted = true)

    private fun section(word: String, reading: String, senses: List<SenseDisplay>) = LensSection(
        WordDefinitionData(word, reading, senses, freqScore = 0, isCommon = false),
        label = null, opens = true, anki = true,
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun rowId(word: String): String =
        lens.shownRowsForTest().filterIsInstance<DefRow.Word>().first { it.data.word == word }.id

    private fun tap(word: String, act: String) {
        lens.tapRowForTest(rowId(word), act)
        idle()
    }

    @Before
    fun setUp() {
        DefinitionShells.reset()
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = "com.ichi2.anki" })
        controller = Robolectric.buildActivity(Host::class.java).setup()
        val activity = controller.get()
        lens = MagnifierLens(activity, activity.windowManager, Display.DEFAULT_DISPLAY)
    }

    @After
    fun tearDown() {
        lens.dismiss()
        controller.pause().stop().destroy()
        DefinitionShells.reset()
    }

    /** The lens up with the tapped unit ググる, which has no entry, and its
     *  secondary 猫, its actions wired by [route]. */
    private fun show(route: WorkspaceRoute) {
        SourceLensActions(
            app, Display.DEFAULT_DISPLAY,
            OverlayHost(app, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY), lens,
            route = route,
            currentSecondary = { i -> if (i == 0) cat else null },
        ) { googled }
        val dm = controller.get().resources.displayMetrics
        lens.show(dm.widthPixels / 2, dm.heightPixels * 3 / 4, dm.widthPixels, dm.heightPixels)
        lens.makeInteractive()
        lens.setSplitDefinitions(
            section("ググる", "ぐぐる", emptyList()),
            listOf(section("猫", "ねこ", listOf(SenseDisplay(pos = emptyList(), definition = "cat", misc = emptyList())))),
        )
        idle()
        // The launch lands on the display of a resumed activity of the app,
        // and this environment's activity has no display (reading it
        // throws): no activity of ours is in front here, as over a game.
        com.playtranslate.PlayTranslateApplication::class.java.getDeclaredField("resumedActivity")
            .apply { isAccessible = true }.set(null, null)
    }

    private fun assertUnlistedReview(review: Intent?) {
        assertEquals(AnkiPermissionActivity::class.java.name, review?.component?.className)
        assertEquals("ググる", review!!.getStringExtra(WordAnkiReviewActivity.EXTRA_WORD))
        assertEquals("ぐぐる", review.getStringExtra(WordAnkiReviewActivity.EXTRA_READING))
        assertEquals("", review.getStringExtra(WordAnkiReviewActivity.EXTRA_POS))
        assertEquals("", review.getStringExtra(WordAnkiReviewActivity.EXTRA_DEFINITION))
        assertEquals(0, review.getIntExtra(WordAnkiReviewActivity.EXTRA_FREQ_SCORE, -1))
        assertEquals(sentenceText, review.getStringExtra(WordAnkiReviewActivity.EXTRA_SENTENCE_ORIGINAL))
        assertEquals(unlistedTarget, review.extras!!.getCardTarget())
    }

    // Mutations: the tap returns for a row with no entry; the extras leave
    // the target out.
    @Test
    fun `a row with no entry opens its review, by the trampoline's extras, naming the word for its sentence card`() {
        show(WorkspaceRoute.None)

        tap("ググる", DefAction.TRAILING)

        assertUnlistedReview(shadowOf(app as Application).nextStartedActivity)
    }

    // Mutation: the hold returns for a row with no entry.
    @Test
    fun `a held Anki button on a row with no entry falls back to the same review where no send can go`() {
        show(WorkspaceRoute.None)

        // No AnkiDroid permission here: the headless send is not possible.
        tap("ググる", DefAction.hold(DefAction.TRAILING))

        assertUnlistedReview(shadowOf(app as Application).nextStartedActivity)
    }

    // Mutation: the workspace's editor page is built without the target.
    @Test
    fun `with the permission held the workspace's editor page opens for a row with no entry, naming the word`() {
        shadowOf(app as Application).grantPermissions(AnkiManager.PERMISSION)
        val pushed = mutableListOf<WorkspacePage>()
        val host = Proxy.newProxyInstance(
            WorkspaceHost::class.java.classLoader, arrayOf(WorkspaceHost::class.java),
        ) { _, method, args ->
            if (method.name == "push") pushed += args[0] as WorkspacePage
            null
        } as WorkspaceHost
        show(WorkspaceRoute.PushInto(host))

        tap("ググる", DefAction.TRAILING)

        val editor = pushed.single() as AnkiEditorPage
        val args = AnkiEditorPage::class.java.getDeclaredField("args").apply { isAccessible = true }
            .get(editor) as Bundle
        assertEquals("ググる", args.getString(WordAnkiReviewBinder.ARG_WORD))
        assertEquals("ぐぐる", args.getString(WordAnkiReviewBinder.ARG_READING))
        assertEquals("", args.getString(WordAnkiReviewBinder.ARG_POS))
        assertEquals("", args.getString(WordAnkiReviewBinder.ARG_DEFINITION))
        assertEquals(unlistedTarget, args.getCardTarget())
    }

    // Mutation: a row with an entry names a target as well.
    @Test
    fun `a row with an entry opens its review with its own fields and no target`() {
        show(WorkspaceRoute.None)

        tap("猫", DefAction.TRAILING)

        val review = shadowOf(app as Application).nextStartedActivity
        assertEquals("猫", review?.getStringExtra(WordAnkiReviewActivity.EXTRA_WORD))
        assertEquals("cat", review!!.getStringExtra(WordAnkiReviewActivity.EXTRA_DEFINITION))
        assertNull(review.extras!!.getCardTarget())
    }
}
