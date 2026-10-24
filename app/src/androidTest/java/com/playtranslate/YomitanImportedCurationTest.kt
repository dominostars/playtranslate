package com.playtranslate

import android.graphics.PixelFormat
import android.view.WindowManager
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.playtranslate.model.ImportedSense
import com.playtranslate.model.ImportedSenseGroup
import com.playtranslate.ui.DefinitionsDocument
import com.playtranslate.ui.WordDefinitionData
import com.playtranslate.ui.YomitanDefinitionsView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Proof-on-engine for the Anki word sheet's imported-row curation
 * ([DefinitionsDocument.ImportedCuration]). The JVM tests pin the markup
 * strings and the bridge-to-hook mapping; what only a real engine can say
 * is what the page DOES with that markup: whether a click on a row's ✕ is
 * routed by the shell's listener as a removal (and never as a body tap),
 * whether the painted height follows a removal down, and whether the
 * dictionary's scoped CSS still reaches its content under the wrapper while
 * reaching nothing of the row's own. The API 30 emulator's WebView takes
 * the legacy per-selector scoping path, the same path the AYN Thor's
 * Chromium 109 takes, so a pass here speaks for the oldest engine the
 * styled panel ships on.
 *
 * Each test hosts its own view in a REAL overlay window (see the smoke
 * test: a detached WebView never gets a width, and the page will not
 * report a height without one) and tears it down in `finally`, so a failed
 * assertion never leaves a window on screen for the next test.
 */
@RunWith(AndroidJUnit4::class)
class YomitanImportedCurationTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext

    @Before
    fun allowOverlayWindow() {
        instrumentation.uiAutomation
            .executeShellCommand("appops set com.playtranslate SYSTEM_ALERT_WINDOW allow")
            .close()
        Thread.sleep(500)
    }

    /**
     * E1. A click on a row's ✕ must reach the host as that row's removal and
     * as nothing else: routed as a body tap it removes nothing (the word
     * sheet's binder installs no body-tap action at all), and routed as both,
     * any host with a tap-anywhere action would run it on every removal.
     * Catches the listener's ✕ branch removed (the click falls through to a
     * body tap and no index arrives) and its `return` dropped (the index
     * arrives, then a body tap behind it).
     */
    @Test
    fun removeClickReachesKotlinWithItsRowIndexAndIsNotABodyTap() {
        val heights = LinkedBlockingQueue<Int>()
        val removed = CountDownLatch(1)
        val removedIndex = AtomicInteger(-1)
        val bodyTaps = AtomicInteger(0)
        var usable = true
        var view: YomitanDefinitionsView? = null

        instrumentation.runOnMainSync {
            val v = attachOverlayView()
            if (v == null) {
                usable = false
                return@runOnMainSync
            }
            view = v
            v.onContentHeight = { h -> if (h > 0) heights.add(h) }
            v.onRemoveImported = { i ->
                removedIndex.set(i)
                removed.countDown()
            }
            v.onBodyTap = { bodyTaps.incrementAndGet() }
            swap(
                v,
                listOf(
                    ImportedSenseGroup(
                        "Gauntlet",
                        listOf(ImportedSense("first sense"), ImportedSense("second sense")),
                    ),
                ),
                DefinitionsDocument.ImportedCuration(
                    removed = emptySet(), removable = true, removeLabel = "Remove from card",
                ),
            )
        }

        try {
            assumeTrue("Skipped: no WebView provider on this image.", usable)
            val v = checkNotNull(view)
            assertNotNull("page never reported a painted height", heights.poll(15, TimeUnit.SECONDS))

            // The SECOND button, so a listener that reported a fixed index or
            // the first row's (a closest() reaching the wrong row) is caught
            // as well as one that reports nothing.
            val clicked = probe(
                v,
                """(function(){
                     var xs = document.querySelectorAll('.imp-x');
                     if (xs.length < 2) return 'pending';
                     xs[1].click();
                     return 'clicked';
                   })()""",
            )
            assertEquals("the page never clicked the second ✕", "\"clicked\"", clicked)
            assertTrue("the ✕ click never reached onRemoveImported", removed.await(15, TimeUnit.SECONDS))
            assertEquals("the ✕ reported the wrong row", 1, removedIndex.get())

            // The probe answering means the click listener has returned, and
            // every bridge call it made (synchronous on the JS side) has
            // already posted its hook; draining the main looper runs any body
            // tap queued behind the removal before the counter is read.
            instrumentation.waitForIdleSync()
            assertEquals("the ✕ click also reported a body tap", 0, bodyTaps.get())
        } finally {
            detach(view)
        }
    }

    /**
     * E2. The host sizes this view from the page's height report alone (the
     * view never scrolls itself), so a removal the report did not follow
     * would leave the sheet's panel at the old height with a blank band
     * where the rows were. Catches the curation's removed set not filtering
     * what the page paints, and a report that never comes for the re-swap.
     */
    @Test
    fun paintedHeightShrinksAfterARemoval() {
        val heights = LinkedBlockingQueue<Int>()
        var usable = true
        var view: YomitanDefinitionsView? = null
        val group = ImportedSenseGroup(
            "Gauntlet",
            listOf(
                ImportedSense("first sense"),
                ImportedSense("second sense"),
                ImportedSense("third sense"),
            ),
        )

        instrumentation.runOnMainSync {
            val v = attachOverlayView()
            if (v == null) {
                usable = false
                return@runOnMainSync
            }
            view = v
            v.onContentHeight = { h -> if (h > 0) heights.add(h) }
            swap(v, listOf(group), curation(removed = emptySet()))
        }

        try {
            assumeTrue("Skipped: no WebView provider on this image.", usable)
            val v = checkNotNull(view)
            val h1 = heights.poll(15, TimeUnit.SECONDS)
            assertTrue("page never reported a painted height (h1=$h1)", h1 != null && h1 > 0)

            // Only the removed set changes between the two swaps; removable
            // stays true, since a row without its ✕ is shorter (the ✕'s
            // min-height sets a row's floor) and would shrink the page with
            // no removal at all. The queue is cleared in the same main-thread
            // turn as the second setContent: only reports stamped with the
            // latest render generation reach the hook, and that check runs on
            // the main thread after this turn has bumped the generation, so
            // the first swap's two-frame refinement can no longer reach the
            // hook and every height polled below is the second swap's.
            instrumentation.runOnMainSync {
                heights.clear()
                swap(v, listOf(group), curation(removed = setOf(0, 1)))
            }
            val h2 = heights.poll(15, TimeUnit.SECONDS)
            assertNotNull("page never reported a height for the re-swap", h2)
            assertTrue("removing two of three rows did not shrink the page (h1=$h1, h2=$h2)", h2!! < h1!!)
        } finally {
            detach(view)
        }
    }

    /**
     * E3. Curation wraps each kept row in `.imp-sense` > `.imp-body`, and the
     * dictionary's scope (`.gloss-scope[data-dictionary]`) sits inside the
     * body around the glossary alone. The scoped rules are descendant
     * selectors under that scope and should not care, but the legacy path
     * builds them by hand, which is exactly where a wrapper could break
     * scoping on an old engine. The ✕'s width is asserted too: the button is
     * the removal's whole hit target, and an unstyled one shrinks to its
     * glyph. The stylesheet also carries hostile rules on bare elements
     * (`button`, `div`, `*`): scoped to the dictionary, they may reach only
     * the dictionary's own nodes, never the row's wrapper or its ✕. Catches
     * the wrapper breaking descendant scoping, the shell's ✕ rule dropped,
     * and the scope back on the section or around the whole row, where those
     * rules would hide the ✕, unflex the row and blank both.
     */
    @Test
    fun dictionaryCssStillAppliesInsideTheWrapperAndTheRemoveButtonHasItsWidth() {
        val heights = LinkedBlockingQueue<Int>()
        var usable = true
        var view: YomitanDefinitionsView? = null

        instrumentation.runOnMainSync {
            val v = attachOverlayView()
            if (v == null) {
                usable = false
                return@runOnMainSync
            }
            view = v
            v.onContentHeight = { h -> if (h > 0) heights.add(h) }
            val glossary =
                """[{"type":"structured-content","content":{"tag":"span","data":{"class":"chip"},"content":"styled chip"}}]"""
            swap(
                v,
                listOf(
                    ImportedSenseGroup(
                        "Gauntlet",
                        listOf(ImportedSense("styled chip", scRowid = 1L)),
                        dictId = "curtest",
                    ),
                ),
                curation(removed = emptySet()),
                structured = mapOf(1L to glossary),
                dictCss = mapOf(
                    "curtest" to
                        "span[data-sc-class=\"chip\"] { background-image: linear-gradient(135deg, rgb(123,47,247), rgb(241,7,163)); }" +
                        // Hostile rules on bare elements, each aimed at a piece
                        // of our chrome: the ✕ (button), the row's flex wrapper
                        // (div), everything (*). Scoped to the dictionary, they
                        // may reach only the dictionary's own nodes.
                        " button { display: none !important; } div { display: inline !important; }" +
                        " * { visibility: hidden !important; }",
                ),
            )
        }

        try {
            assumeTrue("Skipped: no WebView provider on this image.", usable)
            val v = checkNotNull(view)
            assertNotNull("page never reported a painted height", heights.poll(15, TimeUnit.SECONDS))

            val chipBg = probe(
                v,
                """(function(){
                     var el = document.querySelector('span[data-sc-class="chip"]');
                     return el ? getComputedStyle(el).backgroundImage : 'pending';
                   })()""",
            )
            assertTrue(
                "dictionary CSS did not paint the chip inside the curation wrapper (backgroundImage=$chipBg)",
                chipBg != null && chipBg.contains("gradient"),
            )

            val xWidth = probe(
                v,
                """(function(){
                     var x = document.querySelector('.imp-x');
                     return x ? getComputedStyle(x).width : 'pending';
                   })()""",
            )
            assertEquals("the ✕ lost its width", "40px", xWidth?.removeSurrounding("\""))

            // Without this, a gradient on a chip that somehow rendered
            // outside the wrapper would pass the first assertion and prove
            // nothing about the wrapper.
            val inBody = probe(
                v,
                """(function(){
                     var el = document.querySelector('span[data-sc-class="chip"]');
                     return el ? !!el.closest('.imp-body') : 'pending';
                   })()""",
            )
            assertEquals("the chip is not inside .imp-body", "true", inBody)

            // The hostile rules landed on the dictionary's own nodes (the chip
            // took `*`, its glossary item `div`); without this, a stylesheet
            // whose hostile rules never parsed would pass the probes below
            // and prove nothing about the scope.
            val inScope = probe(
                v,
                """(function(){
                     var el = document.querySelector('span[data-sc-class="chip"]');
                     var item = el && el.closest('.gloss-item');
                     return item ? getComputedStyle(el).visibility + '/' + getComputedStyle(item).display : 'pending';
                   })()""",
            )
            assertEquals("the hostile rules never reached the dictionary's own nodes", "\"hidden/inline\"", inScope)

            // The same rules may reach nothing of ours: the ✕ and the row's
            // flex wrapper sit outside the scope, so none of them applies.
            val xDisplay = probe(
                v,
                """(function(){
                     var x = document.querySelector('.imp-x');
                     return x ? getComputedStyle(x).display : 'pending';
                   })()""",
            )
            assertTrue("a dictionary rule hid the ✕ (display=$xDisplay)", xDisplay != null && xDisplay != "\"none\"")
            val rowDisplay = probe(
                v,
                """(function(){
                     var row = document.querySelector('.imp-sense');
                     return row ? getComputedStyle(row).display : 'pending';
                   })()""",
            )
            assertEquals("a dictionary rule reached the row's flex wrapper", "\"flex\"", rowDisplay)
            val xVisibility = probe(
                v,
                """(function(){
                     var x = document.querySelector('.imp-x');
                     return x ? getComputedStyle(x).visibility : 'pending';
                   })()""",
            )
            assertEquals("a dictionary rule blanked the ✕", "\"visible\"", xVisibility)
        } finally {
            detach(view)
        }
    }

    /** Main thread. A fresh view hosted in an overlay window, hardware
     *  accelerated as the lens's is, or null when this image has no WebView
     *  provider (nothing is attached then, so there is nothing to detach). */
    private fun attachOverlayView(): YomitanDefinitionsView? {
        val view = YomitanDefinitionsView(ctx, TOKENS)
        if (!view.isUsable()) return null
        ctx.getSystemService(WindowManager::class.java).addView(
            view,
            WindowManager.LayoutParams(
                800, 600,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ),
        )
        return view
    }

    private fun detach(view: YomitanDefinitionsView?) {
        if (view == null) return
        instrumentation.runOnMainSync {
            ctx.getSystemService(WindowManager::class.java).removeViewImmediate(view)
            view.destroy()
        }
    }

    /** Main thread. Swaps [groups] in under [curation], as the word sheet's
     *  binder does (imported rows only: no pack senses, no meta row). */
    private fun swap(
        view: YomitanDefinitionsView,
        groups: List<ImportedSenseGroup>,
        curation: DefinitionsDocument.ImportedCuration,
        structured: Map<Long, String> = emptyMap(),
        dictCss: Map<String, String> = emptyMap(),
    ) {
        val data = WordDefinitionData(
            word = "猫", reading = "ねこ", senses = emptyList(),
            freqScore = 0, isCommon = false, importedGroups = groups,
        )
        view.setContent(
            DefinitionsDocument.contentHtml(
                data,
                structured = structured,
                localizePos = { it.joinToString("/") },
                importedCuration = curation,
            ),
            dictCss = dictCss,
            sourceLanguage = "ja",
        )
    }

    private fun curation(removed: Set<Int>) = DefinitionsDocument.ImportedCuration(
        removed = removed, removable = true, removeLabel = "Remove from card",
    )

    /** Polls [js] until the page answers something other than `"pending"`
     *  (copied from [YomitanDictCssApplyTest]); the answer comes back
     *  JSON-encoded, so a string arrives in its quotes. */
    private fun probe(view: YomitanDefinitionsView, js: String, timeoutS: Long = 15): String? {
        val result = AtomicReference<String?>()
        val deadline = System.currentTimeMillis() + timeoutS * 1000
        while (System.currentTimeMillis() < deadline) {
            val latch = CountDownLatch(1)
            instrumentation.runOnMainSync {
                val wv = (view.getChildAt(0) as? WebView)
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

    private companion object {
        val TOKENS = DefinitionsDocument.Tokens(
            text = 0xFFEFEFEF.toInt(), textMuted = 0xFFA0A0A0.toInt(),
            textHint = 0xFF606060.toInt(), accent = 0xFF00BCD4.toInt(),
            panel = 0xFF242424.toInt(), baseFontSizePx = 14f,
        )
    }
}
