package com.playtranslate

import android.app.Application
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.playtranslate.capture.CaptureLifecycle
import com.playtranslate.language.LanguagePackStore
import com.playtranslate.language.SourceLangId
import com.playtranslate.ocr.registry.OcrModelManager
import com.playtranslate.overlay.OverlayHost
import com.playtranslate.ui.FloatingIconMenu
import com.playtranslate.ui.FloatingOverlayIcon
import com.playtranslate.ui.LanguageSetupActivity
import com.playtranslate.ui.OverlayWorkspace
import com.playtranslate.ui.SourceListPage
import com.playtranslate.ui.WorkspaceHost
import com.playtranslate.ui.WorkspacePage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDisplayManager
import java.time.Duration

/**
 * The floating icon's "Change game language" gesture (2026-09-28),
 * driven through a real icon's tap and hold callbacks as its touch handling
 * fires them. With exactly two languages downloaded, the current one among
 * them, it switches to the other and says so in the transient pill; with
 * one, or three or more, or a current language that isn't one of the two,
 * it opens the picker the quick menu's Language row opens: in the workspace
 * over the game, or as the app's page with the app in front on the other
 * screen. "Downloaded" is the picker's Suggested rows, so one Chinese
 * download is two languages, Simplified and Traditional.
 *
 * Packs are faked on disk: a dictionary file and a manifest per language,
 * which is all an installed check reads outside Japanese (whose pack here is
 * a real dictionary with the current schema), and every language here has
 * an ML Kit OCR floor, so none needs an OCR pack. No CaptureService
 * runs: what a new game language does to a session is the service's, for
 * every change of it (SourceLanguageChangeTest).
 */
@RunWith(RobolectricTestRunner::class)
class IconChangeGameLanguageGestureTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(ctx)
    private val controller = OverlayUiController(
        ctx, OverlayHost(ctx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY),
    )

    private class StubPage : WorkspacePage {
        override fun title(ctx: Context): CharSequence = "Stub"
        override fun onCreateView(ctx: Context, parent: ViewGroup, host: WorkspaceHost): View = View(ctx)
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    private fun clearPrefs() {
        ctx.getSharedPreferences("playtranslate_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Before fun setUp() {
        clearPrefs()
        LanguagePackStore.rootDir(ctx).deleteRecursively()
        CaptureLifecycle.setFloatingIconSuppressed(ctx, false)
        prefs.captureDisplayIds = setOf(Display.DEFAULT_DISPLAY)
        prefs.iconTapAction = TapAction.CHANGE_GAME_LANGUAGE
        prefs.iconHoldAction = HoldAction.CHANGE_GAME_LANGUAGE
    }

    @After fun tearDown() {
        MainActivity.isInForeground = false
        LanguageSetupActivity.selectionDelegate = null
        controller.hideAll()
        CaptureLifecycle.setFloatingIconSuppressed(ctx, true)
        // Let the churn gate finish every removal inside this test, the
        // pill's fade-out included.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        LanguagePackStore.rootDir(ctx).deleteRecursively()
        clearPrefs()
    }

    /** [ids]' packs on disk, as a finished download leaves them. */
    private fun downloaded(vararg ids: SourceLangId) {
        for (id in ids) {
            val dict = LanguagePackStore.dictDbFor(ctx, id)
            dict.parentFile!!.mkdirs()
            dict.writeText("")
            LanguagePackStore.manifestFileFor(ctx, id).writeText("{}")
        }
    }

    /** A Japanese pack from before Sudachi (v2), as LanguagePackStoreStalenessTest
     *  builds one: its dictionary schema is current, so it counts as
     *  downloaded, but the catalog marks it a forced upgrade. */
    private fun japaneseFromBeforeSudachi() {
        val dict = LanguagePackStore.dictDbFor(ctx, SourceLangId.JA)
        dict.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dict, null).use { db ->
            db.execSQL("CREATE TABLE entry (id INTEGER PRIMARY KEY, is_common INTEGER, freq_score INTEGER)")
            db.execSQL(
                "CREATE TABLE headword (entry_id INTEGER, position INTEGER, text TEXT, " +
                    "ke_pri TEXT DEFAULT '', rank_score INTEGER DEFAULT 0)"
            )
            db.execSQL(
                "CREATE TABLE reading (entry_id INTEGER, position INTEGER, text TEXT, " +
                    "no_kanji INTEGER, re_pri TEXT, freq_score INTEGER, " +
                    "re_inf TEXT, rank_score INTEGER, uk_applicable INTEGER)"
            )
            db.execSQL("CREATE TABLE sense (entry_id INTEGER, position INTEGER, pos TEXT, glosses TEXT, misc TEXT)")
            db.execSQL(
                "CREATE TABLE kanjidic (literal TEXT PRIMARY KEY, on_readings TEXT, kun_readings TEXT, " +
                    "jlpt INTEGER, grade INTEGER, stroke_count INTEGER)"
            )
            db.execSQL("CREATE TABLE kanji_meaning (literal TEXT, lang TEXT, meanings TEXT, PRIMARY KEY(literal, lang))")
        }
        LanguagePackStore.manifestFileFor(ctx, SourceLangId.JA).writeText(
            """
            {
              "langId": "ja",
              "schemaVersion": 1,
              "packVersion": 2,
              "appMinVersion": 0,
              "files": [{"path": "dict.sqlite", "size": 0, "sha256": null}],
              "totalSize": 0,
              "licenses": []
            }
            """.trimIndent()
        )
        assertTrue("precondition: a forced upgrade", LanguagePackStore.isForcedUpgrade(ctx, SourceLangId.JA))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T =
        OverlayUiController::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(controller) as T

    /** Install the default display's icon and return it. */
    private fun icon(): FloatingOverlayIcon {
        controller.reconcileFloatingIcons(freshAppearance = false)
        settle()
        val handle = field<Map<*, *>>("iconHandles")[Display.DEFAULT_DISPLAY]
        assertNotNull("an icon on the default display", handle)
        return handle!!.javaClass.getDeclaredField("icon").apply { isAccessible = true }
            .get(handle) as FloatingOverlayIcon
    }

    /** What the transient pill says, or null when none is up. */
    private fun pill(): String? {
        val view = field<View?>("pillView") ?: return null
        return view.javaClass.getDeclaredField("\$message").apply { isAccessible = true }
            .get(view) as String
    }

    /** The workspace's root page while one is open over the game. */
    private fun workspacePage(): WorkspacePage? {
        val ws = field<OverlayWorkspace?>("workspace") ?: return null
        val stack = OverlayWorkspace::class.java.getDeclaredField("stack")
            .apply { isAccessible = true }.get(ws) as List<*>
        val entry = stack.first()!!
        return entry.javaClass.getDeclaredField("page").apply { isAccessible = true }
            .get(entry) as WorkspacePage
    }

    private fun startedActivities(): List<Intent> {
        val app = shadowOf(ctx as Application)
        return generateSequence { app.nextStartedActivity }.toList()
    }

    // ── two languages: the switch ──────────────────────────────────────

    @Test fun `with two languages downloaded a tap switches to the other and says so, and back`() {
        downloaded(SourceLangId.EN, SourceLangId.ES)
        prefs.sourceLang = "en"
        val icon = icon()

        icon.onTap!!.invoke()
        settle()
        assertEquals(SourceLangId.ES, prefs.sourceLangId)
        assertEquals("Translating from Spanish", pill())

        icon.onTap!!.invoke()
        settle()
        assertEquals(SourceLangId.EN, prefs.sourceLangId)
        assertEquals("Translating from English", pill())

        assertNull("no picker", workspacePage())
        assertEquals(emptyList<Intent>(), startedActivities())
    }

    @Test fun `one Chinese download is two languages, and the tap switches Simplified and Traditional`() {
        downloaded(SourceLangId.ZH)
        prefs.sourceLang = "zh"

        icon().onTap!!.invoke()
        settle()

        assertEquals(SourceLangId.ZH_HANT, prefs.sourceLangId)
        assertEquals("Translating from Chinese (Traditional)", pill())
        assertNull(workspacePage())
    }

    // ── anything else: the picker ──────────────────────────────────────

    @Test fun `Chinese and one other language are three, and the tap opens the picker`() {
        downloaded(SourceLangId.EN, SourceLangId.ZH)
        prefs.sourceLang = "en"

        icon().onTap!!.invoke()
        settle()

        assertTrue(workspacePage() is SourceListPage)
        assertEquals("unchanged until a pick", SourceLangId.EN, prefs.sourceLangId)
        assertNull("no pill", pill())
    }

    @Test fun `with one language downloaded the tap opens the picker`() {
        downloaded(SourceLangId.EN)
        prefs.sourceLang = "en"

        icon().onTap!!.invoke()
        settle()

        assertTrue(workspacePage() is SourceListPage)
        assertEquals(SourceLangId.EN, prefs.sourceLangId)
        assertNull(pill())
    }

    @Test fun `with two downloaded and the current language not one of them the tap opens the picker`() {
        downloaded(SourceLangId.EN, SourceLangId.ES)
        prefs.sourceLang = "fr"

        icon().onTap!!.invoke()
        settle()

        assertTrue(workspacePage() is SourceListPage)
        assertEquals(SourceLangId.FR, prefs.sourceLangId)
        assertNull(pill())
    }

    // Codex adversarial 2026-09-28: such a pack counts as downloaded but
    // can't run. Picking it in the picker re-downloads it; a switch would
    // have made it the game language as it is.
    @Test fun `when the other language's pack needs a forced upgrade the tap opens the picker`() {
        downloaded(SourceLangId.EN)
        japaneseFromBeforeSudachi()
        prefs.sourceLang = "en"
        assertEquals(
            "two downloaded",
            listOf(SourceLangId.JA, SourceLangId.EN), OcrModelManager.fullyInstalledSources(ctx),
        )

        icon().onTap!!.invoke()
        settle()

        assertTrue(workspacePage() is SourceListPage)
        assertEquals(SourceLangId.EN, prefs.sourceLangId)
        assertNull(pill())
    }

    // Leaving such a pack for a language that runs is a plain switch.
    @Test fun `when the current language's pack needs a forced upgrade the tap switches to the other`() {
        downloaded(SourceLangId.EN)
        japaneseFromBeforeSudachi()
        prefs.sourceLang = "ja"

        icon().onTap!!.invoke()
        settle()

        assertEquals(SourceLangId.EN, prefs.sourceLangId)
        assertEquals("Translating from English", pill())
        assertNull(workspacePage())
    }

    // The quick menu's route: full pages belong on the app's screen when
    // the app is in front there.
    @Test fun `with the app in front on the other screen the picker opens as the app's page`() {
        ShadowDisplayManager.addDisplay("w640dp-h480dp")
        MainActivity.isInForeground = true
        downloaded(SourceLangId.EN)
        prefs.sourceLang = "en"
        LanguageSetupActivity.selectionDelegate = object : LanguageSetupActivity.Delegate {
            override fun onSourceSelectionDone(sourceId: SourceLangId) = Unit
            override fun onTargetSelectionDone(targetCode: String) = Unit
        }

        icon().onTap!!.invoke()
        settle()

        val launch = startedActivities().single()
        assertEquals(LanguageSetupActivity::class.java.name, launch.component?.className)
        assertEquals(LanguageSetupActivity.MODE_SOURCE, launch.getStringExtra(LanguageSetupActivity.EXTRA_MODE))
        assertTrue(launch.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull("no Settings callback on a pick made from the game", LanguageSetupActivity.selectionDelegate)
        assertNull(workspacePage())
    }

    // Regression for the extraction: the menu's row and the gesture share
    // the one picker launch.
    @Test fun `the quick menu's Language row opens the same picker`() {
        downloaded(SourceLangId.EN, SourceLangId.ES)
        prefs.sourceLang = "en"
        prefs.iconTapAction = TapAction.OPEN_QUICK_MENU
        icon().onTap!!.invoke()
        settle()
        val menu = field<FloatingIconMenu?>("floatingMenu")
        assertNotNull(menu)

        menu!!.onSelectLanguage!!.invoke()
        settle()

        assertNull("the menu closed", field<FloatingIconMenu?>("floatingMenu"))
        assertTrue(workspacePage() is SourceListPage)
        assertEquals("the menu's row never switches", SourceLangId.EN, prefs.sourceLangId)
    }

    // ── the hold ───────────────────────────────────────────────────────

    @Test fun `a hold switches once at the threshold, and neither its lift nor a slide into a drag switches back`() {
        downloaded(SourceLangId.EN, SourceLangId.ES)
        prefs.sourceLang = "en"
        val icon = icon()

        icon.onHoldStart!!.invoke()
        assertEquals(SourceLangId.ES, prefs.sourceLangId)
        icon.onHoldEnd!!.invoke()
        assertEquals(SourceLangId.ES, prefs.sourceLangId)

        icon.onHoldStart!!.invoke()
        assertEquals(SourceLangId.EN, prefs.sourceLangId)
        icon.onHoldCancel!!.invoke()
        assertEquals(SourceLangId.EN, prefs.sourceLangId)
    }

    @Test fun `a hold's picker stays up at the lift and closes when the finger slides into a drag`() {
        downloaded(SourceLangId.EN)
        prefs.sourceLang = "en"
        val icon = icon()

        icon.onHoldStart!!.invoke()
        settle()
        assertTrue(workspacePage() is SourceListPage)
        icon.onHoldEnd!!.invoke()
        settle()
        assertTrue("the lift leaves it up", workspacePage() is SourceListPage)

        icon.onHoldStart!!.invoke()
        settle()
        icon.onHoldCancel!!.invoke()
        settle()
        assertNull("the slide closed it before the lens opens", workspacePage())
    }

    // Only a picker the hold itself opened closes; a hold that switched
    // leaves whatever else is open alone.
    @Test fun `a slide after a hold that switched leaves an open workspace alone`() {
        downloaded(SourceLangId.EN, SourceLangId.ES)
        prefs.sourceLang = "en"
        val icon = icon()
        val stub = StubPage()
        assertTrue(controller.openWorkspace(Display.DEFAULT_DISPLAY) { stub })
        settle()

        icon.onHoldStart!!.invoke()
        icon.onHoldCancel!!.invoke()
        settle()

        assertEquals(SourceLangId.ES, prefs.sourceLangId)
        assertSame(stub, workspacePage())
    }

    // ── the pill is our chrome ─────────────────────────────────────────

    // It goes up as a running session restarts in the new language, and
    // raw frames (the live pinhole tier) contain it: its words must never
    // reach OCR as game text.
    @Test fun `the pill is in the chrome OCR blacks out of raw frames while it is up`() {
        downloaded(SourceLangId.EN, SourceLangId.ES)
        prefs.sourceLang = "en"
        val icon = icon()
        val before = controller.ownChromeRects(Display.DEFAULT_DISPLAY)

        icon.onTap!!.invoke()
        settle()

        val pill = field<View?>("pillView")
        assertNotNull(pill)
        val during = controller.ownChromeRects(Display.DEFAULT_DISPLAY)
        assertEquals(before.size + 1, during.size)
        assertTrue(during.any { it.width() == pill!!.width && it.height() == pill.height })

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertNull("faded out", field<View?>("pillView"))
        assertEquals(before.size, controller.ownChromeRects(Display.DEFAULT_DISPLAY).size)
    }
}
