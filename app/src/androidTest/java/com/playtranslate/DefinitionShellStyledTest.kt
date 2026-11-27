package com.playtranslate

import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.playtranslate.model.ImportedSense
import com.playtranslate.model.ImportedSenseGroup
import com.playtranslate.ui.DefAction
import com.playtranslate.ui.DefRow
import com.playtranslate.ui.DefRowHtml
import com.playtranslate.ui.DefinitionShell
import com.playtranslate.ui.DefinitionsDocument
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Proof on the engine for a dictionary's own content in the definitions
 * page: structured content WITH a dictionary stylesheet goes into a real
 * shell ([DefinitionShell], the page every definitions document loads), and
 * computed styles inside the WebView say what the page did with it.
 *
 *  - A scoped rule paints the content, an `@media` rule inside the
 *    stylesheet applies, and a scope-escape attempt (a stray brace) reaches
 *    neither the page's root nor its body.
 *  - Under the Anki word sheet's curation the dictionary's CSS still
 *    reaches its content inside the wrapper and nothing of the row's own
 *    (the ✕ keeps its box against a stylesheet that would hide every
 *    button), a click on a ✕ reaches the host as that row's removal with
 *    the definition's ORIGINAL index, and the page's height follows a
 *    removal down.
 *
 * The rows are handed to the shell directly, because a document reads
 * structured content from the app's own dictionary store and this must not
 * import anything into it.
 *
 * Runs meaningfully on OLD engines: a WebView before CSS nesting (the API 30
 * emulator's, the AYN Thor's Chromium 109) takes the legacy per-selector
 * scoping path, which is where the styled path first shipped broken.
 *
 * Run (never connectedAndroidTest, which uninstalls the app afterwards):
 *   ./gradlew :app:installDebug :app:installDebugAndroidTest
 *   adb shell am instrument -w -e class com.playtranslate.DefinitionShellStyledTest \
 *     com.playtranslate.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class DefinitionShellStyledTest {

    private val instr get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instr.targetContext

    private val glossary =
        """[{"type":"structured-content","content":{"tag":"span","data":{"class":"chip"},"content":"styled chip"}}]"""

    /** A gradient (the observable), an `@media` rule, a rule that would
     *  hide every button, and a scope-escape attempt at the end. */
    private val css = """
        span[data-sc-class="chip"] { background-image: linear-gradient(135deg, rgb(123,47,247), rgb(241,7,163)); }
        @media (min-width: 1px) { span[data-sc-class="chip"] { font-weight: 700; } }
        button { display: none !important; }
        } #root { display: none !important; } body { visibility: hidden !important; }
    """.trimIndent()

    private val groups = listOf(
        ImportedSenseGroup(
            "Gauntlet",
            listOf(ImportedSense("styled chip", scRowid = 1L), ImportedSense("second chip", scRowid = 2L)),
            dictId = "csstest",
        ),
    )
    private val structured = mapOf(1L to glossary, 2L to glossary)

    @Before
    fun allowOverlayWindow() {
        instr.uiAutomation.executeShellCommand("appops set com.playtranslate SYSTEM_ALERT_WINDOW allow").close()
        Thread.sleep(500)
    }

    /** A shell in a real overlay window (a detached WebView never gets a
     *  width, and the page reports no layout without one), with what the
     *  page reported. Torn down by [close]. */
    private inner class Page {
        val themed = ContextThemeWrapper(app, R.style.Theme_PlayTranslate)
        private val wm = app.getSystemService(WindowManager::class.java)
        private var root: FrameLayout? = null
        var shell: DefinitionShell? = null
        val heights = LinkedBlockingQueue<Int>()
        val actions = LinkedBlockingQueue<Pair<String, String>>()

        fun open(): Boolean {
            instr.runOnMainSync {
                val s = DefinitionShell.create(app) ?: return@runOnMainSync
                shell = s
                s.adopt(themed)
                s.listener = object : DefinitionShell.Listener {
                    override fun onLayout(gen: Int, heightCssPx: Int, targetsJson: String, headsJson: String, redrawnJson: String, labelsJson: String) {
                        if (heightCssPx > 0) heights.add(heightCssPx)
                    }
                    override fun onAction(domId: String, act: String) {
                        actions.add(domId to act)
                    }
                    override fun onRendererGone() = Unit
                }
                root = FrameLayout(themed).apply {
                    addView(s.webView, FrameLayout.LayoutParams(800, 600))
                }
                wm.addView(
                    root,
                    WindowManager.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                        PixelFormat.OPAQUE,
                    ),
                )
                s.exec(
                    DefinitionsDocument.themeJs(
                        DefinitionsDocument.DocTheme(
                            DefinitionsDocument.Tokens(
                                text = 0xFFEFEFEF.toInt(), textMuted = 0xFFA0A0A0.toInt(),
                                textHint = 0xFF606060.toInt(), accent = 0xFF00BCD4.toInt(),
                                panel = 0xFF242424.toInt(), baseFontSizePx = 14f,
                            ),
                            divider = 0xFF555555.toInt(), band = 0,
                        ),
                        rtl = false,
                    ),
                )
                s.exec("ptApplyDictCss(${JSONObject.quote("csstest")},${JSONObject.quote(css)})")
            }
            return shell != null
        }

        /** The page's rows: [row] as the document would build it, under [gen]. */
        fun set(row: DefRow, gen: Int) {
            instr.runOnMainSync {
                val html = DefRowHtml.html(themed, row, "k0", structured, "ja")
                shell?.exec("ptSet(${JSONObject.quote(html)},$gen)")
            }
        }

        /** [js]'s value in the page, polled until it is neither missing nor "pending". */
        fun probe(js: String, timeoutS: Long = 15): String? {
            val result = AtomicReference<String?>()
            val deadline = System.currentTimeMillis() + timeoutS * 1000
            while (System.currentTimeMillis() < deadline) {
                val latch = CountDownLatch(1)
                instr.runOnMainSync {
                    val wv: WebView? = shell?.webView
                    if (wv == null) {
                        result.set(null); latch.countDown()
                    } else {
                        wv.evaluateJavascript(js) { v -> result.set(v); latch.countDown() }
                    }
                }
                latch.await(5, TimeUnit.SECONDS)
                val v = result.get()
                if (v != null && v != "null" && v != "\"pending\"") return v
                Thread.sleep(200)
            }
            return result.get()
        }

        fun close() {
            instr.runOnMainSync {
                shell?.destroy()
                root?.let { wm.removeView(it) }
            }
        }
    }

    @Test
    fun dictionaryCssPaintsContentAndEscapeStaysContained() {
        val page = Page()
        try {
            assumeTrue("Skipped: no WebView provider on this image.", page.open())
            page.set(DefRow.Imported("imported", groups), gen = 1)

            val chipBg = page.probe(
                """(function(){
                     var el = document.querySelector('span[data-sc-class="chip"]');
                     if (!el) return 'pending';
                     return getComputedStyle(el).backgroundImage;
                   })()""",
            )
            assertTrue(
                "dictionary CSS did not paint the chip (backgroundImage=$chipBg)",
                chipBg != null && chipBg.contains("gradient"),
            )
            val mediaWeight = page.probe(
                """(function(){
                     var el = document.querySelector('span[data-sc-class="chip"]');
                     return el ? getComputedStyle(el).fontWeight : 'pending';
                   })()""",
            )
            assertTrue(
                "@media rule did not apply (fontWeight=$mediaWeight)",
                mediaWeight != null && mediaWeight.contains("700"),
            )
            val escape = page.probe(
                """(function(){
                     var root = document.getElementById('root');
                     return getComputedStyle(root).display + '/' + getComputedStyle(document.body).visibility;
                   })()""",
            )
            assertTrue(
                "scope escape reached the page chrome (root/body=$escape)",
                escape != null && !escape.contains("none") && !escape.contains("hidden"),
            )
        } finally {
            page.close()
        }
    }

    @Test
    fun curatedContentKeepsItsStylesItsRemoveButtonsAndReportsARemoval() {
        val page = Page()
        try {
            assumeTrue("Skipped: no WebView provider on this image.", page.open())
            fun row(removed: Set<Int>) = DefRow.Imported(
                "imported", groups, DefRow.SenseLook.SHEET, DefRow.Curation(removed, removable = true),
            )
            page.set(row(emptySet()), gen = 1)

            // The dictionary's CSS reaches its content inside the wrapper, and
            // not the row's own ✕, which its `button` rule would hide.
            val styles = page.probe(
                """(function(){
                     var chip = document.querySelector('.imp-body span[data-sc-class="chip"]');
                     var xs = document.querySelectorAll('.imp-x');
                     if (!chip || xs.length < 2) return 'pending';
                     var b = xs[0].getBoundingClientRect();
                     return getComputedStyle(chip).backgroundImage.indexOf('gradient') >= 0
                       ? 'styled/' + getComputedStyle(xs[0]).display + '/' + Math.round(b.width) + 'x' + Math.round(b.height)
                       : 'pending';
                   })()""",
            )?.trim('"')
            assertTrue("the chip is unstyled under the wrapper, or the ✕ lost its box ($styles)", styles != null && styles.startsWith("styled/"))
            val (display, box) = styles!!.removePrefix("styled/").split("/")
            assertTrue("the dictionary's button rule reached the ✕ (display=$display)", display != "none")
            val (w, h) = box.split("x").map { it.toInt() }
            assertTrue("the ✕ has no tap box ($box)", w >= 40 && h >= 40)

            assertTrue("the page reported no layout", (page.heights.poll(15, TimeUnit.SECONDS) ?: 0) > 0)
            val before = page.probe("Math.ceil(document.getElementById('root').getBoundingClientRect().height)")!!.toInt()

            // A click on the second definition's ✕: that row's removal, by the
            // index the definition had before any removal, and nothing else.
            page.actions.clear()
            page.probe("(function(){document.querySelectorAll('.imp-x')[1].click();return 'clicked';})()")
            val action = page.actions.poll(15, TimeUnit.SECONDS)
            assertEquals("k0" to DefAction.removeItem(1), action)
            assertTrue("one click reported more than one action", page.actions.isEmpty())

            // The removal drawn: the page's height follows it down.
            page.set(row(setOf(1)), gen = 2)
            val after = page.probe(
                """(function(){
                     if (document.querySelectorAll('.imp-sense').length !== 1) return 'pending';
                     return Math.ceil(document.getElementById('root').getBoundingClientRect().height);
                   })()""",
            )!!.toInt()
            assertTrue("the height did not shrink after a removal ($before -> $after)", after < before)
            assertTrue("the kept definition keeps its original index", page.probe("document.querySelector('.imp-sense').getAttribute('data-imp')") == "\"0\"")
        } finally {
            page.close()
        }
    }
}
